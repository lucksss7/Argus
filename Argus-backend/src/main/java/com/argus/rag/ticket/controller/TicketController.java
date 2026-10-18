package com.argus.rag.ticket.controller;

import com.argus.rag.common.api.ApiResponse;
import com.argus.rag.common.log.OperationLog;
import com.argus.rag.ticket.model.dto.ApprovalDecisionRequest;
import com.argus.rag.ticket.model.dto.CreateTicketRequest;
import com.argus.rag.ticket.model.dto.DispositionRequest;
import com.argus.rag.ticket.model.vo.CreateTicketResult;
import com.argus.rag.ticket.model.vo.ExecuteTicketResult;
import com.argus.rag.ticket.model.vo.ExecutionRecordVO;
import com.argus.rag.ticket.model.vo.TicketDetailResponse;
import com.argus.rag.ticket.service.TicketApprovalService;
import com.argus.rag.ticket.service.TicketDispositionService;
import com.argus.rag.ticket.service.TicketExecutionService;
import com.argus.rag.ticket.service.TicketService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工单控制器。
 * <p>
 * 仅负责请求接收、参数校验、调用现有 Service 并包装统一响应；不重复实现权限判断、
 * 状态机或事务逻辑。创建人、审批人、执行人一律由 Service 内部从服务端可信登录态
 * （{@link com.argus.rag.auth.CurrentUserService}）获取，请求体不接受任何身份字段。
 */
@OperationLog
@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketService ticketService;
    private final TicketApprovalService ticketApprovalService;
    private final TicketExecutionService ticketExecutionService;
    private final TicketDispositionService ticketDispositionService;

    public TicketController(TicketService ticketService,
                            TicketApprovalService ticketApprovalService,
                            TicketExecutionService ticketExecutionService,
                            TicketDispositionService ticketDispositionService) {
        this.ticketService = ticketService;
        this.ticketApprovalService = ticketApprovalService;
        this.ticketExecutionService = ticketExecutionService;
        this.ticketDispositionService = ticketDispositionService;
    }

    /** 创建工单（初始状态 OPEN，创建人取登录态） */
    @PostMapping
    public ApiResponse<CreateTicketResult> create(@Valid @RequestBody CreateTicketRequest request) {
        return ApiResponse.success(ticketService.createTicket(request));
    }

    /** 查询当前用户可见的工单列表（普通用户看自己，管理员看全部） */
    @GetMapping
    public ApiResponse<List<TicketDetailResponse>> list() {
        return ApiResponse.success(ticketService.listTickets());
    }

    /** 查询工单详情（创建人本人或系统管理员可见） */
    @GetMapping("/{ticketId}")
    public ApiResponse<TicketDetailResponse> detail(@PathVariable Long ticketId) {
        return ApiResponse.success(ticketService.getTicketDetail(ticketId));
    }

    /** 查询工单执行尝试历史（按 attempt_no 升序，创建人本人或系统管理员可见） */
    @GetMapping("/{ticketId}/executions")
    public ApiResponse<List<ExecutionRecordVO>> executions(@PathVariable Long ticketId) {
        return ApiResponse.success(ticketService.getTicketExecutions(ticketId));
    }

    /** 提交审批（仅创建人，OPEN -> PENDING_APPROVAL） */
    @PostMapping("/{ticketId}/submit")
    public ApiResponse<Void> submit(@PathVariable Long ticketId) {
        ticketApprovalService.submitForApproval(ticketId);
        return ApiResponse.success(null);
    }

    /** 审批通过（仅系统管理员，PENDING_APPROVAL -> APPROVED） */
    @PostMapping("/{ticketId}/approve")
    public ApiResponse<Void> approve(@PathVariable Long ticketId,
                                     @Valid @RequestBody(required = false) ApprovalDecisionRequest request) {
        ticketApprovalService.approve(ticketId, request == null ? null : request.comment());
        return ApiResponse.success(null);
    }

    /** 审批拒绝（仅系统管理员，PENDING_APPROVAL -> REJECTED） */
    @PostMapping("/{ticketId}/reject")
    public ApiResponse<Void> reject(@PathVariable Long ticketId,
                                    @Valid @RequestBody(required = false) ApprovalDecisionRequest request) {
        ticketApprovalService.reject(ticketId, request == null ? null : request.comment());
        return ApiResponse.success(null);
    }

    /** 执行账号开通（仅系统管理员手动触发，APPROVED -> IN_PROGRESS -> RESOLVED/APPROVED） */
    @PostMapping("/{ticketId}/execute")
    public ApiResponse<ExecuteTicketResult> execute(@PathVariable Long ticketId) {
        return ApiResponse.success(ticketExecutionService.execute(ticketId));
    }

    /** 人工处置（仅系统管理员，见 {@link TicketDispositionService}；操作人取登录态，不信任请求体身份） */
    @PostMapping("/{ticketId}/dispose")
    public ApiResponse<Void> dispose(@PathVariable Long ticketId,
                                     @Valid @RequestBody DispositionRequest request) {
        ticketDispositionService.dispose(ticketId, request);
        return ApiResponse.success(null);
    }

    /** 关闭工单（创建人或系统管理员，RESOLVED -> CLOSED） */
    @PostMapping("/{ticketId}/close")
    public ApiResponse<Void> close(@PathVariable Long ticketId) {
        ticketService.close(ticketId);
        return ApiResponse.success(null);
    }

    /** 取消工单（仅创建人，OPEN / PENDING_APPROVAL -> CANCELLED） */
    @PostMapping("/{ticketId}/cancel")
    public ApiResponse<Void> cancel(@PathVariable Long ticketId) {
        ticketService.cancel(ticketId);
        return ApiResponse.success(null);
    }
}
