package com.argus.rag.ticket.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.ticket.mapper.TicketDispositionMapper;
import com.argus.rag.ticket.mapper.TicketExecutionMapper;
import com.argus.rag.ticket.mapper.TicketMapper;
import com.argus.rag.ticket.model.dto.DispositionRequest;
import com.argus.rag.ticket.model.entity.Ticket;
import com.argus.rag.ticket.model.entity.TicketDisposition;
import com.argus.rag.ticket.model.entity.TicketExecution;
import com.argus.rag.ticket.model.enums.DispositionType;
import com.argus.rag.ticket.model.enums.ExecutionResult;
import com.argus.rag.ticket.model.enums.ExecutionVerification;
import com.argus.rag.ticket.model.enums.TicketStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 工单人工处置服务：系统管理员对异常工单执行确认成功 / 确认未执行 / 授权重试 / 终止。
 * <p>
 * 所有处置操作仅允许 {@code APPROVED} 工单；操作人 ID 一律取自登录态
 * （{@link CurrentUserService#requireSystemAdmin()}），不信任请求参数。
 * </p>
 * <p>
 * 状态流转与核验写入统一使用「基于当前状态的条件更新」，影响行数为 0 即抛出业务异常，
 * 与审计记录写入同处一个事务：任一步骤失败即整体回滚，不留下部分核验或孤立审计记录。
 * </p>
 * <p>
 * 核验结论（{@code verification_status}）与执行器原始结果（{@code result}）分离，
 * 本服务只写入核验结论，绝不覆盖 {@code result = UNCERTAIN} 的原始执行结果。
 * </p>
 */
@Slf4j
@Service
public class TicketDispositionService {

    private static final int MAX_REASON_LENGTH = 500;

    private final TicketMapper ticketMapper;
    private final TicketExecutionMapper ticketExecutionMapper;
    private final TicketDispositionMapper ticketDispositionMapper;
    private final CurrentUserService currentUserService;

    public TicketDispositionService(TicketMapper ticketMapper,
                                    TicketExecutionMapper ticketExecutionMapper,
                                    TicketDispositionMapper ticketDispositionMapper,
                                    CurrentUserService currentUserService) {
        this.ticketMapper = ticketMapper;
        this.ticketExecutionMapper = ticketExecutionMapper;
        this.ticketDispositionMapper = ticketDispositionMapper;
        this.currentUserService = currentUserService;
    }

    /** 统一处置入口：仅系统管理员；原因必填且不超过 500 字符。 */
    @Transactional
    public void dispose(Long ticketId, DispositionRequest request) {
        CurrentUserService.CurrentUser operator = currentUserService.requireSystemAdmin();
        if (request == null) {
            throw new BusinessException("处置请求不能为空");
        }
        DispositionType type = requireType(request.type());
        String reason = requireReason(request.reason());

        switch (type) {
            case CONFIRM_SUCCESS -> confirmSuccess(ticketId, operator, request.executionId(), reason);
            case CONFIRM_NOT_EXECUTED -> confirmNotExecuted(ticketId, operator, request.executionId(), reason);
            case RETRY_AUTHORIZE -> retryAuthorize(ticketId, operator, reason);
            case TERMINATE -> terminate(ticketId, operator, reason);
        }
    }

    /** 确认执行成功：核验该执行记录并置 CONFIRMED_SUCCESS，工单 APPROVED -> RESOLVED */
    private void confirmSuccess(Long ticketId, CurrentUserService.CurrentUser operator, Long executionId, String reason) {
        requireApprovedTicket(ticketId);
        requireUnverifiedUncertainExecution(ticketId, executionId);

        int updatedRecord = ticketExecutionMapper.updateVerification(
                executionId, ExecutionVerification.CONFIRMED_SUCCESS.name(), operator.userId(), LocalDateTime.now());
        if (updatedRecord == 0) {
            throw new BusinessException("执行记录已被核验，请勿重复处置");
        }

        int updatedTicket = ticketMapper.updateTicketStatus(
                ticketId, TicketStatus.APPROVED.name(), TicketStatus.RESOLVED.name());
        if (updatedTicket == 0) {
            throw new BusinessException("工单状态已变化，处置未生效");
        }

        insertDisposition(ticketId, executionId, DispositionType.CONFIRM_SUCCESS,
                reason, operator.userId(), TicketStatus.APPROVED, TicketStatus.RESOLVED);
        log.info("确认执行成功: ticketId={}, executionId={}, operatorUserId={}", ticketId, executionId, operator.userId());
    }

    /** 确认未执行：核验该执行记录并置 CONFIRMED_NOT_EXECUTED，工单保持 APPROVED 允许再次执行 */
    private void confirmNotExecuted(Long ticketId, CurrentUserService.CurrentUser operator, Long executionId, String reason) {
        requireApprovedTicket(ticketId);
        requireUnverifiedUncertainExecution(ticketId, executionId);

        int updatedRecord = ticketExecutionMapper.updateVerification(
                executionId, ExecutionVerification.CONFIRMED_NOT_EXECUTED.name(), operator.userId(), LocalDateTime.now());
        if (updatedRecord == 0) {
            throw new BusinessException("执行记录已被核验，请勿重复处置");
        }

        // 工单保持 APPROVED，不更新状态；是否仍有其他未核验记录由执行侧 count 守卫兜底。
        insertDisposition(ticketId, executionId, DispositionType.CONFIRM_NOT_EXECUTED,
                reason, operator.userId(), TicketStatus.APPROVED, TicketStatus.APPROVED);
        log.info("确认未执行: ticketId={}, executionId={}, operatorUserId={}", ticketId, executionId, operator.userId());
    }

    /** 授权重试：原子递增额外执行额度，工单保持 APPROVED */
    private void retryAuthorize(Long ticketId, CurrentUserService.CurrentUser operator, String reason) {
        requireApprovedTicket(ticketId);

        int updated = ticketMapper.incrementExtraAttempts(ticketId);
        if (updated == 0) {
            throw new BusinessException("工单状态已变化，无法授权重试");
        }

        insertDisposition(ticketId, null, DispositionType.RETRY_AUTHORIZE,
                reason, operator.userId(), TicketStatus.APPROVED, TicketStatus.APPROVED);
        log.info("授权重试: ticketId={}, operatorUserId={}", ticketId, operator.userId());
    }

    /** 终止工单：APPROVED -> ABORTED，不修改原有执行记录 */
    private void terminate(Long ticketId, CurrentUserService.CurrentUser operator, String reason) {
        requireApprovedTicket(ticketId);

        int updated = ticketMapper.updateTicketStatus(
                ticketId, TicketStatus.APPROVED.name(), TicketStatus.ABORTED.name());
        if (updated == 0) {
            throw new BusinessException("工单状态已变化，无法终止");
        }

        insertDisposition(ticketId, null, DispositionType.TERMINATE,
                reason, operator.userId(), TicketStatus.APPROVED, TicketStatus.ABORTED);
        log.info("终止工单: ticketId={}, operatorUserId={}", ticketId, operator.userId());
    }

    private Ticket requireApprovedTicket(Long ticketId) {
        if (ticketId == null || ticketId <= 0) {
            throw new BusinessException("工单 ID 非法");
        }
        Ticket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null) {
            throw new BusinessException("工单不存在");
        }
        if (ticket.getStatus() != TicketStatus.APPROVED) {
            throw new BusinessException("工单当前状态不允许处置");
        }
        return ticket;
    }

    private void requireUnverifiedUncertainExecution(Long ticketId, Long executionId) {
        if (executionId == null || executionId <= 0) {
            throw new BusinessException("执行记录 ID 非法");
        }
        TicketExecution execution = ticketExecutionMapper.selectById(executionId);
        if (execution == null) {
            throw new BusinessException("执行记录不存在");
        }
        if (!ticketId.equals(execution.getTicketId())) {
            throw new BusinessException("执行记录不属于该工单");
        }
        if (execution.getResult() != ExecutionResult.UNCERTAIN) {
            throw new BusinessException("该执行记录结果不是未确定，无法核验");
        }
        if (execution.getVerificationStatus() != null) {
            throw new BusinessException("该执行记录已核验，请勿重复处置");
        }
    }

    private DispositionType requireType(DispositionType type) {
        if (type == null) {
            throw new BusinessException("处置类型不能为空");
        }
        return type;
    }

    private String requireReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException("处置原因不能为空");
        }
        String trimmed = reason.trim();
        if (trimmed.length() > MAX_REASON_LENGTH) {
            throw new BusinessException("处置原因长度不能超过 " + MAX_REASON_LENGTH);
        }
        return trimmed;
    }

    private void insertDisposition(Long ticketId, Long executionId, DispositionType type,
                                   String reason, Long operatorUserId,
                                   TicketStatus fromStatus, TicketStatus toStatus) {
        TicketDisposition disposition = new TicketDisposition();
        disposition.setTicketId(ticketId);
        disposition.setExecutionId(executionId);
        disposition.setDispositionType(type);
        disposition.setReason(reason);
        disposition.setOperatorUserId(operatorUserId);
        disposition.setFromStatus(fromStatus);
        disposition.setToStatus(toStatus);
        disposition.setCreatedAt(LocalDateTime.now());
        ticketDispositionMapper.insert(disposition);
    }
}
