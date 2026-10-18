package com.argus.rag.ticket.model.enums;

/** 工单执行结果 */
public enum ExecutionResult {
    /** 待执行 */
    PENDING,
    /** 成功 */
    SUCCESS,
    /** 失败 */
    FAILED,
    /** 结果不确定：外部操作可能已成功也可能未成功，需人工核实，不能当作普通失败自动重试 */
    UNCERTAIN
}
