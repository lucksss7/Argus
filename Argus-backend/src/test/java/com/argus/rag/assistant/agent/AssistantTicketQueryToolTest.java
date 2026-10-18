package com.argus.rag.assistant.agent;

import com.argus.rag.assistant.model.vo.tool.TicketQueryToolResponse;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.ForbiddenException;
import com.argus.rag.ticket.model.enums.TicketStatus;
import com.argus.rag.ticket.model.enums.TicketType;
import com.argus.rag.ticket.model.vo.TicketListResult;
import com.argus.rag.ticket.model.vo.TicketSummaryVO;
import com.argus.rag.ticket.service.TicketService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("工单查询工具")
class AssistantTicketQueryToolTest {

    @Mock
    private TicketService ticketService;

    private AssistantTicketQueryTool tool() {
        return new AssistantTicketQueryTool(ticketService);
    }

    @Test
    @DisplayName("从工具上下文读取身份并委托 TicketService 查询")
    void shouldDelegateToListVisibleTicketsForUser() {
        when(ticketService.listVisibleTickets(5L, SystemRole.USER))
                .thenReturn(new TicketListResult(List.of(summary(1L, "zhangsan")), false));

        TicketQueryToolResponse response = tool().query(context(5L, SystemRole.USER));

        assertThat(response.success()).isTrue();
        assertThat(response.tickets()).hasSize(1);
        assertThat(response.tickets().getFirst().id()).isEqualTo(1L);
        assertThat(response.hasMore()).isFalse();
        verify(ticketService).listVisibleTickets(5L, SystemRole.USER);
    }

    @Test
    @DisplayName("系统管理员身份委托 TicketService 查询全部")
    void shouldDelegateToListVisibleTicketsForAdmin() {
        when(ticketService.listVisibleTickets(1L, SystemRole.ADMIN))
                .thenReturn(new TicketListResult(List.of(summary(1L, "zhangsan"), summary(2L, "lisi")), false));

        TicketQueryToolResponse response = tool().query(context(1L, SystemRole.ADMIN));

        assertThat(response.success()).isTrue();
        assertThat(response.tickets()).hasSize(2);
        assertThat(response.hasMore()).isFalse();
        verify(ticketService).listVisibleTickets(1L, SystemRole.ADMIN);
    }

    @Test
    @DisplayName("hasMore 标记应从服务结果透传到工具响应")
    void shouldPropagateHasMoreFromService() {
        when(ticketService.listVisibleTickets(5L, SystemRole.USER))
                .thenReturn(new TicketListResult(List.of(summary(1L, "zhangsan")), true));

        TicketQueryToolResponse response = tool().query(context(5L, SystemRole.USER));

        assertThat(response.success()).isTrue();
        assertThat(response.hasMore()).isTrue();
    }

    @Test
    @DisplayName("缺少用户 ID 上下文时应结构化失败，且不降级查询")
    void shouldFailWhenUserIdMissing() {
        ToolContext ctx = new ToolContext(Map.of(
                AbstractAssistantTicketTool.SYSTEM_ROLE_CONTEXT_KEY, SystemRole.USER));

        TicketQueryToolResponse response = tool().query(ctx);

        assertThat(response.success()).isFalse();
        assertThat(response.reasonCode()).isEqualTo(AbstractAssistantTicketTool.REASON_CODE_QUERY_FAILED);
        assertThat(response.tickets()).isEmpty();
        assertThat(response.hasMore()).isFalse();
        verify(ticketService, never()).listVisibleTickets(any(), any());
    }

    @Test
    @DisplayName("缺少角色上下文时应结构化失败，且不降级查询")
    void shouldFailWhenRoleMissing() {
        ToolContext ctx = new ToolContext(Map.of(
                AbstractAssistantTicketTool.USER_ID_CONTEXT_KEY, 5L));

        TicketQueryToolResponse response = tool().query(ctx);

        assertThat(response.success()).isFalse();
        assertThat(response.reasonCode()).isEqualTo(AbstractAssistantTicketTool.REASON_CODE_QUERY_FAILED);
        assertThat(response.tickets()).isEmpty();
        verify(ticketService, never()).listVisibleTickets(any(), any());
    }

    @Test
    @DisplayName("工具上下文为 null 时应结构化失败")
    void shouldFailWhenContextNull() {
        TicketQueryToolResponse response = tool().query(null);

        assertThat(response.success()).isFalse();
        assertThat(response.reasonCode()).isEqualTo(AbstractAssistantTicketTool.REASON_CODE_QUERY_FAILED);
        verify(ticketService, never()).listVisibleTickets(any(), any());
    }

    @Test
    @DisplayName("非法用户 ID 上下文时应结构化失败")
    void shouldFailWhenUserIdInvalid() {
        ToolContext ctx = new ToolContext(Map.of(
                AbstractAssistantTicketTool.USER_ID_CONTEXT_KEY, 0L,
                AbstractAssistantTicketTool.SYSTEM_ROLE_CONTEXT_KEY, SystemRole.USER));

        TicketQueryToolResponse response = tool().query(ctx);

        assertThat(response.success()).isFalse();
        assertThat(response.reasonCode()).isEqualTo(AbstractAssistantTicketTool.REASON_CODE_QUERY_FAILED);
        verify(ticketService, never()).listVisibleTickets(any(), any());
    }

    @Test
    @DisplayName("用户 ID 为小数时应结构化失败，不得静默截断")
    void shouldFailWhenUserIdDecimal() {
        ToolContext ctx = new ToolContext(Map.of(
                AbstractAssistantTicketTool.USER_ID_CONTEXT_KEY, 2.5d,
                AbstractAssistantTicketTool.SYSTEM_ROLE_CONTEXT_KEY, SystemRole.USER));

        TicketQueryToolResponse response = tool().query(ctx);

        assertThat(response.success()).isFalse();
        assertThat(response.reasonCode()).isEqualTo(AbstractAssistantTicketTool.REASON_CODE_QUERY_FAILED);
        verify(ticketService, never()).listVisibleTickets(any(), any());
    }

    @Test
    @DisplayName("用户 ID 为非法字符串时应结构化失败")
    void shouldFailWhenUserIdWrongType() {
        ToolContext ctx = new ToolContext(Map.of(
                AbstractAssistantTicketTool.USER_ID_CONTEXT_KEY, "5",
                AbstractAssistantTicketTool.SYSTEM_ROLE_CONTEXT_KEY, SystemRole.USER));

        TicketQueryToolResponse response = tool().query(ctx);

        assertThat(response.success()).isFalse();
        assertThat(response.reasonCode()).isEqualTo(AbstractAssistantTicketTool.REASON_CODE_QUERY_FAILED);
        verify(ticketService, never()).listVisibleTickets(any(), any());
    }

    @Test
    @DisplayName("业务异常应结构化失败，不转为成功空列表，且不暴露内部细节")
    void shouldReturnStructuredFailureOnBusinessException() {
        when(ticketService.listVisibleTickets(5L, SystemRole.USER))
                .thenThrow(new ForbiddenException("无权查看该工单"));

        TicketQueryToolResponse response = tool().query(context(5L, SystemRole.USER));

        assertThat(response.success()).isFalse();
        assertThat(response.reasonCode()).isEqualTo(AbstractAssistantTicketTool.REASON_CODE_QUERY_FAILED);
        assertThat(response.reasonMessage()).doesNotContain("无权查看");
        assertThat(response.tickets()).isEmpty();
        assertThat(response.hasMore()).isFalse();
    }

    @Test
    @DisplayName("意外系统异常应返回通用失败，不暴露数据库/堆栈等内部细节")
    void shouldReturnGenericFailureOnUnexpectedException() {
        when(ticketService.listVisibleTickets(5L, SystemRole.USER))
                .thenThrow(new IllegalStateException("boom jdbc:postgresql://10.0.0.5:5432 secret=p@ss"));

        TicketQueryToolResponse response = tool().query(context(5L, SystemRole.USER));

        assertThat(response.success()).isFalse();
        assertThat(response.reasonCode()).isEqualTo(AbstractAssistantTicketTool.REASON_CODE_INTERNAL_ERROR);
        assertThat(response.reasonMessage()).doesNotContain("boom", "jdbc", "postgresql", "secret", "p@ss");
        assertThat(response.tickets()).isEmpty();
        assertThat(response.hasMore()).isFalse();
    }

    private ToolContext context(Long userId, SystemRole role) {
        return new ToolContext(Map.of(
                AbstractAssistantTicketTool.USER_ID_CONTEXT_KEY, userId,
                AbstractAssistantTicketTool.SYSTEM_ROLE_CONTEXT_KEY, role));
    }

    private TicketSummaryVO summary(Long id, String targetUsername) {
        return new TicketSummaryVO(
                id,
                "TK" + id,
                "账号开通申请",
                TicketType.ACCOUNT_PROVISIONING,
                TicketStatus.OPEN,
                "erp",
                targetUsername,
                LocalDateTime.now());
    }
}
