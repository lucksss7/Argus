package com.argus.rag.ticket.model.vo;

import com.argus.rag.ticket.model.enums.ExecutionResult;

/**
 * 执行结果响应。
 */
public record ExecuteTicketResult(
        Long ticketId,
        int attemptNo,
        ExecutionResult result,
        String message
) {
}
