package com.argus.rag.ticket.model.vo;

import java.util.List;

/**
 * 工单摘要列表查询结果。
 * <p>{@code hasMore} 表示是否还有未返回的工单（列表最多返回 50 条）。</p>
 */
public record TicketListResult(
        List<TicketSummaryVO> tickets,
        boolean hasMore
) {

    public TicketListResult {
        tickets = tickets == null ? List.of() : List.copyOf(tickets);
    }
}
