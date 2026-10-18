package com.argus.rag.ticket.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ForbiddenException;
import com.argus.rag.ticket.mapper.ApprovalRequestMapper;
import com.argus.rag.ticket.mapper.TicketMapper;
import com.argus.rag.ticket.model.entity.ApprovalRequest;
import com.argus.rag.ticket.model.entity.Ticket;
import com.argus.rag.ticket.model.enums.ApprovalResult;
import com.argus.rag.ticket.model.enums.TicketStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 工单审批服务：提交审批、审批通过、审批拒绝。
 * <p>
 * 提交审批只能由工单创建人发起（OPEN -> PENDING_APPROVAL）；
 * 审批通过/拒绝只能由系统管理员操作（PENDING_APPROVAL -> APPROVED / REJECTED），
 * 审批人 ID 一律取自登录态（{@link CurrentUserService#requireSystemAdmin()}），不信任请求参数。
 * <p>
 * 状态流转统一使用「基于当前状态的条件更新」（WHERE status = fromStatus），
 * 条件更新影响行数为 0 即抛出业务异常，防止重复审批；
 * 审批记录写入与工单状态更新处于同一事务，避免状态已更新但审批记录缺失。
 * <p>
 * 审批通过仅流转到 APPROVED，不触发账号开通等执行动作（执行由后续执行服务负责）。
 */
@Slf4j
@Service
public class TicketApprovalService {

    private final TicketMapper ticketMapper;
    private final ApprovalRequestMapper approvalRequestMapper;
    private final CurrentUserService currentUserService;

    public TicketApprovalService(TicketMapper ticketMapper,
                                 ApprovalRequestMapper approvalRequestMapper,
                                 CurrentUserService currentUserService) {
        this.ticketMapper = ticketMapper;
        this.approvalRequestMapper = approvalRequestMapper;
        this.currentUserService = currentUserService;
    }

    /** 提交审批：仅创建人可发起，OPEN -> PENDING_APPROVAL */
    @Transactional
    public void submitForApproval(Long ticketId) {
        CurrentUserService.CurrentUser currentUser = currentUserService.getRequiredCurrentUser();
        Ticket ticket = loadTicket(ticketId);
        requireCreator(ticket, currentUser);
        requireStatus(ticket, TicketStatus.OPEN, "工单当前状态不允许提交审批");

        int updated = ticketMapper.updateTicketStatus(
                ticketId, TicketStatus.OPEN.name(), TicketStatus.PENDING_APPROVAL.name());
        if (updated == 0) {
            throw new BusinessException("工单已被处理，请勿重复提交审批");
        }
        log.info("提交审批: ticketId={}, operatorUserId={}", ticketId, currentUser.userId());
    }

    /** 审批通过：仅系统管理员可操作，PENDING_APPROVAL -> APPROVED，审批人记录到工单与审批记录 */
    @Transactional
    public void approve(Long ticketId, String comment) {
        CurrentUserService.CurrentUser approver = currentUserService.requireSystemAdmin();
        Ticket ticket = loadTicket(ticketId);
        requireStatus(ticket, TicketStatus.PENDING_APPROVAL, "工单当前状态不允许审批");

        int updated = ticketMapper.updateTicketDecision(
                ticketId, TicketStatus.PENDING_APPROVAL.name(), TicketStatus.APPROVED.name(), approver.userId());
        if (updated == 0) {
            throw new BusinessException("工单已被处理，请勿重复审批");
        }
        insertApprovalRecord(ticketId, approver.userId(), ApprovalResult.APPROVED, comment);
        log.info("审批通过: ticketId={}, approverUserId={}", ticketId, approver.userId());
    }

    /** 审批拒绝：仅系统管理员可操作，PENDING_APPROVAL -> REJECTED，审批人记录到工单与审批记录 */
    @Transactional
    public void reject(Long ticketId, String comment) {
        CurrentUserService.CurrentUser approver = currentUserService.requireSystemAdmin();
        Ticket ticket = loadTicket(ticketId);
        requireStatus(ticket, TicketStatus.PENDING_APPROVAL, "工单当前状态不允许审批");

        int updated = ticketMapper.updateTicketDecision(
                ticketId, TicketStatus.PENDING_APPROVAL.name(), TicketStatus.REJECTED.name(), approver.userId());
        if (updated == 0) {
            throw new BusinessException("工单已被处理，请勿重复审批");
        }
        insertApprovalRecord(ticketId, approver.userId(), ApprovalResult.REJECTED, comment);
        log.info("审批拒绝: ticketId={}, approverUserId={}", ticketId, approver.userId());
    }

    private Ticket loadTicket(Long ticketId) {
        if (ticketId == null || ticketId <= 0) {
            throw new BusinessException("工单 ID 非法");
        }
        Ticket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null) {
            throw new BusinessException("工单不存在");
        }
        return ticket;
    }

    private void requireCreator(Ticket ticket, CurrentUserService.CurrentUser currentUser) {
        if (!currentUser.userId().equals(ticket.getCreatorUserId())) {
            throw new ForbiddenException("仅工单创建人可提交审批");
        }
    }

    private void requireStatus(Ticket ticket, TicketStatus expected, String message) {
        if (ticket.getStatus() != expected) {
            throw new BusinessException(message);
        }
    }

    private void insertApprovalRecord(Long ticketId, Long approverUserId, ApprovalResult result, String comment) {
        ApprovalRequest record = new ApprovalRequest();
        record.setTicketId(ticketId);
        record.setApproverUserId(approverUserId);
        record.setResult(result);
        record.setComment(comment);
        record.setDecidedAt(LocalDateTime.now());
        approvalRequestMapper.insert(record);
    }
}
