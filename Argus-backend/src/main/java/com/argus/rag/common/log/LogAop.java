package com.argus.rag.common.log;

import com.argus.rag.common.api.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.time.temporal.Temporal;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * 操作日志 AOP 切面，记录被 {@link OperationLog} 标记的方法的类名、方法名、脱敏后的入参与耗时。
 * <p>
 * 入参不再使用 {@code Arrays.toString} 全量打印，而是通过 {@link #describeArgs(Object[])}
 * 做「字段名驱动的脱敏」：凡字段名命中凭据关键词（password/token/secret/authorization 等）
 * 一律输出 {@link #REDACTED}，不落盘原值；无法识别的对象类型退化为「类名摘要」而非调用其
 * {@code toString()}，避免把敏感字段或不可信内容带进日志，同时保留必要的操作审计能力。
 * </p>
 */
@Slf4j
@Component
@Aspect
@Order(1)
public class LogAop {

    /** 脱敏占位符 */
    static final String REDACTED = "[REDACTED]";

    /** 单条日志描述的最大字符数，防止超长入参撑爆日志 */
    private static final int MAX_DESCRIPTION_LENGTH = 400;

    /** 对象递归描述的最大深度，防止深层嵌套或循环引用 */
    private static final int MAX_DEPTH = 3;

    /** 集合/数组最多展开的元素个数 */
    private static final int MAX_ELEMENTS = 20;

    /** 单个标量文本的最大字符数 */
    private static final int MAX_STRING_LENGTH = 200;

    /** 凭据字段关键词（小写、下划线归一化后做包含匹配） */
    private static final Set<String> SENSITIVE_MARKERS = Set.of(
            "password", "passwd", "pwd",
            "token", "secret", "authorization", "credential",
            "apikey", "api_key", "accesskey", "access_key",
            "privatekey", "private_key");

    /** 仅对这些应用包下的普通 Java Bean 做 getter 内省，其余（JDK / Servlet / 框架类型）视为不透明对象 */
    private static final Set<String> INTROSPECTABLE_PACKAGES = Set.of("com.argus.");

    @Pointcut("@within(com.argus.rag.common.log.OperationLog) || @annotation(com.argus.rag.common.log.OperationLog)")
    public void logAdvicePointcut() {
    }

    @Around("logAdvicePointcut()")
    public Object logAroundAdvice(ProceedingJoinPoint joinPoint) throws Throwable {
        String className = joinPoint.getTarget().getClass().getSimpleName();
        String methodName = joinPoint.getSignature().getName();
        String args = describeArgs(joinPoint.getArgs());

        long startTime = System.currentTimeMillis();
        log.info("--> [{}] {}({})", className, methodName, args);

        try {
            Object result = joinPoint.proceed();
            long elapsed = System.currentTimeMillis() - startTime;
            logResult(className, methodName, elapsed, result);
            return result;
        } catch (Throwable e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("<-- [{}] {} FAIL [{}ms] {}", className, methodName, elapsed, e.toString());
            throw e;
        }
    }

    private void logResult(String className, String methodName, long elapsed, Object result) {
        if (result instanceof ApiResponse<?> apiResponse) {
            log.info("<-- [{}] {} [{}ms] success={} {}",
                    className, methodName, elapsed,
                    apiResponse.isSuccess(),
                    apiResponse.getMessage() != null ? apiResponse.getMessage() : "");
        } else {
            log.info("<-- [{}] {} [{}ms]", className, methodName, elapsed);
        }
    }

    /**
     * 将方法入参序列化为可安全记录的文本。
     * <p>敏感字段脱敏为 {@link #REDACTED}；无法识别的对象退化为类名摘要（不调用 {@code toString()}），
     * 绝不因脱敏逻辑抛出异常影响正常业务或参数对象本身。</p>
     */
    static String describeArgs(Object[] args) {
        if (args == null) {
            return "null";
        }
        StringJoiner joiner = new StringJoiner(", ", "[", "]");
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object arg : args) {
            joiner.add(describe(arg, 0, seen));
        }
        return truncate(joiner.toString(), MAX_DESCRIPTION_LENGTH);
    }

    /** 递归描述单个对象：标量 -> 文本；敏感字节数组 -> 脱敏；record/Bean/Map/集合 -> 结构化；其余 -> 类名摘要 */
    private static String describe(Object value, int depth, Set<Object> seen) {
        if (value == null) {
            return "null";
        }
        if (isScalar(value)) {
            return truncate(String.valueOf(value), MAX_STRING_LENGTH);
        }
        if (value instanceof byte[] || value instanceof char[]) {
            return REDACTED;
        }
        if (depth >= MAX_DEPTH) {
            return typeName(value);
        }
        if (!seen.add(value)) {
            return typeName(value) + "(recursive)";
        }
        try {
            if (value instanceof Map<?, ?> map) {
                return describeMap(map, depth, seen);
            }
            if (value instanceof Iterable<?> iterable) {
                return describeIterable(iterable, depth, seen);
            }
            if (value.getClass().isArray()) {
                return describeArray(value, depth, seen);
            }
            if (value.getClass().isRecord()) {
                return describeRecord(value, depth, seen);
            }
            if (isIntrospectableBean(value)) {
                return describeBean(value, depth, seen);
            }
            return typeName(value);
        } finally {
            seen.remove(value);
        }
    }

    private static String describeMap(Map<?, ?> map, int depth, Set<Object> seen) {
        StringJoiner joiner = new StringJoiner(", ", typeName(map) + "{", "}");
        int count = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (count++ >= MAX_ELEMENTS) {
                joiner.add("...");
                break;
            }
            String key = String.valueOf(entry.getKey());
            joiner.add(key + "=" + (isSensitiveField(key) ? REDACTED : describe(entry.getValue(), depth + 1, seen)));
        }
        return joiner.toString();
    }

    private static String describeIterable(Iterable<?> iterable, int depth, Set<Object> seen) {
        StringJoiner joiner = new StringJoiner(", ", "[", "]");
        int count = 0;
        for (Object element : iterable) {
            if (count++ >= MAX_ELEMENTS) {
                joiner.add("...");
                break;
            }
            joiner.add(describe(element, depth + 1, seen));
        }
        return joiner.toString();
    }

    private static String describeArray(Object array, int depth, Set<Object> seen) {
        int length = Array.getLength(array);
        StringJoiner joiner = new StringJoiner(", ", "[", "]");
        int limit = Math.min(length, MAX_ELEMENTS);
        for (int i = 0; i < limit; i++) {
            joiner.add(describe(Array.get(array, i), depth + 1, seen));
        }
        if (length > limit) {
            joiner.add("...");
        }
        return joiner.toString();
    }

    private static String describeRecord(Object value, int depth, Set<Object> seen) {
        RecordComponent[] components = value.getClass().getRecordComponents();
        if (components == null || components.length == 0) {
            return typeName(value);
        }
        StringJoiner joiner = new StringJoiner(", ", typeName(value) + "(", ")");
        for (RecordComponent component : components) {
            String name = component.getName();
            String rendered = isSensitiveField(name) ? REDACTED : readComponentValue(value, component, depth, seen);
            joiner.add(name + "=" + rendered);
        }
        return joiner.toString();
    }

    private static String readComponentValue(Object value, RecordComponent component, int depth, Set<Object> seen) {
        try {
            return describe(component.getAccessor().invoke(value), depth + 1, seen);
        } catch (Exception e) {
            return "<unreadable>";
        }
    }

    private static String describeBean(Object value, int depth, Set<Object> seen) {
        try {
            BeanInfo info = Introspector.getBeanInfo(value.getClass());
            StringJoiner joiner = new StringJoiner(", ", typeName(value) + "{", "}");
            for (PropertyDescriptor descriptor : info.getPropertyDescriptors()) {
                String name = descriptor.getName();
                if ("class".equals(name)) {
                    continue;
                }
                Method readMethod = descriptor.getReadMethod();
                if (readMethod == null || readMethod.getParameterCount() != 0) {
                    continue;
                }
                String rendered = isSensitiveField(name) ? REDACTED : invokeGetter(readMethod, value, depth, seen);
                joiner.add(name + "=" + rendered);
            }
            return joiner.toString();
        } catch (Exception e) {
            // 内省失败（例如动态代理、非标准 Bean）时退化为类名摘要，绝不调用 toString() 兜底。
            return typeName(value);
        }
    }

    private static String invokeGetter(Method readMethod, Object value, int depth, Set<Object> seen) {
        try {
            return describe(readMethod.invoke(value), depth + 1, seen);
        } catch (Exception e) {
            return "<unreadable>";
        }
    }

    /** 叶子标量：其 {@code toString()} 短小且不含凭据结构，可安全输出（byte[]/char[] 已在调用前拦截） */
    private static boolean isScalar(Object value) {
        return value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Enum<?>
                || value instanceof Temporal
                || value instanceof Date
                || value instanceof UUID
                || value instanceof Class<?>;
    }

    /** 判断是否对普通 Java Bean 做 getter 内省：仅限应用自身包，避免触碰 Servlet/JDK/框架对象 */
    private static boolean isIntrospectableBean(Object value) {
        Class<?> type = value.getClass();
        if (type.isRecord() || type.isEnum() || type.isArray() || type.isPrimitive()) {
            return false;
        }
        String name = type.getName();
        for (String pkg : INTROSPECTABLE_PACKAGES) {
            if (name.startsWith(pkg)) {
                return true;
            }
        }
        return false;
    }

    /** 字段名脱敏判定：小写并归一化分隔符后做关键词包含匹配，覆盖 password/currentPassword/newPassword/token/authorization 等 */
    private static boolean isSensitiveField(String fieldName) {
        if (fieldName == null) {
            return false;
        }
        String normalized = fieldName.toLowerCase().replace('-', '_').replace(' ', '_');
        for (String marker : SENSITIVE_MARKERS) {
            if (normalized.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** 不透明对象的类型摘要：仅输出类名（去除 CGLIB/动态代理的 $$ 后缀），不调用 toString() */
    private static String typeName(Object value) {
        Class<?> type = value.getClass();
        String simple = type.getSimpleName();
        if (simple.isEmpty()) {
            simple = type.getName();
        }
        int dollar = simple.indexOf("$$");
        if (dollar >= 0) {
            simple = simple.substring(0, dollar);
        }
        return simple;
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...(truncated)";
    }
}
