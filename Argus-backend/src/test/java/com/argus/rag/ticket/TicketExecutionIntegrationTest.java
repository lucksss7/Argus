package com.argus.rag.ticket;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.ticket.config.TicketConfiguration;
import com.argus.rag.ticket.executor.AccountProvisioningExecutor;
import com.argus.rag.ticket.executor.ProvisionCommand;
import com.argus.rag.ticket.executor.ProvisionResult;
import com.argus.rag.ticket.mapper.ApprovalRequestMapper;
import com.argus.rag.ticket.mapper.TicketExecutionMapper;
import com.argus.rag.ticket.mapper.TicketMapper;
import com.argus.rag.ticket.model.entity.ApprovalRequest;
import com.argus.rag.ticket.model.entity.Ticket;
import com.argus.rag.ticket.model.entity.TicketExecution;
import com.argus.rag.ticket.model.enums.ApprovalResult;
import com.argus.rag.ticket.model.enums.ExecutionResult;
import com.argus.rag.ticket.model.enums.ExecutionType;
import com.argus.rag.ticket.model.enums.PermissionLevel;
import com.argus.rag.ticket.model.enums.TicketStatus;
import com.argus.rag.ticket.model.enums.TicketType;
import com.argus.rag.ticket.model.vo.ExecuteTicketResult;
import com.argus.rag.ticket.service.AttemptContext;
import com.argus.rag.ticket.service.TicketExecutionService;
import com.argus.rag.ticket.service.TicketExecutionTransactionService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 工单执行模块的真实 PostgreSQL 集成测试。
 * <p>
 * 使用聚焦切片（只装配 ticket 执行相关 Bean + 真实 DataSource / 事务管理器 / MyBatis-Plus Mapper），
 * 而不是整容器启动，避免无关模块（Spring AI / pgvector / MinIO / ES / 文档回收 Runner）干扰。
 * <p>
 * 关键点：{@link TicketExecutionTransactionService} 与 {@link TicketExecutionService} 都是 Spring 管理的
 * Bean，测试通过注入的代理调用，真实经过 {@code @Transactional} 事务代理，而非手动 new。
 * <p>
 * 运行前置：需要独立测试库 argus_rag_test 存在（不动开发库 argus_rag），且本地 PostgreSQL 可达。
 * 本类仅当设置环境变量 ARGUS_INTEGRATION=true 时激活，避免影响默认 {@code mvnw test}。
 */
@SpringBootTest(classes = TicketExecutionIntegrationTest.Config.class)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "ARGUS_INTEGRATION", matches = "true")
@DisplayName("工单执行集成测试（真实 PostgreSQL + Spring 事务代理）")
class TicketExecutionIntegrationTest {

    @Autowired
    private TicketExecutionTransactionService txService;

    @Autowired
    private TicketExecutionService executionService;

    @Autowired
    private TicketMapper ticketMapper;

    @Autowired
    private TicketExecutionMapper executionMapper;

    @Autowired
    private ApprovalRequestMapper approvalRequestMapper;

    @Autowired
    private RecordingExecutor recordingExecutor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager txManager;

    @MockitoBean
    private CurrentUserService currentUserService;

    private static final AtomicLong SEQ = new AtomicLong();

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // 数据库安全守卫：任何破坏性清理前，先严格校验实际 DataSource JDBC URL 指向 argus_rag_test:5433。
        requireTestDatabaseBeforeCleanup();
        // 独立测试库 + 独立工单：清空三张 ticket 表保证可重复运行（RESTART IDENTITY 复位自增序列）
        jdbcTemplate.execute("TRUNCATE TABLE ticket_executions, approval_requests, tickets RESTART IDENTITY CASCADE");
        when(currentUserService.requireSystemAdmin()).thenReturn(admin(1L));
        recordingExecutor.reset();
    }

    // ─────────────────────────── 1. Mapper SQL 往返 ───────────────────────────

    @Test
    @DisplayName("Mapper SQL 在真实 PostgreSQL 中可执行，枚举/返回类型/条件更新正确")
    void mapperSqlRoundTrip() {
        Long ticketId = givenApprovedTicket("zhangsan");

        // countApprovedByTicketId：审批通过记录数
        assertThat(approvalRequestMapper.countApprovedByTicketId(ticketId)).isEqualTo(1L);

        // countUncertainByTicketId：初始无执行记录 → 0
        assertThat(executionMapper.countUncertainByTicketId(ticketId)).isEqualTo(0L);

        // selectMaxAttemptNoByTicketId：无记录时 coalesce 返回 0
        assertThat(executionMapper.selectMaxAttemptNoByTicketId(ticketId)).isEqualTo(0);

        // 插入一条 PENDING 执行记录，验证主键回填
        TicketExecution pending = pendingRecord(ticketId, 1);
        assertThat(executionMapper.insert(pending)).isEqualTo(1);
        assertThat(pending.getId()).isNotNull();

        // 枚举按 name() 字符串存储（EnumTypeHandler）
        assertThat(jdbcTemplate.queryForObject(
                "select result from ticket_executions where id = ?", String.class, pending.getId()))
                .isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "select execution_type from ticket_executions where id = ?", String.class, pending.getId()))
                .isEqualTo("PROVISION_ACCOUNT");

        // updateExecutionResult：仅当 result='PENDING' 时生效
        int firstUpdate = executionMapper.updateExecutionResult(
                pending.getId(), ExecutionResult.SUCCESS.name(), null, "{\"ok\":true}", LocalDateTime.now());
        assertThat(firstUpdate).isEqualTo(1);
        int secondUpdate = executionMapper.updateExecutionResult(
                pending.getId(), ExecutionResult.FAILED.name(), "x", null, LocalDateTime.now());
        assertThat(secondUpdate).isEqualTo(0); // 已非 PENDING，条件更新拦截

        // updateTicketStatus：仅当 status=fromStatus 时生效
        assertThat(ticketMapper.updateTicketStatus(ticketId, TicketStatus.APPROVED.name(), TicketStatus.IN_PROGRESS.name()))
                .isEqualTo(1);
        assertThat(ticketMapper.updateTicketStatus(ticketId, TicketStatus.APPROVED.name(), TicketStatus.IN_PROGRESS.name()))
                .isEqualTo(0); // 状态已变，条件更新拦截

        // 新增一条 UNCERTAIN 记录后，countUncertainByTicketId 与 max attempt_no 正确
        TicketExecution uncertain = pendingRecord(ticketId, 2);
        uncertain.setResult(ExecutionResult.UNCERTAIN);
        executionMapper.insert(uncertain);
        assertThat(executionMapper.countUncertainByTicketId(ticketId)).isEqualTo(1L);
        assertThat(executionMapper.selectMaxAttemptNoByTicketId(ticketId)).isEqualTo(2);
    }

    // ─────────────────────────── 2. T1 提交与回滚 ───────────────────────────

    @Test
    @DisplayName("T1 提交：begin 返回后状态更新与 PENDING 记录均已提交（真实事务代理）")
    void beginCommitsStatusAndPendingRecord() {
        Long ticketId = givenApprovedTicket("zhangsan");

        // 非事务测试方法中调用 begin → 其自身 @Transactional 独立提交
        AttemptContext ctx = txService.begin(ticketId, 1L);

        // 新连接读取：已提交
        assertThat(ticketMapper.selectById(ticketId).getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(executionMapper.selectCount(new QueryWrapper<TicketExecution>().eq("ticket_id", ticketId)))
                .isEqualTo(1L);

        TicketExecution record = executionMapper.selectById(ctx.executionRecordId());
        assertThat(record.getResult()).isEqualTo(ExecutionResult.PENDING);
        assertThat(record.getAttemptNo()).isEqualTo(1);
        assertThat(record.getIdempotencyKey()).isEqualTo("PROVISION_ACCOUNT:" + ticketId);
    }

    @Test
    @DisplayName("T1 回滚：begin 的状态更新与插入同事务，回滚后不残留部分写入")
    void beginWritesRollBackTogether() {
        Long ticketId = givenApprovedTicket("zhangsan");

        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            txService.begin(ticketId, 1L); // 加入外层事务
            status.setRollbackOnly();      // 强制回滚
        });

        // 回滚后（新连接读取）：状态未变、无执行记录
        assertThat(ticketMapper.selectById(ticketId).getStatus()).isEqualTo(TicketStatus.APPROVED);
        assertThat(executionMapper.selectCount(new QueryWrapper<TicketExecution>().eq("ticket_id", ticketId)))
                .isEqualTo(0L);
    }

    // ─────────────────────────── 3. T2 两步同事务 ───────────────────────────

    @Test
    @DisplayName("T2 回滚：第二步（工单状态更新）失败时，第一步（执行记录更新）一并回滚")
    void finalizeRollsBackBothWritesWhenTicketUpdateFails() {
        Long ticketId = givenApprovedTicket("zhangsan");
        AttemptContext ctx = txService.begin(ticketId, 1L); // 已提交 IN_PROGRESS + PENDING

        // 人为把工单移出 IN_PROGRESS，使 finalize 的 IN_PROGRESS -> RESOLVED 条件更新命中 0 行
        ticketMapper.updateTicketStatus(ticketId, TicketStatus.IN_PROGRESS.name(), TicketStatus.RESOLVED.name());

        ProvisionResult success = ProvisionResult.success(ProvisionResult.ProvisionErrorCode.PROVISIONED, "ext");
        assertThatThrownBy(() -> txService.finalize(ctx, success))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("工单状态异常");

        // 第一步（执行记录 -> SUCCESS）必须已随事务回滚，仍为 PENDING
        TicketExecution record = executionMapper.selectById(ctx.executionRecordId());
        assertThat(record.getResult()).isEqualTo(ExecutionResult.PENDING);
        assertThat(record.getFinishedAt()).isNull();
        // 工单保持我们手动置的 RESOLVED（该次更新已单独提交）
        assertThat(ticketMapper.selectById(ticketId).getStatus()).isEqualTo(TicketStatus.RESOLVED);
    }

    // ─────────────────────────── 4. 并发执行 ───────────────────────────

    @Test
    @DisplayName("并发执行同一工单，仅一个请求成功创建执行记录，另一个被条件更新拦截")
    void concurrentExecuteOnlyOneSucceeds() throws Exception {
        Long ticketId = givenApprovedTicket("zhangsan");
        recordingExecutor.behave(c -> ProvisionResult.success(ProvisionResult.ProvisionErrorCode.PROVISIONED, "ext"));

        int threadCount = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ExecuteTicketResult>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return executionService.execute(ticketId);
            }));
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        int successes = 0;
        int businessFailures = 0;
        for (Future<ExecuteTicketResult> future : futures) {
            try {
                future.get(15, TimeUnit.SECONDS);
                successes++;
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(BusinessException.class);
                businessFailures++;
            }
        }
        pool.shutdownNow();

        assertThat(successes).isEqualTo(1);
        assertThat(businessFailures).isEqualTo(1);

        assertThat(executionMapper.selectCount(new QueryWrapper<TicketExecution>().eq("ticket_id", ticketId)))
                .isEqualTo(1L);
        assertThat(ticketMapper.selectById(ticketId).getStatus()).isEqualTo(TicketStatus.RESOLVED);
    }

    @Test
    @DisplayName("达到执行次数上限后拒绝执行：不新增记录且工单保持 APPROVED")
    void executeRejectedWhenMaxAttemptsReached() {
        Long ticketId = givenApprovedTicket("zhangsan");
        insertFailedExecution(ticketId, 1);
        insertFailedExecution(ticketId, 2);
        insertFailedExecution(ticketId, 3);

        assertThatThrownBy(() -> executionService.execute(ticketId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("执行次数已达上限");

        assertThat(executionMapper.selectCount(new QueryWrapper<TicketExecution>().eq("ticket_id", ticketId)))
                .isEqualTo(3L);
        assertThat(ticketMapper.selectById(ticketId).getStatus()).isEqualTo(TicketStatus.APPROVED);
    }

    @Test
    @DisplayName("并发执行不突破上限：已有 2 次失败时，并发仅新增第 3 次尝试")
    void concurrentExecuteCannotExceedMaxAttempts() throws Exception {
        Long ticketId = givenApprovedTicket("zhangsan");
        insertFailedExecution(ticketId, 1);
        insertFailedExecution(ticketId, 2);
        recordingExecutor.behave(c -> ProvisionResult.success(ProvisionResult.ProvisionErrorCode.PROVISIONED, "ext"));

        int threadCount = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ExecuteTicketResult>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return executionService.execute(ticketId);
            }));
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        int successes = 0;
        int businessFailures = 0;
        for (Future<ExecuteTicketResult> future : futures) {
            try {
                future.get(15, TimeUnit.SECONDS);
                successes++;
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(BusinessException.class);
                businessFailures++;
            }
        }
        pool.shutdownNow();

        assertThat(successes).isEqualTo(1);
        assertThat(businessFailures).isEqualTo(1);

        assertThat(executionMapper.selectCount(new QueryWrapper<TicketExecution>().eq("ticket_id", ticketId)))
                .isEqualTo(3L);
        assertThat(ticketMapper.selectById(ticketId).getStatus()).isEqualTo(TicketStatus.RESOLVED);
    }

    // ─────────────────────────── 5. 执行器调用时机 ───────────────────────────

    @Test
    @DisplayName("执行器调用发生在 T1 提交之后、T2 开始之前（观察到 IN_PROGRESS + PENDING）")
    void executorRunsAfterT1CommitAndBeforeT2() {
        Long ticketId = givenApprovedTicket("zhangsan");
        recordingExecutor.inspectTicket(ticketId);
        recordingExecutor.behave(c -> ProvisionResult.success(ProvisionResult.ProvisionErrorCode.PROVISIONED, "ext"));

        ExecuteTicketResult result = executionService.execute(ticketId);

        // 执行器被调用那一刻，从新连接读到的是 T1 已提交、T2 尚未写入的状态
        assertThat(recordingExecutor.observedTicketStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(recordingExecutor.observedExecutionResult()).isEqualTo(ExecutionResult.PENDING);

        // 结束后 T2 提交
        assertThat(result.result()).isEqualTo(ExecutionResult.SUCCESS);
        assertThat(ticketMapper.selectById(ticketId).getStatus()).isEqualTo(TicketStatus.RESOLVED);
    }

    // ─────────────────────────── 6. 结果不确定（UNCERTAIN） ───────────────────────────

    @Test
    @DisplayName("结果不确定：首次执行 → UNCERTAIN，工单回到 APPROVED，记录与状态一致；再次执行被拒绝且不再调用执行器")
    void shouldRecordUncertainAndRejectSubsequentExecution() {
        Long ticketId = givenApprovedTicket("uncertain-user");
        recordingExecutor.behave(c -> ProvisionResult.uncertain(ProvisionResult.ProvisionErrorCode.TARGET_TIMEOUT, null));

        ExecuteTicketResult result = executionService.execute(ticketId);

        assertThat(result.ticketId()).isEqualTo(ticketId);
        assertThat(result.attemptNo()).isEqualTo(1);
        assertThat(result.result()).isEqualTo(ExecutionResult.UNCERTAIN);
        assertThat(result.message()).isEqualTo("目标系统无响应，无法确认账号是否已开通，请人工核实");

        // 工单回到 APPROVED，执行记录落库为 UNCERTAIN，且带安全 errorMessage。
        assertThat(ticketMapper.selectById(ticketId).getStatus()).isEqualTo(TicketStatus.APPROVED);
        List<TicketExecution> records = executionMapper.selectList(
                new QueryWrapper<TicketExecution>().eq("ticket_id", ticketId));
        assertThat(records).hasSize(1);
        TicketExecution record = records.get(0);
        assertThat(record.getResult()).isEqualTo(ExecutionResult.UNCERTAIN);
        assertThat(record.getAttemptNo()).isEqualTo(1);
        assertThat(record.getFinishedAt()).isNotNull();
        assertThat(record.getErrorMessage()).isEqualTo("目标系统无响应，无法确认账号是否已开通，请人工核实");

        // 执行器只被调用一次；再次执行因存在未核实的 UNCERTAIN 记录被拒绝，且不再调用执行器、不新增记录。
        assertThat(recordingExecutor.invocationCount()).isEqualTo(1);
        assertThatThrownBy(() -> executionService.execute(ticketId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未核实");
        assertThat(recordingExecutor.invocationCount()).isEqualTo(1);
        assertThat(executionMapper.selectCount(new QueryWrapper<TicketExecution>().eq("ticket_id", ticketId)))
                .isEqualTo(1L);
        assertThat(ticketMapper.selectById(ticketId).getStatus()).isEqualTo(TicketStatus.APPROVED);
    }

    // ─────────────────────────── 测试配置与辅助 ───────────────────────────

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableTransactionManagement
    @MapperScan("com.argus.rag.ticket.mapper")
    @Import({TicketExecutionService.class, TicketExecutionTransactionService.class, TicketConfiguration.class})
    static class Config {

        @Bean
        RecordingExecutor recordingExecutor(TicketMapper ticketMapper, TicketExecutionMapper executionMapper) {
            return new RecordingExecutor(ticketMapper, executionMapper);
        }
    }

    /**
     * 可编程执行器：按测试设定返回 SUCCESS/FAILED/UNCERTAIN 或抛异常；
     * 可选在调用时通过真实 Mapper 抓取 DB 状态（用于验证执行器调用落在 T1 提交之后）。
     */
    static class RecordingExecutor implements AccountProvisioningExecutor {

        private final TicketMapper ticketMapper;
        private final TicketExecutionMapper executionMapper;
        private volatile Function<ProvisionCommand, ProvisionResult> behavior =
                c -> ProvisionResult.success(ProvisionResult.ProvisionErrorCode.PROVISIONED, "ext-ref");
        private volatile Long inspectTicketId;
        private volatile TicketStatus observedTicketStatus;
        private volatile ExecutionResult observedExecutionResult;
        private final CopyOnWriteArrayList<ProvisionCommand> invocations = new CopyOnWriteArrayList<>();

        RecordingExecutor(TicketMapper ticketMapper, TicketExecutionMapper executionMapper) {
            this.ticketMapper = ticketMapper;
            this.executionMapper = executionMapper;
        }

        void behave(Function<ProvisionCommand, ProvisionResult> behavior) {
            this.behavior = behavior;
        }

        void inspectTicket(Long ticketId) {
            this.inspectTicketId = ticketId;
        }

        void reset() {
            this.behavior = c -> ProvisionResult.success(ProvisionResult.ProvisionErrorCode.PROVISIONED, "ext-ref");
            this.inspectTicketId = null;
            this.observedTicketStatus = null;
            this.observedExecutionResult = null;
            this.invocations.clear();
        }

        int invocationCount() {
            return invocations.size();
        }

        TicketStatus observedTicketStatus() {
            return observedTicketStatus;
        }

        ExecutionResult observedExecutionResult() {
            return observedExecutionResult;
        }

        @Override
        public ProvisionResult execute(ProvisionCommand command) {
            invocations.add(command);
            Long ticketId = inspectTicketId;
            if (ticketId != null) {
                observedTicketStatus = ticketMapper.selectById(ticketId).getStatus();
                List<TicketExecution> records = executionMapper.selectList(
                        new QueryWrapper<TicketExecution>().eq("ticket_id", ticketId).orderByDesc("attempt_no"));
                observedExecutionResult = records.isEmpty() ? null : records.get(0).getResult();
            }
            return behavior.apply(command);
        }
    }

    private Long givenApprovedTicket(String targetUsername) {
        Ticket ticket = new Ticket();
        ticket.setTicketNo("TK-IT-" + SEQ.incrementAndGet());
        ticket.setTitle("开通账号申请");
        ticket.setType(TicketType.ACCOUNT_PROVISIONING);
        ticket.setStatus(TicketStatus.APPROVED);
        ticket.setCreatorUserId(1L);
        ticket.setSystemName("erp");
        ticket.setTargetUsername(targetUsername);
        ticket.setPermissionLevel(PermissionLevel.READ_ONLY);
        ticket.setVersion(0);
        ticket.setIdempotencyKey("CREATE-IT-" + SEQ.incrementAndGet());
        ticketMapper.insert(ticket);
        Long ticketId = ticket.getId();

        ApprovalRequest approval = new ApprovalRequest();
        approval.setTicketId(ticketId);
        approval.setApproverUserId(2L);
        approval.setResult(ApprovalResult.APPROVED);
        approval.setComment("同意");
        approval.setDecidedAt(LocalDateTime.now());
        approvalRequestMapper.insert(approval);
        return ticketId;
    }

    private TicketExecution pendingRecord(Long ticketId, int attemptNo) {
        TicketExecution execution = new TicketExecution();
        execution.setTicketId(ticketId);
        execution.setExecutionType(ExecutionType.PROVISION_ACCOUNT);
        execution.setResult(ExecutionResult.PENDING);
        execution.setAttemptNo(attemptNo);
        execution.setIdempotencyKey("PROVISION_ACCOUNT:" + ticketId);
        execution.setOperatorUserId(1L);
        execution.setStartedAt(LocalDateTime.now());
        return execution;
    }

    private void insertFailedExecution(Long ticketId, int attemptNo) {
        TicketExecution record = new TicketExecution();
        record.setTicketId(ticketId);
        record.setExecutionType(ExecutionType.PROVISION_ACCOUNT);
        record.setResult(ExecutionResult.FAILED);
        record.setAttemptNo(attemptNo);
        record.setIdempotencyKey("PROVISION_ACCOUNT:" + ticketId);
        record.setOperatorUserId(1L);
        record.setStartedAt(LocalDateTime.now());
        record.setFinishedAt(LocalDateTime.now());
        executionMapper.insert(record);
    }

    private static CurrentUserService.CurrentUser admin(long id) {
        return new CurrentUserService.CurrentUser(id, "u" + id, "管理员" + id, SystemRole.ADMIN, false);
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
}
