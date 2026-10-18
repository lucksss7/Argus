package com.argus.rag.ticket.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ForbiddenException;
import com.argus.rag.ticket.mapper.ApprovalRequestMapper;
import com.argus.rag.ticket.mapper.TicketMapper;
import com.argus.rag.ticket.model.entity.ApprovalRequest;
import com.argus.rag.ticket.model.entity.Ticket;
import com.argus.rag.ticket.model.enums.ApprovalResult;
import com.argus.rag.ticket.model.enums.TicketStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("工单审批服务")
class TicketApprovalServiceTest {

    @Mock
    private TicketMapper ticketMapper;

    @Mock
    private ApprovalRequestMapper approvalRequestMapper;

    @Mock
    private CurrentUserService currentUserService;

    private TicketApprovalService service() {
        return new TicketApprovalService(ticketMapper, approvalRequestMapper, currentUserService);
    }

    @Test
    @DisplayName("创建人提交审批：OPEN -> PENDING_APPROVAL，且不写审批记录")
    void shouldSubmitForApprovalFromCreator() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user(5L));
        when(ticketMapper.selectById(42L)).thenReturn(ticket(42L, 5L, TicketStatus.OPEN));
        when(ticketMapper.updateTicketStatus(42L, "OPEN", "PENDING_APPROVAL")).thenReturn(1);

        service().submitForApproval(42L);

        verify(ticketMapper).updateTicketStatus(42L, "OPEN", "PENDING_APPROVAL");
        verify(approvalRequestMapper, never()).insert(any(ApprovalRequest.class));
    }

    @Test
    @DisplayName("非创建人提交审批应被拒绝（403）")
    void shouldRejectSubmitByNonCreator() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user(9L));
        when(ticketMapper.selectById(42L)).thenReturn(ticket(42L, 5L, TicketStatus.OPEN));

        assertThatThrownBy(() -> service().submitForApproval(42L))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("创建人");
        verify(ticketMapper, never()).updateTicketStatus(any(), any(), any());
    }

    @Test
    @DisplayName("非 OPEN 状态提交审批应报业务异常")
    void shouldRejectSubmitWhenTicketNotOpen() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user(5L));
        when(ticketMapper.selectById(42L)).thenReturn(ticket(42L, 5L, TicketStatus.PENDING_APPROVAL));

        assertThatThrownBy(() -> service().submitForApproval(42L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不允许提交审批");
        verify(ticketMapper, never()).updateTicketStatus(any(), any(), any());
    }

    @Test
    @DisplayName("提交审批条件更新失败（并发）应报业务异常")
    void shouldThrowOnSubmitWhenConditionalUpdateLost() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user(5L));
        when(ticketMapper.selectById(42L)).thenReturn(ticket(42L, 5L, TicketStatus.OPEN));
        when(ticketMapper.updateTicketStatus(42L, "OPEN", "PENDING_APPROVAL")).thenReturn(0);

        assertThatThrownBy(() -> service().submitForApproval(42L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重复提交审批");
    }

    @Test
    @DisplayName("系统管理员审批通过：状态更新与审批记录同事务，审批人取自登录态")
    void shouldApproveFromAdmin() {
        when(currentUserService.requireSystemAdmin()).thenReturn(admin(1L));
        when(ticketMapper.selectById(42L)).thenReturn(ticket(42L, 5L, TicketStatus.PENDING_APPROVAL));
        when(ticketMapper.updateTicketDecision(42L, "PENDING_APPROVAL", "APPROVED", 1L)).thenReturn(1);

        service().approve(42L, "同意开通");

        InOrder inOrder = inOrder(ticketMapper, approvalRequestMapper);
        inOrder.verify(ticketMapper).updateTicketDecision(42L, "PENDING_APPROVAL", "APPROVED", 1L);
        ArgumentCaptor<ApprovalRequest> captor = ArgumentCaptor.forClass(ApprovalRequest.class);
        inOrder.verify(approvalRequestMapper).insert(captor.capture());

        ApprovalRequest record = captor.getValue();
        assertThat(record.getTicketId()).isEqualTo(42L);
        assertThat(record.getApproverUserId()).isEqualTo(1L);
        assertThat(record.getResult()).isEqualTo(ApprovalResult.APPROVED);
        assertThat(record.getComment()).isEqualTo("同意开通");
        assertThat(record.getDecidedAt()).isNotNull();
    }

    @Test
    @DisplayName("系统管理员审批拒绝：状态更新与审批记录同事务")
    void shouldRejectFromAdmin() {
        when(currentUserService.requireSystemAdmin()).thenReturn(admin(1L));
        when(ticketMapper.selectById(42L)).thenReturn(ticket(42L, 5L, TicketStatus.PENDING_APPROVAL));
        when(ticketMapper.updateTicketDecision(42L, "PENDING_APPROVAL", "REJECTED", 1L)).thenReturn(1);

        service().reject(42L, "资料不全");

        verify(ticketMapper).updateTicketDecision(42L, "PENDING_APPROVAL", "REJECTED", 1L);
        ArgumentCaptor<ApprovalRequest> captor = ArgumentCaptor.forClass(ApprovalRequest.class);
        verify(approvalRequestMapper).insert(captor.capture());

        ApprovalRequest record = captor.getValue();
        assertThat(record.getTicketId()).isEqualTo(42L);
        assertThat(record.getApproverUserId()).isEqualTo(1L);
        assertThat(record.getResult()).isEqualTo(ApprovalResult.REJECTED);
        assertThat(record.getComment()).isEqualTo("资料不全");
    }

    @Test
    @DisplayName("非系统管理员审批应被拒绝（403）")
    void shouldRejectApprovalByNonAdmin() {
        when(currentUserService.requireSystemAdmin())
                .thenThrow(new ForbiddenException("当前用户不是系统管理员"));

        assertThatThrownBy(() -> service().approve(42L, null))
                .isInstanceOf(ForbiddenException.class);
        verify(ticketMapper, never()).selectById(any());
        verify(approvalRequestMapper, never()).insert(any(ApprovalRequest.class));
    }

    @Test
    @DisplayName("非 PENDING_APPROVAL 状态审批应报业务异常")
    void shouldRejectApprovalWhenTicketNotPending() {
        when(currentUserService.requireSystemAdmin()).thenReturn(admin(1L));
        when(ticketMapper.selectById(42L)).thenReturn(ticket(42L, 5L, TicketStatus.OPEN));

        assertThatThrownBy(() -> service().approve(42L, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不允许审批");
        verify(ticketMapper, never()).updateTicketDecision(any(), any(), any(), any());
        verify(approvalRequestMapper, never()).insert(any(ApprovalRequest.class));
    }

    @Test
    @DisplayName("重复审批（条件更新影响 0 行）应报业务异常且不写审批记录")
    void shouldRejectDuplicateApproval() {
        when(currentUserService.requireSystemAdmin()).thenReturn(admin(1L));
        when(ticketMapper.selectById(42L)).thenReturn(ticket(42L, 5L, TicketStatus.PENDING_APPROVAL));
        when(ticketMapper.updateTicketDecision(42L, "PENDING_APPROVAL", "APPROVED", 1L)).thenReturn(0);

        assertThatThrownBy(() -> service().approve(42L, "同意"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重复审批");
        verify(approvalRequestMapper, never()).insert(any(ApprovalRequest.class));
    }

    @Test
    @DisplayName("工单不存在应报业务异常")
    void shouldThrowWhenTicketNotFound() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user(5L));
        when(ticketMapper.selectById(42L)).thenReturn(null);

        assertThatThrownBy(() -> service().submitForApproval(42L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
    }

    private CurrentUserService.CurrentUser user(long id) {
        return new CurrentUserService.CurrentUser(id, "u" + id, "用户" + id, SystemRole.USER, false);
    }

    private CurrentUserService.CurrentUser admin(long id) {
        return new CurrentUserService.CurrentUser(id, "u" + id, "管理员" + id, SystemRole.ADMIN, false);
    }

    private Ticket ticket(long id, long creatorUserId, TicketStatus status) {
        Ticket ticket = new Ticket();
        ticket.setId(id);
        ticket.setTicketNo("TK" + id);
        ticket.setCreatorUserId(creatorUserId);
        ticket.setStatus(status);
        return ticket;
    }
}
