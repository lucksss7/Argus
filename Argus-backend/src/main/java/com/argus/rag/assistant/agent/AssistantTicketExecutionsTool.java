package com.argus.rag.assistant.agent;

import com.argus.rag.assistant.model.vo.tool.TicketExecutionListToolResponse;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ForbiddenException;
import com.argus.rag.common.exception.UnauthorizedException;
import com.argus.rag.ticket.model.vo.ExecutionRecordVO;
import com.argus.rag.ticket.service.TicketService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agent 工单只读工具：查询单个工单的执行尝试历史。
 * <p>
 * 仅创建人本人或系统管理员可见；身份从服务端注入的 {@link ToolContext} 读取，
 * 模型只能提供 {@code ticketId} 这一业务参数，无法覆盖身份。
 * </p>
 */
@Slf4j
@Component
public class AssistantTicketExecutionsTool extends AbstractAssistantTicketTool {

    public static final String TOOL_NAME = "getTicketExecutions";

    private static final String MESSAGE_QUERY_FAILED = "工单执行记录查询失败，请稍后重试";
    private static final String MESSAGE_INTERNAL_ERROR = "系统内部错误，请稍后重试";

    private final TicketService ticketService;

    public AssistantTicketExecutionsTool(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @Tool(name = TOOL_NAME, description = "查询指定工单的执行尝试历史（按尝试序号升序）。仅创建人本人或系统管理员可见；"
            + "该工具只做只读查询，不创建、不审批、不执行工单。")
    public TicketExecutionListToolResponse getExecutions(
            @ToolParam(description = "要查询执行记录的工单 ID") Long ticketId,
            ToolContext toolContext) {
        try {
            Long userId = readUserId(toolContext);
            SystemRole systemRole = readSystemRole(toolContext);
            List<ExecutionRecordVO> executions = ticketService.getTicketExecutionsFor(ticketId, userId, systemRole);
            return TicketExecutionListToolResponse.ok(executions);
        } catch (BusinessException | ForbiddenException | UnauthorizedException e) {
            log.warn("getTicketExecutions 业务异常: {}", e.getMessage());
            return TicketExecutionListToolResponse.failed(REASON_CODE_QUERY_FAILED, MESSAGE_QUERY_FAILED);
        } catch (RuntimeException e) {
            log.error("getTicketExecutions 工具意外异常", e);
            return TicketExecutionListToolResponse.failed(REASON_CODE_INTERNAL_ERROR, MESSAGE_INTERNAL_ERROR);
        }
    }
}
