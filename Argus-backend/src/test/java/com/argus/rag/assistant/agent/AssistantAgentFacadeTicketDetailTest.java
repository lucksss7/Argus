package com.argus.rag.assistant.agent;

import com.argus.rag.assistant.memory.AssistantShortTermMemoryHook;
import com.argus.rag.assistant.model.enums.AssistantToolMode;
import com.argus.rag.assistant.model.vo.chat.AssistantAgentResult;
import com.argus.rag.assistant.service.AssistantConversationService;
import com.argus.rag.assistant.support.config.AssistantPromptContextBuilder;
import com.argus.rag.assistant.support.config.AssistantRunnableConfigFactory;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.ForbiddenException;
import com.argus.rag.ticket.model.enums.ExecutionResult;
import com.argus.rag.ticket.model.enums.ExecutionType;
import com.argus.rag.ticket.model.enums.ExecutionVerification;
import com.argus.rag.ticket.model.enums.PermissionLevel;
import com.argus.rag.ticket.model.enums.TicketStatus;
import com.argus.rag.ticket.model.enums.TicketType;
import com.argus.rag.ticket.model.vo.ExecutionRecordVO;
import com.argus.rag.ticket.model.vo.TicketDetailToolVO;
import com.argus.rag.ticket.service.TicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 真实链路（A 层 Mock 模型）验证：新增的两个工单只读工具
 * {@link AssistantTicketDetailTool} 与 {@link AssistantTicketExecutionsTool} 的触发与身份透传。
 * <p>结构与 {@link AssistantAgentFacadeTicketQueryTest} 一致：使用真实的
 * {@link AssistantReactAgentFactory}、真实工具与真实提示词构建逻辑，仅替换 ChatModel 与对话上下文依赖。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Agent 工单详情/执行记录真实链路（Mock 模型）")
class AssistantAgentFacadeTicketDetailTest {

    private static final Long USER_ID = 5L;
    private static final Long SESSION_ID = 1L;

    @Mock
    private TicketService ticketService;

    @Mock
    private AssistantConversationService conversationService;

    @Mock
    private AssistantKnowledgeBaseTool knowledgeBaseTool;

    private RecordingChatModel chatModel;
    private AssistantAgentFacade facade;

    @BeforeEach
    void setUp() {
        when(conversationService.loadConversationContext(anyLong(), anyLong(), anyInt()))
                .thenReturn(new AssistantConversationService.AssistantConversationContext(null, null, null, List.of()));

        AssistantShortTermMemoryHook memoryHook = new AssistantShortTermMemoryHook(conversationService);
        AssistantTicketCreateTool createTool = new AssistantTicketCreateTool(ticketService);
        AssistantTicketQueryTool queryTool = new AssistantTicketQueryTool(ticketService);
        AssistantTicketDetailTool detailTool = new AssistantTicketDetailTool(ticketService);
        AssistantTicketExecutionsTool executionsTool = new AssistantTicketExecutionsTool(ticketService);
        AssistantReactAgentFactory factory = new AssistantReactAgentFactory(
                chatModel(), memoryHook, knowledgeBaseTool, createTool, queryTool, detailTool, executionsTool);
        facade = new AssistantAgentFacade(
                factory,
                new AssistantRunnableConfigFactory(),
                new AssistantPromptContextBuilder());
    }

    @Test
    @DisplayName("触发 getTicketDetail：真实工具被调用，服务端身份透传，详情进入第二次模型调用")
    void shouldTriggerDetailToolAndPassServerIdentity() {
        when(ticketService.getTicketDetailFor(42L, USER_ID, SystemRole.USER))
                .thenReturn(detail(42L, "zhangsan"));

        chatModel.enqueue(
                toolCallMessage("call-1", "getTicketDetail", "{\"ticketId\":42}"),
                new AssistantMessage("工单 TK42 正在等待处理"));

        AssistantAgentResult result = facade.chat(
                USER_ID, SystemRole.USER, SESSION_ID, AssistantToolMode.TICKET_QUERY, null, null,"帮我查 42 号工单详情");

        assertThat(result.reply()).isEqualTo("工单 TK42 正在等待处理");
        verify(ticketService).getTicketDetailFor(42L, USER_ID, SystemRole.USER);
        assertThat(chatModel.recordedPrompts()).hasSize(2);
        assertThat(joinedText(chatModel.recordedPrompts().get(1)))
                .contains("\"ticketNo\":\"TK42\"")
                .contains("\"title\":\"账号开通申请\"");
    }

    @Test
    @DisplayName("触发 getTicketExecutions：真实工具被调用，服务端身份透传，记录进入第二次模型调用")
    void shouldTriggerExecutionsToolAndPassServerIdentity() {
        when(ticketService.getTicketExecutionsFor(42L, USER_ID, SystemRole.USER))
                .thenReturn(List.of(execution(1, ExecutionResult.SUCCESS)));

        chatModel.enqueue(
                toolCallMessage("call-1", "getTicketExecutions", "{\"ticketId\":42}"),
                new AssistantMessage("工单 42 有 1 次成功执行记录"));

        AssistantAgentResult result = facade.chat(
                USER_ID, SystemRole.USER, SESSION_ID, AssistantToolMode.TICKET_QUERY, null, null,"帮我查 42 号工单执行记录");

        assertThat(result.reply()).isEqualTo("工单 42 有 1 次成功执行记录");
        verify(ticketService).getTicketExecutionsFor(42L, USER_ID, SystemRole.USER);
        assertThat(chatModel.recordedPrompts()).hasSize(2);
        assertThat(joinedText(chatModel.recordedPrompts().get(1))).contains("\"attemptNo\":1");
    }

    @Test
    @DisplayName("触发 getTicketExecutions：核验结论与核验时间进入第二次模型调用，且不泄露核验人")
    void shouldPassVerificationStatusAndVerifiedAtToModel() {
        when(ticketService.getTicketExecutionsFor(42L, USER_ID, SystemRole.USER))
                .thenReturn(List.of(execution(1, ExecutionResult.UNCERTAIN,
                        ExecutionVerification.CONFIRMED_SUCCESS, LocalDateTime.of(2026, 1, 2, 3, 4, 5))));

        chatModel.enqueue(
                toolCallMessage("call-1", "getTicketExecutions", "{\"ticketId\":42}"),
                new AssistantMessage("该工单的执行记录已核验为成功"));

        AssistantAgentResult result = facade.chat(
                USER_ID, SystemRole.USER, SESSION_ID, AssistantToolMode.TICKET_QUERY, null, null,
                "帮我查 42 号工单执行记录");

        assertThat(result.reply()).isEqualTo("该工单的执行记录已核验为成功");
        verify(ticketService).getTicketExecutionsFor(42L, USER_ID, SystemRole.USER);
        String secondPrompt = joinedText(chatModel.recordedPrompts().get(1));
        assertThat(secondPrompt).contains("\"verificationStatus\":\"CONFIRMED_SUCCESS\"");
        assertThat(secondPrompt).contains("\"verifiedAt\"");
        assertThat(secondPrompt).doesNotContain("\"verifiedBy\"");
    }

    @Test
    @DisplayName("详情工具权限拒绝：失败码进入模型上下文，且不泄漏内部异常细节")
    void shouldPropagateStructuredFailureToModel() {
        when(ticketService.getTicketDetailFor(42L, USER_ID, SystemRole.USER))
                .thenThrow(new ForbiddenException("无权查看该工单"));

        chatModel.enqueue(
                toolCallMessage("call-1", "getTicketDetail", "{\"ticketId\":42}"),
                new AssistantMessage("工单详情查询失败，请稍后重试"));

        AssistantAgentResult result = facade.chat(
                USER_ID, SystemRole.USER, SESSION_ID, AssistantToolMode.TICKET_QUERY, null, null,"帮我查 42 号工单详情");

        assertThat(result.reply()).isEqualTo("工单详情查询失败，请稍后重试");
        verify(ticketService).getTicketDetailFor(42L, USER_ID, SystemRole.USER);
        String secondPrompt = joinedText(chatModel.recordedPrompts().get(1));
        assertThat(secondPrompt).contains("\"reasonCode\":\"QUERY_FAILED\"");
        assertThat(secondPrompt).doesNotContain("无权查看");
    }

    private RecordingChatModel chatModel() {
        if (chatModel == null) {
            chatModel = new RecordingChatModel();
        }
        return chatModel;
    }

    private static AssistantMessage toolCallMessage(String id, String toolName, String arguments) {
        return AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", toolName, arguments)))
                .build();
    }

    private static String joinedText(List<Message> messages) {
        return messages.stream()
                .map(Message::getText)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));
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

    private ExecutionRecordVO execution(int attemptNo, ExecutionResult result) {
        return new ExecutionRecordVO(
                100L + attemptNo,
                attemptNo,
                ExecutionType.PROVISION_ACCOUNT,
                result,
                null,
                LocalDateTime.now(),
                LocalDateTime.now(),
                null, null);
    }

    private ExecutionRecordVO execution(int attemptNo, ExecutionResult result,
                                        ExecutionVerification verificationStatus, LocalDateTime verifiedAt) {
        return new ExecutionRecordVO(
                100L + attemptNo,
                attemptNo,
                ExecutionType.PROVISION_ACCOUNT,
                result,
                null,
                LocalDateTime.now(),
                LocalDateTime.now(),
                verificationStatus,
                verifiedAt);
    }

    /**
     * 可控的 Mock ChatModel：按入队顺序依次返回 AssistantMessage，
     * 并记录每一次 {@link #call(Prompt)} 收到的消息列表，用于断言工具结果是否进入模型上下文。
     */
    private static final class RecordingChatModel implements ChatModel {

        private final List<AssistantMessage> responses = new ArrayList<>();
        private final List<List<Message>> recordedPrompts = new ArrayList<>();

        void enqueue(AssistantMessage... messages) {
            responses.addAll(List.of(messages));
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            recordedPrompts.add(prompt.getInstructions());
            AssistantMessage next = responses.isEmpty() ? new AssistantMessage("") : responses.remove(0);
            return new ChatResponse(List.of(new Generation(next)));
        }

        List<List<Message>> recordedPrompts() {
            return recordedPrompts;
        }
    }
}
