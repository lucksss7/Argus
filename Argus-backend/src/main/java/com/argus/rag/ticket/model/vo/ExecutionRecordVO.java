package com.argus.rag.ticket.model.vo;

import com.argus.rag.ticket.model.enums.ExecutionResult;
import com.argus.rag.ticket.model.enums.ExecutionType;
import com.argus.rag.ticket.model.enums.ExecutionVerification;

import java.time.LocalDateTime;

/**
 * 一次执行尝试的历史记录响应。
 * <p>
 * 仅暴露可见字段，不暴露 {@code requestPayload} / {@code responsePayload} 等可能包含
 * 敏感信息的内部载荷。数据库 {@code ticket_executions} 无独立的 externalRef 列，
 * 执行器的 externalRef 序列化在 responsePayload 内，故本 VO 不提供该字段。
 * <p>
 * 人工核验结论与核验时间通过 {@code verificationStatus} / {@code verifiedAt} 暴露；
 * 核验操作人 {@code verifiedBy}（内部用户 ID）不对外暴露。
 */
public record ExecutionRecordVO(
        Long id,
        Integer attemptNo,
        ExecutionType executionType,
        ExecutionResult result,
        String errorMessage,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        ExecutionVerification verificationStatus,
        LocalDateTime verifiedAt
) {

    /** errorMessage 长度上限（按码点计），仅作格式兜底，避免异常长文本影响接口输出 */
    private static final int MAX_ERROR_MESSAGE_LENGTH = 200;

    public ExecutionRecordVO {
        errorMessage = sanitizeErrorMessage(errorMessage);
    }

    /**
     * 格式兜底：只处理 null、空/纯空白、控制字符与超长截断，不包含敏感信息检测或关键词过滤。
     * <p>敏感信息防护由 {@code TicketService} 的受控状态派生负责，本方法仅保证输出可安全展示。</p>
     */
    private static String sanitizeErrorMessage(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int i = 0; i < value.length() && count < MAX_ERROR_MESSAGE_LENGTH; ) {
            int codePoint = value.codePointAt(i);
            if (!Character.isISOControl(codePoint)) {
                sb.appendCodePoint(codePoint);
                count++;
            }
            i += Character.charCount(codePoint);
        }
        String sanitized = sb.toString();
        return sanitized.isBlank() ? null : sanitized;
    }
}
