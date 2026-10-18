package com.argus.rag.ticket.model.vo;

/**
 * 创建工单结果。
 *
 * @param ticket        工单详情
 * @param alreadyExists 是否命中幂等复用（true 表示返回的是已有工单，而非本次新建）
 */
public record CreateTicketResult(
        TicketDetailResponse ticket,
        boolean alreadyExists
) {
}
