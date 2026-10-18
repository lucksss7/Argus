package com.argus.rag.assistant.agent;

import com.argus.rag.assistant.model.vo.tool.TicketExecutionListToolResponse;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.ForbiddenException;
import com.argus.rag.ticket.model.enums.ExecutionResult;
import com.argus.rag.ticket.model.enums.ExecutionType;
import com.argus.rag.ticket.model.enums.ExecutionVerification;
import com.argus.rag.ticket.model.vo.ExecutionRecordVO;
import com.argus.rag.ticket.service.TicketService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
@DisplayName("工单执行记录工具")
class AssistantTicketExecutionsToolTest {

    @Mock
    private TicketService ticketService;

    private AssistantTicketExecutionsTool tool() {
        return new AssistantTicketExecutionsTool(ticketService);
    }

    @Test
    @DisplayName("从工具上下文读取身份并委托 TicketService 查询执行记录")
    void shouldDelegateToGetTicketExecutionsForUser() {
        when(ticketService.getTicketExecutionsFor(42L, 5L, SystemRole.USER))
                .thenReturn(List.of(execution(1, ExecutionResult.SUCCESS)));

        TicketExecutionListToolResponse response = tool().getExecutions(42L, context(5L, SystemRole.USER));

        assertThat(response.success()).isTrue();
        assertThat(response.executions()).hasSize(1);
        assertThat(response.executions().getFirst().attemptNo()).isEqualTo(1);
        assertThat(response.executions().getFirst().result()).isEqualTo(ExecutionResult.SUCCESS);
        verify(ticketService).getTicketExecutionsFor(42L, 5L, SystemRole.USER);
    }

    @Test
    @DisplayName("系统管理员身份委托 TicketService 查询执行记录")
    void shouldDelegateToGetTicketExecutionsForAdmin() {
        when(ticketService.getTicketExecutionsFor(1L, 1L, SystemRole.ADMIN))
                .thenReturn(List.of(execution(1, ExecutionResult.SUCCESS), execution(2, ExecutionResult.FAILED)));

        TicketExecutionListToolResponse response = tool().getExecutions(1L, context(1L, SystemRole.ADMIN));

        assertThat(response.success()).isTrue();
        assertThat(response.executions()).hasSize(2);
        verify(ticketService).getTicketExecutionsFor(1L, 1L, SystemRole.ADMIN);
    }

    @Test
    @DisplayName("缺少用户 ID 上下文时应结构化失败，且不降级查询")
    void shouldFailWhenUserIdMissing() {
        ToolContext ctx = new ToolContext(Map.of(
                AbstractAssistantTicketTool.SYSTEM_ROLE_CONTEXT_KEY, SystemRole.USER));

        TicketExecutionListToolResponse response = tool().getExecutions(42L, ctx);

        assertThat(response.success()).isFalse();
        assertThat(response.reasonCode()).isEqualTo(AbstractAssistantTicketTool.REASON_CODE_QUERY_FAILED);
        assertThat(response.executions()).isEmpty();
        verify(ticketService, never()).getTicketExecutionsFor(any(), any(), any());
    }

    @Test
    @DisplayName("缺少角色上下文时应结构化失败，且不降级查询")
    void shouldFailWhenRoleMissing() {
        ToolContext ctx = new ToolContext(Map.of(
                AbstractAssistantTicketTool.USER_ID_CONTEXT_KEY, 5L));

        TicketExecutionListToolResponse response = tool().getExecutions(42L, ctx);

        assertThat(response.success()).isFalse();
        assertThat(response.reasonCode()).isEqualTo(AbstractAssistantTicketTool.REASON_CODE_QUERY_FAILED);
        verify(ticketService, never()).getTicketExecutionsFor(any(), any(), any());
    }

    @Test
    @DisplayName("工具上下文为 null 时应结构化失败")
    void shouldFailWhenContextNull() {
        TicketExecutionListToolResponse response = tool().getExecutions(42L, null);

        assertThat(response.success()).isFalse();
        assertThat(response.reasonCode()).isEqualTo(AbstractAssistantTicketTool.REASON_CODE_QUERY_FAILED);
        verify(ticketService, never()).getTicketExecutionsFor(any(), any(), any());
    }

    @Test
    @DisplayName("用户 ID 为零时应结构化失败")
    void shouldFailWhenUserIdZero() {
        ToolContext ctx = new ToolContext(Map.of(
                AbstractAssistantTicketTool.USER_ID_CONTEXT_KEY, 0L,
                AbstractAssistantTicketTool.SYSTEM_ROLE_CONTEXT_KEY, SystemRole.USER));

        TicketExecutionListToolResponse response = tool().getExecutions(42L, ctx);

        assertThat(response.success()).isFalse();
        assertThat(response.reasonCode()).isEqualTo(AbstractAssistantTicketTool.REASON_CODE_QUERY_FAILED);
        verify(ticketService, never()).getTicketExecutionsFor(any(), any(), any());
    }

    @Test
    @DisplayName("用户 ID 为负数时应结构化失败")
    void shouldFailWhenUserIdNegative() {
        TicketExecutionListToolResponse response = tool().getExecutions(42L, context(-1L, SystemRole.USER));

        assertThat(response.success()).isFalse();
        assertThat(response.reasonCode()).isEqualTo(AbstractAssistantTicketTool.REASON_CODE_QUERY_FAILED);
        verify(ticketService, never()).getTicketExecutionsFor(any(), any(), any());
    }

    @Test
    @DisplayName("用户 ID 为小数时应结构化失败，不得静默截断")
    void shouldFailWhenUserIdDecimal() {
        ToolContext ctx = new ToolContext(Map.of(
                AbstractAssistantTicketTool.USER_ID_CONTEXT_KEY, 2.5d,
                AbstractAssistantTicketTool.SYSTEM_ROLE_CONTEXT_KEY, SystemRole.USER));

        TicketExecutionListToolResponse response = tool().getExecutions(42L, ctx);

        assertThat(response.success()).isFalse();
        assertThat(response.reasonCode()).isEqualTo(AbstractAssistantTicketTool.REASON_CODE_QUERY_FAILED);
        verify(ticketService, never()).getTicketExecutionsFor(any(), any(), any());
    }

    @Test
    @DisplayName("用户 ID 为非法字符串时应结构化失败")
    void shouldFailWhenUserIdWrongType() {
        ToolContext ctx = new ToolContext(Map.of(
                AbstractAssistantTicketTool.USER_ID_CONTEXT_KEY, "5",
                AbstractAssistantTicketTool.SYSTEM_ROLE_CONTEXT_KEY, SystemRole.USER));

        TicketExecutionListToolResponse response = tool().getExecutions(42L, ctx);

        assertThat(response.success()).isFalse();
        assertThat(response.reasonCode()).isEqualTo(AbstractAssistantTicketTool.REASON_CODE_QUERY_FAILED);
        verify(ticketService, never()).getTicketExecutionsFor(any(), any(), any());
    }

    @Test
    @DisplayName("权限拒绝应结构化失败，且不暴露内部细节")
    void shouldReturnStructuredFailureOnForbidden() {
        when(ticketService.getTicketExecutionsFor(42L, 5L, SystemRole.USER))
                .thenThrow(new ForbiddenException("无权查看该工单"));

        TicketExecutionListToolResponse response = tool().getExecutions(42L, context(5L, SystemRole.USER));

        assertThat(response.success()).isFalse();
        assertThat(response.reasonCode()).isEqualTo(AbstractAssistantTicketTool.REASON_CODE_QUERY_FAILED);
        assertThat(response.reasonMessage()).doesNotContain("无权查看");
        assertThat(response.executions()).isEmpty();
    }

    @Test
    @DisplayName("意外系统异常应返回通用失败，不暴露数据库/堆栈等内部细节")
    void shouldReturnGenericFailureOnUnexpectedException() {
        when(ticketService.getTicketExecutionsFor(42L, 5L, SystemRole.USER))
                .thenThrow(new IllegalStateException("boom jdbc:postgresql://10.0.0.5:5432 secret=p@ss"));

        TicketExecutionListToolResponse response = tool().getExecutions(42L, context(5L, SystemRole.USER));

        assertThat(response.success()).isFalse();
        assertThat(response.reasonCode()).isEqualTo(AbstractAssistantTicketTool.REASON_CODE_INTERNAL_ERROR);
        assertThat(response.reasonMessage()).doesNotContain("boom", "jdbc", "postgresql", "secret", "p@ss");
        assertThat(response.executions()).isEmpty();
    }

    @Test
    @DisplayName("透传 CONFIRMED_SUCCESS 核验结论与核验时间，不覆盖原始 result，结果不含核验人")
    void shouldPassThroughConfirmedSuccessVerification() {
        LocalDateTime verifiedAt = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        when(ticketService.getTicketExecutionsFor(42L, 5L, SystemRole.USER))
                .thenReturn(List.of(execution(1, ExecutionResult.UNCERTAIN,
                        ExecutionVerification.CONFIRMED_SUCCESS, verifiedAt)));

        TicketExecutionListToolResponse response = tool().getExecutions(42L, context(5L, SystemRole.USER));

        assertThat(response.success()).isTrue();
        ExecutionRecordVO record = response.executions().getFirst();
        assertThat(record.verificationStatus()).isEqualTo(ExecutionVerification.CONFIRMED_SUCCESS);
        assertThat(record.verifiedAt()).isEqualTo(verifiedAt);
        // 核验结论与原始执行结果分离：result 保持 UNCERTAIN，不被核验结论覆盖
        assertThat(record.result()).isEqualTo(ExecutionResult.UNCERTAIN);

        // 真实序列化后的工具结果：含 verificationStatus / verifiedAt，不含 verifiedBy
        JsonNode node = toJson(response).get("executions").get(0);
        assertThat(node.get("verificationStatus").asText()).isEqualTo("CONFIRMED_SUCCESS");
        assertThat(node.has("verifiedAt")).isTrue();
        assertThat(node.get("verifiedAt").isNull()).isFalse();
        assertThat(node.has("verifiedBy")).isFalse();
    }

    @Test
    @DisplayName("透传 CONFIRMED_NOT_EXECUTED 核验结论与核验时间")
    void shouldPassThroughConfirmedNotExecutedVerification() {
        LocalDateTime verifiedAt = LocalDateTime.of(2026, 2, 3, 4, 5, 6);
        when(ticketService.getTicketExecutionsFor(42L, 5L, SystemRole.USER))
                .thenReturn(List.of(execution(1, ExecutionResult.UNCERTAIN,
                        ExecutionVerification.CONFIRMED_NOT_EXECUTED, verifiedAt)));

        TicketExecutionListToolResponse response = tool().getExecutions(42L, context(5L, SystemRole.USER));

        assertThat(response.success()).isTrue();
        ExecutionRecordVO record = response.executions().getFirst();
        assertThat(record.verificationStatus()).isEqualTo(ExecutionVerification.CONFIRMED_NOT_EXECUTED);
        assertThat(record.verifiedAt()).isEqualTo(verifiedAt);
        assertThat(record.result()).isEqualTo(ExecutionResult.UNCERTAIN);
    }

    private ToolContext context(Long userId, SystemRole role) {
        return new ToolContext(Map.of(
                AbstractAssistantTicketTool.USER_ID_CONTEXT_KEY, userId,
                AbstractAssistantTicketTool.SYSTEM_ROLE_CONTEXT_KEY, role));
    }

    private ExecutionRecordVO execution(int attemptNo, ExecutionResult result) {
        return new ExecutionRecordVO(
                100L + attemptNo,
                attemptNo,
                ExecutionType.PROVISION_ACCOUNT,
                result,
                result == ExecutionResult.FAILED ? "账号已存在" : null,
                LocalDateTime.now(),
                result == ExecutionResult.PENDING ? null : LocalDateTime.now(),
                null, null);
    }

    private ExecutionRecordVO execution(int attemptNo, ExecutionResult result,
                                        ExecutionVerification verificationStatus, LocalDateTime verifiedAt) {
        return new ExecutionRecordVO(
                100L + attemptNo,
                attemptNo,
                ExecutionType.PROVISION_ACCOUNT,
                result,
                result == ExecutionResult.FAILED ? "账号已存在" : null,
                LocalDateTime.now(),
                result == ExecutionResult.PENDING ? null : LocalDateTime.now(),
                verificationStatus,
                verifiedAt);
    }

    private JsonNode toJson(Object value) {
        return new ObjectMapper().findAndRegisterModules().valueToTree(value);
    }
}
