package com.argus.rag.ticket.model.vo;

import com.argus.rag.ticket.model.enums.PermissionLevel;
import com.argus.rag.ticket.model.enums.TicketStatus;
import com.argus.rag.ticket.model.enums.TicketType;

import java.time.LocalDateTime;

/**
 * 工单详情响应。
 */
public record TicketDetailResponse(
        Long id,
        String ticketNo,
        String title,
        String description,
        TicketType type,
        TicketStatus status,
        String priority,
        Long creatorUserId,
        Long assigneeUserId,
        Long approverUserId,
        String systemName,
        String targetUsername,
        PermissionLevel permissionLevel,
        String resolution,
        Integer version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
