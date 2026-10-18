package com.argus.rag.assistant.model.vo.tool;

import com.argus.rag.ticket.model.vo.TicketDetailToolVO;

/**
 * createTicket 工具返回结构。
 * <p>
 * 与其它工单工具返回结构保持同一套 {@code success}/{@code reasonCode}/
 * {@code reasonMessage} 契约，业务性失败与未预期异常都折叠为结构化失败而非抛出，
 * 避免把堆栈、凭据或内部数据库信息带进模型上下文。
 * </p>
 *
 * @param ticket 建单成功后的脱敏工单详情（{@link TicketDetailToolVO}，不含内部用户 ID、
 *               乐观锁版本与处理结果说明），失败时为 {@code null}
 */
public record TicketCreateToolResponse(
        boolean success,
        String reasonCode,
        String reasonMessage,
        TicketDetailToolVO ticket
) {

    public static TicketCreateToolResponse ok(TicketDetailToolVO ticket) {
        return new TicketCreateToolResponse(true, null, null, ticket);
    }

    public static TicketCreateToolResponse failed(String reasonCode, String reasonMessage) {
        return new TicketCreateToolResponse(false, reasonCode, reasonMessage, null);
    }
}
