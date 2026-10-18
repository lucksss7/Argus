package com.argus.rag.ticket.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ForbiddenException;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("工单人工处置服务")
class TicketDispositionServiceTest {

    @Mock
    private TicketMapper ticketMapper;

    @Mock
    private TicketExecutionMapper ticketExecutionMapper;

    @Mock
    private TicketDispositionMapper ticketDispositionMapper;

    @Mock
    private CurrentUserService currentUserService;

    private TicketDispositionService service() {
        return new TicketDispositionService(
                ticketMapper, ticketExecutionMapper, ticketDispositionMapper, currentUserService);
    }

    // ---------- 四种操作的正常路径 ----------

    @Test
    @DisplayName("确认成功：核验记录置 CONFIRMED_SUCCESS，工单 APPROVED -> RESOLVED，留审计")
    void shouldConfirmSuccess() {
        stubApprovedTicketAndUnverifiedUncertain();
        when(ticketExecutionMapper.updateVerification(eq(100L), eq("CONFIRMED_SUCCESS"), eq(1L), any())).thenReturn(1);
        when(ticketMapper.updateTicketStatus(42L, "APPROVED", "RESOLVED")).thenReturn(1);

        service().dispose(42L, new DispositionRequest(DispositionType.CONFIRM_SUCCESS, 100L, "已确认开通成功"));

        ArgumentCaptor<TicketDisposition> captor = ArgumentCaptor.forClass(TicketDisposition.class);
        verify(ticketDispositionMapper).insert(captor.capture());
        TicketDisposition d = captor.getValue();
        assertThat(d.getTicketId()).isEqualTo(42L);
        assertThat(d.getExecutionId()).isEqualTo(100L);
        assertThat(d.getDispositionType()).isEqualTo(DispositionType.CONFIRM_SUCCESS);
        assertThat(d.getReason()).isEqualTo("已确认开通成功");
        assertThat(d.getOperatorUserId()).isEqualTo(1L);
        assertThat(d.getFromStatus()).isEqualTo(TicketStatus.APPROVED);
        assertThat(d.getToStatus()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(d.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("确认未执行：核验记录置 CONFIRMED_NOT_EXECUTED，工单保持 APPROVED，不改状态")
    void shouldConfirmNotExecuted() {
        stubApprovedTicketAndUnverifiedUncertain();
        when(ticketExecutionMapper.updateVerification(eq(100L), eq("CONFIRMED_NOT_EXECUTED"), eq(1L), any())).thenReturn(1);

        service().dispose(42L, new DispositionRequest(DispositionType.CONFIRM_NOT_EXECUTED, 100L, "确认未执行"));

        verify(ticketMapper, never()).updateTicketStatus(any(), any(), any());
        ArgumentCaptor<TicketDisposition> captor = ArgumentCaptor.forClass(TicketDisposition.class);
        verify(ticketDispositionMapper).insert(captor.capture());
        TicketDisposition d = captor.getValue();
        assertThat(d.getDispositionType()).isEqualTo(DispositionType.CONFIRM_NOT_EXECUTED);
        assertThat(d.getExecutionId()).isEqualTo(100L);
        assertThat(d.getFromStatus()).isEqualTo(TicketStatus.APPROVED);
        assertThat(d.getToStatus()).isEqualTo(TicketStatus.APPROVED);
    }

    @Test
    @DisplayName("授权重试：原子递增额外额度，工单保持 APPROVED，审计不带 executionId")
    void shouldRetryAuthorize() {
        when(currentUserService.requireSystemAdmin()).thenReturn(admin(1L));
        when(ticketMapper.selectById(42L)).thenReturn(ticket(42L, TicketStatus.APPROVED));
        when(ticketMapper.incrementExtraAttempts(42L)).thenReturn(1);

        service().dispose(42L, new DispositionRequest(DispositionType.RETRY_AUTHORIZE, null, "授权重试一次"));

        verify(ticketMapper).incrementExtraAttempts(42L);
        ArgumentCaptor<TicketDisposition> captor = ArgumentCaptor.forClass(TicketDisposition.class);
        verify(ticketDispositionMapper).insert(captor.capture());
        TicketDisposition d = captor.getValue();
        assertThat(d.getDispositionType()).isEqualTo(DispositionType.RETRY_AUTHORIZE);
        assertThat(d.getExecutionId()).isNull();
        assertThat(d.getFromStatus()).isEqualTo(TicketStatus.APPROVED);
        assertThat(d.getToStatus()).isEqualTo(TicketStatus.APPROVED);
    }

    @Test
    @DisplayName("终止：工单 APPROVED -> ABORTED，不修改执行记录，审计不带 executionId")
    void shouldTerminate() {
        when(currentUserService.requireSystemAdmin()).thenReturn(admin(1L));
        when(ticketMapper.selectById(42L)).thenReturn(ticket(42L, TicketStatus.APPROVED));
        when(ticketMapper.updateTicketStatus(42L, "APPROVED", "ABORTED")).thenReturn(1);

        service().dispose(42L, new DispositionRequest(DispositionType.TERMINATE, null, "不再执行"));

        verify(ticketExecutionMapper, never()).updateVerification(any(), any(), any(), any());
        ArgumentCaptor<TicketDisposition> captor = ArgumentCaptor.forClass(TicketDisposition.class);
        verify(ticketDispositionMapper).insert(captor.capture());
        TicketDisposition d = captor.getValue();
        assertThat(d.getDispositionType()).isEqualTo(DispositionType.TERMINATE);
        assertThat(d.getExecutionId()).isNull();
        assertThat(d.getToStatus()).isEqualTo(TicketStatus.ABORTED);
    }

    // ---------- 权限与入参校验 ----------

    @Test
    @DisplayName("非系统管理员应被拒绝（403），不触碰任何 Mapper")
    void shouldRejectNonAdmin() {
        when(currentUserService.requireSystemAdmin())
                .thenThrow(new ForbiddenException("当前用户不是系统管理员"));

        assertThatThrownBy(() -> service().dispose(42L,
                new DispositionRequest(DispositionType.CONFIRM_SUCCESS, 100L, "原因")))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(ticketMapper, ticketExecutionMapper, ticketDispositionMapper);
    }

    @Test
    @DisplayName("工单非 APPROVED 状态时四种处置均应拒绝")
    void shouldRejectWhenTicketNotApprovedForAllTypes() {
        when(currentUserService.requireSystemAdmin()).thenReturn(admin(1L));
        when(ticketMapper.selectById(42L)).thenReturn(ticket(42L, TicketStatus.RESOLVED));

        TicketDispositionService service = service();
        for (DispositionType type : DispositionType.values()) {
            assertThatThrownBy(() -> service.dispose(42L, new DispositionRequest(type, null, "原因")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不允许处置");
        }

        verify(ticketExecutionMapper, never()).updateVerification(any(), any(), any(), any());
        verify(ticketMapper, never()).updateTicketStatus(any(), any(), any());
        verify(ticketMapper, never()).incrementExtraAttempts(any());
        verify(ticketDispositionMapper, never()).insert(any(TicketDisposition.class));
    }

    @Test
    @DisplayName("执行记录不存在应拒绝")
    void shouldRejectWhenExecutionNotFound() {
        when(currentUserService.requireSystemAdmin()).thenReturn(admin(1L));
        when(ticketMapper.selectById(42L)).thenReturn(ticket(42L, TicketStatus.APPROVED));
        when(ticketExecutionMapper.selectById(100L)).thenReturn(null);

        assertThatThrownBy(() -> service().dispose(42L,
                new DispositionRequest(DispositionType.CONFIRM_SUCCESS, 100L, "原因")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("执行记录不存在");

        verify(ticketExecutionMapper, never()).updateVerification(any(), any(), any(), any());
        verify(ticketDispositionMapper, never()).insert(any(TicketDisposition.class));
    }

    @Test
    @DisplayName("执行记录不属于该工单应拒绝")
    void shouldRejectWhenExecutionBelongsToOtherTicket() {
        when(currentUserService.requireSystemAdmin()).thenReturn(admin(1L));
        when(ticketMapper.selectById(42L)).thenReturn(ticket(42L, TicketStatus.APPROVED));
        when(ticketExecutionMapper.selectById(100L)).thenReturn(execution(100L, 999L, ExecutionResult.UNCERTAIN, null));

        assertThatThrownBy(() -> service().dispose(42L,
                new DispositionRequest(DispositionType.CONFIRM_SUCCESS, 100L, "原因")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于该工单");
    }

    @Test
    @DisplayName("执行记录结果非 UNCERTAIN 应拒绝")
    void shouldRejectWhenResultNotUncertain() {
        when(currentUserService.requireSystemAdmin()).thenReturn(admin(1L));
        when(ticketMapper.selectById(42L)).thenReturn(ticket(42L, TicketStatus.APPROVED));
        when(ticketExecutionMapper.selectById(100L)).thenReturn(execution(100L, 42L, ExecutionResult.SUCCESS, null));

        assertThatThrownBy(() -> service().dispose(42L,
                new DispositionRequest(DispositionType.CONFIRM_SUCCESS, 100L, "原因")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不是未确定");
    }

    @Test
    @DisplayName("执行记录已核验应拒绝重复处置")
    void shouldRejectWhenAlreadyVerified() {
        when(currentUserService.requireSystemAdmin()).thenReturn(admin(1L));
        when(ticketMapper.selectById(42L)).thenReturn(ticket(42L, TicketStatus.APPROVED));
        when(ticketExecutionMapper.selectById(100L))
                .thenReturn(execution(100L, 42L, ExecutionResult.UNCERTAIN, ExecutionVerification.CONFIRMED_SUCCESS));

        assertThatThrownBy(() -> service().dispose(42L,
                new DispositionRequest(DispositionType.CONFIRM_SUCCESS, 100L, "原因")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已核验");

        verify(ticketExecutionMapper, never()).updateVerification(any(), any(), any(), any());
        verify(ticketDispositionMapper, never()).insert(any(TicketDisposition.class));
    }

    @Test
    @DisplayName("处置原因为空应拒绝，且不读取工单")
    void shouldRejectWhenReasonBlank() {
        when(currentUserService.requireSystemAdmin()).thenReturn(admin(1L));

        assertThatThrownBy(() -> service().dispose(42L,
                new DispositionRequest(DispositionType.CONFIRM_SUCCESS, 100L, "   ")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("原因不能为空");

        verify(ticketMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("处置原因超过 500 字符应拒绝")
    void shouldRejectWhenReasonTooLong() {
        when(currentUserService.requireSystemAdmin()).thenReturn(admin(1L));

        String longReason = "x".repeat(501);
        assertThatThrownBy(() -> service().dispose(42L,
                new DispositionRequest(DispositionType.CONFIRM_SUCCESS, 100L, longReason)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("长度不能超过 500");

        verify(ticketMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("确认类处置缺少 executionId 应拒绝")
    void shouldRejectWhenExecutionIdMissingForConfirm() {
        when(currentUserService.requireSystemAdmin()).thenReturn(admin(1L));
        when(ticketMapper.selectById(42L)).thenReturn(ticket(42L, TicketStatus.APPROVED));

        assertThatThrownBy(() -> service().dispose(42L,
                new DispositionRequest(DispositionType.CONFIRM_SUCCESS, null, "原因")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("执行记录 ID 非法");

        verify(ticketExecutionMapper, never()).updateVerification(any(), any(), any(), any());
    }

    // ---------- 条件更新失败与并发 ----------

    @Test
    @DisplayName("重复核验：条件更新 0 行应拒绝，且不写审计")
    void shouldRejectDuplicateVerification() {
        stubApprovedTicketAndUnverifiedUncertain();
        when(ticketExecutionMapper.updateVerification(eq(100L), any(), eq(1L), any())).thenReturn(0);

        assertThatThrownBy(() -> service().dispose(42L,
                new DispositionRequest(DispositionType.CONFIRM_SUCCESS, 100L, "原因")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重复处置");

        verify(ticketMapper, never()).updateTicketStatus(any(), any(), any());
        verify(ticketDispositionMapper, never()).insert(any(TicketDisposition.class));
    }

    @Test
    @DisplayName("确认成功时工单状态流转 0 行应整体失败，不留孤立审计")
    void shouldNotLeaveAuditWhenTicketUpdateFails() {
        stubApprovedTicketAndUnverifiedUncertain();
        when(ticketExecutionMapper.updateVerification(eq(100L), eq("CONFIRMED_SUCCESS"), eq(1L), any())).thenReturn(1);
        when(ticketMapper.updateTicketStatus(42L, "APPROVED", "RESOLVED")).thenReturn(0);

        assertThatThrownBy(() -> service().dispose(42L,
                new DispositionRequest(DispositionType.CONFIRM_SUCCESS, 100L, "原因")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("处置未生效");

        verify(ticketDispositionMapper, never()).insert(any(TicketDisposition.class));
    }

    @Test
    @DisplayName("授权重试时递增 0 行应拒绝，不留孤立审计")
    void shouldRejectRetryAuthorizeWhenConcurrent() {
        when(currentUserService.requireSystemAdmin()).thenReturn(admin(1L));
        when(ticketMapper.selectById(42L)).thenReturn(ticket(42L, TicketStatus.APPROVED));
        when(ticketMapper.incrementExtraAttempts(42L)).thenReturn(0);

        assertThatThrownBy(() -> service().dispose(42L,
                new DispositionRequest(DispositionType.RETRY_AUTHORIZE, null, "原因")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无法授权重试");

        verify(ticketDispositionMapper, never()).insert(any(TicketDisposition.class));
    }

    @Test
    @DisplayName("终止时状态流转 0 行应拒绝，不留孤立审计")
    void shouldRejectTerminateWhenConcurrent() {
        when(currentUserService.requireSystemAdmin()).thenReturn(admin(1L));
        when(ticketMapper.selectById(42L)).thenReturn(ticket(42L, TicketStatus.APPROVED));
        when(ticketMapper.updateTicketStatus(42L, "APPROVED", "ABORTED")).thenReturn(0);

        assertThatThrownBy(() -> service().dispose(42L,
                new DispositionRequest(DispositionType.TERMINATE, null, "原因")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无法终止");

        verify(ticketDispositionMapper, never()).insert(any(TicketDisposition.class));
    }

    // ---------- 桩与工厂 ----------

    /** 工单 APPROVED + 执行记录 result=UNCERTAIN 且未核验的前置桩 */
    private void stubApprovedTicketAndUnverifiedUncertain() {
        when(currentUserService.requireSystemAdmin()).thenReturn(admin(1L));
        when(ticketMapper.selectById(42L)).thenReturn(ticket(42L, TicketStatus.APPROVED));
        when(ticketExecutionMapper.selectById(100L)).thenReturn(execution(100L, 42L, ExecutionResult.UNCERTAIN, null));
    }

    private CurrentUserService.CurrentUser admin(long id) {
        return new CurrentUserService.CurrentUser(id, "u" + id, "管理员" + id, SystemRole.ADMIN, false);
    }

    private Ticket ticket(long id, TicketStatus status) {
        Ticket ticket = new Ticket();
        ticket.setId(id);
        ticket.setTicketNo("TK" + id);
        ticket.setStatus(status);
        return ticket;
    }

    private TicketExecution execution(long id, long ticketId, ExecutionResult result, ExecutionVerification verification) {
        TicketExecution e = new TicketExecution();
        e.setId(id);
        e.setTicketId(ticketId);
        e.setResult(result);
        e.setVerificationStatus(verification);
        return e;
    }
}
