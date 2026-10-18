package com.argus.rag.assistant.agent;

import com.argus.rag.assistant.memory.AssistantShortTermMemoryHook;
import com.argus.rag.assistant.model.enums.AssistantToolMode;
import com.argus.rag.assistant.model.vo.chat.AssistantAgentResult;
import com.argus.rag.assistant.service.AssistantConversationService;
import com.argus.rag.assistant.support.config.AssistantPromptContextBuilder;
import com.argus.rag.assistant.support.config.AssistantRunnableConfigFactory;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ForbiddenException;
import com.argus.rag.ticket.model.enums.TicketStatus;
import com.argus.rag.ticket.model.enums.TicketType;
import com.argus.rag.ticket.model.vo.TicketListResult;
import com.argus.rag.ticket.model.vo.TicketSummaryVO;
import com.argus.rag.ticket.service.TicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 真实链路（A 层 Mock 模型）验证：落在 {@link AssistantAgentFacade}，
 * 使用真实的 {@link AssistantReactAgentFactory}、{@link AssistantTicketQueryTool}
 * 与真实提示词构建逻辑，仅替换 ChatModel 与对话上下文依赖。
 *
 * <p>通过可控的 RecordingChatModel 依次返回「工具调用请求」与「最终回答」，
 * 验证 ReactAgent 确实触发 queryTickets 工具、工具使用服务端注入的身份、
 * 工具结果进入第二次模型调用的上下文，以及 Facade 正确返回最终结果。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Agent 工单查询真实链路（Mock 模型）")
class AssistantAgentFacadeTicketQueryTest {

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
        // 短期记忆 Hook 会读取对话上下文，mock 成空上下文，避免依赖真实数据库。
        when(conversationService.loadConversationContext(anyLong(), anyLong(), anyInt()))
                .thenReturn(new AssistantConversationService.AssistantConversationContext(null, null, null, List.of()));

        AssistantShortTermMemoryHook memoryHook = new AssistantShortTermMemoryHook(conversationService);
        AssistantTicketCreateTool createTool = new AssistantTicketCreateTool(ticketService);
        AssistantTicketQueryTool ticketQueryTool = new AssistantTicketQueryTool(ticketService);
        AssistantTicketDetailTool detailTool = new AssistantTicketDetailTool(ticketService);
        AssistantTicketExecutionsTool executionsTool = new AssistantTicketExecutionsTool(ticketService);
        AssistantReactAgentFactory factory = new AssistantReactAgentFactory(
                chatModel(), memoryHook, knowledgeBaseTool, createTool, ticketQueryTool, detailTool, executionsTool);
        facade = new AssistantAgentFacade(
                factory,
                new AssistantRunnableConfigFactory(),
                new AssistantPromptContextBuilder());
    }

    @Test
    @DisplayName("查询到工单：真实工具被触发，服务端身份透传，工具结果进入第二次模型调用")
    void shouldQueryTicketsAndReturnFinalAnswer() {
        when(ticketService.listVisibleTickets(USER_ID, SystemRole.USER))
                .thenReturn(new TicketListResult(List.of(summary(1L, "zhangsan")), false));

        chatModel.enqueue(
                toolCallMessage("call-1", "queryTickets"),
                new AssistantMessage("为你找到 1 条工单：TK1"));

        AssistantAgentResult result = facade.chat(
                USER_ID, SystemRole.USER, SESSION_ID, AssistantToolMode.TICKET_QUERY, null, null,"帮我查工单");

        assertThat(result.reply()).isEqualTo("为你找到 1 条工单：TK1");
        // 真实 ReactAgent 触发真实 queryTickets 工具，且身份取自服务端注入的上下文。
        verify(ticketService).listVisibleTickets(USER_ID, SystemRole.USER);
        // 工具返回的工单摘要进入了第二次模型调用的上下文。
        assertThat(chatModel.recordedPrompts()).hasSize(2);
        assertThat(joinedText(chatModel.recordedPrompts().get(1))).contains("\"ticketNo\":\"TK1\"");
    }

    @Test
    @DisplayName("查询无工单：工具返回 success=true 且空列表，模型基于空结果作答")
    void shouldQueryTicketsAndAnswerEmpty() {
        when(ticketService.listVisibleTickets(USER_ID, SystemRole.USER))
                .thenReturn(new TicketListResult(List.of(), false));

        chatModel.enqueue(
                toolCallMessage("call-1", "queryTickets"),
                new AssistantMessage("没有查到符合条件的工单"));

        AssistantAgentResult result = facade.chat(
                USER_ID, SystemRole.USER, SESSION_ID, AssistantToolMode.TICKET_QUERY, null, null,"帮我查工单");

        assertThat(result.reply()).isEqualTo("没有查到符合条件的工单");
        verify(ticketService).listVisibleTickets(USER_ID, SystemRole.USER);
        assertThat(joinedText(chatModel.recordedPrompts().get(1)))
                .contains("\"success\":true")
                .contains("\"tickets\":[]");
    }

    @Test
    @DisplayName("工具返回 success=false：失败码进入模型上下文，且不泄漏内部异常细节")
    void shouldPropagateStructuredFailureToModel() {
        when(ticketService.listVisibleTickets(USER_ID, SystemRole.USER))
                .thenThrow(new ForbiddenException("无权查看该工单"));

        chatModel.enqueue(
                toolCallMessage("call-1", "queryTickets"),
                new AssistantMessage("工单查询失败，请稍后重试"));

        AssistantAgentResult result = facade.chat(
                USER_ID, SystemRole.USER, SESSION_ID, AssistantToolMode.TICKET_QUERY, null, null,"帮我查工单");

        assertThat(result.reply()).isEqualTo("工单查询失败，请稍后重试");
        verify(ticketService).listVisibleTickets(USER_ID, SystemRole.USER);
        String secondPrompt = joinedText(chatModel.recordedPrompts().get(1));
        assertThat(secondPrompt).contains("\"reasonCode\":\"QUERY_FAILED\"");
        assertThat(secondPrompt).doesNotContain("无权查看");
    }

    @Test
    @DisplayName("工具返回 hasMore=true：截断标记进入模型上下文")
    void shouldPropagateHasMoreToModel() {
        when(ticketService.listVisibleTickets(USER_ID, SystemRole.USER))
                .thenReturn(new TicketListResult(List.of(summary(1L, "zhangsan")), true));

        chatModel.enqueue(
                toolCallMessage("call-1", "queryTickets"),
                new AssistantMessage("为你找到部分工单，可能还有更多"));

        AssistantAgentResult result = facade.chat(
                USER_ID, SystemRole.USER, SESSION_ID, AssistantToolMode.TICKET_QUERY, null, null,"帮我查工单");

        assertThat(result.reply()).isEqualTo("为你找到部分工单，可能还有更多");
        verify(ticketService).listVisibleTickets(USER_ID, SystemRole.USER);
        assertThat(joinedText(chatModel.recordedPrompts().get(1))).contains("\"hasMore\":true");
    }

    @Test
    @DisplayName("模型未调用工具：不应执行 listVisibleTickets")
    void shouldNotExecuteToolWhenModelAnswersDirectly() {
        chatModel.enqueue(new AssistantMessage("我不需要查询工单"));

        AssistantAgentResult result = facade.chat(
                USER_ID, SystemRole.USER, SESSION_ID, AssistantToolMode.TICKET_QUERY, null, null,"帮我查工单");

        assertThat(result.reply()).isEqualTo("我不需要查询工单");
        verify(ticketService, never()).listVisibleTickets(any(), any());
        assertThat(chatModel.recordedPrompts()).hasSize(1);
    }

    @Test
    @DisplayName("发送给模型的系统指令应包含 TICKET_QUERY 关键规则")
    void shouldIncludeTicketQueryRulesInSystemInstruction() {
        chatModel.enqueue(new AssistantMessage("好的"));

        facade.chat(USER_ID, SystemRole.USER, SESSION_ID, AssistantToolMode.TICKET_QUERY, null, null,"帮我查工单");

        // 断言对象是实际发送给 ChatModel.call(Prompt) 的 Prompt，而非提示词构建器的中间结果。
        // 该框架把 builder.instruction(...) 作为一条 user 角色消息前置注入（而非 SystemMessage），
        // 因此这里按整个 Prompt 的文本断言关键规则确实送达模型。
        String firstPromptText = joinedText(chatModel.firstRecordedPrompt());
        assertThat(firstPromptText)
                .contains("queryTickets")
                .contains("success=false")
                .contains("tickets 为空")
                .contains("hasMore=true")
                .contains("结果不完整")
                .contains("只调用一次")
                .contains("不得因结果为空而重复调用")
                .contains("不得再次调用该工具");
    }

    @Test
    @Timeout(60)
    @DisplayName("模型连续重复调用工具：递归上限处有界终止，Facade 拒绝把工具调用消息当最终回答")
    void shouldTerminateRepeatedToolCallWithinRecursionLimit() {
        AtomicInteger toolExecutions = new AtomicInteger();
        when(ticketService.listVisibleTickets(USER_ID, SystemRole.USER))
                .thenAnswer(invocation -> {
                    toolExecutions.incrementAndGet();
                    return new TicketListResult(List.of(), false);
                });
        chatModel.alwaysReturn(toolCallMessage("call-1", "queryTickets"));

        AssistantAgentResult result = null;
        BusinessException failure = null;
        try {
            result = facade.chat(USER_ID, SystemRole.USER, SESSION_ID,
                    AssistantToolMode.TICKET_QUERY, null, null,"帮我查工单");
        } catch (BusinessException e) {
            failure = e;
        }

        int modelCalls = chatModel.recordedPrompts().size();
        System.out.println("[MOCK-LOOP] modelCalls=" + modelCalls
                + " toolExecutions=" + toolExecutions.get()
                + " reply=" + (result == null ? "<null>" : result.reply())
                + " failure=" + (failure == null ? "<null>" : failure.getMessage()));

        // 1) 工具确实被调用，且不止一次（发生重复调用）。
        verify(ticketService, atLeast(2)).listVisibleTickets(USER_ID, SystemRole.USER);

        // 2) 工具结果进入后续模型上下文（第 2 轮 Prompt 含工具返回 JSON）。
        assertThat(chatModel.recordedPrompts()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(joinedText(chatModel.recordedPrompts().get(1)))
                .contains("\"success\":true")
                .contains("\"tickets\":[]");

        // 3) 有界终止：递归上限 10 个 super-step、每循环约 3 个 super-step，
        //    调用次数应落在 [2, 5] 区间而非无限增长（不写死等于 3）。
        assertThat(modelCalls).isBetween(2, 5);
        assertThat(toolExecutions.get()).isBetween(2, 5);

        // 4) 递归上限静默终止后，状态里最后一条 AssistantMessage 仍是工具调用消息；
        //    Facade 的最终回复校验应识别「仍携带工具调用」并拒绝，抛出「助手返回内容为空」，
        //    而不是把工具调用消息（其文本经框架序列化变成字面量 "null"）当作最终回答返回。
        assertThat(failure).isNotNull();
        assertThat(failure.getMessage()).isEqualTo("助手返回内容为空");
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("最终回答为字面量 null 文本：应拒绝并抛出空回复异常")
    void shouldRejectLiteralNullTextAsFinalAnswer() {
        chatModel.enqueue(new AssistantMessage("null"));

        assertEmptyReplyRejected();
    }

    @Test
    @DisplayName("最终回答为空串：应拒绝并抛出空回复异常")
    void shouldRejectEmptyTextAsFinalAnswer() {
        chatModel.enqueue(new AssistantMessage(""));

        assertEmptyReplyRejected();
    }

    @Test
    @DisplayName("最终回答为纯空白：应拒绝并抛出空回复异常")
    void shouldRejectBlankTextAsFinalAnswer() {
        chatModel.enqueue(new AssistantMessage("   "));

        assertEmptyReplyRejected();
    }

    @Test
    @DisplayName("最终回答为 Java null 文本（无工具调用）：应拒绝并抛出空回复异常")
    void shouldRejectNullTextAsFinalAnswer() {
        // AssistantMessage.builder().build() 的 textContent 为 Java null；
        // 经图状态 Jackson 序列化往返后会在框架内部变成字面量 "null"，
        // 无论哪种形态都不能作为有效最终回答返回。
        chatModel.enqueue(AssistantMessage.builder().build());

        assertEmptyReplyRejected();
    }

    /** 断言 Facade 对当前入队的无效最终回答抛出「助手返回内容为空」业务异常。 */
    private void assertEmptyReplyRejected() {
        BusinessException failure = null;
        try {
            facade.chat(USER_ID, SystemRole.USER, SESSION_ID, AssistantToolMode.TICKET_QUERY, null, null,"帮我查工单");
        } catch (BusinessException e) {
            failure = e;
        }
        assertThat(failure).as("应抛出「助手返回内容为空」业务异常").isNotNull();
        assertThat(failure.getMessage()).isEqualTo("助手返回内容为空");
    }

    private RecordingChatModel chatModel() {
        if (chatModel == null) {
            chatModel = new RecordingChatModel();
        }
        return chatModel;
    }

    private static AssistantMessage toolCallMessage(String id, String toolName) {
        return AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", toolName, "{}")))
                .build();
    }

    private static String joinedText(List<Message> messages) {
        return messages.stream()
                .map(Message::getText)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));
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

    /**
     * 可控的 Mock ChatModel：按入队顺序依次返回 AssistantMessage，
     * 并记录每一次 {@link #call(Prompt)} 收到的消息列表，用于断言工具结果是否进入模型上下文。
     */
    private static final class RecordingChatModel implements ChatModel {

        private final List<AssistantMessage> responses = new ArrayList<>();
        private final List<List<Message>> recordedPrompts = new ArrayList<>();
        private AssistantMessage stickyMessage;

        void enqueue(AssistantMessage... messages) {
            responses.addAll(List.of(messages));
        }

        /** 队列耗尽后持续返回同一条消息，用于模拟连续重复的工具调用；默认不启用，不影响现有用例。 */
        void alwaysReturn(AssistantMessage message) {
            this.stickyMessage = message;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            recordedPrompts.add(prompt.getInstructions());
            AssistantMessage next;
            if (!responses.isEmpty()) {
                next = responses.remove(0);
            } else if (stickyMessage != null) {
                next = stickyMessage;
            } else {
                next = new AssistantMessage("");
            }
            return new ChatResponse(List.of(new Generation(next)));
        }

        List<List<Message>> recordedPrompts() {
            return recordedPrompts;
        }

        /** 第一次模型调用收到的消息列表（即首次发送给模型的 Prompt）。 */
        List<Message> firstRecordedPrompt() {
            return recordedPrompts.isEmpty() ? List.of() : recordedPrompts.get(0);
        }
    }
}
