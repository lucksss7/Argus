package com.argus.rag.assistant.agent;

import com.argus.rag.assistant.model.vo.tool.TicketCreateToolResponse;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.ticket.model.dto.CreateTicketRequest;
import com.argus.rag.ticket.model.enums.PermissionLevel;
import com.argus.rag.ticket.model.enums.TicketStatus;
import com.argus.rag.ticket.model.enums.TicketType;
import com.argus.rag.ticket.model.vo.CreateTicketResult;
import com.argus.rag.ticket.model.vo.TicketDetailResponse;
import com.argus.rag.ticket.model.vo.TicketDetailToolVO;
import com.argus.rag.ticket.service.TicketService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("工单创建工具")
class AssistantTicketCreateToolTest {

    @Mock
    private TicketService ticketService;

    private AssistantTicketCreateTool tool() {
        return new AssistantTicketCreateTool(ticketService);
    }

    @Test
    @DisplayName("从工具上下文读取身份并委托 TicketService 建单，返回脱敏工单详情")
    void shouldDelegateToCreateTicketForUser() {
        when(ticketService.createTicketFor(eq(5L), eq(SystemRole.USER), any(CreateTicketRequest.class)))
                .thenReturn(createResult(42L));

        TicketCreateToolResponse response = tool().create(
                TicketType.ACCOUNT_PROVISIONING, "erp", "zhangsan",
                PermissionLevel.READ_ONLY, "需要只读账号", "账号开通申请", null,
                context(5L, SystemRole.USER));

        assertThat(response.success()).isTrue();
        assertThat(response.reasonCode()).isNull();
        assertThat(response.ticket()).isInstanceOf(TicketDetailToolVO.class);
        assertThat(response.ticket().id()).isEqualTo(42L);
        assertThat(response.ticket().ticketNo()).isEqualTo("TK42");
        assertThat(response.ticket().status()).isEqualTo(TicketStatus.OPEN);
        assertThat(response.ticket().targetUsername()).isEqualTo("zhangsan");
        // 返回的是 TicketDetailToolVO，天然不含 creatorUserId / assigneeUserId / approverUserId / version / resolution
        verify(ticketService).createTicketFor(eq(5L), eq(SystemRole.USER), any(CreateTicketRequest.class));
    }

    @Test
    @DisplayName("系统管理员身份委托 TicketService 建单")
    void shouldDelegateToCreateTicketForAdmin() {
        when(ticketService.createTicketFor(eq(1L), eq(SystemRole.ADMIN), any(CreateTicketRequest.class)))
                .thenReturn(createResult(42L));

        TicketCreateToolResponse response = tool().create(
                TicketType.ACCOUNT_PROVISIONING, "erp", "zhangsan",
                PermissionLevel.READ_ONLY, "需要只读账号", "账号开通申请", null,
                context(1L, SystemRole.ADMIN));

        assertThat(response.success()).isTrue();
        assertThat(response.ticket().id()).isEqualTo(42L);
        verify(ticketService).createTicketFor(eq(1L), eq(SystemRole.ADMIN), any(CreateTicketRequest.class));
    }

    @Test
    @DisplayName("同一 messageId 与相同业务参数重复调用，产生相同幂等键")
    void shouldProduceSameIdempotencyKeyForSameMessageIdAndContent() {
        when(ticketService.createTicketFor(any(), any(), any(CreateTicketRequest.class)))
                .thenReturn(createResult(42L));

        tool().create(TicketType.ACCOUNT_PROVISIONING, "erp", "zhangsan",
                PermissionLevel.READ_ONLY, "需要只读账号", "账号开通申请", "LOW", context(5L, SystemRole.USER, 100L));
        tool().create(TicketType.ACCOUNT_PROVISIONING, "erp", "zhangsan",
                PermissionLevel.READ_ONLY, "需要只读账号", "账号开通申请", "LOW", context(5L, SystemRole.USER, 100L));

        List<CreateTicketRequest> requests = capturedRequests(2);
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).idempotencyKey()).isEqualTo(requests.get(1).idempotencyKey());
    }

    @Test
    @DisplayName("不同 messageId、相同业务参数，产生不同幂等键")
    void shouldProduceDifferentKeyForDifferentMessageId() {
        when(ticketService.createTicketFor(any(), any(), any(CreateTicketRequest.class)))
                .thenReturn(createResult(42L));

        tool().create(TicketType.ACCOUNT_PROVISIONING, "erp", "zhangsan",
                PermissionLevel.READ_ONLY, "需要只读账号", "账号开通申请", "LOW", context(5L, SystemRole.USER, 100L));
        tool().create(TicketType.ACCOUNT_PROVISIONING, "erp", "zhangsan",
                PermissionLevel.READ_ONLY, "需要只读账号", "账号开通申请", "LOW", context(5L, SystemRole.USER, 101L));

        List<CreateTicketRequest> requests = capturedRequests(2);
        assertThat(requests.get(0).idempotencyKey()).isNotEqualTo(requests.get(1).idempotencyKey());
    }

    @Test
    @DisplayName("同一 messageId、不同业务参数，产生不同幂等键")
    void shouldProduceDifferentKeyForDifferentContentSameMessageId() {
        when(ticketService.createTicketFor(any(), any(), any(CreateTicketRequest.class)))
                .thenReturn(createResult(42L));

        tool().create(TicketType.ACCOUNT_PROVISIONING, "erp", "zhangsan",
                PermissionLevel.READ_ONLY, "需要只读账号", "账号开通申请", "LOW", context(5L, SystemRole.USER, 100L));
        tool().create(TicketType.ACCOUNT_PROVISIONING, "erp", "lisi",
                PermissionLevel.READ_ONLY, "需要只读账号", "账号开通申请", "LOW", context(5L, SystemRole.USER, 100L));

        List<CreateTicketRequest> requests = capturedRequests(2);
        assertThat(requests.get(0).idempotencyKey()).isNotEqualTo(requests.get(1).idempotencyKey());
    }

    @Test
    @DisplayName("幂等键以 assistant: 开头且不超过 128 字符")
    void shouldPrefixIdempotencyKeyAndStayWithinLength() {
        when(ticketService.createTicketFor(any(), any(), any(CreateTicketRequest.class)))
                .thenReturn(createResult(42L));

        tool().create(TicketType.ACCOUNT_PROVISIONING, "erp", "zhangsan",
                PermissionLevel.READ_ONLY, "需要只读账号", "账号开通申请", "LOW",
                context(5L, SystemRole.USER, Long.MAX_VALUE));

        ArgumentCaptor<CreateTicketRequest> captor = ArgumentCaptor.forClass(CreateTicketRequest.class);
        verify(ticketService).createTicketFor(any(), any(), captor.capture());
        assertThat(captor.getValue().idempotencyKey())
                .startsWith("assistant:")
                .hasSizeLessThanOrEqualTo(128);
    }

    @Test
    @DisplayName("缺少消息 ID 上下文时应结构化失败，且不建单")
    void shouldFailWhenMessageIdMissing() {
        ToolContext ctx = new ToolContext(Map.of(
                AbstractAssistantTicketTool.USER_ID_CONTEXT_KEY, 5L,
                AbstractAssistantTicketTool.SYSTEM_ROLE_CONTEXT_KEY, SystemRole.USER));

        TicketCreateToolResponse response = tool().create(
                TicketType.ACCOUNT_PROVISIONING, "erp", "zhangsan",
                PermissionLevel.READ_ONLY, "需要只读账号", "账号开通申请", "LOW", ctx);

        assertThat(response.success()).isFalse();
        assertThat(response.reasonCode()).isEqualTo(AssistantTicketCreateTool.REASON_CODE_CREATE_FAILED);
        assertThat(response.ticket()).isNull();
        verify(ticketService, never()).createTicketFor(any(), any(), any());
    }

    @Test
    @DisplayName("非正数、小数或字符串消息 ID 均拒绝建单，且不退回随机 UUID")
    void shouldFailWhenMessageIdInvalid() {
        for (Object invalid : new Object[]{0L, -1L, 2.5d, "100"}) {
            TicketCreateToolResponse response = tool().create(
                    TicketType.ACCOUNT_PROVISIONING, "erp", "zhangsan",
                    PermissionLevel.READ_ONLY, "需要只读账号", "账号开通申请", "LOW",
                    context(5L, SystemRole.USER, invalid));

            assertThat(response.success()).isFalse();
            assertThat(response.reasonCode()).isEqualTo(AssistantTicketCreateTool.REASON_CODE_CREATE_FAILED);
        }
        verify(ticketService, never()).createTicketFor(any(), any(), any());
    }

    @Test
    @DisplayName("文本字段 trim 后等价，产生相同幂等键")
    void shouldTrimTextFieldsForCanonicalPayload() {
        when(ticketService.createTicketFor(any(), any(), any(CreateTicketRequest.class)))
                .thenReturn(createResult(42L));

        tool().create(TicketType.ACCOUNT_PROVISIONING, "  erp ", " zhangsan ",
                PermissionLevel.READ_ONLY, " 需要只读账号 ", " 账号开通申请 ", " LOW ",
                context(5L, SystemRole.USER, 100L));
        tool().create(TicketType.ACCOUNT_PROVISIONING, "erp", "zhangsan",
                PermissionLevel.READ_ONLY, "需要只读账号", "账号开通申请", "LOW",
                context(5L, SystemRole.USER, 100L));

        List<CreateTicketRequest> requests = capturedRequests(2);
        assertThat(requests.get(0).idempotencyKey()).isEqualTo(requests.get(1).idempotencyKey());
    }

    @Test
    @DisplayName("priority 为空串与 null 产生相同幂等键")
    void shouldTreatBlankPriorityAsNullInCanonicalPayload() {
        when(ticketService.createTicketFor(any(), any(), any(CreateTicketRequest.class)))
                .thenReturn(createResult(42L));

        tool().create(TicketType.ACCOUNT_PROVISIONING, "erp", "zhangsan",
                PermissionLevel.READ_ONLY, "需要只读账号", "账号开通申请", "",
                context(5L, SystemRole.USER, 100L));
        tool().create(TicketType.ACCOUNT_PROVISIONING, "erp", "zhangsan",
                PermissionLevel.READ_ONLY, "需要只读账号", "账号开通申请", null,
                context(5L, SystemRole.USER, 100L));

        List<CreateTicketRequest> requests = capturedRequests(2);
        assertThat(requests.get(0).idempotencyKey()).isEqualTo(requests.get(1).idempotencyKey());
    }

    private List<CreateTicketRequest> capturedRequests(int expectedInvocations) {
        ArgumentCaptor<CreateTicketRequest> captor = ArgumentCaptor.forClass(CreateTicketRequest.class);
        verify(ticketService, times(expectedInvocations)).createTicketFor(any(), any(), captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("缺少用户 ID 上下文时应结构化失败，且不建单")
    void shouldFailWhenUserIdMissing() {
        ToolContext ctx = new ToolContext(Map.of(
                AbstractAssistantTicketTool.SYSTEM_ROLE_CONTEXT_KEY, SystemRole.USER));

        TicketCreateToolResponse response = tool().create(
                TicketType.ACCOUNT_PROVISIONING, "erp", "zhangsan",
                PermissionLevel.READ_ONLY, "需要只读账号", "账号开通申请", null, ctx);

        assertThat(response.success()).isFalse();
        assertThat(response.reasonCode()).isEqualTo(AssistantTicketCreateTool.REASON_CODE_CREATE_FAILED);
        assertThat(response.ticket()).isNull();
        verify(ticketService, never()).createTicketFor(any(), any(), any());
    }

    @Test
    @DisplayName("缺少角色上下文时应结构化失败，且不建单")
    void shouldFailWhenRoleMissing() {
        ToolContext ctx = new ToolContext(Map.of(
                AbstractAssistantTicketTool.USER_ID_CONTEXT_KEY, 5L));

        TicketCreateToolResponse response = tool().create(
                TicketType.ACCOUNT_PROVISIONING, "erp", "zhangsan",
                PermissionLevel.READ_ONLY, "需要只读账号", "账号开通申请", null, ctx);

        assertThat(response.success()).isFalse();
        assertThat(response.reasonCode()).isEqualTo(AssistantTicketCreateTool.REASON_CODE_CREATE_FAILED);
        verify(ticketService, never()).createTicketFor(any(), any(), any());
    }

    @Test
    @DisplayName("工具上下文为 null 时应结构化失败")
    void shouldFailWhenContextNull() {
        TicketCreateToolResponse response = tool().create(
                TicketType.ACCOUNT_PROVISIONING, "erp", "zhangsan",
                PermissionLevel.READ_ONLY, "需要只读账号", "账号开通申请", null, null);

        assertThat(response.success()).isFalse();
        assertThat(response.reasonCode()).isEqualTo(AssistantTicketCreateTool.REASON_CODE_CREATE_FAILED);
        verify(ticketService, never()).createTicketFor(any(), any(), any());
    }

    @Test
    @DisplayName("用户 ID 为小数时应结构化失败，不得静默截断")
    void shouldFailWhenUserIdDecimal() {
        ToolContext ctx = new ToolContext(Map.of(
                AbstractAssistantTicketTool.USER_ID_CONTEXT_KEY, 2.5d,
                AbstractAssistantTicketTool.SYSTEM_ROLE_CONTEXT_KEY, SystemRole.USER));

        TicketCreateToolResponse response = tool().create(
                TicketType.ACCOUNT_PROVISIONING, "erp", "zhangsan",
                PermissionLevel.READ_ONLY, "需要只读账号", "账号开通申请", null, ctx);

        assertThat(response.success()).isFalse();
        assertThat(response.reasonCode()).isEqualTo(AssistantTicketCreateTool.REASON_CODE_CREATE_FAILED);
        verify(ticketService, never()).createTicketFor(any(), any(), any());
    }

    @Test
    @DisplayName("用户 ID 为非法字符串时应结构化失败")
    void shouldFailWhenUserIdWrongType() {
        ToolContext ctx = new ToolContext(Map.of(
                AbstractAssistantTicketTool.USER_ID_CONTEXT_KEY, "5",
                AbstractAssistantTicketTool.SYSTEM_ROLE_CONTEXT_KEY, SystemRole.USER));

        TicketCreateToolResponse response = tool().create(
                TicketType.ACCOUNT_PROVISIONING, "erp", "zhangsan",
                PermissionLevel.READ_ONLY, "需要只读账号", "账号开通申请", null, ctx);

        assertThat(response.success()).isFalse();
        assertThat(response.reasonCode()).isEqualTo(AssistantTicketCreateTool.REASON_CODE_CREATE_FAILED);
        verify(ticketService, never()).createTicketFor(any(), any(), any());
    }

    @Test
    @DisplayName("业务校验失败应结构化失败，不暴露内部校验细节")
    void shouldReturnStructuredFailureOnValidationError() {
        when(ticketService.createTicketFor(eq(5L), eq(SystemRole.USER), any(CreateTicketRequest.class)))
                .thenThrow(new BusinessException("标题不能为空"));

        TicketCreateToolResponse response = tool().create(
                TicketType.ACCOUNT_PROVISIONING, "erp", "zhangsan",
                PermissionLevel.READ_ONLY, "需要只读账号", " ", null,
                context(5L, SystemRole.USER));

        assertThat(response.success()).isFalse();
        assertThat(response.reasonCode()).isEqualTo(AssistantTicketCreateTool.REASON_CODE_CREATE_FAILED);
        assertThat(response.reasonMessage()).doesNotContain("标题");
        assertThat(response.ticket()).isNull();
    }

    @Test
    @DisplayName("意外系统异常应返回通用失败，不暴露数据库/堆栈等内部细节")
    void shouldReturnGenericFailureOnUnexpectedException() {
        when(ticketService.createTicketFor(eq(5L), eq(SystemRole.USER), any(CreateTicketRequest.class)))
                .thenThrow(new IllegalStateException("boom jdbc:postgresql://10.0.0.5:5432 secret=p@ss"));

        TicketCreateToolResponse response = tool().create(
                TicketType.ACCOUNT_PROVISIONING, "erp", "zhangsan",
                PermissionLevel.READ_ONLY, "需要只读账号", "账号开通申请", null,
                context(5L, SystemRole.USER));

        assertThat(response.success()).isFalse();
        assertThat(response.reasonCode()).isEqualTo(AbstractAssistantTicketTool.REASON_CODE_INTERNAL_ERROR);
        assertThat(response.reasonMessage()).doesNotContain("boom", "jdbc", "postgresql", "secret", "p@ss");
        assertThat(response.ticket()).isNull();
    }

    private ToolContext context(Long userId, SystemRole role) {
        return context(userId, role, 100L);
    }

    private ToolContext context(Long userId, SystemRole role, Object messageId) {
        Map<String, Object> map = new HashMap<>();
        map.put(AbstractAssistantTicketTool.USER_ID_CONTEXT_KEY, userId);
        map.put(AbstractAssistantTicketTool.SYSTEM_ROLE_CONTEXT_KEY, role);
        map.put(AbstractAssistantTicketTool.MESSAGE_ID_CONTEXT_KEY, messageId);
        return new ToolContext(map);
    }

    private CreateTicketResult createResult(Long id) {
        TicketDetailResponse detail = new TicketDetailResponse(
                id,
                "TK" + id,
                "账号开通申请",
                "需要只读账号",
                TicketType.ACCOUNT_PROVISIONING,
                TicketStatus.OPEN,
                "LOW",
                5L,
                null,
                null,
                "erp",
                "zhangsan",
                PermissionLevel.READ_ONLY,
                null,
                0,
                LocalDateTime.now(),
                LocalDateTime.now());
        return new CreateTicketResult(detail, false);
    }
}
