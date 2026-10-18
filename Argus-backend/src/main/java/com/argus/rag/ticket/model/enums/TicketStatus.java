package com.argus.rag.ticket.model.enums;

/** 工单状态 */
public enum TicketStatus {
    /** 待处理（含待分配，用 assignee 判空区分） */
    OPEN,
    /** 待审批 */
    PENDING_APPROVAL,
    /** 已审批通过，业务尚未执行 */
    APPROVED,
    /** 执行中 */
    IN_PROGRESS,
    /** 已解决 */
    RESOLVED,
    /** 已关闭 */
    CLOSED,
    /** 审批被拒 */
    REJECTED,
    /** 已取消 */
    CANCELLED,
    /** 已终止（人工处置终结，不再执行） */
    ABORTED
}
