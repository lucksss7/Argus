package com.argus.rag.assistant.agent;

import com.argus.rag.assistant.memory.AssistantShortTermMemoryHook;
import com.argus.rag.assistant.model.enums.AssistantToolMode;
import com.argus.rag.assistant.model.vo.chat.AssistantAgentResult;
import com.argus.rag.assistant.service.AssistantConversationService;
import com.argus.rag.assistant.support.config.AssistantPromptContextBuilder;
import com.argus.rag.assistant.support.config.AssistantRunnableConfigFactory;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.ticket.model.dto.CreateTicketRequest;
import com.argus.rag.ticket.model.enums.PermissionLevel;
import com.argus.rag.ticket.model.enums.TicketStatus;
import com.argus.rag.ticket.model.enums.TicketType;
import com.argus.rag.ticket.model.vo.CreateTicketResult;
import com.argus.rag.ticket.model.vo.TicketDetailResponse;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 工单助手（TICKET_ASSIST）真实链路（Mock 模型）验证：新增建单工具
 * {@link AssistantTicketCreateTool} 的触发、服务端身份透传、脱敏建单结果进入模型上下文，
 * 以及 system instruction 中的建单与只读边界规则。
 * <p>结构与 {@link AssistantAgentFacadeTicketQueryTest} 一致：使用真实的
 * {@link AssistantReactAgentFactory}、真实工具与真实提示词构建逻辑，仅替换 ChatModel 与对话上下文依赖。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Agent 工单助手真实链路（Mock 模型）")
class AssistantAgentFacadeTicketAssistTest {

    private static final Long USER_ID = 5L;
    private static final Long SESSION_ID = 1L;
    private static final Long MESSAGE_ID = 100L;
    private static final String CREATE_ARGUMENTS =
            "{\"type\":\"ACCOUNT_PROVISIONING\",\"systemName\":\"erp\",\"targetUsername\":\"zhangsan\","
            + "\"permissionLevel\":\"READ_ONLY\",\"description\":\"需要只读账号\",\"title\":\"账号开通申请\",\"priority\":\"LOW\"}";

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
    @DisplayName("触发 createTicket：真实工具被调用，服务端身份透传，脱敏建单结果进入第二次模型调用")
    void shouldCreateTicketAndPassServerIdentity() {
        when(ticketService.createTicketFor(eq(USER_ID), eq(SystemRole.USER), any(CreateTicketRequest.class)))
                .thenReturn(createResult(42L));

        chatModel.enqueue(
                toolCallMessage("call-1", "createTicket", CREATE_ARGUMENTS),
                new AssistantMessage("已为你创建工单 TK42，状态为 OPEN。"));

        AssistantAgentResult result = facade.chat(
                USER_ID, SystemRole.USER, SESSION_ID, AssistantToolMode.TICKET_ASSIST, null, MESSAGE_ID,
                "帮我给 zhangsan 在 erp 开通只读账号");

        assertThat(result.reply()).isEqualTo("已为你创建工单 TK42，状态为 OPEN。");
        // 建单身份取自服务端注入的上下文，而非模型提供的参数。
        verify(ticketService).createTicketFor(eq(USER_ID), eq(SystemRole.USER), any(CreateTicketRequest.class));
        assertThat(chatModel.recordedPrompts()).hasSize(2);
        String secondPrompt = joinedText(chatModel.recordedPrompts().get(1));
        assertThat(secondPrompt)
                .contains("\"success\":true")
                .contains("\"ticketNo\":\"TK42\"");
    }

    @Test
    @DisplayName("建单失败：CREATE_FAILED 结构化结果进入第二次模型调用，且不泄露内部校验细节")
    void shouldPassCreateFailureToModelWithoutLeakingInternals() {
        when(ticketService.createTicketFor(eq(USER_ID), eq(SystemRole.USER), any(CreateTicketRequest.class)))
                .thenThrow(new BusinessException("标题不能为空"));

        chatModel.enqueue(
                toolCallMessage("call-1", "createTicket", CREATE_ARGUMENTS),
                new AssistantMessage("工单创建失败，请稍后重试"));

        AssistantAgentResult result = facade.chat(
                USER_ID, SystemRole.USER, SESSION_ID, AssistantToolMode.TICKET_ASSIST, null, MESSAGE_ID,
                "帮我给 zhangsan 在 erp 开通只读账号");

        assertThat(result.reply()).isEqualTo("工单创建失败，请稍后重试");
        verify(ticketService).createTicketFor(eq(USER_ID), eq(SystemRole.USER), any(CreateTicketRequest.class));
        assertThat(chatModel.recordedPrompts()).hasSize(2);
        String secondPrompt = joinedText(chatModel.recordedPrompts().get(1));
        assertThat(secondPrompt).contains("\"success\":false");
        assertThat(secondPrompt).contains("\"reasonCode\":\"CREATE_FAILED\"");
        // 不泄露服务端校验细节与异常类型
        assertThat(secondPrompt).doesNotContain("不能为空", "BusinessException");
    }

    @Test
    @DisplayName("发送给模型的系统指令应包含 TICKET_ASSIST 建单与只读边界规则")
    void shouldIncludeTicketAssistRulesInSystemInstruction() {
        chatModel.enqueue(new AssistantMessage("好的"));

        facade.chat(USER_ID, SystemRole.USER, SESSION_ID, AssistantToolMode.TICKET_ASSIST, null, MESSAGE_ID, "帮我建一张工单");

        String firstPromptText = joinedText(chatModel.firstRecordedPrompt());
        assertThat(firstPromptText)
                .contains("createTicket")
                .contains("初始状态固定为 OPEN")
                .contains("同一建单意图只调用一次")
                .contains("没有任何工具能让你")
                .contains("提交审批")
                .contains("关闭或取消工单")
                .contains("绕过审批");
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

    private CreateTicketResult createResult(Long id) {
        TicketDetailResponse detail = new TicketDetailResponse(
                id,
                "TK" + id,
                "账号开通申请",
                "需要只读账号",
                TicketType.ACCOUNT_PROVISIONING,
                TicketStatus.OPEN,
                "LOW",
                USER_ID,
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

        List<Message> firstRecordedPrompt() {
            return recordedPrompts.isEmpty() ? List.of() : recordedPrompts.get(0);
        }
    }
}
