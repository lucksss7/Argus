package com.argus.rag.ticket.model.enums;

/** 审批结果（审批结果不等于执行结果） */
public enum ApprovalResult {
    /** 待审批 */
    PENDING,
    /** 已通过 */
    APPROVED,
    /** 已拒绝 */
    REJECTED
}
