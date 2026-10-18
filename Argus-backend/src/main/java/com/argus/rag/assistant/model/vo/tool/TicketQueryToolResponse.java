package com.argus.rag.assistant.model.vo.tool;

import com.argus.rag.ticket.model.vo.TicketSummaryVO;

import java.util.List;

/**
 * queryTickets 工具返回结果。
 * <p>以结构化字段回传模型，成功时 {@code success=true} 且带工单摘要列表；
 * 失败时 {@code success=false} 并带原因，避免模型把失败误判为成功。</p>
 * <p>{@code hasMore} 表示当前权限范围内是否还有未返回的工单（列表最多返回 50 条）。</p>
 */
public record TicketQueryToolResponse(
        boolean success,
        String reasonCode,
        String reasonMessage,
        List<TicketSummaryVO> tickets,
        boolean hasMore
) {

    public TicketQueryToolResponse {
        tickets = tickets == null ? List.of() : List.copyOf(tickets);
    }

    public static TicketQueryToolResponse ok(List<TicketSummaryVO> tickets, boolean hasMore) {
        return new TicketQueryToolResponse(true, null, null, tickets, hasMore);
    }

    public static TicketQueryToolResponse failed(String reasonCode, String reasonMessage) {
        return new TicketQueryToolResponse(false, reasonCode, reasonMessage, List.of(), false);
    }
}
