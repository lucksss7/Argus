package com.argus.rag.ticket;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.auth.config.AuthConfiguration;
import com.argus.rag.auth.security.JwtAccessTokenService;
import com.argus.rag.auth.security.JwtAuthenticationFilter;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.enums.UserStatus;
import com.argus.rag.common.exception.GlobalExceptionHandler;
import com.argus.rag.ticket.config.TicketConfiguration;
import com.argus.rag.ticket.controller.TicketController;
import com.argus.rag.ticket.executor.MockAccountProvisioningExecutor;
import com.argus.rag.ticket.mapper.ApprovalRequestMapper;
import com.argus.rag.ticket.mapper.TicketExecutionMapper;
import com.argus.rag.ticket.mapper.TicketMapper;
import com.argus.rag.ticket.model.dto.CreateTicketRequest;
import com.argus.rag.ticket.model.entity.ApprovalRequest;
import com.argus.rag.ticket.model.entity.Ticket;
import com.argus.rag.ticket.model.entity.TicketExecution;
import com.argus.rag.ticket.model.enums.ApprovalResult;
import com.argus.rag.ticket.model.enums.ExecutionResult;
import com.argus.rag.ticket.model.enums.ExecutionType;
import com.argus.rag.ticket.model.enums.PermissionLevel;
import com.argus.rag.ticket.model.enums.TicketStatus;
import com.argus.rag.ticket.model.enums.TicketType;
import com.argus.rag.ticket.model.vo.CreateTicketResult;
import com.argus.rag.ticket.service.TicketApprovalService;
import com.argus.rag.ticket.service.TicketDispositionService;
import com.argus.rag.ticket.service.TicketExecutionService;
import com.argus.rag.ticket.service.TicketExecutionTransactionService;
import com.argus.rag.ticket.service.TicketService;
import com.argus.rag.user.service.UserQueryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 工单 HTTP 集成测试：真实 Servlet 容器（RANDOM_PORT）+ 真实 PostgreSQL 测试库，
 * 端到端验证「JWT 过滤器 -> 身份解析 -> 服务端鉴权 -> 状态机 -> 落库 -> 统一响应」完整链路。
 *
 * <h3>为什么用 RANDOM_PORT 而非 @WebMvcTest / MockMvc</h3>
 * <p>项目没有 Spring Security，认证靠 {@link JwtAuthenticationFilter}（一个 {@code @Component} 的
 * {@code OncePerRequestFilter}）。该过滤器只有在真实 Servlet 容器里才会被
 * {@code ServletContextInitializerBeans} 自动注册进请求过滤链；{@code @WebMvcTest} 的 MockMvc
 * 不会注册普通 {@code Filter} bean。因此只有 RANDOM_PORT + {@link TestRestTemplate} 才能
 * 真实验证「缺 token / 非法 token / 合法 token」三种请求确实经过了过滤器。</p>
 *
 * <h3>过滤器是否被触发的直接证据</h3>
 * <ul>
 *   <li>非法 token 返回 401 且 message 以 {@code access token} 开头——该 401 由过滤器
 *       {@code writeUnauthorized} 直接写出，不是 {@link GlobalExceptionHandler} 产生；</li>
 *   <li>合法 token 能返回 200——过滤器解析 JWT 并写入 {@code UserContext}，否则下游
 *       {@link CurrentUserService} 会抛「当前请求未登录」；</li>
 *   <li>缺 token 返回 401「当前请求未登录」——过滤器透传后由下游鉴权拒绝。</li>
 * </ul>
 *
 * <h3>测试库与用户表</h3>
 * <p>Flyway 只有 V1（三张工单表），没有 {@code users} 表迁移。因此本类用
 * {@code @MockitoBean UserQueryService} 只替换「按 id 查用户」这一 DB 边界，
 * {@link CurrentUserService} 及其「从 DB 重新读取角色、不信任 JWT claim」的鉴权逻辑保持真实。</p>
 *
 * <h3>运行前置</h3>
 * <p>仅当设置环境变量 {@code ARGUS_INTEGRATION=true} 时激活，连接独立测试库
 * {@code argus_rag_test}（见 application-test.yml），不动开发库 {@code argus_rag}。</p>
 */
@SpringBootTest(
        classes = TicketHttpIntegrationTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "argus.rag.auth.jwt-secret=http-integration-test-jwt-secret-key-0123456789abcdef"
)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "ARGUS_INTEGRATION", matches = "true")
@DisplayName("工单 HTTP 集成测试（真实过滤器链 + PostgreSQL）")
class TicketHttpIntegrationTest {

    /** 管理员用户 ID（对应 JWT 与 mock UserQueryService） */
    private static final Long ADMIN_ID = 1L;
    /** 普通用户 A 用户 ID */
    private static final Long USER_A_ID = 100L;
    /** 普通用户 B 用户 ID（用于「只能看自己」与「不能提交他人」的隔离验证） */
    private static final Long USER_B_ID = 200L;

    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private TicketMapper ticketMapper;

    @Autowired
    private ApprovalRequestMapper approvalRequestMapper;

    @Autowired
    private TicketExecutionMapper ticketExecutionMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtAccessTokenService jwtAccessTokenService;

    @Autowired
    private ObjectMapper objectMapper;

    /** 真实工单服务（并发建单测试直接调用 createTicketFor，走真实事务 + 唯一约束）。 */
    @Autowired
    private TicketService ticketService;

    @MockitoBean
    private UserQueryService userQueryService;

    private String adminToken;
    private String userAToken;
    private String userBToken;

    @BeforeEach
    void setUp() {
        // 数据库安全守卫：任何破坏性清理前，先严格校验实际 DataSource JDBC URL 指向 argus_rag_test:5433。
        requireTestDatabaseBeforeCleanup();
        // 独立测试库 + 独立工单：清空三张 ticket 表保证可重复运行（RESTART IDENTITY 复位自增序列）
        jdbcTemplate.execute("TRUNCATE TABLE ticket_dispositions, ticket_executions, approval_requests, tickets RESTART IDENTITY CASCADE");

        // 用户表无迁移，mock 掉按 id 查用户这一 DB 边界；角色/状态由这里决定，与 JWT claim 无关。
        when(userQueryService.findById(ADMIN_ID)).thenReturn(userRecord(ADMIN_ID, SystemRole.ADMIN));
        when(userQueryService.findById(USER_A_ID)).thenReturn(userRecord(USER_A_ID, SystemRole.USER));
        when(userQueryService.findById(USER_B_ID)).thenReturn(userRecord(USER_B_ID, SystemRole.USER));

        // 用真实 JwtAccessTokenService 签发测试令牌（与过滤器解析使用同一密钥）。
        adminToken = issueToken(ADMIN_ID, "u1", "管理员", SystemRole.ADMIN);
        userAToken = issueToken(USER_A_ID, "u100", "用户A", SystemRole.USER);
        userBToken = issueToken(USER_B_ID, "u200", "用户B", SystemRole.USER);
    }

    // ─────────────────────────── 1. 未认证 / 非法令牌 ───────────────────────────

    @Test
    @DisplayName("无令牌访问受保护接口：返回 401「当前请求未登录」（过滤器透传后由下游鉴权拒绝）")
    void shouldReturn401WhenNoToken() {
        HttpResult result = get("/api/tickets", null);

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.body().get("success").asBoolean()).isFalse();
        assertThat(result.body().get("message").asText()).isEqualTo("当前请求未登录");
    }

    @Test
    @DisplayName("无效令牌访问受保护接口：返回 401「access token 非法或已过期」（由过滤器直接写出）")
    void shouldReturn401WhenInvalidToken() {
        HttpResult result = get("/api/tickets", "definitely-not-a-valid-jwt-token");

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.body().get("success").asBoolean()).isFalse();
        // 该 401 由过滤器 writeUnauthorized 用 response.getWriter() 写出，响应字符集取决于容器配置，
        // 中文字尾在不同编码下可能被改写，故只断言稳定的 ASCII 前缀「access token」+ 状态码，
        // 以证明命中的是过滤器路径而非全局异常处理（后者消息为「当前请求未登录」）。
        assertThat(result.body().get("message").asText()).startsWith("access token");
    }

    // ─────────────────────────── 2. 权限边界 ───────────────────────────

    @Test
    @DisplayName("普通用户只能查询自己创建的工单，不能看到其他用户的工单")
    void shouldListOnlyOwnTicketsForRegularUser() {
        Long ticketA = insertTicket(USER_A_ID, TicketStatus.OPEN);
        insertTicket(USER_B_ID, TicketStatus.OPEN);

        HttpResult result = get("/api/tickets", userAToken);

        assertThat(result.status()).isEqualTo(200);
        JsonNode data = result.body().get("data");
        assertThat(data.isArray()).isTrue();
        assertThat(data.size()).isEqualTo(1);
        assertThat(data.get(0).get("id").asLong()).isEqualTo(ticketA);
        assertThat(data.get(0).get("creatorUserId").asLong()).isEqualTo(USER_A_ID);
    }

    @Test
    @DisplayName("普通用户审批工单：返回 403「当前用户不是系统管理员」")
    void shouldReturn403WhenRegularUserApproves() {
        Long ticketId = insertTicket(USER_A_ID, TicketStatus.PENDING_APPROVAL);

        HttpResult result = post("/api/tickets/" + ticketId + "/approve", userAToken, null);

        assertThat(result.status()).isEqualTo(403);
        assertThat(result.body().get("success").asBoolean()).isFalse();
        assertThat(result.body().get("message").asText()).isEqualTo("当前用户不是系统管理员");
    }

    @Test
    @DisplayName("普通用户提交其他用户的工单：返回 403「仅工单创建人可提交审批」")
    void shouldReturn403WhenRegularUserSubmitsOthersTicket() {
        Long ticketId = insertTicket(USER_B_ID, TicketStatus.OPEN);

        HttpResult result = post("/api/tickets/" + ticketId + "/submit", userAToken, null);

        assertThat(result.status()).isEqualTo(403);
        assertThat(result.body().get("success").asBoolean()).isFalse();
        assertThat(result.body().get("message").asText()).isEqualTo("仅工单创建人可提交审批");
    }

    // ─────────────────────────── 3. 审批与状态流转 ───────────────────────────

    @Test
    @DisplayName("管理员审批 PENDING_APPROVAL 工单：返回 200 且状态流转为 APPROVED")
    void shouldApprovePendingTicketAsAdmin() {
        Long ticketId = insertTicket(USER_A_ID, TicketStatus.PENDING_APPROVAL);

        HttpResult approve = post("/api/tickets/" + ticketId + "/approve", adminToken, null);
        assertThat(approve.status()).isEqualTo(200);
        assertThat(approve.body().get("success").asBoolean()).isTrue();

        HttpResult detail = get("/api/tickets/" + ticketId, adminToken);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.body().get("data").get("status").asText()).isEqualTo("APPROVED");
        assertThat(detail.body().get("data").get("approverUserId").asLong()).isEqualTo(ADMIN_ID);
    }

    @Test
    @DisplayName("管理员审批 OPEN 工单：非法状态流转返回 400「工单当前状态不允许审批」")
    void shouldRejectIllegalTransitionWhenAdminApprovesOpenTicket() {
        Long ticketId = insertTicket(USER_A_ID, TicketStatus.OPEN);

        HttpResult result = post("/api/tickets/" + ticketId + "/approve", adminToken, null);

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.body().get("success").asBoolean()).isFalse();
        assertThat(result.body().get("message").asText()).isEqualTo("工单当前状态不允许审批");
    }

    @Test
    @DisplayName("普通用户通过 HTTP 创建工单：返回 200，回查详情状态为 OPEN")
    void shouldCreateTicketOverHttpAndQueryOpenStatus() {
        String idempotencyKey = "HTTP-CREATE-" + SEQ.incrementAndGet();
        HttpResult create = post("/api/tickets", userAToken,
                createTicketBody(idempotencyKey, "create-" + SEQ.incrementAndGet()));

        assertThat(create.status()).isEqualTo(200);
        JsonNode data = create.body().get("data");
        assertThat(data.get("alreadyExists").asBoolean()).isFalse();
        assertThat(data.get("ticket").get("status").asText()).isEqualTo("OPEN");
        assertThat(data.get("ticket").get("creatorUserId").asLong()).isEqualTo(USER_A_ID);

        Long ticketId = data.get("ticket").get("id").asLong();
        HttpResult detail = get("/api/tickets/" + ticketId, userAToken);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.body().get("data").get("status").asText()).isEqualTo("OPEN");
    }

    // ─────────────────────────── 4. 完整工单业务流程 ───────────────────────────

    @Test
    @DisplayName("完整流程：创建 -> 提交审批 -> 管理员审批通过 -> 管理员执行 -> 查询最终状态与执行历史")
    void shouldRunFullTicketLifecycleOverHttp() {
        String targetUsername = "fullflow-" + SEQ.incrementAndGet();

        // 1) 普通用户 A 创建工单
        HttpResult create = post("/api/tickets", userAToken,
                createTicketBody("HTTP-FLOW-" + SEQ.incrementAndGet(), targetUsername));
        assertThat(create.status()).isEqualTo(200);
        Long ticketId = create.body().get("data").get("ticket").get("id").asLong();
        assertThat(create.body().get("data").get("ticket").get("status").asText()).isEqualTo("OPEN");

        // 2) 普通用户 A 提交审批（OPEN -> PENDING_APPROVAL）
        HttpResult submit = post("/api/tickets/" + ticketId + "/submit", userAToken, null);
        assertThat(submit.status()).isEqualTo(200);
        assertThat(get("/api/tickets/" + ticketId, userAToken).body()
                .get("data").get("status").asText()).isEqualTo("PENDING_APPROVAL");

        // 3) 管理员审批通过（PENDING_APPROVAL -> APPROVED）
        HttpResult approve = post("/api/tickets/" + ticketId + "/approve", adminToken, null);
        assertThat(approve.status()).isEqualTo(200);
        assertThat(get("/api/tickets/" + ticketId, adminToken).body()
                .get("data").get("status").asText()).isEqualTo("APPROVED");

        // 4) 管理员执行（APPROVED -> IN_PROGRESS -> RESOLVED，使用 MockAccountProvisioningExecutor）
        HttpResult execute = post("/api/tickets/" + ticketId + "/execute", adminToken, null);
        assertThat(execute.status()).isEqualTo(200);
        JsonNode execResult = execute.body().get("data");
        assertThat(execResult.get("ticketId").asLong()).isEqualTo(ticketId);
        assertThat(execResult.get("attemptNo").asInt()).isEqualTo(1);
        assertThat(execResult.get("result").asText()).isEqualTo("SUCCESS");

        // 5) 查询最终状态为 RESOLVED，执行历史有一条 SUCCESS 记录
        HttpResult detail = get("/api/tickets/" + ticketId, adminToken);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.body().get("data").get("status").asText()).isEqualTo("RESOLVED");

        HttpResult executions = get("/api/tickets/" + ticketId + "/executions", adminToken);
        assertThat(executions.status()).isEqualTo(200);
        JsonNode execArr = executions.body().get("data");
        assertThat(execArr.isArray()).isTrue();
        assertThat(execArr.size()).isEqualTo(1);
        assertThat(execArr.get(0).get("attemptNo").asInt()).isEqualTo(1);
        assertThat(execArr.get(0).get("result").asText()).isEqualTo("SUCCESS");
        assertThat(execArr.get(0).get("executionType").asText()).isEqualTo("PROVISION_ACCOUNT");
    }

    // ─────────────────────────── 5. 权限边界与重复操作（D4 补充） ───────────────────────────

    @Test
    @DisplayName("管理员对 RESOLVED 工单再次执行：返回 400，状态与记录数不变")
    void shouldReturn400WhenAdminExecutesResolvedTicket() {
        Long ticketId = insertTicket(USER_A_ID, TicketStatus.RESOLVED);
        insertApprovalRecord(ticketId, ADMIN_ID, ApprovalResult.APPROVED);
        insertExecution(ticketId, 1, ExecutionResult.SUCCESS);

        HttpResult result = post("/api/tickets/" + ticketId + "/execute", adminToken, null);

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.body().get("message").asText()).isEqualTo("工单当前状态不允许执行");
        assertThat(ticketMapper.selectById(ticketId).getStatus()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(executionCount(ticketId)).isEqualTo(1L);
        assertThat(approvalCount(ticketId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("普通用户查询管理员创建的工单详情：返回 403「无权查看该工单」")
    void shouldReturn403WhenRegularUserViewsOthersTicketDetail() {
        Long ticketId = insertTicket(ADMIN_ID, TicketStatus.OPEN);

        HttpResult result = get("/api/tickets/" + ticketId, userAToken);

        assertThat(result.status()).isEqualTo(403);
        assertThat(result.body().get("message").asText()).isEqualTo("无权查看该工单");
        assertThat(ticketMapper.selectById(ticketId).getStatus()).isEqualTo(TicketStatus.OPEN);
    }

    @Test
    @DisplayName("普通用户调用执行接口：返回 403，不产生执行记录")
    void shouldReturn403WhenRegularUserExecutes() {
        Long ticketId = insertTicket(USER_A_ID, TicketStatus.APPROVED);
        insertApprovalRecord(ticketId, ADMIN_ID, ApprovalResult.APPROVED);

        HttpResult result = post("/api/tickets/" + ticketId + "/execute", userAToken, null);

        assertThat(result.status()).isEqualTo(403);
        assertThat(result.body().get("message").asText()).isEqualTo("当前用户不是系统管理员");
        assertThat(ticketMapper.selectById(ticketId).getStatus()).isEqualTo(TicketStatus.APPROVED);
        assertThat(executionCount(ticketId)).isEqualTo(0L);
        assertThat(approvalCount(ticketId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("普通用户拒绝 PENDING_APPROVAL 工单：返回 403，状态与审批记录不变")
    void shouldReturn403WhenRegularUserRejects() {
        Long ticketId = insertTicket(USER_A_ID, TicketStatus.PENDING_APPROVAL);

        HttpResult result = post("/api/tickets/" + ticketId + "/reject", userAToken, Map.of());

        assertThat(result.status()).isEqualTo(403);
        assertThat(result.body().get("message").asText()).isEqualTo("当前用户不是系统管理员");
        assertThat(ticketMapper.selectById(ticketId).getStatus()).isEqualTo(TicketStatus.PENDING_APPROVAL);
        assertThat(approvalCount(ticketId)).isEqualTo(0L);
    }

    // ─────────────────────────── 6. 建单幂等（HTTP 与并发，A 矩阵） ───────────────────────────

    @Test
    @DisplayName("HTTP 建单幂等：相同幂等键 + 相同内容重复创建 → 复用已建工单（alreadyExists=true）")
    void shouldReuseTicketForSameIdempotencyKeyAndContent() {
        String idempotencyKey = "HTTP-IDEM-REUSE-" + SEQ.incrementAndGet();
        String targetUsername = "idem-reuse-" + SEQ.incrementAndGet();

        HttpResult first = post("/api/tickets", userAToken, createTicketBody(idempotencyKey, targetUsername));
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body().get("data").get("alreadyExists").asBoolean()).isFalse();
        Long firstTicketId = first.body().get("data").get("ticket").get("id").asLong();

        HttpResult second = post("/api/tickets", userAToken, createTicketBody(idempotencyKey, targetUsername));
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.body().get("data").get("alreadyExists").asBoolean()).isTrue();
        Long secondTicketId = second.body().get("data").get("ticket").get("id").asLong();

        assertThat(secondTicketId).isEqualTo(firstTicketId);
        assertThat(ticketCountByIdempotencyKey(idempotencyKey)).isEqualTo(1L);
    }

    @Test
    @DisplayName("HTTP 建单幂等：相同幂等键 + 不同内容 → 400 拒绝，不新增工单")
    void shouldRejectWhenIdempotencyKeyReusedWithDifferentContent() {
        String idempotencyKey = "HTTP-IDEM-REJECT-" + SEQ.incrementAndGet();
        String targetUsername = "idem-reject-" + SEQ.incrementAndGet();

        HttpResult first = post("/api/tickets", userAToken, createTicketBody(idempotencyKey, targetUsername));
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body().get("data").get("alreadyExists").asBoolean()).isFalse();

        HttpResult second = post("/api/tickets", userAToken,
                createTicketBody(idempotencyKey, "idem-reject-different-" + SEQ.incrementAndGet()));
        assertThat(second.status()).isEqualTo(400);
        assertThat(second.body().get("success").asBoolean()).isFalse();
        assertThat(second.body().get("message").asText()).isEqualTo("幂等键已存在，但请求内容不一致");

        assertThat(ticketCountByIdempotencyKey(idempotencyKey)).isEqualTo(1L);
    }

    @Test
    @DisplayName("并发重复建单（相同幂等键 + 相同内容）：唯一约束兜底复用，最终仅一条工单")
    void shouldReuseTicketWhenConcurrentDuplicateInsert() throws Exception {
        String idempotencyKey = "HTTP-CONCURRENT-" + SEQ.incrementAndGet();
        String targetUsername = "concurrent-" + SEQ.incrementAndGet();
        CreateTicketRequest request = createTicketRequest(idempotencyKey, targetUsername);

        int threadCount = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CreateTicketResult>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return ticketService.createTicketFor(USER_A_ID, SystemRole.USER, request);
            }));
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        List<CreateTicketResult> results = new ArrayList<>();
        for (Future<CreateTicketResult> future : futures) {
            results.add(future.get(15, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        assertThat(results).hasSize(2);
        assertThat(results).filteredOn(CreateTicketResult::alreadyExists).hasSize(1);
        assertThat(results).filteredOn(result -> !result.alreadyExists()).hasSize(1);
        assertThat(ticketCountByIdempotencyKey(idempotencyKey)).isEqualTo(1L);
    }

    // ─────────────────────────── 7. 人工处置（阶段 4） ───────────────────────────

    @Test
    @DisplayName("管理员确认执行成功：工单 RESOLVED，执行记录置 CONFIRMED_SUCCESS，审计 1 条")
    void shouldConfirmSuccessOverHttp() {
        Long ticketId = insertTicket(USER_A_ID, TicketStatus.APPROVED);
        Long executionId = insertExecution(ticketId, 1, ExecutionResult.UNCERTAIN);

        HttpResult result = post("/api/tickets/" + ticketId + "/dispose", adminToken,
                disposeBody("CONFIRM_SUCCESS", executionId, "确认已开通成功"));

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body().get("success").asBoolean()).isTrue();
        assertThat(ticketMapper.selectById(ticketId).getStatus()).isEqualTo(TicketStatus.RESOLVED);

        JsonNode execArr = get("/api/tickets/" + ticketId + "/executions", adminToken).body().get("data");
        assertThat(execArr.get(0).get("verificationStatus").asText()).isEqualTo("CONFIRMED_SUCCESS");
        assertThat(execArr.get(0).has("verifiedAt")).isTrue();
        assertThat(execArr.get(0).has("verifiedBy")).isFalse();
        assertThat(dispositionCount(ticketId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("管理员确认未执行：工单保持 APPROVED，执行记录置 CONFIRMED_NOT_EXECUTED")
    void shouldConfirmNotExecutedOverHttp() {
        Long ticketId = insertTicket(USER_A_ID, TicketStatus.APPROVED);
        Long executionId = insertExecution(ticketId, 1, ExecutionResult.UNCERTAIN);

        HttpResult result = post("/api/tickets/" + ticketId + "/dispose", adminToken,
                disposeBody("CONFIRM_NOT_EXECUTED", executionId, "确认未执行"));

        assertThat(result.status()).isEqualTo(200);
        assertThat(ticketMapper.selectById(ticketId).getStatus()).isEqualTo(TicketStatus.APPROVED);

        JsonNode execArr = get("/api/tickets/" + ticketId + "/executions", adminToken).body().get("data");
        assertThat(execArr.get(0).get("verificationStatus").asText()).isEqualTo("CONFIRMED_NOT_EXECUTED");
        assertThat(dispositionCount(ticketId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("管理员授权重试：extra_attempts 递增为 1，工单保持 APPROVED，审计 1 条")
    void shouldRetryAuthorizeOverHttp() {
        Long ticketId = insertTicket(USER_A_ID, TicketStatus.APPROVED);

        HttpResult result = post("/api/tickets/" + ticketId + "/dispose", adminToken,
                disposeBody("RETRY_AUTHORIZE", null, "授权重试一次"));

        assertThat(result.status()).isEqualTo(200);
        assertThat(ticketMapper.selectById(ticketId).getStatus()).isEqualTo(TicketStatus.APPROVED);
        assertThat(ticketMapper.selectById(ticketId).getExtraAttempts()).isEqualTo(1);
        assertThat(dispositionCount(ticketId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("管理员终止工单：工单 ABORTED，审计 1 条")
    void shouldTerminateOverHttp() {
        Long ticketId = insertTicket(USER_A_ID, TicketStatus.APPROVED);

        HttpResult result = post("/api/tickets/" + ticketId + "/dispose", adminToken,
                disposeBody("TERMINATE", null, "不再执行"));

        assertThat(result.status()).isEqualTo(200);
        assertThat(ticketMapper.selectById(ticketId).getStatus()).isEqualTo(TicketStatus.ABORTED);
        assertThat(dispositionCount(ticketId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("普通用户处置：返回 403，工单状态与审计均不变")
    void shouldReturn403WhenRegularUserDisposes() {
        Long ticketId = insertTicket(USER_A_ID, TicketStatus.APPROVED);

        HttpResult result = post("/api/tickets/" + ticketId + "/dispose", userAToken,
                disposeBody("TERMINATE", null, "不再执行"));

        assertThat(result.status()).isEqualTo(403);
        assertThat(result.body().get("message").asText()).isEqualTo("当前用户不是系统管理员");
        assertThat(ticketMapper.selectById(ticketId).getStatus()).isEqualTo(TicketStatus.APPROVED);
        assertThat(dispositionCount(ticketId)).isEqualTo(0L);
    }

    @Test
    @DisplayName("未登录处置：返回 401「当前请求未登录」")
    void shouldReturn401WhenDisposingWithoutToken() {
        Long ticketId = insertTicket(USER_A_ID, TicketStatus.APPROVED);

        HttpResult result = post("/api/tickets/" + ticketId + "/dispose", null,
                disposeBody("TERMINATE", null, "不再执行"));

        assertThat(result.status()).isEqualTo(401);
        assertThat(result.body().get("message").asText()).isEqualTo("当前请求未登录");
        assertThat(dispositionCount(ticketId)).isEqualTo(0L);
    }

    @Test
    @DisplayName("处置参数校验失败：缺失处置类型返回 400")
    void shouldReturn400WhenDisposeMissingType() {
        Long ticketId = insertTicket(USER_A_ID, TicketStatus.APPROVED);

        HttpResult result = post("/api/tickets/" + ticketId + "/dispose", adminToken,
                Map.of("reason", "确认已开通"));

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.body().get("message").asText()).isEqualTo("处置类型不能为空");
        assertThat(dispositionCount(ticketId)).isEqualTo(0L);
    }

    @Test
    @DisplayName("处置参数校验失败：缺失处置原因返回 400")
    void shouldReturn400WhenDisposeMissingReason() {
        Long ticketId = insertTicket(USER_A_ID, TicketStatus.APPROVED);

        HttpResult result = post("/api/tickets/" + ticketId + "/dispose", adminToken,
                Map.of("type", "TERMINATE"));

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.body().get("message").asText()).isEqualTo("处置原因不能为空");
        assertThat(dispositionCount(ticketId)).isEqualTo(0L);
    }

    @Test
    @DisplayName("处置业务失败：非 APPROVED 工单返回 400")
    void shouldReturn400WhenDisposeNonApprovedTicket() {
        Long ticketId = insertTicket(USER_A_ID, TicketStatus.OPEN);

        HttpResult result = post("/api/tickets/" + ticketId + "/dispose", adminToken,
                disposeBody("TERMINATE", null, "不再执行"));

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.body().get("message").asText()).isEqualTo("工单当前状态不允许处置");
        assertThat(dispositionCount(ticketId)).isEqualTo(0L);
    }

    @Test
    @DisplayName("处置业务失败：重复核验返回 400")
    void shouldReturn400WhenDisposeAlreadyVerified() {
        Long ticketId = insertTicket(USER_A_ID, TicketStatus.APPROVED);
        Long executionId = insertExecution(ticketId, 1, ExecutionResult.UNCERTAIN);
        // 预置已核验状态，触发 requireUnverifiedUncertainExecution 的重复处置拒绝
        ticketExecutionMapper.updateVerification(executionId, "CONFIRMED_NOT_EXECUTED", ADMIN_ID, LocalDateTime.now());

        HttpResult result = post("/api/tickets/" + ticketId + "/dispose", adminToken,
                disposeBody("CONFIRM_SUCCESS", executionId, "确认已开通成功"));

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.body().get("message").asText()).isEqualTo("该执行记录已核验，请勿重复处置");
        assertThat(ticketMapper.selectById(ticketId).getStatus()).isEqualTo(TicketStatus.APPROVED);
        assertThat(dispositionCount(ticketId)).isEqualTo(0L);
    }

    // ─────────────────────────── 测试配置与辅助 ───────────────────────────

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableTransactionManagement
    @MapperScan("com.argus.rag.ticket.mapper")
    @Import({
            TicketController.class,
            TicketService.class,
            TicketApprovalService.class,
            TicketDispositionService.class,
            TicketExecutionService.class,
            TicketExecutionTransactionService.class,
            TicketConfiguration.class,
            CurrentUserService.class,
            JwtAuthenticationFilter.class,
            JwtAccessTokenService.class,
            AuthConfiguration.class,
            GlobalExceptionHandler.class,
            MockAccountProvisioningExecutor.class
    })
    static class Config {
    }

    /** HTTP 请求结果：状态码 + 已解析的 JSON 响应体（可能为 null）。 */
    private record HttpResult(int status, JsonNode body) {
    }

    private HttpResult get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return toResult(rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), byte[].class));
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

    private String issueToken(Long userId, String userCode, String displayName, SystemRole role) {
        return jwtAccessTokenService.issueToken(
                new JwtAccessTokenService.TokenSubject(userId, userCode, displayName, role, false));
    }

    private UserQueryService.UserRecord userRecord(Long userId, SystemRole role) {
        return new UserQueryService.UserRecord(userId, "u" + userId, "用户" + userId, role, UserStatus.ACTIVE, false);
    }

    private Map<String, Object> createTicketBody(String idempotencyKey, String targetUsername) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("idempotencyKey", idempotencyKey);
        body.put("type", "ACCOUNT_PROVISIONING");
        body.put("systemName", "erp");
        body.put("targetUsername", targetUsername);
        body.put("permissionLevel", "READ_ONLY");
        body.put("description", "HTTP integration test");
        body.put("title", "account provisioning");
        return body;
    }

    /** 处置请求体：executionId 为 null 时省略该字段（TERMINATE / RETRY_AUTHORIZE 等无执行记录场景）。 */
    private Map<String, Object> disposeBody(String type, Long executionId, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        if (executionId != null) {
            body.put("executionId", executionId);
        }
        body.put("reason", reason);
        return body;
    }

    /** 并发建单用 {@link CreateTicketRequest}（与 {@link #createTicketBody} 内容等价，直接走服务方法）。 */
    private CreateTicketRequest createTicketRequest(String idempotencyKey, String targetUsername) {
        return new CreateTicketRequest(
                idempotencyKey,
                TicketType.ACCOUNT_PROVISIONING,
                "erp",
                targetUsername,
                PermissionLevel.READ_ONLY,
                "concurrent idempotency test",
                "account provisioning",
                "LOW");
    }

    private long ticketCountByIdempotencyKey(String idempotencyKey) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tickets WHERE idempotency_key = ?", Long.class, idempotencyKey);
        return count == null ? 0L : count;
    }

    /**
     * 数据库安全守卫：在执行任何 {@code TRUNCATE} 前，从<b>实际 DataSource 连接</b>读取 JDBC URL，
     * 严格校验数据库名、端口与主机，避免把开发库 {@code argus_rag} 误当成测试库清理。
     * 校验失败立即抛出，绝不执行破坏性清理。
     */
    private void requireTestDatabaseBeforeCleanup() {
        String url;
        try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
            url = connection.getMetaData().getURL();
        } catch (SQLException e) {
            throw new IllegalStateException("集成测试安全守卫：无法连接数据库以校验 JDBC URL，已拒绝执行 TRUNCATE", e);
        }
        parseAndValidateJdbcUrl(url);
    }

    /** 严格解析 PostgreSQL JDBC URL 并校验数据库名 / 端口 / 主机，不做易误匹配的子串判断。 */
    private static void parseAndValidateJdbcUrl(String url) {
        if (url == null || !url.startsWith("jdbc:postgresql://")) {
            throw new IllegalStateException("集成测试安全守卫：JDBC URL 不是 PostgreSQL 连接串，已拒绝执行 TRUNCATE: " + url);
        }
        URI uri;
        try {
            uri = URI.create(url.substring("jdbc:".length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("集成测试安全守卫：JDBC URL 解析失败，已拒绝执行 TRUNCATE: " + url, e);
        }
        String host = uri.getHost();
        int port = uri.getPort();
        String path = uri.getPath();
        String database = (path == null || path.length() <= 1) ? "" : path.substring(1);
        if (!"argus_rag_test".equals(database)) {
            throw new IllegalStateException(
                    "集成测试安全守卫：数据库名不是 argus_rag_test，已拒绝执行 TRUNCATE（实际：" + database + "）");
        }
        if (port != 5433) {
            throw new IllegalStateException(
                    "集成测试安全守卫：端口不是 5433，已拒绝执行 TRUNCATE（实际：" + port + "）");
        }
        if (!"localhost".equals(host) && !"127.0.0.1".equals(host)) {
            throw new IllegalStateException(
                    "集成测试安全守卫：主机不是本机，已拒绝执行 TRUNCATE（实际：" + host + "）");
        }
    }

    private Long insertTicket(Long creatorUserId, TicketStatus status) {
        long seq = SEQ.incrementAndGet();
        Ticket ticket = new Ticket();
        ticket.setTicketNo("TK-HTTP-" + seq);
        ticket.setTitle("账号开通申请");
        ticket.setDescription("HTTP 集成测试");
        ticket.setType(TicketType.ACCOUNT_PROVISIONING);
        ticket.setStatus(status);
        ticket.setCreatorUserId(creatorUserId);
        ticket.setSystemName("erp");
        ticket.setTargetUsername("seed-user-" + seq);
        ticket.setPermissionLevel(PermissionLevel.READ_ONLY);
        ticket.setVersion(0);
        ticket.setIdempotencyKey("HTTP-SEED-" + seq);
        LocalDateTime now = LocalDateTime.now();
        ticket.setCreatedAt(now);
        ticket.setUpdatedAt(now);
        ticketMapper.insert(ticket);
        return ticket.getId();
    }

    private Long insertApprovalRecord(Long ticketId, Long approverUserId, ApprovalResult result) {
        ApprovalRequest record = new ApprovalRequest();
        record.setTicketId(ticketId);
        record.setApproverUserId(approverUserId);
        record.setResult(result);
        record.setComment(null);
        record.setDecidedAt(LocalDateTime.now());
        approvalRequestMapper.insert(record);
        return record.getId();
    }

    private Long insertExecution(Long ticketId, int attemptNo, ExecutionResult result) {
        TicketExecution record = new TicketExecution();
        record.setTicketId(ticketId);
        record.setExecutionType(ExecutionType.PROVISION_ACCOUNT);
        record.setResult(result);
        record.setAttemptNo(attemptNo);
        record.setIdempotencyKey("PROVISION_ACCOUNT:" + ticketId);
        record.setOperatorUserId(ADMIN_ID);
        record.setStartedAt(LocalDateTime.now());
        ticketExecutionMapper.insert(record);
        return record.getId();
    }

    private long executionCount(Long ticketId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ticket_executions WHERE ticket_id = ?", Long.class, ticketId);
        return count == null ? 0L : count;
    }

    private long approvalCount(Long ticketId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM approval_requests WHERE ticket_id = ?", Long.class, ticketId);
        return count == null ? 0L : count;
    }

    private long dispositionCount(Long ticketId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ticket_dispositions WHERE ticket_id = ?", Long.class, ticketId);
        return count == null ? 0L : count;
    }
}
