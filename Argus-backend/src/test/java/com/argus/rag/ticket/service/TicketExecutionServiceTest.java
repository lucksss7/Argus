package com.argus.rag.ticket.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ForbiddenException;
import com.argus.rag.ticket.config.TicketProperties;
import com.argus.rag.ticket.executor.AccountProvisioningExecutor;
import com.argus.rag.ticket.executor.ProvisionCommand;
import com.argus.rag.ticket.executor.ProvisionResult;
import com.argus.rag.ticket.mapper.ApprovalRequestMapper;
import com.argus.rag.ticket.mapper.TicketExecutionMapper;
import com.argus.rag.ticket.mapper.TicketMapper;
import com.argus.rag.ticket.model.entity.Ticket;
import com.argus.rag.ticket.model.entity.TicketExecution;
import com.argus.rag.ticket.model.enums.ExecutionResult;
import com.argus.rag.ticket.model.enums.ExecutionType;
import com.argus.rag.ticket.model.enums.PermissionLevel;
import com.argus.rag.ticket.model.enums.TicketStatus;
import com.argus.rag.ticket.model.vo.ExecuteTicketResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("工单执行服务")
class TicketExecutionServiceTest {

    @Mock
    private TicketMapper ticketMapper;

    @Mock
    private TicketExecutionMapper ticketExecutionMapper;

    @Mock
    private ApprovalRequestMapper approvalRequestMapper;

    @Mock
    private AccountProvisioningExecutor executor;

    @Mock
    private CurrentUserService currentUserService;

    private TicketExecutionService service() {
        TicketExecutionTransactionService txService = new TicketExecutionTransactionService(
                ticketMapper, ticketExecutionMapper, approvalRequestMapper, new ObjectMapper(), new TicketProperties());
        return new TicketExecutionService(txService, executor, currentUserService);
    }

    @Test
    @DisplayName("成功执行：IN_PROGRESS -> RESOLVED，记录 SUCCESS")
    void shouldExecuteSuccessfully() {
        stubPreconditionsAndBegin();
        when(executor.execute(any(ProvisionCommand.class)))
                .thenReturn(ProvisionResult.success(ProvisionResult.ProvisionErrorCode.PROVISIONED, "ext-1"));
        when(ticketExecutionMapper.updateExecutionResult(any(), any(), any(), any(), any())).thenReturn(1);
        when(ticketMapper.updateTicketStatus(42L, "IN_PROGRESS", "RESOLVED")).thenReturn(1);

        ExecuteTicketResult result = service().execute(42L);

        assertThat(result.ticketId()).isEqualTo(42L);
        assertThat(result.attemptNo()).isEqualTo(1);
        assertThat(result.result()).isEqualTo(ExecutionResult.SUCCESS);

        verify(ticketMapper).updateTicketStatus(42L, "APPROVED", "IN_PROGRESS");
        verify(ticketMapper).updateTicketStatus(42L, "IN_PROGRESS", "RESOLVED");
        verify(ticketExecutionMapper).updateExecutionResult(eq(100L), eq("SUCCESS"), isNull(), any(), any());
        verify(executor).execute(any(ProvisionCommand.class));

        ArgumentCaptor<TicketExecution> captor = ArgumentCaptor.forClass(TicketExecution.class);
        verify(ticketExecutionMapper).insert(captor.capture());
        TicketExecution record = captor.getValue();
        assertThat(record.getResult()).isEqualTo(ExecutionResult.PENDING);
        assertThat(record.getAttemptNo()).isEqualTo(1);
        assertThat(record.getExecutionType()).isEqualTo(ExecutionType.PROVISION_ACCOUNT);
        assertThat(record.getTicketId()).isEqualTo(42L);
        assertThat(record.getOperatorUserId()).isEqualTo(1L);
        assertThat(record.getIdempotencyKey()).isEqualTo("PROVISION_ACCOUNT:42");
    }

    @Test
    @DisplayName("明确失败：工单回到 APPROVED，记录 FAILED")
    void shouldRecordFailureAndReturnToApproved() {
        stubPreconditionsAndBegin();
        when(executor.execute(any(ProvisionCommand.class))).thenReturn(ProvisionResult.failed(ProvisionResult.ProvisionErrorCode.TARGET_REFUSED));
        when(ticketExecutionMapper.updateExecutionResult(any(), any(), any(), any(), any())).thenReturn(1);
        when(ticketMapper.updateTicketStatus(42L, "IN_PROGRESS", "APPROVED")).thenReturn(1);

        ExecuteTicketResult result = service().execute(42L);

        assertThat(result.result()).isEqualTo(ExecutionResult.FAILED);
        assertThat(result.message()).isEqualTo("目标系统拒绝：账号开通失败");
        verify(ticketMapper).updateTicketStatus(42L, "IN_PROGRESS", "APPROVED");
        verify(ticketExecutionMapper).updateExecutionResult(eq(100L), eq("FAILED"), eq("目标系统拒绝：账号开通失败"), isNull(), any());
    }

    @Test
    @DisplayName("结果不确定：工单回到 APPROVED，记录 UNCERTAIN")
    void shouldRecordUncertainAndReturnToApproved() {
        stubPreconditionsAndBegin();
        when(executor.execute(any(ProvisionCommand.class)))
                .thenReturn(ProvisionResult.uncertain(ProvisionResult.ProvisionErrorCode.TARGET_TIMEOUT, null));
        when(ticketExecutionMapper.updateExecutionResult(any(), any(), any(), any(), any())).thenReturn(1);
        when(ticketMapper.updateTicketStatus(42L, "IN_PROGRESS", "APPROVED")).thenReturn(1);

        ExecuteTicketResult result = service().execute(42L);

        assertThat(result.result()).isEqualTo(ExecutionResult.UNCERTAIN);
        assertThat(result.message()).isEqualTo("目标系统无响应，无法确认账号是否已开通，请人工核实");
        verify(ticketMapper).updateTicketStatus(42L, "IN_PROGRESS", "APPROVED");
        verify(ticketExecutionMapper).updateExecutionResult(eq(100L), eq("UNCERTAIN"), eq("目标系统无响应，无法确认账号是否已开通，请人工核实"), isNull(), any());
    }

    @Test
    @DisplayName("工单非 APPROVED 状态应拒绝执行")
    void shouldRejectExecutionWhenTicketNotApproved() {
        when(currentUserService.requireSystemAdmin()).thenReturn(admin(1L));
        when(ticketMapper.selectById(42L)).thenReturn(ticket(42L, TicketStatus.OPEN));

        assertThatThrownBy(() -> service().execute(42L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不允许执行");
        verify(ticketMapper, never()).updateTicketStatus(any(), any(), any());
    }

    @Test
    @DisplayName("缺少审批通过记录应拒绝执行")
    void shouldRejectExecutionWithoutApprovalRecord() {
        when(currentUserService.requireSystemAdmin()).thenReturn(admin(1L));
        when(ticketMapper.selectById(42L)).thenReturn(ticket(42L, TicketStatus.APPROVED));
        when(approvalRequestMapper.countApprovedByTicketId(42L)).thenReturn(0L);

        assertThatThrownBy(() -> service().execute(42L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("审批通过记录");
        verify(ticketMapper, never()).updateTicketStatus(any(), any(), any());
    }

    @Test
    @DisplayName("存在未核实的 UNCERTAIN 记录应拒绝再次执行")
    void shouldRejectExecutionWhenUncertainExists() {
        when(currentUserService.requireSystemAdmin()).thenReturn(admin(1L));
        when(ticketMapper.selectById(42L)).thenReturn(ticket(42L, TicketStatus.APPROVED));
        when(approvalRequestMapper.countApprovedByTicketId(42L)).thenReturn(1L);
        when(ticketExecutionMapper.countUncertainByTicketId(42L)).thenReturn(1L);

        assertThatThrownBy(() -> service().execute(42L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未核实");
        verify(ticketMapper, never()).updateTicketStatus(any(), any(), any());
    }

    @Test
    @DisplayName("执行次数达到上限时拒绝执行，不创建执行记录也不进入执行")
    void shouldRejectExecutionWhenMaxAttemptsReached() {
        when(currentUserService.requireSystemAdmin()).thenReturn(admin(1L));
        when(ticketMapper.selectById(42L)).thenReturn(ticket(42L, TicketStatus.APPROVED));
        when(approvalRequestMapper.countApprovedByTicketId(42L)).thenReturn(1L);
        when(ticketExecutionMapper.countUncertainByTicketId(42L)).thenReturn(0L);
        when(ticketMapper.updateTicketStatus(42L, "APPROVED", "IN_PROGRESS")).thenReturn(1);
        when(ticketExecutionMapper.selectMaxAttemptNoByTicketId(42L)).thenReturn(3);

        assertThatThrownBy(() -> service().execute(42L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("执行次数已达上限");

        // 上限拒绝发生在 begin 阶段：不写 PENDING 记录、不回写执行结果、不进入执行器
        verify(ticketExecutionMapper, never()).insert(any(TicketExecution.class));
        verify(ticketExecutionMapper, never()).updateExecutionResult(any(), any(), any(), any(), any());
        verify(executor, never()).execute(any(ProvisionCommand.class));
        // 不进入 finalize 的状态流转（fromStatus=IN_PROGRESS 的更新）
        verify(ticketMapper, never()).updateTicketStatus(eq(42L), eq("IN_PROGRESS"), any());
    }

    @Test
    @DisplayName("尚未达到上限时允许执行下一次尝试（attempt_no=3）")
    void shouldAllowExecutionWhenUnderMaxAttempts() {
        when(currentUserService.requireSystemAdmin()).thenReturn(admin(1L));
        when(ticketMapper.selectById(42L)).thenReturn(ticket(42L, TicketStatus.APPROVED));
        when(approvalRequestMapper.countApprovedByTicketId(42L)).thenReturn(1L);
        when(ticketExecutionMapper.countUncertainByTicketId(42L)).thenReturn(0L);
        when(ticketMapper.updateTicketStatus(42L, "APPROVED", "IN_PROGRESS")).thenReturn(1);
        when(ticketExecutionMapper.selectMaxAttemptNoByTicketId(42L)).thenReturn(2);
        when(ticketExecutionMapper.insert(any(TicketExecution.class))).thenAnswer(inv -> {
            inv.<TicketExecution>getArgument(0).setId(100L);
            return 1;
        });
        when(executor.execute(any(ProvisionCommand.class)))
                .thenReturn(ProvisionResult.success(ProvisionResult.ProvisionErrorCode.PROVISIONED, "ext-1"));
        when(ticketExecutionMapper.updateExecutionResult(any(), any(), any(), any(), any())).thenReturn(1);
        when(ticketMapper.updateTicketStatus(42L, "IN_PROGRESS", "RESOLVED")).thenReturn(1);

        ExecuteTicketResult result = service().execute(42L);

        assertThat(result.attemptNo()).isEqualTo(3);
        assertThat(result.result()).isEqualTo(ExecutionResult.SUCCESS);

        ArgumentCaptor<TicketExecution> captor = ArgumentCaptor.forClass(TicketExecution.class);
        verify(ticketExecutionMapper).insert(captor.capture());
        assertThat(captor.getValue().getAttemptNo()).isEqualTo(3);
    }

    @Test
    @DisplayName("存在 UNCERTAIN 记录时优先以未核实拒绝，不误报达到上限")
    void shouldPrioritizeUncertainOverMaxAttempts() {
        when(currentUserService.requireSystemAdmin()).thenReturn(admin(1L));
        when(ticketMapper.selectById(42L)).thenReturn(ticket(42L, TicketStatus.APPROVED));
        when(approvalRequestMapper.countApprovedByTicketId(42L)).thenReturn(1L);
        when(ticketExecutionMapper.countUncertainByTicketId(42L)).thenReturn(1L);

        assertThatThrownBy(() -> service().execute(42L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未核实")
                .hasMessageNotContaining("已达上限");

        // UNCERTAIN 检查先于上限检查：不触发状态更新、不查 max、不写记录
        verify(ticketMapper, never()).updateTicketStatus(any(), any(), any());
        verify(ticketExecutionMapper, never()).selectMaxAttemptNoByTicketId(any());
        verify(ticketExecutionMapper, never()).insert(any(TicketExecution.class));
    }

    @Test
    @DisplayName("额外授权后允许按新上限执行：锁后重读 extraAttempts=1，attempt_no=4 不再超限")
    void shouldAllowExecutionBeyondBaseLimitWithExtraAttempts() {
        when(currentUserService.requireSystemAdmin()).thenReturn(admin(1L));
        // 锁前快照 extraAttempts=0；锁后重读为 1（RETRY_AUTHORIZE 已生效）
        when(ticketMapper.selectById(42L)).thenReturn(
                ticketWithExtra(42L, TicketStatus.APPROVED, 0),
                ticketWithExtra(42L, TicketStatus.APPROVED, 1));
        when(approvalRequestMapper.countApprovedByTicketId(42L)).thenReturn(1L);
        when(ticketExecutionMapper.countUncertainByTicketId(42L)).thenReturn(0L);
        when(ticketMapper.updateTicketStatus(42L, "APPROVED", "IN_PROGRESS")).thenReturn(1);
        when(ticketExecutionMapper.selectMaxAttemptNoByTicketId(42L)).thenReturn(3); // attempt_no = 4
        when(ticketExecutionMapper.insert(any(TicketExecution.class))).thenAnswer(inv -> {
            inv.<TicketExecution>getArgument(0).setId(100L);
            return 1;
        });
        when(executor.execute(any(ProvisionCommand.class)))
                .thenReturn(ProvisionResult.success(ProvisionResult.ProvisionErrorCode.PROVISIONED, "ext-1"));
        when(ticketExecutionMapper.updateExecutionResult(any(), any(), any(), any(), any())).thenReturn(1);
        when(ticketMapper.updateTicketStatus(42L, "IN_PROGRESS", "RESOLVED")).thenReturn(1);

        ExecuteTicketResult result = service().execute(42L);

        assertThat(result.attemptNo()).isEqualTo(4);
        assertThat(result.result()).isEqualTo(ExecutionResult.SUCCESS);
    }

    @Test
    @DisplayName("并发执行守卫：APPROVED->IN_PROGRESS 条件更新 0 行应拒绝")
    void shouldRejectConcurrentExecution() {
        when(currentUserService.requireSystemAdmin()).thenReturn(admin(1L));
        when(ticketMapper.selectById(42L)).thenReturn(ticket(42L, TicketStatus.APPROVED));
        when(approvalRequestMapper.countApprovedByTicketId(42L)).thenReturn(1L);
        when(ticketExecutionMapper.countUncertainByTicketId(42L)).thenReturn(0L);
        when(ticketMapper.updateTicketStatus(42L, "APPROVED", "IN_PROGRESS")).thenReturn(0);

        assertThatThrownBy(() -> service().execute(42L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重复执行");
        verify(ticketExecutionMapper, never()).insert(any(TicketExecution.class));
    }

    @Test
    @DisplayName("执行器抛异常应按结果不确定处理，且不把原始异常消息写入 errorMessage")
    void shouldTreatExecutorExceptionAsUncertainWithoutLeakingMessage() {
        stubPreconditionsAndBegin();
        when(executor.execute(any(ProvisionCommand.class)))
                .thenThrow(new IllegalStateException("boom jdbc:postgresql://10.0.0.5:5432 secret=p@ss"));
        when(ticketExecutionMapper.updateExecutionResult(any(), any(), any(), any(), any())).thenReturn(1);
        when(ticketMapper.updateTicketStatus(42L, "IN_PROGRESS", "APPROVED")).thenReturn(1);

        ExecuteTicketResult result = service().execute(42L);

        assertThat(result.result()).isEqualTo(ExecutionResult.UNCERTAIN);

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(ticketExecutionMapper).updateExecutionResult(eq(100L), eq("UNCERTAIN"), messageCaptor.capture(), isNull(), any());
        assertThat(messageCaptor.getValue())
                .isEqualTo("执行器调用异常，结果不确定，请联系管理员人工核实")
                .doesNotContain("boom", "jdbc", "postgresql", "secret", "p@ss");
    }

    @Test
    @DisplayName("每次尝试独立留档，attempt_no 递增且旧记录不被覆盖")
    void shouldIncrementAttemptNoAndKeepSeparateRecords() {
        when(currentUserService.requireSystemAdmin()).thenReturn(admin(1L));
        when(ticketMapper.selectById(42L)).thenReturn(ticket(42L, TicketStatus.APPROVED));
        when(approvalRequestMapper.countApprovedByTicketId(42L)).thenReturn(1L);
        when(ticketExecutionMapper.countUncertainByTicketId(42L)).thenReturn(0L);
        when(ticketMapper.updateTicketStatus(42L, "APPROVED", "IN_PROGRESS")).thenReturn(1);
        when(ticketExecutionMapper.selectMaxAttemptNoByTicketId(42L)).thenReturn(0, 1);
        when(ticketExecutionMapper.insert(any(TicketExecution.class))).thenAnswer(inv -> {
            inv.<TicketExecution>getArgument(0).setId(100L);
            return 1;
        });
        when(executor.execute(any(ProvisionCommand.class)))
                .thenReturn(ProvisionResult.failed(ProvisionResult.ProvisionErrorCode.TARGET_REFUSED), ProvisionResult.success(ProvisionResult.ProvisionErrorCode.PROVISIONED, "ext-1"));
        when(ticketExecutionMapper.updateExecutionResult(any(), any(), any(), any(), any())).thenReturn(1);
        when(ticketMapper.updateTicketStatus(42L, "IN_PROGRESS", "APPROVED")).thenReturn(1);
        when(ticketMapper.updateTicketStatus(42L, "IN_PROGRESS", "RESOLVED")).thenReturn(1);

        service().execute(42L);
        ExecuteTicketResult second = service().execute(42L);

        assertThat(second.attemptNo()).isEqualTo(2);
        assertThat(second.result()).isEqualTo(ExecutionResult.SUCCESS);

        ArgumentCaptor<TicketExecution> captor = ArgumentCaptor.forClass(TicketExecution.class);
        verify(ticketExecutionMapper, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues().get(0).getAttemptNo()).isEqualTo(1);
        assertThat(captor.getAllValues().get(1).getAttemptNo()).isEqualTo(2);
        verify(ticketExecutionMapper, times(2)).updateExecutionResult(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("非系统管理员执行应被拒绝（403）")
    void shouldRejectExecutionByNonAdmin() {
        when(currentUserService.requireSystemAdmin())
                .thenThrow(new ForbiddenException("当前用户不是系统管理员"));

        assertThatThrownBy(() -> service().execute(42L))
                .isInstanceOf(ForbiddenException.class);
        verify(ticketMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("成功执行：response_payload 仅持久化受控 code 与 externalRef，不含派生 message 或自由文本")
    void shouldPersistOnlyCodeAndExternalRefOnSuccess() {
        stubPreconditionsAndBegin();
        when(executor.execute(any(ProvisionCommand.class)))
                .thenReturn(ProvisionResult.success(ProvisionResult.ProvisionErrorCode.PROVISIONED, "ext-1"));
        when(ticketExecutionMapper.updateExecutionResult(any(), any(), any(), any(), any())).thenReturn(1);
        when(ticketMapper.updateTicketStatus(42L, "IN_PROGRESS", "RESOLVED")).thenReturn(1);

        service().execute(42L);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(ticketExecutionMapper).updateExecutionResult(eq(100L), eq("SUCCESS"), isNull(), payloadCaptor.capture(), any());
        assertThat(payloadCaptor.getValue()).isEqualTo("{\"code\":\"PROVISIONED\",\"externalRef\":\"ext-1\"}");
    }

    /** 共用前置桩：管理员触发、工单 APPROVED、有审批记录、无 UNCERTAIN、begin 条件更新成功、attempt_no=0、插入回填 id=100 */
    private void stubPreconditionsAndBegin() {
        when(currentUserService.requireSystemAdmin()).thenReturn(admin(1L));
        when(ticketMapper.selectById(42L)).thenReturn(ticket(42L, TicketStatus.APPROVED));
        when(approvalRequestMapper.countApprovedByTicketId(42L)).thenReturn(1L);
        when(ticketExecutionMapper.countUncertainByTicketId(42L)).thenReturn(0L);
        when(ticketMapper.updateTicketStatus(42L, "APPROVED", "IN_PROGRESS")).thenReturn(1);
        when(ticketExecutionMapper.selectMaxAttemptNoByTicketId(42L)).thenReturn(0);
        when(ticketExecutionMapper.insert(any(TicketExecution.class))).thenAnswer(inv -> {
            inv.<TicketExecution>getArgument(0).setId(100L);
            return 1;
        });
    }

    private CurrentUserService.CurrentUser admin(long id) {
        return new CurrentUserService.CurrentUser(id, "u" + id, "管理员" + id, SystemRole.ADMIN, false);
    }

    private Ticket ticket(long id, TicketStatus status) {
        Ticket ticket = new Ticket();
        ticket.setId(id);
        ticket.setTicketNo("TK" + id);
        ticket.setStatus(status);
        ticket.setSystemName("erp");
        ticket.setTargetUsername("zhangsan");
        ticket.setPermissionLevel(PermissionLevel.READ_ONLY);
        return ticket;
    }

    private Ticket ticketWithExtra(long id, TicketStatus status, int extraAttempts) {
        Ticket ticket = ticket(id, status);
        ticket.setExtraAttempts(extraAttempts);
        return ticket;
    }
}
