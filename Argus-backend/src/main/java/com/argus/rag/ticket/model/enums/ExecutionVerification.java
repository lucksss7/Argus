package com.argus.rag.ticket.model.enums;

/**
 * 执行记录的人工核验结论。
 * <p>与执行器原始结果 {@code ExecutionResult} 分离：{@code result} 仍保存执行器输出
 * （SUCCESS / FAILED / UNCERTAIN），本枚举单独记录人工对「不确定执行」的核验结论，
 * 仅在 {@code result = UNCERTAIN} 且需人工处置时写入，{@code null} 表示尚未核验。</p>
 */
public enum ExecutionVerification {
    /** 人工确认执行成功 */
    CONFIRMED_SUCCESS,
    /** 人工确认未执行 */
    CONFIRMED_NOT_EXECUTED
}
