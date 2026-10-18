package com.argus.rag.ticket.model.vo;

import com.argus.rag.ticket.model.enums.PermissionLevel;
import com.argus.rag.ticket.model.enums.TicketStatus;
import com.argus.rag.ticket.model.enums.TicketType;

import java.time.LocalDateTime;

/**
 * 工单详情响应（Agent 只读工具面向）。
 * <p>仅暴露业务可见字段，不暴露 {@code creatorUserId} / {@code assigneeUserId} /
 * {@code approverUserId}（内部用户 ID）、{@code version}（内部乐观锁）及
 * {@code resolution}（处理结果说明，自由文本）。
 */
public record TicketDetailToolVO(
        Long id,
        String ticketNo,
        String title,
        String description,
        TicketType type,
        TicketStatus status,
        String priority,
        String systemName,
        String targetUsername,
        PermissionLevel permissionLevel,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
