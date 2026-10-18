package com.argus.rag.assistant.agent;

import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.BusinessException;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 工单只读 Agent 工具的共享基类。
 * <p>
 * 统一提供三份服务端可信身份上下文的读取逻辑：用户 ID、系统角色、工具上下文。
 * 身份只能来自服务端注入的 {@link ToolContext}，绝不回退到 ThreadLocal，
 * 也不信任任何可能由模型提供的身份字段。身份缺失或类型非法时以 {@link BusinessException}
 * 安全失败，由各工具捕获后折叠为统一的失败码。
 * </p>
 */
public abstract class AbstractAssistantTicketTool {

    public static final String USER_ID_CONTEXT_KEY = "userId";
    public static final String SYSTEM_ROLE_CONTEXT_KEY = "systemRole";
    public static final String MESSAGE_ID_CONTEXT_KEY = "messageId";

    /** 业务性失败（参数非法 / 身份缺失 / 权限拒绝 / 工单不存在等）的统一失败码 */
    public static final String REASON_CODE_QUERY_FAILED = "QUERY_FAILED";

    /** 未预期异常的通用失败码，不携带堆栈、凭据或内部数据库信息 */
    public static final String REASON_CODE_INTERNAL_ERROR = "INTERNAL_ERROR";

    protected Long readUserId(ToolContext toolContext) {
        Object value = requireContext(toolContext).get(USER_ID_CONTEXT_KEY);
        // 身份约定为 Long 正整数；只接受正 Long，拒绝 Integer/Double/BigDecimal 等任何
        // 可被 longValue() 静默截断的数值，也拒绝小数、NaN/Infinity、非正数与字符串等非法类型。
        if (value instanceof Long userId && userId > 0) {
            return userId;
        }
        throw new BusinessException("工单查询缺少用户上下文");
    }

    protected SystemRole readSystemRole(ToolContext toolContext) {
        Object value = requireContext(toolContext).get(SYSTEM_ROLE_CONTEXT_KEY);
        if (value instanceof SystemRole role) {
            return role;
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return SystemRole.valueOf(text);
            } catch (IllegalArgumentException ignored) {
                // 落入下方统一抛出，避免把非法枚举值泄露给模型
            }
        }
        throw new BusinessException("工单查询缺少角色上下文");
    }

    /**
     * 从服务端注入的 {@link ToolContext} 读取用户消息 ID（建单幂等边界）。
     * <p>仅接受正 {@link Long}，拒绝 {@code null}、非正数、小数/字符串等可被截断的类型，
     * 与 {@link #readUserId(ToolContext)} 保持同一套严格身份解析约定。</p>
     */
    protected Long readMessageId(ToolContext toolContext) {
        Object value = requireContext(toolContext).get(MESSAGE_ID_CONTEXT_KEY);
        if (value instanceof Long messageId && messageId > 0) {
            return messageId;
        }
        throw new BusinessException("工单创建缺少消息上下文");
    }

    protected Map<String, Object> requireContext(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            throw new BusinessException("工单查询缺少工具上下文");
        }
        return toolContext.getContext();
    }
}
