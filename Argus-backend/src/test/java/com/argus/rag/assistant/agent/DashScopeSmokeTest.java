package com.argus.rag.assistant.agent;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.argus.rag.assistant.memory.AssistantShortTermMemoryHook;
import com.argus.rag.assistant.model.enums.AssistantToolMode;
import com.argus.rag.assistant.model.vo.chat.AssistantAgentResult;
import com.argus.rag.assistant.service.AssistantConversationService;
import com.argus.rag.assistant.support.config.AssistantPromptContextBuilder;
import com.argus.rag.assistant.support.config.AssistantRunnableConfigFactory;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.ticket.model.vo.TicketListResult;
import com.argus.rag.ticket.service.TicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DashScope 真实模型冒烟测试（B 阶段）。
 *
 * <h3>运行方式（务必显式执行，普通 {@code ./mvnw test} 不会触发真实模型）</h3>
 * <pre>{@code
 * ./mvnw -Ddashscope.smoke=true -Dtest=DashScopeSmokeTest test
 * }</pre>
 * 本类同时受两个条件守卫，二者缺一即整类跳过、不产生任何真实模型调用：
 * <ul>
 *   <li>{@code DASHSCOPE_API_KEY} 环境变量已设置（与 {@code application-dev.yml} 中
 *       {@code spring.ai.dashscope.api-key: ${DASHSCOPE_API_KEY}} 的取值来源一致）；</li>
 *   <li>显式 opt-in 系统属性 {@code dashscope.smoke=true}——普通 {@code ./mvnw test}
 *       不携带该属性，因此即使环境里已有 Key 也不会自动联网。</li>
 * </ul>
 *
 * <h3>B1 配置隔离（手动构造 DashScope 模型）</h3>
 * <p>优先尝试用 {@code ApplicationContextRunner} 装配 {@code DashScopeChatAutoConfiguration}，
 * 但其 {@code dashScopeChatModel(...)} 工厂方法依赖 {@code RetryTemplate}、
 * {@code ToolCallingManager}、{@code ResponseErrorHandler}、{@code RestClient.Builder}、
 * {@code WebClient.Builder}、{@code DashScopeChatProperties}、{@code DashScopeConnectionProperties}
 * 等<b>来自其它 Spring Boot / Spring AI 自动配置的传递依赖</b>，无法由该自动配置独立满足
 * （逐项枚举这些自动配置脆弱且易随版本漂移），故按约定回退为手动构造。</p>
 * <p>经字节码核验，手动构造与自动配置装配出的模型使用<b>同一组底层默认值</b>：
 * {@code ToolCallingManager}=DefaultToolCallingManager、{@code RetryTemplate}=RetryUtils.DEFAULT_RETRY_TEMPLATE、
 * {@code ObservationRegistry}=NOOP、{@code ToolExecutionEligibilityPredicate}=DefaultToolExecutionEligibilityPredicate、
 * 默认 baseUrl={@code https://dashscope.aliyuncs.com}。本测试显式固定：</p>
 * <ul>
 *   <li>模型名 {@code qwen-plus}（与 {@code spring.ai.dashscope.chat.options.model} 一致）；</li>
 *   <li>API Key 来源 {@code System.getenv("DASHSCOPE_API_KEY")}（与 dev 配置一致，不打印、不落盘、不入库）；</li>
 *   <li>其余参数使用与自动配置相同的默认值。</li>
 * </ul>
 *
 * <h3>业务数据与费用隔离</h3>
 * <ul>
 *   <li>本类不加载 Spring 上下文，不连接任何真实数据库 / Elasticsearch / MinIO；</li>
 *   <li>{@link TicketService} 被 Mock 为空列表、{@link AssistantConversationService} 被 Mock 为空上下文，
 *       使用显式测试用户 ID {@value #USER_ID} 与 {@link SystemRole#USER} 角色；</li>
 *   <li>预计真实模型调用次数：B1=1、B2=1；B3 至少 2 次（若模型连续调用工具可能更多，由递归上限截断）；本类自身无重试 / 循环，失败即终止。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DashScope 真实模型冒烟测试")
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
@EnabledIfSystemProperty(named = "dashscope.smoke", matches = "true")
class DashScopeSmokeTest {

    private static final String ENV_API_KEY = "DASHSCOPE_API_KEY";

    /** 与 application-dev.yml `spring.ai.dashscope.chat.options.model` 一致 */
    private static final String MODEL_NAME = "qwen-plus";

    /** 明确的测试用户 / 会话 ID，避免与真实业务数据混淆 */
    private static final Long USER_ID = 900001L;
    private static final Long SESSION_ID = 990001L;

    @Mock
    private TicketService ticketService;

    @Mock
    private AssistantConversationService conversationService;

    @Mock
    private AssistantKnowledgeBaseTool knowledgeBaseTool;

    private DashScopeChatModel realChatModel;
    private RecordingChatModel recordingChatModel;
    private AssistantAgentFacade facade;

    @BeforeEach
    void setUp() {
        String apiKey = System.getenv(ENV_API_KEY);
        DashScopeApi api = DashScopeApi.builder().apiKey(apiKey).build();
        realChatModel = DashScopeChatModel.builder()
                .dashScopeApi(api)
                .defaultOptions(DashScopeChatOptions.builder().model(MODEL_NAME).build())
                .build();
        recordingChatModel = new RecordingChatModel(realChatModel);

        // 短期记忆 Hook 会读取对话上下文，mock 成空上下文，避免依赖真实数据库。
        // 使用 lenient：B1 只直连真实模型、不经过 Agent 门面，因此该桩在 B1 中不会被触发。
        lenient().when(conversationService.loadConversationContext(anyLong(), anyLong(), anyInt()))
                .thenReturn(new AssistantConversationService.AssistantConversationContext(null, null, null, List.of()));

        AssistantShortTermMemoryHook memoryHook = new AssistantShortTermMemoryHook(conversationService);
        AssistantTicketCreateTool createTool = new AssistantTicketCreateTool(ticketService);
        AssistantTicketQueryTool ticketQueryTool = new AssistantTicketQueryTool(ticketService);
        AssistantTicketDetailTool detailTool = new AssistantTicketDetailTool(ticketService);
        AssistantTicketExecutionsTool executionsTool = new AssistantTicketExecutionsTool(ticketService);
        AssistantReactAgentFactory factory = new AssistantReactAgentFactory(
                recordingChatModel, memoryHook, knowledgeBaseTool, createTool, ticketQueryTool, detailTool, executionsTool);
        facade = new AssistantAgentFacade(
                factory,
                new AssistantRunnableConfigFactory(),
                new AssistantPromptContextBuilder());
    }

    @Test
    @DisplayName("B1 配置隔离：模型/Key/参数与 dev 一致，且能发起一次真实调用")
    void b1_dashScopeModelConfiguredAgainstDevConfig() {
        ChatResponse response = realChatModel.call(new Prompt("请只回复两个汉字：正常"));

        assertThat(response.getResult().getOutput()).isInstanceOf(AssistantMessage.class);
        assertThat(response.getResult().getOutput().getText()).isNotBlank();
    }

    @Test
    @DisplayName("B2 结果验证：CHAT 模式返回有效模型答复而非框架错误")
    void b2_chatReturnsValidModelReply() {
        AssistantAgentResult result = facade.chat(
                USER_ID, SystemRole.USER, SESSION_ID, AssistantToolMode.CHAT, null, null,
                "请只回复两个汉字：正常");

        // 无异常抛出（有异常会在此断言前使测试失败）+ 返回内容符合指令，即真实模型答复而非框架错误串。
        assertThat(result.reply()).contains("正常");
        // CHAT 模式未装配任何工具，应只发生一次模型调用。
        assertThat(recordingChatModel.recordedResponses()).hasSize(1);
    }

    @Test
    @DisplayName("B3 真实工具调用：模型发起 queryTickets，工具执行，结果进入后续处理")
    void b3_ticketQueryTriggersRealToolCall() {
        // lenient：若真实模型未发起工具调用，该桩不会被触发，此时由下方 verify 直接给出
        // 「工具未被调用」的清晰失败信号，而不是叠加 UnnecessaryStubbingException 噪音。
        lenient().when(ticketService.listVisibleTickets(USER_ID, SystemRole.USER))
                .thenReturn(new TicketListResult(List.of(), false));

        AssistantAgentResult result = null;
        BusinessException failure = null;
        try {
            result = facade.chat(
                    USER_ID, SystemRole.USER, SESSION_ID, AssistantToolMode.TICKET_QUERY, null, null,
                    "请调用 queryTickets 工具查询我的工单，然后根据结果回答我。");
        } catch (BusinessException e) {
            // 不吞异常：先记录，待下方直接证据断言跑完后，再以原异常为 cause 重新抛出。
            failure = e;
        } finally {
            // 无论成败都输出本轮真实模型交互记录，便于定位「最终答复为空」这类只出现在真实模型下的问题。
            dumpRecordings();
        }

        // 1) 工具确实执行，且身份来自服务端注入的测试用户 / 角色，而非模型或请求体。
        verify(ticketService, atLeastOnce()).listVisibleTickets(USER_ID, SystemRole.USER);

        // 2) 模型确实发起了工具调用：第一次模型调用的返回是工具调用，而非直接回答。
        assertThat(recordingChatModel.recordedResponses()).hasSizeGreaterThanOrEqualTo(2);
        AssistantMessage firstOutput =
                (AssistantMessage) recordingChatModel.recordedResponses().get(0).getResult().getOutput();
        assertThat(firstOutput.hasToolCalls()).isTrue();
        assertThat(firstOutput.getToolCalls())
                .anyMatch(toolCall -> "queryTickets".equals(toolCall.name()));

        // 3) 工具结果进入 Agent 后续处理：第二次模型调用的上下文包含工具返回的 JSON，
        //    而不是仅验证模型调用次数。
        assertThat(joinedText(recordingChatModel.recordedPrompts().get(1)))
                .contains("\"success\":true")
                .contains("\"tickets\":[]");

        if (failure != null) {
            // 工具已执行、结果已回传，但 Agent 最终答复为空：保留原始异常作为 cause。
            // 区分「连续重复调用工具（疑似递归上限被静默终止）」与「单纯空回复」两种终止路径，
            // 不把「重复调用」写死成某个固定的递归迭代次数。
            int callCount = recordingChatModel.recordedResponses().size();
            boolean everyResponseIsToolCall = recordingChatModel.recordedResponses().stream()
                    .allMatch(r -> ((AssistantMessage) r.getResult().getOutput()).hasToolCalls());
            if (everyResponseIsToolCall && callCount >= 2) {
                throw new AssertionError(
                        "工具已执行、结果已回传，但模型连续 " + callCount
                                + " 次调用 queryTickets 后未给出最终答复，疑似命中递归上限被静默终止（诊断见上方输出）",
                        failure);
            }
            throw new AssertionError("工具已执行、结果已回传，但 Agent 最终答复为空（诊断见上方输出）", failure);
        }

        // 4) 最终返回正常答复。
        assertThat(result.reply()).isNotBlank();
    }

    /** 打印本轮真实模型交互的诊断信息（不含 API Key，仅消息角色 / 工具调用 / 文本摘要）。 */
    private void dumpRecordings() {
        System.out.println("[SMOKE-DIAG] modelCallCount=" + recordingChatModel.recordedResponses().size());
        for (int i = 0; i < recordingChatModel.recordedResponses().size(); i++) {
            AssistantMessage output =
                    (AssistantMessage) recordingChatModel.recordedResponses().get(i).getResult().getOutput();
            System.out.println("[SMOKE-DIAG] call[" + i + "] hasToolCalls=" + output.hasToolCalls()
                    + " toolCalls=" + output.getToolCalls().stream()
                            .map(toolCall -> toolCall.name() + "(" + toolCall.arguments() + ")").toList()
                    + " text=" + abbreviate(output.getText()));
        }
        for (int i = 0; i < recordingChatModel.recordedPrompts().size(); i++) {
            for (Message message : recordingChatModel.recordedPrompts().get(i)) {
                System.out.println("[SMOKE-DIAG] prompt[" + i + "] " + message.getClass().getSimpleName()
                        + " text=" + abbreviate(message.getText()));
            }
        }
    }

    private String abbreviate(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.replace("\r", " ").replace('\n', ' ');
        return normalized.length() > 300 ? normalized.substring(0, 300) + "..." : normalized;
    }

    private static String joinedText(List<Message> messages) {
        return messages.stream()
                .map(Message::getText)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));
    }

    /**
     * 真实模型的记录装饰器：把每一次 {@link #call(Prompt)} 原样委托给底层真实模型，
     * 同时记录发送的 Prompt 与收到的 ChatResponse，用于 B3 证明模型发起工具调用、工具结果进入后续处理。
     */
    private static final class RecordingChatModel implements ChatModel {

        private final ChatModel delegate;
        private final List<ChatResponse> recordedResponses = new ArrayList<>();
        private final List<List<Message>> recordedPrompts = new ArrayList<>();

        RecordingChatModel(ChatModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            ChatResponse response = delegate.call(prompt);
            recordedPrompts.add(prompt.getInstructions());
            recordedResponses.add(response);
            return response;
        }

        List<ChatResponse> recordedResponses() {
            return recordedResponses;
        }

        List<List<Message>> recordedPrompts() {
            return recordedPrompts;
        }
    }
}
