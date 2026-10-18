package com.argus.rag.common.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.argus.rag.auth.model.dto.LoginRequest;
import com.argus.rag.auth.model.dto.RegisterRequest;
import com.argus.rag.user.model.dto.ChangePasswordRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LogAop} 入参脱敏逻辑的单元测试。
 * <p>直接验证脱敏产出的文本，以及真实写到日志的文本，确认凭据字段的原始值不会进入操作日志，
 * 同时非敏感信息仍被保留。</p>
 */
class LogAopTest {

    // ---- 三个真实 DTO：登录 / 注册 / 改密 ----

    @Test
    @DisplayName("登录入参脱敏：密码不出现，loginId 保留")
    void loginPasswordIsRedactedAndLoginIdKept() {
        String password = "s3cret-login-p@ss";
        LoginRequest request = new LoginRequest("alice", password);

        String described = LogAop.describeArgs(new Object[]{request});

        assertThat(described)
                .doesNotContain(password)
                .contains("alice")
                .contains("password=" + LogAop.REDACTED);
    }

    @Test
    @DisplayName("注册入参脱敏：密码不出现，用户名/邮箱保留")
    void registerPasswordIsRedactedAndProfileKept() {
        String password = "hunter2-register";
        RegisterRequest request = new RegisterRequest("bob", "bob@example.com", "Bob", password);

        String described = LogAop.describeArgs(new Object[]{request});

        assertThat(described)
                .doesNotContain(password)
                .contains("bob")
                .contains("bob@example.com")
                .contains("password=" + LogAop.REDACTED);
    }

    @Test
    @DisplayName("改密入参脱敏：currentPassword 与 newPassword 都不出现")
    void changePasswordBothFieldsAreRedacted() {
        ChangePasswordRequest request = new ChangePasswordRequest("old-p@ss", "new-p@ss");

        String described = LogAop.describeArgs(new Object[]{request});

        assertThat(described)
                .doesNotContain("old-p@ss")
                .doesNotContain("new-p@ss")
                .contains("currentPassword=" + LogAop.REDACTED)
                .contains("newPassword=" + LogAop.REDACTED);
    }

    // ---- 嵌套对象 / 集合 / 无法识别类型 ----

    record Inner(String token, String note) {
    }

    record Outer(String name, Inner inner) {
    }

    @Test
    @DisplayName("嵌套 record 与 Map：敏感字段脱敏，安全字段保留")
    void nestedRecordAndMapSecretsAreRedacted() {
        Outer outer = new Outer("visible-name", new Inner("nested-token-value", "note-ok"));
        Map<String, String> props = Map.of("secret", "map-secret-value", "tenant", "acme");

        String described = LogAop.describeArgs(new Object[]{outer, props});

        assertThat(described)
                .doesNotContain("nested-token-value")
                .doesNotContain("map-secret-value")
                .contains("visible-name")
                .contains("note-ok")
                .contains("token=" + LogAop.REDACTED)
                .contains("secret=" + LogAop.REDACTED)
                .contains("tenant=acme");
    }

    @Test
    @DisplayName("无法识别的对象类型退化为类名摘要，不调用 toString")
    void unrecognizedTypeFallsBackToClassNameSummary() {
        // AtomicReference 非标量、非 record、非 com.argus.* Bean；其 toString() 会泄露内部引用值。
        String described = LogAop.describeArgs(new Object[]{new AtomicReference<>("leaked-secret")});

        assertThat(described)
                .isEqualTo("[AtomicReference]")
                .doesNotContain("leaked-secret");
    }

    @Test
    @DisplayName("byte[] 脱敏，普通集合/数组按元素展开")
    void byteArrayRedactedWhileCollectionsAreExpanded() {
        String described = LogAop.describeArgs(new Object[]{
                new byte[]{1, 2, 3},
                List.of("x", "y")
        });

        assertThat(described)
                .contains(LogAop.REDACTED)
                .contains("x")
                .contains("y")
                .doesNotContain("1")
                .doesNotContain("2")
                .doesNotContain("3");
    }

    // ---- 端到端：真实调用 advice，捕获实际日志文本 ----

    @Test
    @DisplayName("登录操作日志真实输出不含原始密码，保留非敏感 loginId")
    void loginOperationLogLineContainsNoRawPassword() throws Throwable {
        String password = "s3cret-login-p@ss";
        LoginRequest request = new LoginRequest("alice", password);

        Logger logbackLogger = (Logger) LoggerFactory.getLogger(LogAop.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();

        Level originalLevel = logbackLogger.getLevel();
        logbackLogger.setLevel(Level.INFO);
        logbackLogger.addAppender(appender);
        try {
            new LogAop().logAroundAdvice(fakeJoinPoint("login", new Object[]{request, null}));

            String logged = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));

            assertThat(logged)
                    .contains("login")
                    .doesNotContain(password)
                    .contains("alice")
                    .contains("password=" + LogAop.REDACTED);
        } finally {
            logbackLogger.detachAppender(appender);
            logbackLogger.setLevel(originalLevel);
            appender.stop();
        }
    }

    /** 构造一个最小可用的 {@link ProceedingJoinPoint} 代理，仅满足 advice 所需的 getTarget/getSignature/getArgs/proceed。 */
    private static ProceedingJoinPoint fakeJoinPoint(String methodName, Object[] args) {
        Object signature = Proxy.newProxyInstance(
                LogAopTest.class.getClassLoader(),
                new Class<?>[]{org.aspectj.lang.Signature.class},
                (proxy, method, methodArgs) -> {
                    switch (method.getName()) {
                        case "getName":
                            return methodName;
                        case "toString":
                            return "Signature(" + methodName + ")";
                        case "hashCode":
                            return methodName.hashCode();
                        case "equals":
                            return false;
                        default:
                            return null;
                    }
                });

        Object target = new Object();
        return (ProceedingJoinPoint) Proxy.newProxyInstance(
                LogAopTest.class.getClassLoader(),
                new Class<?>[]{ProceedingJoinPoint.class},
                (proxy, method, methodArgs) -> {
                    switch (method.getName()) {
                        case "getTarget":
                            return target;
                        case "getSignature":
                            return signature;
                        case "getArgs":
                            return args;
                        case "proceed":
                            return null;
                        case "toString":
                            return "ProceedingJoinPoint";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return false;
                        default:
                            return null;
                    }
                });
    }
}
