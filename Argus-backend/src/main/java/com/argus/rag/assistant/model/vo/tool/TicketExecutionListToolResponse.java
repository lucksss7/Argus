package com.argus.rag.assistant.model.vo.tool;

import com.argus.rag.ticket.model.vo.ExecutionRecordVO;

import java.util.List;

/**
 * Agent 工单执行记录工具的返回结构。
 * <p>
 * 与 {@code TicketQueryToolResponse} 保持同一套 {@code success}/{@code reasonCode}/
 * {@code reasonMessage} 结构，业务性失败与未预期异常都折叠为结构化失败而非抛出。
 * </p>
 *
 * @param executions 脱敏后的执行尝试记录（按尝试序号升序），失败时为空列表
 */
public record TicketExecutionListToolResponse(
        boolean success,
        String reasonCode,
        String reasonMessage,
        List<ExecutionRecordVO> executions
) {

    public TicketExecutionListToolResponse {
        executions = executions == null ? List.of() : List.copyOf(executions);
    }

    public static TicketExecutionListToolResponse ok(List<ExecutionRecordVO> executions) {
        return new TicketExecutionListToolResponse(true, null, null, executions);
    }

    public static TicketExecutionListToolResponse failed(String reasonCode, String reasonMessage) {
        return new TicketExecutionListToolResponse(false, reasonCode, reasonMessage, List.of());
    }
}
