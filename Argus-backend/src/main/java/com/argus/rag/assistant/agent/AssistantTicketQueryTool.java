package com.argus.rag.assistant.agent;

import com.argus.rag.assistant.model.vo.tool.TicketQueryToolResponse;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ForbiddenException;
import com.argus.rag.common.exception.UnauthorizedException;
import com.argus.rag.ticket.model.vo.TicketListResult;
import com.argus.rag.ticket.service.TicketService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * 工单查询工具（只读）。
 * <p>工具名 {@value #TOOL_NAME}，查询当前登录用户可见的工单列表：
 * 普通用户只能查询自己创建的工单，系统管理员可查询全部工单；单次最多返回 50 条。</p>
 * <p>用户身份（userId / systemRole）来自服务端注入的工具上下文
 * {@link ToolContext}，绝不接受模型生成的身份字段，也不依赖请求线程的
 * {@code ThreadLocal}（{@code UserContext}），因此无论工具在哪个线程执行都安全。</p>
 * <p>身份解析统一继承自 {@link AbstractAssistantTicketTool} 的严格实现（仅接受正 Long），
 * 与详情/执行记录/建单工具保持一致，避免小数或字符串被静默截断。</p>
 * <p>失败契约统一为结构化 {@code success=false}：预期业务/权限异常返回通用失败码，
 * 意外系统异常记录堆栈后返回通用失败码，均不向模型暴露内部细节。</p>
 */
@Slf4j
@Component
public class AssistantTicketQueryTool extends AbstractAssistantTicketTool {

    public static final String TOOL_NAME = "queryTickets";

    private static final String MESSAGE_QUERY_FAILED = "工单查询失败，请稍后重试或联系管理员";
    private static final String MESSAGE_INTERNAL_ERROR = "系统内部错误，请稍后重试";

    private final TicketService ticketService;

    public AssistantTicketQueryTool(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    /**
     * 查询当前用户可见的工单列表。
     *
     * @param toolContext 服务端注入的工具上下文，含 userId 与 systemRole
     * @return 工单摘要列表（或结构化失败结果）
     */
    @Tool(
            name = TOOL_NAME,
            description = "查询当前登录用户可见的工单列表。普通用户只能查询自己创建的工单，"
                    + "系统管理员可查询全部工单，每次最多返回 50 条。该工具只做只读查询，"
                    + "不创建、不审批、不执行工单。"
    )
    public TicketQueryToolResponse query(ToolContext toolContext) {
        try {
            Long userId = readUserId(toolContext);
            SystemRole systemRole = readSystemRole(toolContext);
            TicketListResult result = ticketService.listVisibleTickets(userId, systemRole);
            return TicketQueryToolResponse.ok(result.tickets(), result.hasMore());
        } catch (BusinessException | ForbiddenException | UnauthorizedException e) {
            // 预期业务/权限/身份异常：结构化失败，不抛给框架，也不暴露内部细节。
            log.warn("queryTickets 业务异常: {}", e.getMessage());
            return TicketQueryToolResponse.failed(REASON_CODE_QUERY_FAILED, MESSAGE_QUERY_FAILED);
        } catch (RuntimeException e) {
            // 意外系统异常：记录完整堆栈便于排查，对外只返回通用失败码。
            log.error("queryTickets 工具意外异常", e);
            return TicketQueryToolResponse.failed(REASON_CODE_INTERNAL_ERROR, MESSAGE_INTERNAL_ERROR);
        }
    }
}
