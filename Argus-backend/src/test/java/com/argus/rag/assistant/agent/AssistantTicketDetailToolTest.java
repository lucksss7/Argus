package com.argus.rag.assistant.agent;

import com.argus.rag.assistant.model.vo.tool.TicketDetailToolResponse;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.ForbiddenException;
import com.argus.rag.ticket.model.enums.PermissionLevel;
import com.argus.rag.ticket.model.enums.TicketStatus;
import com.argus.rag.ticket.model.enums.TicketType;
import com.argus.rag.ticket.model.vo.TicketDetailToolVO;
import com.argus.rag.ticket.service.TicketService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("工单详情工具")
class AssistantTicketDetailToolTest {

    @Mock
    private TicketService ticketService;

    private AssistantTicketDetailTool tool() {
        return new AssistantTicketDetailTool(ticketService);
    }

    @Test
    @DisplayName("从工具上下文读取身份并委托 TicketService 查询详情")
    void shouldDelegateToGetTicketDetailForUser() {
        when(ticketService.getTicketDetailFor(42L, 5L, SystemRole.USER))
                .thenReturn(detail(42L, "zhangsan"));

        TicketDetailToolResponse response = tool().getDetail(42L, context(5L, SystemRole.USER));

        assertThat(response.success()).isTrue();
        assertThat(response.ticket().id()).isEqualTo(42L);
        assertThat(response.ticket().ticketNo()).isEqualTo("TK42");
        assertThat(response.ticket().targetUsername()).isEqualTo("zhangsan");
        verify(ticketService).getTicketDetailFor(42L, 5L, SystemRole.USER);
    }

    @Test
    @DisplayName("系统管理员身份委托 TicketService 查询详情")
    void shouldDelegateToGetTicketDetailForAdmin() {
        when(ticketService.getTicketDetailFor(1L, 1L, SystemRole.ADMIN))
                .thenReturn(detail(1L, "zhangsan"));

        TicketDetailToolResponse response = tool().getDetail(1L, context(1L, SystemRole.ADMIN));

        assertThat(response.success()).isTrue();
        assertThat(response.ticket().id()).isEqualTo(1L);
        verify(ticketService).getTicketDetailFor(1L, 1L, SystemRole.ADMIN);
    }

    @Test
    @DisplayName("缺少用户 ID 上下文时应结构化失败，且不降级查询")
    void shouldFailWhenUserIdMissing() {
        ToolContext ctx = new ToolContext(Map.of(
                AbstractAssistantTicketTool.SYSTEM_ROLE_CONTEXT_KEY, SystemRole.USER));

        TicketDetailToolResponse response = tool().getDetail(42L, ctx);

        assertThat(response.success()).isFalse();
        assertThat(response.reasonCode()).isEqualTo(AbstractAssistantTicketTool.REASON_CODE_QUERY_FAILED);
        assertThat(response.ticket()).isNull();
        verify(ticketService, never()).getTicketDetailFor(any(), any(), any());
    }

    @Test
    @DisplayName("缺少角色上下文时应结构化失败，且不降级查询")
    void shouldFailWhenRoleMissing() {
        ToolContext ctx = new ToolContext(Map.of(
                AbstractAssistantTicketTool.USER_ID_CONTEXT_KEY, 5L));

        TicketDetailToolResponse response = tool().getDetail(42L, ctx);

        assertThat(response.success()).isFalse();
        assertThat(response.reasonCode()).isEqualTo(AbstractAssistantTicketTool.REASON_CODE_QUERY_FAILED);
        verify(ticketService, never()).getTicketDetailFor(any(), any(), any());
    }

    @Test
    @DisplayName("工具上下文为 null 时应结构化失败")
    void shouldFailWhenContextNull() {
        TicketDetailToolResponse response = tool().getDetail(42L, null);

        assertThat(response.success()).isFalse();
        assertThat(response.reasonCode()).isEqualTo(AbstractAssistantTicketTool.REASON_CODE_QUERY_FAILED);
        verify(ticketService, never()).getTicketDetailFor(any(), any(), any());
    }

    @Test
    @DisplayName("用户 ID 为零时应结构化失败")
    void shouldFailWhenUserIdZero() {
        ToolContext ctx = new ToolContext(Map.of(
                AbstractAssistantTicketTool.USER_ID_CONTEXT_KEY, 0L,
                AbstractAssistantTicketTool.SYSTEM_ROLE_CONTEXT_KEY, SystemRole.USER));

        TicketDetailToolResponse response = tool().getDetail(42L, ctx);

        assertThat(response.success()).isFalse();
        assertThat(response.reasonCode()).isEqualTo(AbstractAssistantTicketTool.REASON_CODE_QUERY_FAILED);
        verify(ticketService, never()).getTicketDetailFor(any(), any(), any());
    }

    @Test
    @DisplayName("用户 ID 为负数时应结构化失败")
    void shouldFailWhenUserIdNegative() {
        TicketDetailToolResponse response = tool().getDetail(42L, context(-1L, SystemRole.USER));

        assertThat(response.success()).isFalse();
        assertThat(response.reasonCode()).isEqualTo(AbstractAssistantTicketTool.REASON_CODE_QUERY_FAILED);
        verify(ticketService, never()).getTicketDetailFor(any(), any(), any());
    }

    @Test
    @DisplayName("用户 ID 为小数时应结构化失败，不得静默截断")
    void shouldFailWhenUserIdDecimal() {
        ToolContext ctx = new ToolContext(Map.of(
                AbstractAssistantTicketTool.USER_ID_CONTEXT_KEY, 2.5d,
                AbstractAssistantTicketTool.SYSTEM_ROLE_CONTEXT_KEY, SystemRole.USER));

        TicketDetailToolResponse response = tool().getDetail(42L, ctx);

        assertThat(response.success()).isFalse();
        assertThat(response.reasonCode()).isEqualTo(AbstractAssistantTicketTool.REASON_CODE_QUERY_FAILED);
        verify(ticketService, never()).getTicketDetailFor(any(), any(), any());
    }

    @Test
    @DisplayName("用户 ID 为非法字符串时应结构化失败")
    void shouldFailWhenUserIdWrongType() {
        ToolContext ctx = new ToolContext(Map.of(
                AbstractAssistantTicketTool.USER_ID_CONTEXT_KEY, "5",
                AbstractAssistantTicketTool.SYSTEM_ROLE_CONTEXT_KEY, SystemRole.USER));

        TicketDetailToolResponse response = tool().getDetail(42L, ctx);

        assertThat(response.success()).isFalse();
        assertThat(response.reasonCode()).isEqualTo(AbstractAssistantTicketTool.REASON_CODE_QUERY_FAILED);
        verify(ticketService, never()).getTicketDetailFor(any(), any(), any());
    }

    @Test
    @DisplayName("权限拒绝应结构化失败，且不暴露内部细节")
    void shouldReturnStructuredFailureOnForbidden() {
        when(ticketService.getTicketDetailFor(42L, 5L, SystemRole.USER))
                .thenThrow(new ForbiddenException("无权查看该工单"));

        TicketDetailToolResponse response = tool().getDetail(42L, context(5L, SystemRole.USER));

        assertThat(response.success()).isFalse();
        assertThat(response.reasonCode()).isEqualTo(AbstractAssistantTicketTool.REASON_CODE_QUERY_FAILED);
        assertThat(response.reasonMessage()).doesNotContain("无权查看");
        assertThat(response.ticket()).isNull();
    }

    @Test
    @DisplayName("意外系统异常应返回通用失败，不暴露数据库/堆栈等内部细节")
    void shouldReturnGenericFailureOnUnexpectedException() {
        when(ticketService.getTicketDetailFor(42L, 5L, SystemRole.USER))
                .thenThrow(new IllegalStateException("boom jdbc:postgresql://10.0.0.5:5432 secret=p@ss"));

        TicketDetailToolResponse response = tool().getDetail(42L, context(5L, SystemRole.USER));

        assertThat(response.success()).isFalse();
        assertThat(response.reasonCode()).isEqualTo(AbstractAssistantTicketTool.REASON_CODE_INTERNAL_ERROR);
        assertThat(response.reasonMessage()).doesNotContain("boom", "jdbc", "postgresql", "secret", "p@ss");
        assertThat(response.ticket()).isNull();
    }

    private ToolContext context(Long userId, SystemRole role) {
        return new ToolContext(Map.of(
                AbstractAssistantTicketTool.USER_ID_CONTEXT_KEY, userId,
                AbstractAssistantTicketTool.SYSTEM_ROLE_CONTEXT_KEY, role));
    }

    private TicketDetailToolVO detail(Long id, String targetUsername) {
        return new TicketDetailToolVO(
                id,
                "TK" + id,
                "账号开通申请",
                "需要只读账号",
                TicketType.ACCOUNT_PROVISIONING,
                TicketStatus.OPEN,
                "LOW",
                "erp",
                targetUsername,
                PermissionLevel.READ_ONLY,
                LocalDateTime.now(),
                LocalDateTime.now());
    }
}
