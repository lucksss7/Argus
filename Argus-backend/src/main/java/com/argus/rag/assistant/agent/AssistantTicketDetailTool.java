package com.argus.rag.assistant.agent;

import com.argus.rag.assistant.model.vo.tool.TicketDetailToolResponse;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ForbiddenException;
import com.argus.rag.common.exception.UnauthorizedException;
import com.argus.rag.ticket.model.vo.TicketDetailToolVO;
import com.argus.rag.ticket.service.TicketService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Agent 工单只读工具：查询单个工单的脱敏详情。
 * <p>
 * 仅创建人本人或系统管理员可见；身份从服务端注入的 {@link ToolContext} 读取，
 * 模型只能提供 {@code ticketId} 这一业务参数，无法覆盖身份。
 * </p>
 */
@Slf4j
@Component
public class AssistantTicketDetailTool extends AbstractAssistantTicketTool {

    public static final String TOOL_NAME = "getTicketDetail";

    private static final String MESSAGE_QUERY_FAILED = "工单详情查询失败，请稍后重试";
    private static final String MESSAGE_INTERNAL_ERROR = "系统内部错误，请稍后重试";

    private final TicketService ticketService;

    public AssistantTicketDetailTool(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @Tool(name = TOOL_NAME, description = "查询指定工单的脱敏详情。仅创建人本人或系统管理员可见；"
            + "该工具只做只读查询，不创建、不审批、不执行工单。")
    public TicketDetailToolResponse getDetail(
            @ToolParam(description = "要查询详情的工单 ID") Long ticketId,
            ToolContext toolContext) {
        try {
            Long userId = readUserId(toolContext);
            SystemRole systemRole = readSystemRole(toolContext);
            TicketDetailToolVO detail = ticketService.getTicketDetailFor(ticketId, userId, systemRole);
            return TicketDetailToolResponse.ok(detail);
        } catch (BusinessException | ForbiddenException | UnauthorizedException e) {
            log.warn("getTicketDetail 业务异常: {}", e.getMessage());
            return TicketDetailToolResponse.failed(REASON_CODE_QUERY_FAILED, MESSAGE_QUERY_FAILED);
        } catch (RuntimeException e) {
            log.error("getTicketDetail 工具意外异常", e);
            return TicketDetailToolResponse.failed(REASON_CODE_INTERNAL_ERROR, MESSAGE_INTERNAL_ERROR);
        }
    }
}
