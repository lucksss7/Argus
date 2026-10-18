package com.argus.rag.assistant;

import com.argus.rag.assistant.agent.AssistantAgentFacade;
import com.argus.rag.assistant.agent.AssistantKnowledgeBaseTool;
import com.argus.rag.assistant.agent.AssistantReactAgentFactory;
import com.argus.rag.assistant.agent.AssistantTicketCreateTool;
import com.argus.rag.assistant.agent.AssistantTicketDetailTool;
import com.argus.rag.assistant.agent.AssistantTicketExecutionsTool;
import com.argus.rag.assistant.agent.AssistantTicketQueryTool;
import com.argus.rag.assistant.controller.AssistantChatController;
import com.argus.rag.assistant.memory.AssistantShortTermMemoryHook;
import com.argus.rag.assistant.model.enums.AssistantMessageRole;
import com.argus.rag.assistant.model.enums.AssistantToolMode;
import com.argus.rag.assistant.model.vo.message.AssistantMessageVO;
import com.argus.rag.assistant.service.AssistantConversationService;
import com.argus.rag.assistant.service.AssistantService;
import com.argus.rag.assistant.support.config.AssistantPromptContextBuilder;
import com.argus.rag.assistant.support.config.AssistantRunnableConfigFactory;
import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.auth.config.AuthConfiguration;
import com.argus.rag.auth.security.JwtAccessTokenService;
import com.argus.rag.auth.security.JwtAuthenticationFilter;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.enums.UserStatus;
import com.argus.rag.common.exception.GlobalExceptionHandler;
import com.argus.rag.group.service.GroupMembershipService;
import com.argus.rag.metrics.collector.LlmUsageCollector;
import com.argus.rag.metrics.cost.LlmCostCalculator;
import com.argus.rag.ticket.mapper.TicketMapper;
import com.argus.rag.ticket.model.dto.CreateTicketRequest;
import com.argus.rag.ticket.model.entity.Ticket;
import com.argus.rag.ticket.model.enums.PermissionLevel;
import com.argus.rag.ticket.model.enums.TicketStatus;
import com.argus.rag.ticket.model.enums.TicketType;
import com.argus.rag.ticket.service.TicketService;
import com.argus.rag.user.service.UserQueryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Assistant 工单只读工具 HTTP 集成测试。
 *
 * <p>真实 Servlet 容器（RANDOM_PORT）+ 真实 PostgreSQL 测试库 {@code argus_rag_test}，
 * 端到端验证「JWT 过滤器 → 身份解析 → AssistantService → Agent 门面 → ReactAgent →
 * 工单只读工具 → 真实 TicketService → 真实 DB」完整链路；仅模型用
 * {@link RecordingChatModel} 测试替身（不调用真实大模型 / 外部服务）。</p>
 *
 * <h3>运行方式</h3>
 * <p>仅当设置<b>环境变量</b> {@code ARGUS_INTEGRATION=true} 时激活（不是 {@code -D} 系统属性），
 * 连接独立测试库 {@code argus_rag_test}（见 application-test.yml），不触碰开发库 {@code argus_rag}。</p>
 */
@SpringBootTest(
        classes = AssistantTicketToolHttpIntegrationTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "argus.rag.auth.jwt-secret=http-integration-test-jwt-secret-key-0123456789abcdef"
)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "ARGUS_INTEGRATION", matches = "true")
@DisplayName("Assistant 工单只读工具 HTTP 集成测试")
class AssistantTicketToolHttpIntegrationTest {

    /** 普通用户 A 用户 ID（对应 JWT 与 mock UserQueryService） */
    private static final Long USER_A_ID = 100L;
    /** 普通用户 B 用户 ID（TC-2 中工单归属人，其工单对 USER_A 不可见） */
    private static final Long USER_B_ID = 200L;
    /** 测试会话 ID */
    private static final Long SESSION_ID = 90001L;
    /** TC-1 模型替身返回的最终答复文本（不含任何内部 ID，供泄漏断言使用） */
    private static final String FINAL_REPLY = "工单详情已查询到，请核对。";
    /** TC-2 模型替身返回的最终答复文本 */
    private static final String FORBIDDEN_REPLY = "无法查询该工单详情。";

    /** 工单服务以 {@code @MockitoSpyBean} 包装真实实例，用于 verify 显式身份方法调用。 */
    @MockitoSpyBean
    private TicketService ticketService;

    /** 助手会话持久化边界（assistant 表无 Flyway 迁移，且由其唯一访问点替换，避免依赖 assistant 表）。 */
    @MockitoBean
    private AssistantConversationService assistantConversationService;

    /** 知识库检索工具（本测试只关注工单只读工具，知识库工具整体替换）。 */
    @MockitoBean
    private AssistantKnowledgeBaseTool assistantKnowledgeBaseTool;

    /** 知识库组成员校验（仅在 KB_SEARCH 分支使用，工单链路不涉及，替换）。 */
    @MockitoBean
    private GroupMembershipService groupMembershipService;

    /** LLM 用量采集接口（替换以隔离指标落库）。 */
    @MockitoBean
    private LlmUsageCollector llmUsageCollector;

    /** LLM 成本计算接口（替换以隔离指标计算）。 */
    @MockitoBean
    private LlmCostCalculator llmCostCalculator;

    /** 按 id 查用户这一 DB 边界（users 表无 Flyway 迁移，仅替换该查询，CurrentUserService 逻辑保持真实）。 */
    @MockitoBean
    private UserQueryService userQueryService;

    /** 模型测试替身，供 TC 用例 enqueue 工具调用 / 最终答复，并记录模型交互。 */
    @Autowired
    private RecordingChatModel recordingChatModel;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private TicketMapper ticketMapper;

    @Autowired
    private JwtAccessTokenService jwtAccessTokenService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        // 数据库安全守卫：任何写库前先校验实际 DataSource JDBC URL 指向 argus_rag_test:5433。
        requireTestDatabase();
        // 重置共享的模型测试替身状态，避免跨测试用例的 prompt / 响应队列污染（tc1/tc2 计数顺序相关）。
        recordingChatModel.reset();
    }

    /** 从实际 DataSource 连接读取 JDBC URL 并严格校验，避免把开发库 argus_rag 误当测试库写入。 */
    private void requireTestDatabase() {
        String url;
        try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
            url = connection.getMetaData().getURL();
        } catch (SQLException e) {
            throw new IllegalStateException("集成测试安全守卫：无法连接数据库以校验 JDBC URL，已拒绝执行数据库写入", e);
        }
        parseAndValidateJdbcUrl(url);
    }

    /** 严格解析 PostgreSQL JDBC URL 并校验数据库名 / 端口 / 主机，不做易误匹配的子串判断。 */
    private static void parseAndValidateJdbcUrl(String url) {
        if (url == null || !url.startsWith("jdbc:postgresql://")) {
            throw new IllegalStateException("集成测试安全守卫：JDBC URL 不是 PostgreSQL 连接串，已拒绝执行数据库写入: " + url);
        }
        URI uri;
        try {
            uri = URI.create(url.substring("jdbc:".length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("集成测试安全守卫：JDBC URL 解析失败，已拒绝执行数据库写入: " + url, e);
        }
        String host = uri.getHost();
        int port = uri.getPort();
        String path = uri.getPath();
        String database = (path == null || path.length() <= 1) ? "" : path.substring(1);
        if (!"argus_rag_test".equals(database)) {
            throw new IllegalStateException(
                    "集成测试安全守卫：数据库名不是 argus_rag_test，已拒绝执行数据库写入（实际：" + database + "）");
        }
        if (port != 5433) {
            throw new IllegalStateException(
                    "集成测试安全守卫：端口不是 5433，已拒绝执行数据库写入（实际：" + port + "）");
        }
        if (!"localhost".equals(host) && !"127.0.0.1".equals(host)) {
            throw new IllegalStateException(
                    "集成测试安全守卫：主机不是本机，已拒绝执行数据库写入（实际：" + host + "）");
        }
    }

    @Test
    @DisplayName("最小上下文成功启动：真实 Agent 门面 + 三只读工具 + 工单服务，模型/持久化/权限边界被 mock")
    void contextLoads() {
        // 上下文启动本身即证明 @MockitoSpyBean 成功包装了真实 TicketService：
        // 真实 TicketService 构造需要 TicketMapper / TicketExecutionMapper / CurrentUserService，
        // 若无法实例化真实实例，整个上下文加载会失败。
        assertThat(ticketService).isNotNull();
        assertThat(Mockito.mockingDetails(ticketService).isSpy()).isTrue();

        // 模型替身与数据源配置就位，供后续 TC 用例使用。
        assertThat(recordingChatModel).isNotNull();
    }

    @Test
    @DisplayName("TC-1：普通用户通过 Agent 只读工具 getTicketDetail 查询自己的工单详情")
    void tc1_userQueriesOwnTicketDetail() {
        // 1) 服务端身份边界：仅替换「按 id 查用户」这一 DB 边界，角色由 DB 决定（普通用户，非管理员）。
        when(userQueryService.findById(USER_A_ID))
                .thenReturn(new UserQueryService.UserRecord(
                        USER_A_ID, "u100", "用户A", SystemRole.USER, UserStatus.ACTIVE, false));
        // 2) 助手持久化边界：assistant 表无迁移，mock 掉上下文加载与消息保存；最终答复透传为响应 reply。
        when(assistantConversationService.loadConversationContext(anyLong(), anyLong(), anyInt()))
                .thenReturn(new AssistantConversationService.AssistantConversationContext(null, null, null, List.of()));
        when(assistantConversationService.saveUserMessage(anyLong(), any()))
                .thenReturn(new AssistantMessageVO(90001L, SESSION_ID, AssistantMessageRole.USER,
                        AssistantToolMode.TICKET_QUERY, null, "帮我查一下我的工单详情", null, LocalDateTime.now()));
        when(assistantConversationService.saveAssistantMessage(anyLong(), any()))
                .thenReturn(new AssistantMessageVO(90001L, SESSION_ID, AssistantMessageRole.ASSISTANT,
                        AssistantToolMode.TICKET_QUERY, null, FINAL_REPLY, null, LocalDateTime.now()));

        // 3) 仅创建一条属于 USER_A 的可识别测试工单（唯一 ticketNo / idempotencyKey）。
        Ticket ticket = insertTicket(USER_A_ID, UUID.randomUUID().toString(),
                "账号开通申请", "TC-1 集成测试工单", "tc1-user-a");
        Long ticketId = ticket.getId();
        try {
            // 4) 模型替身：第一次调用返回 getTicketDetail 工具调用，第二次返回最终答复。
            recordingChatModel.enqueue(
                    toolCallMessage("call-1", "getTicketDetail", "{\"ticketId\":" + ticketId + "}"),
                    new AssistantMessage(FINAL_REPLY));

            // 5) 真实 HTTP 请求 + 真实 JWT + 真实 Agent 工具链 + 真实 TicketService + 真实测试库。
            HttpResult result = post("/api/assistant/chat", issueUserAToken(), requestBody("帮我查一下我的工单详情"));

            // 6) 验证 HTTP 请求成功，返回 USER_A 自己的工单的最终答复。
            assertThat(result.status()).isEqualTo(200);
            assertThat(result.body().get("success").asBoolean()).isTrue();
            assertThat(result.body().get("data").get("reply").asText()).isEqualTo(FINAL_REPLY);
            assertThat(result.body().get("data").get("toolMode").asText()).isEqualTo("TICKET_QUERY");

            // 7) 真实 TicketService.getTicketDetailFor 被调用，参数与 JWT 对应的服务端身份一致（USER_A / USER）。
            verify(ticketService).getTicketDetailFor(ticketId, USER_A_ID, SystemRole.USER);

            // 8) Agent 实际收到工具结果并生成最终响应：模型调用两次（工具调用 + 最终答复），
            //    第二次调用上下文包含工单脱敏字段。
            assertThat(recordingChatModel.recordedPrompts()).hasSize(2);
            String secondPrompt = joinedText(recordingChatModel.recordedPrompts().get(1));
            assertThat(secondPrompt).contains("\"ticketNo\":\"" + ticket.getTicketNo() + "\"");
            assertThat(secondPrompt).contains("\"title\":\"账号开通申请\"");

            // 9) 不泄露内部用户 ID / 审批人 ID / 乐观锁版本 / 处理结果说明（工具结果与最终答复均不含）。
            assertThat(secondPrompt)
                    .doesNotContain("creatorUserId", "assigneeUserId", "approverUserId", "version", "resolution");
            assertThat(result.body().get("data").get("reply").asText())
                    .doesNotContain("creatorUserId", "assigneeUserId", "approverUserId", "version", "resolution");
        } finally {
            // 10) 只清理本测试创建且可明确识别的记录：按主键精确删除，不做 TRUNCATE / 全表 DELETE。
            if (ticketId != null) {
                ticketMapper.deleteById(ticketId);
            }
        }
    }

    @Test
    @DisplayName("TC-2：普通用户不能通过 Agent 只读工具 getTicketDetail 查询其他用户的工单")
    void tc2_userCannotQueryAnotherUsersTicket() {
        // 1) 身份边界：USER_A 的 JWT 对应 DB 角色 USER（普通用户，非管理员）。
        when(userQueryService.findById(USER_A_ID))
                .thenReturn(new UserQueryService.UserRecord(
                        USER_A_ID, "u100", "用户A", SystemRole.USER, UserStatus.ACTIVE, false));
        when(assistantConversationService.loadConversationContext(anyLong(), anyLong(), anyInt()))
                .thenReturn(new AssistantConversationService.AssistantConversationContext(null, null, null, List.of()));
        when(assistantConversationService.saveUserMessage(anyLong(), any()))
                .thenReturn(new AssistantMessageVO(90002L, SESSION_ID, AssistantMessageRole.USER,
                        AssistantToolMode.TICKET_QUERY, null, "帮我查一下这个工单的详情", null, LocalDateTime.now()));
        when(assistantConversationService.saveAssistantMessage(anyLong(), any()))
                .thenReturn(new AssistantMessageVO(90002L, SESSION_ID, AssistantMessageRole.ASSISTANT,
                        AssistantToolMode.TICKET_QUERY, null, FORBIDDEN_REPLY, null, LocalDateTime.now()));

        // 2) 创建一条属于 USER_B 的可识别测试工单（唯一 ticketNo / idempotencyKey，标题/描述/目标用户名可区分）。
        Ticket ticketB = insertTicket(USER_B_ID, UUID.randomUUID().toString(),
                "账号开通申请（他人）", "TC-2 越权测试工单", "tc2-user-b");
        Long ticketBId = ticketB.getId();
        try {
            // 3) USER_A 的模型发起 getTicketDetail 查询 USER_B 的工单。
            recordingChatModel.enqueue(
                    toolCallMessage("call-1", "getTicketDetail", "{\"ticketId\":" + ticketBId + "}"),
                    new AssistantMessage(FORBIDDEN_REPLY));

            // 4) 真实 HTTP 请求 + USER_A 真实 JWT + 真实 Agent 工具链 + 真实 TicketService + 真实测试库。
            HttpResult result = post("/api/assistant/chat", issueUserAToken(), requestBody("帮我查一下这个工单的详情"));

            // 5) HTTP 与 Agent 调用链按预期完成，返回最终答复。
            assertThat(result.status()).isEqualTo(200);
            assertThat(result.body().get("success").asBoolean()).isTrue();
            assertThat(result.body().get("data").get("reply").asText()).isEqualTo(FORBIDDEN_REPLY);

            // 6) 真实 TicketService.getTicketDetailFor 被调用，身份是 USER_A（不是 USER_B）。
            verify(ticketService).getTicketDetailFor(ticketBId, USER_A_ID, SystemRole.USER);
            verify(ticketService, never()).getTicketDetailFor(ticketBId, USER_B_ID, SystemRole.USER);

            // 7) 工具拒绝越权查询：第二次模型调用收到结构化失败 QUERY_FAILED，
            //    且不泄露 USER_B 工单的标题/描述/目标用户名等受保护业务数据（并非只看最终答复）。
            assertThat(recordingChatModel.recordedPrompts()).hasSize(2);
            String secondPrompt = joinedText(recordingChatModel.recordedPrompts().get(1));
            assertThat(secondPrompt).contains("\"reasonCode\":\"QUERY_FAILED\"");
            assertThat(secondPrompt).doesNotContain("无权查看");
            assertThat(secondPrompt)
                    .doesNotContain(ticketB.getTitle(), ticketB.getDescription(), ticketB.getTargetUsername());
        } finally {
            // 8) 只清理本测试创建的记录：按主键精确删除，不做 TRUNCATE / 全表 DELETE。
            if (ticketBId != null) {
                ticketMapper.deleteById(ticketBId);
            }
        }
    }

    // ─────────────────────────── TICKET_ASSIST 建单幂等（A 矩阵） ───────────────────────────

    @Test
    @DisplayName("A1：同一消息级幂等键 + 相同参数重复建单 → 仅一条工单，第二次复用原工单")
    void a1_sameMessageIdSameParamsCreatesOneTicket() {
        String targetUsername = "a1-user-" + UUID.randomUUID();
        long messageId = 91001L;

        when(userQueryService.findById(USER_A_ID))
                .thenReturn(new UserQueryService.UserRecord(
                        USER_A_ID, "u100", "用户A", SystemRole.USER, UserStatus.ACTIVE, false));
        when(assistantConversationService.loadConversationContext(anyLong(), anyLong(), anyInt()))
                .thenReturn(new AssistantConversationService.AssistantConversationContext(null, null, null, List.of()));
        // 两条 HTTP 请求复用同一个消息级幂等键（messageId 固定），验证同一消息被重复执行时只落一条工单。
        when(assistantConversationService.saveUserMessage(anyLong(), any()))
                .thenReturn(new AssistantMessageVO(messageId, SESSION_ID, AssistantMessageRole.USER,
                        AssistantToolMode.TICKET_ASSIST, null, "帮我建一张工单", null, LocalDateTime.now()));
        when(assistantConversationService.saveAssistantMessage(anyLong(), any()))
                .thenReturn(new AssistantMessageVO(91002L, SESSION_ID, AssistantMessageRole.ASSISTANT,
                        AssistantToolMode.TICKET_ASSIST, null, "已为你创建工单", null, LocalDateTime.now()));

        String arguments = createTicketArguments(targetUsername);
        List<Long> createdTicketIds = new ArrayList<>();
        try {
            recordingChatModel.enqueue(
                    toolCallMessage("call-1", "createTicket", arguments), new AssistantMessage("已为你创建工单"));
            HttpResult first = post("/api/assistant/chat", issueUserAToken(), requestBody("帮我建一张工单", "TICKET_ASSIST"));
            assertThat(first.status()).isEqualTo(200);
            assertThat(first.body().get("success").asBoolean()).isTrue();

            recordingChatModel.enqueue(
                    toolCallMessage("call-2", "createTicket", arguments), new AssistantMessage("已为你创建工单"));
            HttpResult second = post("/api/assistant/chat", issueUserAToken(), requestBody("帮我建一张工单", "TICKET_ASSIST"));
            assertThat(second.status()).isEqualTo(200);
            assertThat(second.body().get("success").asBoolean()).isTrue();

            // 先识别本测试创建的工单主键（尽早收集，确保 finally 始终能按主键精确清理）。
            List<Ticket> created = ticketMapper.selectList(
                    new LambdaQueryWrapper<Ticket>().eq(Ticket::getTargetUsername, targetUsername));
            createdTicketIds.addAll(created.stream().map(Ticket::getId).toList());
            // 两次工具调用都真实进入 createTicketFor（未被框架短路），但只落一条工单记录。
            verify(ticketService, times(2)).createTicketFor(eq(USER_A_ID), eq(SystemRole.USER), any(CreateTicketRequest.class));
            assertThat(createdTicketIds).hasSize(1);
        } finally {
            if (!createdTicketIds.isEmpty()) {
                for (Long id : createdTicketIds) {
                    ticketMapper.deleteById(id);
                }
            }
        }
    }

    @Test
    @DisplayName("A2：不同消息级幂等键 + 相同参数 → 创建两张独立工单")
    void a2_differentMessageIdSameParamsCreatesTwoTickets() {
        String targetUsername = "a2-user-" + UUID.randomUUID();

        when(userQueryService.findById(USER_A_ID))
                .thenReturn(new UserQueryService.UserRecord(
                        USER_A_ID, "u100", "用户A", SystemRole.USER, UserStatus.ACTIVE, false));
        when(assistantConversationService.loadConversationContext(anyLong(), anyLong(), anyInt()))
                .thenReturn(new AssistantConversationService.AssistantConversationContext(null, null, null, List.of()));
        // 两条不同消息（messageId 不同）承载完全相同的内容：必须各自建单，不得误判复用。
        when(assistantConversationService.saveUserMessage(anyLong(), any()))
                .thenReturn(
                        new AssistantMessageVO(92001L, SESSION_ID, AssistantMessageRole.USER,
                                AssistantToolMode.TICKET_ASSIST, null, "帮我建一张工单", null, LocalDateTime.now()),
                        new AssistantMessageVO(92002L, SESSION_ID, AssistantMessageRole.USER,
                                AssistantToolMode.TICKET_ASSIST, null, "帮我建一张工单", null, LocalDateTime.now()));
        when(assistantConversationService.saveAssistantMessage(anyLong(), any()))
                .thenReturn(new AssistantMessageVO(92003L, SESSION_ID, AssistantMessageRole.ASSISTANT,
                        AssistantToolMode.TICKET_ASSIST, null, "已为你创建工单", null, LocalDateTime.now()));

        String arguments = createTicketArguments(targetUsername);
        List<Long> createdTicketIds = new ArrayList<>();
        try {
            recordingChatModel.enqueue(
                    toolCallMessage("call-1", "createTicket", arguments), new AssistantMessage("已为你创建工单"));
            HttpResult first = post("/api/assistant/chat", issueUserAToken(), requestBody("帮我建一张工单", "TICKET_ASSIST"));
            assertThat(first.status()).isEqualTo(200);

            recordingChatModel.enqueue(
                    toolCallMessage("call-2", "createTicket", arguments), new AssistantMessage("已为你创建工单"));
            HttpResult second = post("/api/assistant/chat", issueUserAToken(), requestBody("帮我建一张工单", "TICKET_ASSIST"));
            assertThat(second.status()).isEqualTo(200);

            // 先识别本测试创建的工单主键（尽早收集，确保 finally 始终能按主键精确清理）。
            List<Ticket> created = ticketMapper.selectList(
                    new LambdaQueryWrapper<Ticket>().eq(Ticket::getTargetUsername, targetUsername));
            createdTicketIds.addAll(created.stream().map(Ticket::getId).toList());
            verify(ticketService, times(2)).createTicketFor(eq(USER_A_ID), eq(SystemRole.USER), any(CreateTicketRequest.class));
            assertThat(createdTicketIds).hasSize(2);
        } finally {
            if (!createdTicketIds.isEmpty()) {
                for (Long id : createdTicketIds) {
                    ticketMapper.deleteById(id);
                }
            }
        }
    }

    /** 构造 createTicket 工具调用参数（业务字段固定，仅 targetUsername 随测试变化，保证可识别）。 */
    private static String createTicketArguments(String targetUsername) {
        return "{\"type\":\"ACCOUNT_PROVISIONING\",\"systemName\":\"erp\",\"targetUsername\":\""
                + targetUsername + "\",\"permissionLevel\":\"READ_ONLY\",\"description\":\"Agent 建单集成测试\","
                + "\"title\":\"账号开通申请\",\"priority\":\"LOW\"}";
    }

    // ─────────────────────────── 测试配置 ───────────────────────────

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableTransactionManagement
    @MapperScan("com.argus.rag.ticket.mapper")
    @Import({
            AssistantChatController.class,
            AssistantService.class,
            AssistantAgentFacade.class,
            AssistantReactAgentFactory.class,
            AssistantTicketCreateTool.class,
            AssistantTicketQueryTool.class,
            AssistantTicketDetailTool.class,
            AssistantTicketExecutionsTool.class,
            AssistantShortTermMemoryHook.class,
            AssistantPromptContextBuilder.class,
            AssistantRunnableConfigFactory.class,
            CurrentUserService.class,
            JwtAuthenticationFilter.class,
            JwtAccessTokenService.class,
            AuthConfiguration.class,
            GlobalExceptionHandler.class,
            TicketService.class
    })
    static class Config {

        @Bean
        RecordingChatModel recordingChatModel() {
            return new RecordingChatModel();
        }
    }

    // ─────────────────────────── HTTP 与数据辅助 ───────────────────────────

    /** HTTP 请求结果：状态码 + 已解析的 JSON 响应体（可能为 null）。 */
    private record HttpResult(int status, JsonNode body) {
    }

    private HttpResult post(String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        HttpEntity<?> entity;
        if (body == null) {
            entity = new HttpEntity<>(headers);
        } else {
            headers.setContentType(MediaType.APPLICATION_JSON);
            entity = new HttpEntity<>(body, headers);
        }
        return toResult(rest.exchange(path, HttpMethod.POST, entity, byte[].class));
    }

    private HttpResult toResult(ResponseEntity<byte[]> response) {
        int status = response.getStatusCodeValue();
        byte[] bytes = response.getBody();
        JsonNode body = null;
        if (bytes != null && bytes.length > 0) {
            try {
                body = objectMapper.readTree(new String(bytes, StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new IllegalStateException("响应体不是合法 JSON", e);
            }
        }
        return new HttpResult(status, body);
    }

    private String issueUserAToken() {
        return jwtAccessTokenService.issueToken(
                new JwtAccessTokenService.TokenSubject(USER_A_ID, "u100", "用户A", SystemRole.USER, false));
    }

    private Map<String, Object> requestBody(String message) {
        return requestBody(message, "TICKET_QUERY");
    }

    private Map<String, Object> requestBody(String message, String toolMode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", SESSION_ID);
        body.put("message", message);
        body.put("toolMode", toolMode);
        body.put("groupId", null);
        return body;
    }

    /** 直接经 TicketMapper 插入一条可识别的测试工单（绕过工单服务，写入唯一 ticketNo/idempotencyKey）。 */
    private Ticket insertTicket(Long creatorUserId, String uniqueSuffix,
                                String title, String description, String targetUsername) {
        Ticket ticket = new Ticket();
        ticket.setTicketNo("TK-TC-" + uniqueSuffix);
        ticket.setTitle(title);
        ticket.setDescription(description);
        ticket.setType(TicketType.ACCOUNT_PROVISIONING);
        ticket.setStatus(TicketStatus.OPEN);
        ticket.setPriority("LOW");
        ticket.setCreatorUserId(creatorUserId);
        ticket.setSystemName("erp");
        ticket.setTargetUsername(targetUsername);
        ticket.setPermissionLevel(PermissionLevel.READ_ONLY);
        ticket.setVersion(0);
        ticket.setIdempotencyKey("TC-" + uniqueSuffix);
        LocalDateTime now = LocalDateTime.now();
        ticket.setCreatedAt(now);
        ticket.setUpdatedAt(now);
        ticketMapper.insert(ticket);
        return ticket;
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

    /**
     * {@link ChatModel} 测试替身：按队列返回预置的助手消息（工具调用 / 最终答复），
     * 并记录每次调用的 Prompt，用于证明「模型发起工具调用 → 工具真实执行 → 工具结果进入后续模型调用」。
     * 不调用任何真实大模型 / 外部服务。
     * <p>方法加 {@code synchronized}：HTTP 请求在 Servlet 线程中触发 {@link #call}，
     * 而测试线程在请求前 {@link #enqueue}、请求后读取 {@link #recordedPrompts}，需保证跨线程可见性。</p>
     */
    static final class RecordingChatModel implements ChatModel {

        private final List<AssistantMessage> responses = new ArrayList<>();
        private final List<List<Message>> recordedPrompts = new ArrayList<>();

        synchronized void enqueue(AssistantMessage... messages) {
            responses.addAll(List.of(messages));
        }

        /** 清空响应队列与已记录 prompt，供 @BeforeEach 在每个测试用例开始前隔离状态。 */
        synchronized void reset() {
            responses.clear();
            recordedPrompts.clear();
        }

        @Override
        public synchronized ChatResponse call(Prompt prompt) {
            recordedPrompts.add(prompt.getInstructions());
            AssistantMessage next = responses.isEmpty() ? new AssistantMessage("") : responses.remove(0);
            return new ChatResponse(List.of(new Generation(next)));
        }

        synchronized List<List<Message>> recordedPrompts() {
            return new ArrayList<>(recordedPrompts);
        }
    }
}
