package com.argus.rag.assistant.eval;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.argus.rag.assistant.agent.AssistantAgentFacade;
import com.argus.rag.assistant.agent.AssistantKnowledgeBaseTool;
import com.argus.rag.assistant.agent.AssistantReactAgentFactory;
import com.argus.rag.assistant.agent.AssistantTicketCreateTool;
import com.argus.rag.assistant.agent.AssistantTicketDetailTool;
import com.argus.rag.assistant.agent.AssistantTicketExecutionsTool;
import com.argus.rag.assistant.agent.AssistantTicketQueryTool;
import com.argus.rag.assistant.memory.AssistantShortTermMemoryHook;
import com.argus.rag.assistant.model.enums.AssistantToolMode;
import com.argus.rag.assistant.model.vo.chat.AssistantAgentResult;
import com.argus.rag.assistant.service.AssistantConversationService;
import com.argus.rag.assistant.support.config.AssistantPromptContextBuilder;
import com.argus.rag.assistant.support.config.AssistantRunnableConfigFactory;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.ticket.model.dto.CreateTicketRequest;
import com.argus.rag.ticket.model.enums.PermissionLevel;
import com.argus.rag.ticket.model.enums.TicketStatus;
import com.argus.rag.ticket.model.enums.TicketType;
import com.argus.rag.ticket.model.vo.CreateTicketResult;
import com.argus.rag.ticket.model.vo.TicketDetailResponse;
import com.argus.rag.ticket.model.vo.TicketListResult;
import com.argus.rag.ticket.service.TicketService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 工单 Agent 真实模型评测（阶段五）。
 *
 * <h3>运行方式（务必显式执行，普通 {@code ./mvnw test} 不会触发真实模型）</h3>
 * <pre>{@code
 * ./mvnw -Dagent.eval=true -Dtest=AgentEvaluationTest test
 * }</pre>
 * 需同时满足两个条件，缺一即整类跳过、不产生任何真实模型调用：
 * <ul>
 *   <li>{@code DASHSCOPE_API_KEY} 环境变量已设置；</li>
 *   <li>显式 opt-in 系统属性 {@code agent.eval=true}。</li>
 * </ul>
 *
 * <p>评测内容：加载 {@code /eval/eval-cases.json}，逐条通过 {@link AssistantAgentFacade}
 * 以真实模型跑一遍，记录模型发起的工具调用（名称 + 参数）与最终回复，交给
 * {@link AgentEvalSupport#score} 评分，最后汇总为维度准确率与逐条明细，写入
 * {@code target/eval-report/eval-report.md}。</p>
 *
 * <p>业务数据隔离：{@link TicketService} 被 Mock（建单返回成功、查询返回空列表），
 * 不连接真实数据库；模型只被评测「是否选择正确工具、抽取正确参数、是否误调用、是否谎报成功」，
 * 工单服务本身的正确性已由单元/集成测试覆盖，不在本评测范围内。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("工单 Agent 真实模型评测")
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
@EnabledIfSystemProperty(named = "agent.eval", matches = "true")
class AgentEvaluationTest {

    private static final String MODEL_NAME = System.getProperty("agent.eval.model", "qwen-plus");
    private static final Long USER_ID = 900001L;
    private static final Long SESSION_ID = 990001L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private TicketService ticketService;

    @Mock
    private AssistantConversationService conversationService;

    @Mock
    private AssistantKnowledgeBaseTool knowledgeBaseTool;

    private RecordingChatModel recordingChatModel;
    private AssistantAgentFacade facade;
    private AtomicBoolean createInvoked;

    @BeforeEach
    void setUp() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        DashScopeApi api = DashScopeApi.builder().apiKey(apiKey).build();
        DashScopeChatModel realChatModel = DashScopeChatModel.builder()
                .dashScopeApi(api)
                // temperature=0：评测要求可复现，避免采样随机性导致同一用例每次跑结果漂移。
                .defaultOptions(DashScopeChatOptions.builder().model(MODEL_NAME).temperature(0.0).build())
                .build();
        recordingChatModel = new RecordingChatModel(realChatModel);

        lenient().when(conversationService.loadConversationContext(anyLong(), anyLong(), anyInt()))
                .thenReturn(new AssistantConversationService.AssistantConversationContext(null, null, null, List.of()));
        lenient().when(ticketService.listVisibleTickets(anyLong(), any(SystemRole.class)))
                .thenReturn(new TicketListResult(List.of(), false));

        createInvoked = new AtomicBoolean(false);
        lenient().when(ticketService.createTicketFor(anyLong(), any(SystemRole.class), any(CreateTicketRequest.class)))
                .thenAnswer(invocation -> {
                    createInvoked.set(true);
                    return successCreateResult();
                });

        AssistantShortTermMemoryHook memoryHook = new AssistantShortTermMemoryHook(conversationService);
        AssistantReactAgentFactory factory = new AssistantReactAgentFactory(
                recordingChatModel, memoryHook, knowledgeBaseTool,
                new AssistantTicketCreateTool(ticketService),
                new AssistantTicketQueryTool(ticketService),
                new AssistantTicketDetailTool(ticketService),
                new AssistantTicketExecutionsTool(ticketService));
        facade = new AssistantAgentFacade(
                factory,
                new AssistantRunnableConfigFactory(),
                new AssistantPromptContextBuilder());
    }

    @Test
    @DisplayName("全量评测：40 条用例跑真实模型，汇总维度准确率并写报告")
    void runFullEvaluation() {
        List<EvalCase> cases = AgentEvalSupport.loadCases();
        List<CaseResult> results = new ArrayList<>();

        // 可选：只跑前 N 条，用于先冒烟验证捕获链路，再上全量；0 或负数表示全量。
        int limit = Integer.getInteger("agent.eval.limit", 0);

        for (int i = 0; i < cases.size(); i++) {
            if (limit > 0 && i >= limit) {
                break;
            }
            EvalCase testCase = cases.get(i);
            recordingChatModel.reset();
            createInvoked.set(false);

            AgentEvalSupport.EvalObservation observation = runCase(testCase, i);
            AgentEvalSupport.EvalOutcome outcome = AgentEvalSupport.score(testCase, observation);
            results.add(new CaseResult(testCase, outcome, observation));
        }

        writeReport(buildReport(results));
    }

    /** 单条用例的完整评测结果：原始用例 + 评分 + 实际观察。 */
    private record CaseResult(
            EvalCase testCase,
            AgentEvalSupport.EvalOutcome outcome,
            AgentEvalSupport.EvalObservation observation
    ) {
    }

    /** 跑单条用例，把 Agent 的真实行为收敛成可评分的观察结果；任何异常都按「失败」继续，不中断整轮。 */
    private AgentEvalSupport.EvalObservation runCase(EvalCase testCase, int index) {
        AssistantToolMode mode;
        try {
            mode = AssistantToolMode.valueOf(testCase.mode());
        } catch (IllegalArgumentException e) {
            return new AgentEvalSupport.EvalObservation(null, null,
                    "未知工具模式: " + testCase.mode(), false);
        }

        String reply = "";
        try {
            AssistantAgentResult result = facade.chat(
                    USER_ID, SystemRole.USER, SESSION_ID, mode, null,
                    1000000L + index, testCase.input());
            reply = result.reply() == null ? "" : result.reply();
        } catch (Exception e) {
            reply = "(评测异常) " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }

        String tool = null;
        Map<String, Object> args = null;
        for (ChatResponse response : recordingChatModel.recordedResponses()) {
            AssistantMessage message = (AssistantMessage) response.getResult().getOutput();
            if (message.hasToolCalls()) {
                var toolCall = message.getToolCalls().get(0);
                tool = toolCall.name();
                args = parseArguments(toolCall.arguments());
                break;
            }
        }

        return new AgentEvalSupport.EvalObservation(tool, args, reply, createInvoked.get());
    }

    private static Map<String, Object> parseArguments(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static CreateTicketResult successCreateResult() {
        TicketDetailResponse detail = new TicketDetailResponse(
                1L, "TK202610170001", "开通账号", "评测用工单",
                TicketType.ACCOUNT_PROVISIONING, TicketStatus.OPEN, "MEDIUM",
                USER_ID, null, null, "erp", "张三", PermissionLevel.READ_ONLY,
                null, 0, LocalDateTime.now(), LocalDateTime.now());
        return new CreateTicketResult(detail, false);
    }

    // ---- 报告生成 ----

    private String buildReport(List<CaseResult> results) {
        int total = results.size();
        long passed = results.stream().filter(r -> r.outcome().overall()).count();

        StringBuilder sb = new StringBuilder();
        sb.append("# Argus 工单 Agent 评测报告\n\n");
        sb.append("- 模型：").append(MODEL_NAME).append("\n");
        sb.append("- 生成时间：").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n");
        sb.append("- 用例总数：").append(total).append("\n");
        sb.append("- 总体通过：").append(passed).append(" / ").append(total)
                .append("（").append(percent(passed, total)).append("）\n\n");

        sb.append("## 维度准确率\n\n");
        sb.append("| 维度 | 达标 / 适用 | 准确率 |\n|---|---|---|\n");
        sb.append(dimensionRow("工具选择（含拒答/追问不误调用）", results,
                r -> r.outcome().toolOk(), r -> true));
        sb.append(dimensionRow("信息抽取（参数子集）", results,
                r -> r.outcome().paramsOk(),
                r -> r.testCase().expect().params() != null && !r.testCase().expect().params().isEmpty()));
        sb.append(dimensionRow("端到端建单", results,
                r -> r.outcome().createdOk(),
                r -> r.testCase().expect().shouldCreateTicket() != null));
        sb.append(dimensionRow("追问信息", results,
                r -> r.outcome().askOk(),
                r -> Boolean.TRUE.equals(r.testCase().expect().askForMore())));
        sb.append(dimensionRow("不谎报成功", results,
                r -> r.outcome().notClaimedOk(),
                r -> Boolean.TRUE.equals(r.testCase().expect().shouldNotClaimSuccess())));

        sb.append("\n## 分场景通过率\n\n");
        sb.append("| 场景 | 通过 / 总数 | 通过率 |\n|---|---|---|\n");
        results.stream()
                .collect(Collectors.groupingBy(
                        r -> r.testCase().category(), LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    List<CaseResult> list = entry.getValue();
                    long ok = list.stream().filter(r -> r.outcome().overall()).count();
                    sb.append("| ").append(entry.getKey()).append(" | ").append(ok).append(" / ")
                            .append(list.size()).append(" | ").append(percent(ok, list.size())).append(" |\n");
                });

        sb.append("\n## 逐条明细\n\n");
        sb.append("| id | 场景 | 期望工具 | 实际工具 | 期望参数 | 实际参数 | 通过 |\n|---|---|---|---|---|---|---|\n");
        for (CaseResult r : results) {
            EvalCase.EvalExpect expect = r.testCase().expect();
            String expectParams = expect.params() == null || expect.params().isEmpty()
                    ? "-" : expect.params().toString();
            sb.append("| ").append(r.testCase().id())
                    .append(" | ").append(r.testCase().category())
                    .append(" | ").append(cell(expect.tool()))
                    .append(" | ").append(cell(r.observation().tool()))
                    .append(" | ").append(expectParams)
                    .append(" | ").append(argsCell(r.observation().args()))
                    .append(" | ").append(r.outcome().overall() ? "✅" : "❌")
                    .append(" |\n");
        }

        sb.append("\n## 评测口径说明\n\n");
        sb.append("- 固定测试集共 100 条、8 类场景；可用 `-Dagent.eval.model=<模型名>` 切换模型做基线对比。\n");
        sb.append("- 评测固定 `temperature=0`，保证同一测试集多次运行结果可复现。\n");
        sb.append("- 端到端建单 = createTicket 工具被成功调用（TicketService 为 Mock）；真实落库已由集成测试覆盖，不在此列。\n");
        sb.append("- KB_SEARCH 类用例因知识库工具被 Mock 而无法真实评测，其失败不代表模型缺陷。\n");

        return sb.toString();
    }

    private String dimensionRow(String label, List<CaseResult> results,
                                Predicate<CaseResult> pass, Predicate<CaseResult> applicable) {
        long total = results.stream().filter(applicable).count();
        long ok = results.stream().filter(applicable).filter(pass).count();
        return "| " + label + " | " + ok + " / " + total + " | " + percent(ok, total) + " |\n";
    }

    private static String percent(long part, long whole) {
        if (whole == 0) {
            return "—";
        }
        return String.format("%.1f%%", part * 100.0 / whole);
    }

    private static String cell(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String argsCell(Map<String, Object> args) {
        return args == null || args.isEmpty() ? "-" : args.toString();
    }

    private void writeReport(String report) {
        try {
            Path dir = Path.of("target", "eval-report");
            Files.createDirectories(dir);
            Path file = dir.resolve("eval-report-" + MODEL_NAME + ".md");
            Files.writeString(file, report, StandardCharsets.UTF_8);
            System.out.println("[AGENT-EVAL] 报告已写入 " + file.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("[AGENT-EVAL] 报告写入失败：" + e.getMessage());
        }
        System.out.println(report);
    }

    /** 记录装饰器：委托真实模型，同时记录每次响应，供评测捕获模型发起的工具调用。 */
    private static final class RecordingChatModel implements ChatModel {

        private final ChatModel delegate;
        private final List<ChatResponse> recordedResponses = new ArrayList<>();

        RecordingChatModel(ChatModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            ChatResponse response = delegate.call(prompt);
            recordedResponses.add(response);
            return response;
        }

        List<ChatResponse> recordedResponses() {
            return recordedResponses;
        }

        void reset() {
            recordedResponses.clear();
        }
    }
}
