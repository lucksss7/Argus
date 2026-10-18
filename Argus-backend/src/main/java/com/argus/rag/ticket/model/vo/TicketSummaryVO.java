package com.argus.rag.ticket.model.vo;

import com.argus.rag.ticket.model.enums.TicketStatus;
import com.argus.rag.ticket.model.enums.TicketType;

import java.time.LocalDateTime;

/**
 * 工单摘要响应（面向 Agent 工具只读查询）。
 * <p>仅暴露必要摘要字段，不暴露 {@code version}、{@code resolution}、
 * 审批人/处理人 ID 等内部或敏感字段。</p>
 */
public record TicketSummaryVO(
        Long id,
        String ticketNo,
        String title,
        TicketType type,
        TicketStatus status,
        String systemName,
        String targetUsername,
        LocalDateTime createdAt
) {
}
