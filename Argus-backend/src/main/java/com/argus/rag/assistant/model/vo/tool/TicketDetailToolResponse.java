package com.argus.rag.assistant.model.vo.tool;

import com.argus.rag.ticket.model.vo.TicketDetailToolVO;

/**
 * Agent 工单详情工具的返回结构。
 * <p>
 * 与 {@code TicketQueryToolResponse} 保持同一套 {@code success}/{@code reasonCode}/
 * {@code reasonMessage} 结构，业务性失败与未预期异常都折叠为结构化失败而非抛出，
 * 避免把堆栈、凭据或内部数据库信息带进模型上下文。
 * </p>
 *
 * @param ticket 脱敏后的工单详情，失败时为 {@code null}
 */
public record TicketDetailToolResponse(
        boolean success,
        String reasonCode,
        String reasonMessage,
        TicketDetailToolVO ticket
) {

    public static TicketDetailToolResponse ok(TicketDetailToolVO ticket) {
        return new TicketDetailToolResponse(true, null, null, ticket);
    }

    public static TicketDetailToolResponse failed(String reasonCode, String reasonMessage) {
        return new TicketDetailToolResponse(false, reasonCode, reasonMessage, null);
    }
}
