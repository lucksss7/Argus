package com.argus.rag.ticket;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.ticket.config.TicketConfiguration;
import com.argus.rag.ticket.executor.AccountProvisioningExecutor;
import com.argus.rag.ticket.executor.ProvisionResult;
import com.argus.rag.ticket.mapper.ApprovalRequestMapper;
import com.argus.rag.ticket.mapper.TicketDispositionMapper;
import com.argus.rag.ticket.mapper.TicketExecutionMapper;
import com.argus.rag.ticket.mapper.TicketMapper;
import com.argus.rag.ticket.model.dto.DispositionRequest;
import com.argus.rag.ticket.model.entity.ApprovalRequest;
import com.argus.rag.ticket.model.entity.Ticket;
import com.argus.rag.ticket.model.entity.TicketDisposition;
import com.argus.rag.ticket.model.entity.TicketExecution;
import com.argus.rag.ticket.model.enums.ApprovalResult;
import com.argus.rag.ticket.model.enums.DispositionType;
import com.argus.rag.ticket.model.enums.ExecutionResult;
import com.argus.rag.ticket.model.enums.ExecutionType;
import com.argus.rag.ticket.model.enums.ExecutionVerification;
import com.argus.rag.ticket.model.enums.PermissionLevel;
import com.argus.rag.ticket.model.enums.TicketStatus;
import com.argus.rag.ticket.model.enums.TicketType;
import com.argus.rag.ticket.model.vo.ExecuteTicketResult;
import com.argus.rag.ticket.service.TicketDispositionService;
import com.argus.rag.ticket.service.TicketExecutionService;
import com.argus.rag.ticket.service.TicketExecutionTransactionService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
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

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 工单人工处置的真实 PostgreSQL 集成测试。
 * <p>
 * 覆盖三个此前单元测试无法证明的风险：
 * <ul>
 *   <li>R1/R2：{@code CONFIRM_SUCCESS} 的「核验更新 + 状态流转 + 审计」是否同一事务、失败是否整体回滚；</li>
 *   <li>C1/C2：{@code RETRY_AUTHORIZE} 与执行 {@code APPROVED -> IN_PROGRESS} 的行锁串行化、额外额度不丢失/不双算、执行不超上限；</li>
 *   <li>M1：{@code updateVerification} / {@code incrementExtraAttempts} / {@code countUncertainByTicketId} 三个 Mapper 的真实 SQL 与参数映射。</li>
 * </ul>
 * <p>
 * 运行前置：独立测试库 {@code argus_rag_test} 已由 Flyway 应用 V2，且仅当设置环境变量
 * {@code ARGUS_INTEGRATION=true} 时激活；每个用例前先经 {@link #requireTestDatabaseBeforeCleanup()}
 * 严格校验实际 DataSource 指向 {@code localhost:5433/argus_rag_test}，绝不触碰开发库 {@code argus_rag}。
 * </p>
 */
@SpringBootTest(classes = TicketDispositionIntegrationTest.Config.class)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "ARGUS_INTEGRATION", matches = "true")
@DisplayName("工单人工处置集成测试（真实 PostgreSQL + Spring 事务代理）")
class TicketDispositionIntegrationTest {

    @Autowired
    private TicketDispositionService dispositionService;

    @Autowired
    private TicketExecutionService executionService;

    @Autowired
    private TicketMapper ticketMapper;

    @Autowired
    private TicketExecutionMapper executionMapper;

    @Autowired
    private TicketDispositionMapper dispositionMapper;

    @Autowired
    private ApprovalRequestMapper approvalRequestMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager txManager;

    @MockitoBean
    private CurrentUserService currentUserService;

    private static final AtomicLong SEQ = new AtomicLong();

    @BeforeEach
    void setUp() {
        // 数据库安全守卫：任何破坏性清理前，先严格校验实际 DataSource JDBC URL 指向 argus_rag_test:5433。
        requireTestDatabaseBeforeCleanup();
        // 独立测试库 + 独立工单：清空工单域四张表保证可重复运行（RESTART IDENTITY 复位自增序列）。
        jdbcTemplate.execute(
                "TRUNCATE TABLE ticket_dispositions, ticket_executions, approval_requests, tickets RESTART IDENTITY CASCADE");
        when(currentUserService.requireSystemAdmin()).thenReturn(admin(1L));
    }

    // ─────────────────────────── 风险 1：真实事务回滚 ───────────────────────────

    @Test
    @DisplayName("R1：确认成功的核验+状态+审计在同一事务，外层回滚后三者均不残留")
    void confirmSuccessWritesRollBackTogether() {
        Long ticketId = givenApprovedTicket("r1-user");
        Long executionId = givenUncertainExecution(ticketId, 1);

        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            dispositionService.dispose(ticketId,
                    new DispositionRequest(DispositionType.CONFIRM_SUCCESS, executionId, "确认成功"));
            status.setRollbackOnly(); // 强制回滚整个事务（含 dispose 的三处写入）
        });

        // 独立新事务回读：核验字段、审计、工单状态均无残留
        TicketExecution record = executionMapper.selectById(executionId);
        assertThat(record.getVerificationStatus()).isNull();
        assertThat(record.getVerifiedBy()).isNull();
        assertThat(record.getVerifiedAt()).isNull();
        assertThat(dispositionMapper.selectCount(
                new QueryWrapper<TicketDisposition>().eq("ticket_id", ticketId))).isZero();
        assertThat(ticketMapper.selectById(ticketId).getStatus()).isEqualTo(TicketStatus.APPROVED);
    }

    @Test
    @DisplayName("R2：核验更新成功后状态更新命中 0 行 → 事务整体回滚，不残留核验/审计")
    void confirmSuccessRollsBackVerificationWhenTicketUpdateFails() throws Exception {
        Long ticketId = givenApprovedTicket("r2-user");
        Long executionId = givenUncertainExecution(ticketId, 1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch lockedAndChanged = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        // 锁线程 L：先对工单行加 FOR UPDATE 行锁，再（未提交地）把状态改为 ABORTED，持锁等待放行。
        Future<?> lockHolder = pool.submit(() -> {
            new TransactionTemplate(txManager).executeWithoutResult(status -> {
                jdbcTemplate.queryForObject(
                        "SELECT id FROM tickets WHERE id = ? FOR UPDATE", Long.class, ticketId);
                jdbcTemplate.update(
                        "UPDATE tickets SET status = 'ABORTED', updated_at = now() WHERE id = ? AND status = 'APPROVED'",
                        ticketId);
                lockedAndChanged.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("R2：锁线程等待放行超时");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("R2：锁线程被中断", e);
                }
            });
            return null;
        });

        assertThat(lockedAndChanged.await(10, TimeUnit.SECONDS))
                .as("锁线程未在预期时间内取得 tickets 行锁").isTrue();

        // dispose 线程 D：其 requireApprovedTicket 的 SELECT 读到已提交的 APPROVED（L 的 ABORTED 未提交不可见），
        // updateVerification 成功后，updateTicketStatus 会阻塞在 L 持有的 tickets 行锁上。
        Future<?> disposer = pool.submit(() -> {
            dispositionService.dispose(ticketId,
                    new DispositionRequest(DispositionType.CONFIRM_SUCCESS, executionId, "确认成功"));
            return null;
        });

        try {
            // 可靠同步：探测执行记录行的行锁。updateVerification 一旦执行就会锁定该行；
            // 探测 FOR UPDATE 发生锁等待超时（55P03）即证明 D 已实际执行核验更新，而非依赖固定 sleep。
            assertThat(awaitExecutionRowLocked(executionId, 5))
                    .as("dispose 未在预期时间内执行核验更新（未观察到执行记录行被锁定）").isTrue();

            // 放行 L：提交 ABORTED、释放 tickets 行锁；D 的 updateTicketStatus 随后命中 0 行 → 抛异常回滚。
            release.countDown();

            Throwable disposeCause = null;
            try {
                disposer.get(15, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                disposeCause = e.getCause();
            }
            assertThat(disposeCause).isInstanceOf(BusinessException.class);
            assertThat(disposeCause.getMessage()).contains("处置未生效");

            // L 正常提交完成
            lockHolder.get(15, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }

        // 独立新事务回读：核验写入已随 dispose 事务回滚，无审计残留，工单状态为 L 提交的 ABORTED。
        TicketExecution record = executionMapper.selectById(executionId);
        assertThat(record.getVerificationStatus()).isNull();
        assertThat(record.getVerifiedBy()).isNull();
        assertThat(record.getVerifiedAt()).isNull();
        assertThat(dispositionMapper.selectCount(
                new QueryWrapper<TicketDisposition>().eq("ticket_id", ticketId))).isZero();
        assertThat(ticketMapper.selectById(ticketId).getStatus()).isEqualTo(TicketStatus.ABORTED);
    }

    // ─────────────────────────── 风险 2：真实并发与行锁 ───────────────────────────

    @Test
    @DisplayName("C1：先授权重试再执行，begin 锁后重读 latest extra，第 4 次按新上限放行")
    void retryAuthorizeThenExecuteAllowsAttemptBeyondBaseLimit() {
        Long ticketId = givenApprovedTicket("c1-user");
        insertFailedExecution(ticketId, 1);
        insertFailedExecution(ticketId, 2);
        insertFailedExecution(ticketId, 3);

        dispositionService.dispose(ticketId,
                new DispositionRequest(DispositionType.RETRY_AUTHORIZE, null, "授权重试一次"));

        ExecuteTicketResult result = executionService.execute(ticketId);

        assertThat(result.attemptNo()).isEqualTo(4);
        assertThat(result.result()).isEqualTo(ExecutionResult.SUCCESS);
        assertThat(ticketMapper.selectById(ticketId).getStatus()).isEqualTo(TicketStatus.RESOLVED);
        assertThat(ticketMapper.selectById(ticketId).getExtraAttempts()).isEqualTo(1);

        List<TicketExecution> records = executionMapper.selectList(
                new QueryWrapper<TicketExecution>().eq("ticket_id", ticketId).orderByAsc("attempt_no"));
        assertThat(records).hasSize(4);
        assertThat(records.get(3).getAttemptNo()).isEqualTo(4);

        assertThat(dispositionMapper.selectCount(
                new QueryWrapper<TicketDisposition>().eq("ticket_id", ticketId)
                        .eq("disposition_type", DispositionType.RETRY_AUTHORIZE.name()))).isEqualTo(1L);
    }

    @Test
    @DisplayName("C2：RETRY_AUTHORIZE 与 APPROVED->IN_PROGRESS 并发，授权不丢失/不双算、执行不超上限")
    void retryAuthorizeConcurrentWithExecuteIsConsistent() throws Exception {
        // 多轮以覆盖不同调度；断言与顺序无关，故非 flaky。
        for (int round = 0; round < 5; round++) {
            Long ticketId = givenApprovedTicket("c2-user-" + round);
            insertFailedExecution(ticketId, 1);
            insertFailedExecution(ticketId, 2);
            insertFailedExecution(ticketId, 3);

            int threadCount = 2;
            ExecutorService pool = Executors.newFixedThreadPool(threadCount);
            CountDownLatch ready = new CountDownLatch(threadCount);
            CountDownLatch start = new CountDownLatch(1);

            Future<ExecuteTicketResult> executeFuture = pool.submit(() -> {
                ready.countDown();
                start.await();
                return executionService.execute(ticketId);
            });
            Future<?> retryFuture = pool.submit(() -> {
                ready.countDown();
                start.await();
                dispositionService.dispose(ticketId,
                        new DispositionRequest(DispositionType.RETRY_AUTHORIZE, null, "并发授权"));
                return null;
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            ExecuteTicketResult executeResult = null;
            Throwable executeFailure = null;
            Throwable retryFailure = null;
            try {
                try {
                    executeResult = executeFuture.get(15, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    executeFailure = e.getCause();
                }
                try {
                    retryFuture.get(15, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    retryFailure = e.getCause();
                }
            } finally {
                pool.shutdownNow();
                pool.awaitTermination(10, TimeUnit.SECONDS);
            }

            // 联合断言 1：额外额度不丢失、不双算 —— 恒为 1。
            assertThat(ticketMapper.selectById(ticketId).getExtraAttempts()).isEqualTo(1);

            // 联合断言 2：审计 —— 恰一条 RETRY_AUTHORIZE，无其他处置审计。
            assertThat(dispositionMapper.selectCount(
                    new QueryWrapper<TicketDisposition>().eq("ticket_id", ticketId)
                            .eq("disposition_type", DispositionType.RETRY_AUTHORIZE.name()))).isEqualTo(1L);
            assertThat(dispositionMapper.selectCount(
                    new QueryWrapper<TicketDisposition>().eq("ticket_id", ticketId))).isEqualTo(1L);

            // 联合断言 3：在所有合法交织下，RETRY_AUTHORIZE 都应成功（先提交，或在 execute 回滚后提交）。
            assertThat(retryFailure).as("RETRY_AUTHORIZE 应在所有合法交织下成功").isNull();

            List<TicketExecution> records = executionMapper.selectList(
                    new QueryWrapper<TicketExecution>().eq("ticket_id", ticketId).orderByAsc("attempt_no"));

            // 联合断言 4/5：执行侧只有两种合法结局，且与授权额度、最终状态强一致。
            if (executeFailure == null) {
                // 授权先生效：execute 读到 extra=1，按新上限放行第 4 次。
                assertThat(executeResult.result()).isEqualTo(ExecutionResult.SUCCESS);
                assertThat(executeResult.attemptNo()).isEqualTo(4);
                assertThat(records).hasSize(4);
                assertThat(records.get(3).getAttemptNo()).isEqualTo(4);
                assertThat(records.get(3).getResult()).isEqualTo(ExecutionResult.SUCCESS);
                assertThat(ticketMapper.selectById(ticketId).getStatus()).isEqualTo(TicketStatus.RESOLVED);
            } else {
                // execute 先拿到行锁、读到 extra=0，第 4 次超上限被拒（T1 回滚），授权随后生效。
                assertThat(executeFailure).isInstanceOf(BusinessException.class);
                assertThat(executeFailure.getMessage()).contains("执行次数已达上限");
                assertThat(records).hasSize(3);
                assertThat(records).allSatisfy(r -> assertThat(r.getAttemptNo()).isLessThanOrEqualTo(3));
                assertThat(ticketMapper.selectById(ticketId).getStatus()).isEqualTo(TicketStatus.APPROVED);
            }
        }
    }

    // ─────────────────────────── 风险 3：Mapper SQL 与参数映射 ───────────────────────────

    @Test
    @DisplayName("M1：updateVerification / incrementExtraAttempts / countUncertainByTicketId 真实执行与映射")
    void mapperSqlRoundTripForDisposition() {
        Long ticketId = givenApprovedTicket("m1-user");

        // ---- countUncertainByTicketId：只统计未核验的 UNCERTAIN（verification_status IS NULL）----
        assertThat(executionMapper.countUncertainByTicketId(ticketId)).isZero();

        Long unverifiedId = givenUncertainExecution(ticketId, 1);
        TicketExecution verifiedUncertain = uncertainRecord(ticketId, 2);
        verifiedUncertain.setVerificationStatus(ExecutionVerification.CONFIRMED_NOT_EXECUTED);
        verifiedUncertain.setVerifiedBy(1L);
        verifiedUncertain.setVerifiedAt(LocalDateTime.now());
        executionMapper.insert(verifiedUncertain);
        TicketExecution success = successRecord(ticketId, 3);
        executionMapper.insert(success);

        // 仅未核验的 UNCERTAIN 计入
        assertThat(executionMapper.countUncertainByTicketId(ticketId)).isEqualTo(1L);

        // ---- updateVerification：正向 / 重复 / 结果非 UNCERTAIN ----
        LocalDateTime verifiedAt = LocalDateTime.now();
        assertThat(executionMapper.updateVerification(unverifiedId, "CONFIRMED_SUCCESS", 1L, verifiedAt)).isEqualTo(1);
        TicketExecution after = executionMapper.selectById(unverifiedId);
        assertThat(after.getVerificationStatus()).isEqualTo(ExecutionVerification.CONFIRMED_SUCCESS);
        assertThat(after.getVerifiedBy()).isEqualTo(1L);
        assertThat(after.getVerifiedAt()).isNotNull();
        // 原始 result 不被覆盖
        assertThat(after.getResult()).isEqualTo(ExecutionResult.UNCERTAIN);

        // 已核验 → 条件更新拦截（0 行）
        assertThat(executionMapper.updateVerification(unverifiedId, "CONFIRMED_NOT_EXECUTED", 2L, LocalDateTime.now()))
                .isZero();
        // 结果非 UNCERTAIN → 条件更新拦截（0 行）
        assertThat(executionMapper.updateVerification(success.getId(), "CONFIRMED_SUCCESS", 1L, LocalDateTime.now()))
                .isZero();

        // 核验后未核验 UNCERTAIN 计数归零
        assertThat(executionMapper.countUncertainByTicketId(ticketId)).isZero();

        // ---- incrementExtraAttempts：自增 + 非 APPROVED 拒绝 ----
        assertThat(ticketMapper.selectById(ticketId).getExtraAttempts()).isZero();
        assertThat(ticketMapper.incrementExtraAttempts(ticketId)).isEqualTo(1);
        assertThat(ticketMapper.selectById(ticketId).getExtraAttempts()).isEqualTo(1);
        assertThat(ticketMapper.incrementExtraAttempts(ticketId)).isEqualTo(1);
        assertThat(ticketMapper.selectById(ticketId).getExtraAttempts()).isEqualTo(2);

        // 非 APPROVED：置为 IN_PROGRESS 后递增应失败且不改变额度
        ticketMapper.updateTicketStatus(ticketId, TicketStatus.APPROVED.name(), TicketStatus.IN_PROGRESS.name());
        assertThat(ticketMapper.incrementExtraAttempts(ticketId)).isZero();
        assertThat(ticketMapper.selectById(ticketId).getExtraAttempts()).isEqualTo(2);
    }

    // ─────────────────────────── 测试配置 ───────────────────────────

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableTransactionManagement
    @MapperScan("com.argus.rag.ticket.mapper")
    @Import({TicketDispositionService.class, TicketExecutionService.class,
            TicketExecutionTransactionService.class, TicketConfiguration.class})
    static class Config {

        @Bean
        AccountProvisioningExecutor accountProvisioningExecutor() {
            return command -> ProvisionResult.success(ProvisionResult.ProvisionErrorCode.PROVISIONED, "it-ext");
        }
    }

    // ─────────────────────────── 同步与数据辅助 ───────────────────────────

    /**
     * 有界探测执行记录行的行锁，确认 dispose 线程已实际执行 {@code updateVerification}。
     * <p>{@code updateVerification} 会锁定该执行记录行；若探测的 {@code SELECT ... FOR UPDATE}
     * 发生锁等待超时（SQLSTATE 55P03），说明该行已被 dispose 事务锁定，即核验更新已真正执行，
     * 而非依赖固定 sleep 推断。</p>
     */
    private boolean awaitExecutionRowLocked(Long executionId, long timeoutSeconds)
            throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    try (Statement st = conn.createStatement()) {
                        st.execute("SET LOCAL lock_timeout = '200ms'");
                        try (ResultSet ignored = st.executeQuery(
                                "SELECT id FROM ticket_executions WHERE id = " + executionId + " FOR UPDATE")) {
                            // 成功取得锁：该执行记录行尚未被 updateVerification 锁定。
                        }
                    }
                } catch (SQLException e) {
                    if ("55P03".equals(e.getSQLState())) {
                        return true; // 锁等待超时 → updateVerification 已执行并锁定该行。
                    }
                    throw e;
                } finally {
                    conn.rollback();
                }
            }
            Thread.sleep(50);
        }
        return false;
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

    private Long givenUncertainExecution(Long ticketId, int attemptNo) {
        TicketExecution record = uncertainRecord(ticketId, attemptNo);
        executionMapper.insert(record);
        return record.getId();
    }

    private TicketExecution uncertainRecord(Long ticketId, int attemptNo) {
        TicketExecution record = new TicketExecution();
        record.setTicketId(ticketId);
        record.setExecutionType(ExecutionType.PROVISION_ACCOUNT);
        record.setResult(ExecutionResult.UNCERTAIN);
        record.setAttemptNo(attemptNo);
        record.setIdempotencyKey("PROVISION_ACCOUNT:" + ticketId);
        record.setOperatorUserId(1L);
        record.setStartedAt(LocalDateTime.now());
        record.setFinishedAt(LocalDateTime.now());
        return record;
    }

    private TicketExecution successRecord(Long ticketId, int attemptNo) {
        TicketExecution record = uncertainRecord(ticketId, attemptNo);
        record.setResult(ExecutionResult.SUCCESS);
        return record;
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
}
