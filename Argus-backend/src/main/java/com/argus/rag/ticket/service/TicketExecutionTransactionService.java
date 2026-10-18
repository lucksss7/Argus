package com.argus.rag.ticket.service;

import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.ticket.config.TicketProperties;
import com.argus.rag.ticket.executor.ProvisionCommand;
import com.argus.rag.ticket.executor.ProvisionResult;
import com.argus.rag.ticket.mapper.ApprovalRequestMapper;
import com.argus.rag.ticket.mapper.TicketExecutionMapper;
import com.argus.rag.ticket.mapper.TicketMapper;
import com.argus.rag.ticket.model.entity.Ticket;
import com.argus.rag.ticket.model.entity.TicketExecution;
import com.argus.rag.ticket.model.enums.ExecutionResult;
import com.argus.rag.ticket.model.enums.ExecutionType;
import com.argus.rag.ticket.model.enums.TicketStatus;
import com.argus.rag.ticket.model.vo.ExecuteTicketResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 执行开始与结束的事务服务。
 * <p>
 * 数据库写操作拆成两个独立事务（{@link #begin} 与 {@link #finalize}），
 * 外部执行器调用夹在两者之间、位于事务之外，避免「外部操作已成功但数据库事务回滚」造成不一致。
 * <p>
 * {@link #begin} 先持久化 PENDING 执行记录再交回执行器调用，作为崩溃恢复的持久证据
 * （进程可能在执行器调用前后崩溃，留下 IN_PROGRESS + PENDING，需人工核实，不能当作失败直接重试）。
 * 同一工单的并发执行由 APPROVED -> IN_PROGRESS 条件更新串行化：该条件更新对工单行加锁，
 * 只有一个事务能改走 APPROVED，因此 {@code SELECT MAX(attempt_no)+1} 在串行化保护下无竞态。
 */
@Slf4j
@Service
public class TicketExecutionTransactionService {

    /**
     * 执行记录幂等键前缀，拼上 ticketId 后形如 PROVISION_ACCOUNT:42。
     * 仅用于 ticket_executions 表按工单给多次尝试分组（配合 attempt_no 唯一），
     * 与执行器的业务去重键（mock 按 systemName+targetUsername+permissionLevel 推导）不是同一概念，
     * 也绝不代表外部账号只开通一次。
     */
    private static final String EXECUTION_IDEMPOTENCY_PREFIX = "PROVISION_ACCOUNT:";

    private final TicketMapper ticketMapper;
    private final TicketExecutionMapper ticketExecutionMapper;
    private final ApprovalRequestMapper approvalRequestMapper;
    private final ObjectMapper objectMapper;
    private final TicketProperties ticketProperties;

    public TicketExecutionTransactionService(TicketMapper ticketMapper,
                                             TicketExecutionMapper ticketExecutionMapper,
                                             ApprovalRequestMapper approvalRequestMapper,
                                             ObjectMapper objectMapper,
                                             TicketProperties ticketProperties) {
        this.ticketMapper = ticketMapper;
        this.ticketExecutionMapper = ticketExecutionMapper;
        this.approvalRequestMapper = approvalRequestMapper;
        this.objectMapper = objectMapper;
        this.ticketProperties = ticketProperties;
    }

    /** 事务 T1：校验前置条件、APPROVED -> IN_PROGRESS、插入 PENDING 执行记录 */
    @Transactional
    public AttemptContext begin(Long ticketId, Long operatorUserId) {
        Ticket ticket = loadTicket(ticketId);
        requireApproved(ticket);
        requireApprovalRecord(ticketId);
        requireNoUnresolvedUncertain(ticketId);

        int updated = ticketMapper.updateTicketStatus(
                ticketId, TicketStatus.APPROVED.name(), TicketStatus.IN_PROGRESS.name());
        if (updated == 0) {
            throw new BusinessException("工单已被处理，请勿重复执行");
        }

        // APPROVED -> IN_PROGRESS 条件更新已取得工单行锁，与 RETRY_AUTHORIZE 的
        // 原子递增（WHERE status='APPROVED'）串行化；此处重读以获得最新的 extra_attempts，
        // 而不是使用锁前加载的旧快照，避免「授权已加但执行仍按旧上限」或「超限执行」。
        Ticket locked = loadTicket(ticketId);

        int attemptNo = nextAttemptNo(ticketId);
        requireWithinMaxAttempts(attemptNo, locked.getExtraAttempts());
        ProvisionCommand command = new ProvisionCommand(
                ticket.getSystemName(), ticket.getTargetUsername(), ticket.getPermissionLevel());
        TicketExecution record = buildPendingRecord(ticketId, operatorUserId, attemptNo, command);
        ticketExecutionMapper.insert(record);

        log.info("开始执行: ticketId={}, attemptNo={}, operatorUserId={}", ticketId, attemptNo, operatorUserId);
        return new AttemptContext(record.getId(), ticketId, attemptNo, command);
    }

    /** 事务 T2：写入该次尝试的最终结果，并流转工单状态 */
    @Transactional
    public ExecuteTicketResult finalize(AttemptContext ctx, ProvisionResult result) {
        ExecutionResult executionResult = toExecutionResult(result.outcome());
        TicketStatus targetStatus = toTargetStatus(result.outcome());
        String errorMessage = result.outcome() == ProvisionResult.Outcome.SUCCESS ? null : result.message();
        String responsePayload = result.outcome() == ProvisionResult.Outcome.SUCCESS
                ? toJson(safePayload(result)) : null;
        LocalDateTime finishedAt = LocalDateTime.now();

        int updatedRecord = ticketExecutionMapper.updateExecutionResult(
                ctx.executionRecordId(), executionResult.name(), errorMessage, responsePayload, finishedAt);
        if (updatedRecord == 0) {
            throw new BusinessException("执行记录状态异常，无法写入执行结果");
        }

        int updatedTicket = ticketMapper.updateTicketStatus(
                ctx.ticketId(), TicketStatus.IN_PROGRESS.name(), targetStatus.name());
        if (updatedTicket == 0) {
            throw new BusinessException("工单状态异常，无法写入执行结果");
        }

        log.info("结束执行: ticketId={}, attemptNo={}, result={}", ctx.ticketId(), ctx.attemptNo(), executionResult);
        return new ExecuteTicketResult(ctx.ticketId(), ctx.attemptNo(), executionResult, result.message());
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

    private void requireApproved(Ticket ticket) {
        if (ticket.getStatus() != TicketStatus.APPROVED) {
            throw new BusinessException("工单当前状态不允许执行");
        }
    }

    private void requireApprovalRecord(Long ticketId) {
        Long count = approvalRequestMapper.countApprovedByTicketId(ticketId);
        if (count == null || count == 0) {
            throw new BusinessException("工单缺少有效的审批通过记录");
        }
    }

    /** 存在尚未人工核验的 UNCERTAIN 记录（result=UNCERTAIN 且 verification_status IS NULL）时拒绝执行 */
    private void requireNoUnresolvedUncertain(Long ticketId) {
        Long count = ticketExecutionMapper.countUncertainByTicketId(ticketId);
        if (count != null && count > 0) {
            throw new BusinessException("该工单存在结果未核实的执行记录，请先人工核实后再执行");
        }
    }

    private int nextAttemptNo(Long ticketId) {
        Integer max = ticketExecutionMapper.selectMaxAttemptNoByTicketId(ticketId);
        return (max == null ? 0 : max) + 1;
    }

    /**
     * 执行次数上限校验：上限 = {@code maxExecutionAttempts + extraAttempts}。
     * 必须在 APPROVED -> IN_PROGRESS 条件更新取得行锁后、基于锁后重读到的 extraAttempts 判断
     * （见 {@link #begin}），与 RETRY_AUTHORIZE 的原子递增串行化，避免超限执行或授权丢失。
     * 超限时抛出异常使本事务回滚，工单保持 APPROVED、不产生新的执行记录。
     */
    private void requireWithinMaxAttempts(int attemptNo, Integer extraAttempts) {
        int extra = extraAttempts == null ? 0 : extraAttempts;
        int limit = ticketProperties.getMaxExecutionAttempts() + extra;
        if (attemptNo > limit) {
            throw new BusinessException("执行次数已达上限，请联系管理员处理");
        }
    }

    private TicketExecution buildPendingRecord(Long ticketId, Long operatorUserId, int attemptNo, ProvisionCommand command) {
        TicketExecution record = new TicketExecution();
        record.setTicketId(ticketId);
        record.setExecutionType(ExecutionType.PROVISION_ACCOUNT);
        record.setResult(ExecutionResult.PENDING);
        record.setAttemptNo(attemptNo);
        record.setIdempotencyKey(EXECUTION_IDEMPOTENCY_PREFIX + ticketId);
        record.setOperatorUserId(operatorUserId);
        record.setRequestPayload(toJson(command));
        record.setStartedAt(LocalDateTime.now());
        return record;
    }

    private ExecutionResult toExecutionResult(ProvisionResult.Outcome outcome) {
        return switch (outcome) {
            case SUCCESS -> ExecutionResult.SUCCESS;
            case FAILED -> ExecutionResult.FAILED;
            case UNCERTAIN -> ExecutionResult.UNCERTAIN;
        };
    }

    private TicketStatus toTargetStatus(ProvisionResult.Outcome outcome) {
        return switch (outcome) {
            case SUCCESS -> TicketStatus.RESOLVED;
            case FAILED, UNCERTAIN -> TicketStatus.APPROVED;
        };
    }

    /**
     * 成功结果只持久化稳定的错误码与外部引用，不把派生 message 或任何自由文本写入 response_payload。
     */
    private Map<String, Object> safePayload(ProvisionResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", result.code().name());
        payload.put("externalRef", result.externalRef());
        return payload;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }
}
