package com.argus.rag.ticket.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.ticket.executor.AccountProvisioningExecutor;
import com.argus.rag.ticket.executor.ProvisionResult;
import com.argus.rag.ticket.model.vo.ExecuteTicketResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 执行编排服务：受控执行账号开通。
 * <p>
 * 由系统管理员手动触发；编排「开始事务 -> 外部执行器调用 -> 结束事务」，
 * 外部执行器调用位于两个数据库事务之间（见 {@link TicketExecutionTransactionService}）。
 * <p>
 * 执行器抛出运行时异常时无法确认外部副作用是否已发生，按结果不确定（UNCERTAIN）处理，
 * 绝不宣称失败或成功；若结束事务（T2）落库失败，异常向上抛出，数据库留
 * IN_PROGRESS + PENDING 供人工核实，同样不得宣称失败或成功。
 */
@Slf4j
@Service
public class TicketExecutionService {

    private final TicketExecutionTransactionService txService;
    private final AccountProvisioningExecutor executor;
    private final CurrentUserService currentUserService;

    public TicketExecutionService(TicketExecutionTransactionService txService,
                                  AccountProvisioningExecutor executor,
                                  CurrentUserService currentUserService) {
        this.txService = txService;
        this.executor = executor;
        this.currentUserService = currentUserService;
    }

    /** 执行工单账号开通。本方法不开启事务，事务边界由 txService 的两个方法分别控制。 */
    public ExecuteTicketResult execute(Long ticketId) {
        CurrentUserService.CurrentUser operator = currentUserService.requireSystemAdmin();

        AttemptContext ctx = txService.begin(ticketId, operator.userId());

        ProvisionResult result;
        try {
            result = executor.execute(ctx.command());
        } catch (RuntimeException e) {
            log.warn("执行器调用异常，按结果不确定处理: ticketId={}, attemptNo={}, code={}, exceptionType={}",
                    ticketId, ctx.attemptNo(), ProvisionResult.ProvisionErrorCode.EXECUTOR_EXCEPTION,
                    e.getClass().getSimpleName());
            result = ProvisionResult.uncertain(ProvisionResult.ProvisionErrorCode.EXECUTOR_EXCEPTION, null);
        }

        return txService.finalize(ctx, result);
    }
}
