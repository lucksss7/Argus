package com.argus.rag.ticket.model.enums;

/** 人工处置类型（审计用，记录管理员对异常工单采取的动作） */
public enum DispositionType {
    /** 确认执行成功（工单终结为已解决） */
    CONFIRM_SUCCESS,
    /** 确认未执行（解除 UNCERTAIN 阻塞，允许重新执行） */
    CONFIRM_NOT_EXECUTED,
    /** 授权重试（在 APPROVED 状态下递增额外执行次数） */
    RETRY_AUTHORIZE,
    /** 终止工单（流转为 ABORTED，不再执行） */
    TERMINATE
}
