package com.argus.rag.ticket.service;

import com.argus.rag.auth.CurrentUserService;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ForbiddenException;
import com.argus.rag.ticket.mapper.TicketExecutionMapper;
import com.argus.rag.ticket.mapper.TicketMapper;
import com.argus.rag.ticket.model.dto.CreateTicketRequest;
import com.argus.rag.ticket.model.entity.Ticket;
import com.argus.rag.ticket.model.entity.TicketExecution;
import com.argus.rag.ticket.model.enums.ExecutionResult;
import com.argus.rag.ticket.model.enums.ExecutionType;
import com.argus.rag.ticket.model.enums.PermissionLevel;
import com.argus.rag.ticket.model.enums.TicketStatus;
import com.argus.rag.ticket.model.enums.TicketType;
import com.argus.rag.ticket.model.vo.CreateTicketResult;
import com.argus.rag.ticket.model.vo.ExecutionRecordVO;
import com.argus.rag.ticket.model.vo.TicketDetailResponse;
import com.argus.rag.ticket.model.vo.TicketDetailToolVO;
import com.argus.rag.ticket.model.vo.TicketListResult;
import com.argus.rag.ticket.model.vo.TicketSummaryVO;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.SharedString;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("工单服务")
class TicketServiceTest {

    @Mock
    private TicketMapper ticketMapper;

    @Mock
    private TicketExecutionMapper ticketExecutionMapper;

    @Mock
    private CurrentUserService currentUserService;

    private TicketService service() {
        return new TicketService(ticketMapper, ticketExecutionMapper, currentUserService);
    }

    @Test
    @DisplayName("创建工单应从登录态取创建人，初始状态为 OPEN")
    void shouldCreateTicketFromAuthenticatedCreator() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user(5L));
        when(ticketMapper.selectOne(any())).thenReturn(null);
        when(ticketMapper.insertIgnoreConflict(any(Ticket.class))).thenReturn(1);

        CreateTicketResult result = service().createTicket(validRequest());

        assertThat(result.alreadyExists()).isFalse();
        assertThat(result.ticket().ticketNo()).isNotBlank();
        assertThat(result.ticket().status()).isEqualTo(TicketStatus.OPEN);
        assertThat(result.ticket().version()).isZero();
        assertThat(result.ticket().creatorUserId()).isEqualTo(5L);

        ArgumentCaptor<Ticket> captor = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketMapper).insertIgnoreConflict(captor.capture());
        Ticket saved = captor.getValue();
        assertThat(saved.getCreatorUserId()).isEqualTo(5L);
        assertThat(saved.getStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(saved.getVersion()).isZero();
        assertThat(saved.getTicketNo()).isNotBlank();
        assertThat(saved.getSystemName()).isEqualTo("erp");
        assertThat(saved.getTargetUsername()).isEqualTo("zhangsan");
        assertThat(saved.getPermissionLevel()).isEqualTo(PermissionLevel.READ_ONLY);
    }

    @Test
    @DisplayName("相同幂等键且内容一致时应返回已有工单")
    void shouldReturnExistingTicketOnIdempotentDuplicate() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user(5L));
        when(ticketMapper.selectOne(any())).thenReturn(existingTicket(42L, 5L, "zhangsan"));

        CreateTicketResult result = service().createTicket(validRequest());

        assertThat(result.alreadyExists()).isTrue();
        assertThat(result.ticket().id()).isEqualTo(42L);
        verify(ticketMapper, never()).insertIgnoreConflict(any(Ticket.class));
    }

    @Test
    @DisplayName("相同幂等键但内容不一致时应拒绝")
    void shouldRejectIdempotentKeyWithDifferentContent() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user(5L));
        when(ticketMapper.selectOne(any())).thenReturn(existingTicket(42L, 5L, "lisi"));

        assertThatThrownBy(() -> service().createTicket(validRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不一致");
    }

    @Test
    @DisplayName("相同幂等键但标题不同应拒绝")
    void shouldRejectIdempotentKeyWithDifferentTitle() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user(5L));
        Ticket existing = existingTicket(42L, 5L, "zhangsan");
        existing.setTitle("完全不同的标题");
        when(ticketMapper.selectOne(any())).thenReturn(existing);

        assertThatThrownBy(() -> service().createTicket(validRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不一致");
    }

    @Test
    @DisplayName("相同幂等键但优先级不同应拒绝")
    void shouldRejectIdempotentKeyWithDifferentPriority() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user(5L));
        Ticket existing = existingTicket(42L, 5L, "zhangsan");
        existing.setPriority("HIGH");
        when(ticketMapper.selectOne(any())).thenReturn(existing);
        CreateTicketRequest requestWithPriority = new CreateTicketRequest(
                "key-1", TicketType.ACCOUNT_PROVISIONING, "erp", "zhangsan",
                PermissionLevel.READ_ONLY, "需要只读账号", "账号开通申请", "LOW");

        assertThatThrownBy(() -> service().createTicket(requestWithPriority))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不一致");
    }

    @Test
    @DisplayName("并发插入触发幂等键唯一约束时应复用已有工单")
    void shouldHandleConcurrentInsertRace() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user(5L));
        when(ticketMapper.selectOne(any()))
                .thenReturn(null, existingTicket(43L, 5L, "zhangsan"));
        when(ticketMapper.insertIgnoreConflict(any(Ticket.class))).thenReturn(0);

        CreateTicketResult result = service().createTicket(validRequest());

        assertThat(result.alreadyExists()).isTrue();
        assertThat(result.ticket().id()).isEqualTo(43L);
    }

    @Test
    @DisplayName("工单号唯一约束冲突时应重新生成工单号重试，而非复用其他工单")
    void shouldRetryWithNewTicketNoOnTicketNoCollision() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user(5L));
        when(ticketMapper.selectOne(any())).thenReturn(null);
        List<String> generatedTicketNos = new ArrayList<>();
        when(ticketMapper.insertIgnoreConflict(any(Ticket.class))).thenAnswer(invocation -> {
            Ticket ticket = invocation.getArgument(0);
            generatedTicketNos.add(ticket.getTicketNo());
            return generatedTicketNos.size() == 1 ? 0 : 1; // 首次撞号，换号后成功
        });

        CreateTicketResult result = service().createTicket(validRequest());

        assertThat(result.alreadyExists()).isFalse();
        assertThat(generatedTicketNos).hasSize(2);
        assertThat(generatedTicketNos.get(0)).isNotEqualTo(generatedTicketNos.get(1));
        assertThat(result.ticket().ticketNo()).isEqualTo(generatedTicketNos.get(1));
    }

    @Test
    @DisplayName("并发插入触发幂等键冲突但内容不一致时应拒绝")
    void shouldRejectIdempotentKeyConflictWithDifferentContent() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user(5L));
        when(ticketMapper.selectOne(any())).thenReturn(null, existingTicket(43L, 5L, "lisi"));
        when(ticketMapper.insertIgnoreConflict(any(Ticket.class))).thenReturn(0);

        assertThatThrownBy(() -> service().createTicket(validRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不一致");
    }

    @Test
    @DisplayName("工单号持续冲突达到重试上限后抛受控业务异常")
    void shouldThrowWhenTicketNoRetriesExhausted() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user(5L));
        when(ticketMapper.selectOne(any())).thenReturn(null);
        when(ticketMapper.insertIgnoreConflict(any(Ticket.class))).thenReturn(0);

        assertThatThrownBy(() -> service().createTicket(validRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("工单号");

        // MAX_TICKET_NO_RETRIES = 3：初次插入 + 3 次额外重试，共 4 次 INSERT。
        verify(ticketMapper, times(4)).insertIgnoreConflict(any(Ticket.class));
    }

    @Test
    @DisplayName("缺失幂等键应拒绝")
    void shouldRejectMissingIdempotencyKey() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user(5L));
        CreateTicketRequest request = new CreateTicketRequest(
                "  ", TicketType.ACCOUNT_PROVISIONING, "erp", "zhangsan",
                PermissionLevel.READ_ONLY, "需要只读账号", "账号开通申请", null);

        assertThatThrownBy(() -> service().createTicket(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("幂等键");
    }

    @Test
    @DisplayName("缺失必填业务字段应拒绝")
    void shouldRejectMissingRequiredBusinessFields() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user(5L));

        assertThatThrownBy(() -> service().createTicket(new CreateTicketRequest(
                "key-1", TicketType.ACCOUNT_PROVISIONING, " ", "zhangsan",
                PermissionLevel.READ_ONLY, "需要只读账号", "账号开通申请", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("目标系统");
        assertThatThrownBy(() -> service().createTicket(new CreateTicketRequest(
                "key-1", TicketType.ACCOUNT_PROVISIONING, "erp", " ",
                PermissionLevel.READ_ONLY, "需要只读账号", "账号开通申请", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("目标用户名");
        assertThatThrownBy(() -> service().createTicket(new CreateTicketRequest(
                "key-1", TicketType.ACCOUNT_PROVISIONING, "erp", "zhangsan",
                PermissionLevel.READ_ONLY, " ", "账号开通申请", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("申请原因");
        assertThatThrownBy(() -> service().createTicket(new CreateTicketRequest(
                "key-1", TicketType.ACCOUNT_PROVISIONING, "erp", "zhangsan",
                PermissionLevel.READ_ONLY, "需要只读账号", " ", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("标题");
    }

    @Test
    @DisplayName("缺失类型或权限级别应拒绝")
    void shouldRejectMissingTypeOrPermissionLevel() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user(5L));

        assertThatThrownBy(() -> service().createTicket(new CreateTicketRequest(
                "key-1", null, "erp", "zhangsan",
                PermissionLevel.READ_ONLY, "需要只读账号", "账号开通申请", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("工单类型");
        assertThatThrownBy(() -> service().createTicket(new CreateTicketRequest(
                "key-1", TicketType.ACCOUNT_PROVISIONING, "erp", "zhangsan",
                null, "需要只读账号", "账号开通申请", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("权限级别");
    }

    @Test
    @DisplayName("字段超长应拒绝")
    void shouldRejectOverLengthFields() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user(5L));
        CreateTicketRequest request = new CreateTicketRequest(
                "key-1", TicketType.ACCOUNT_PROVISIONING, "e".repeat(101), "zhangsan",
                PermissionLevel.READ_ONLY, "需要只读账号", "账号开通申请", null);

        assertThatThrownBy(() -> service().createTicket(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("目标系统");
    }

    // ─────────────────────────── 显式身份建单 ───────────────────────────

    @Test
    @DisplayName("显式身份建单：普通用户身份建单成功，初始 OPEN，且不读取 ThreadLocal")
    void shouldCreateTicketForWithExplicitUserIdentity() {
        when(ticketMapper.selectOne(any())).thenReturn(null);
        when(ticketMapper.insertIgnoreConflict(any(Ticket.class))).thenReturn(1);

        CreateTicketResult result = service().createTicketFor(5L, SystemRole.USER, validRequest());

        assertThat(result.alreadyExists()).isFalse();
        assertThat(result.ticket().status()).isEqualTo(TicketStatus.OPEN);
        assertThat(result.ticket().creatorUserId()).isEqualTo(5L);

        ArgumentCaptor<Ticket> captor = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketMapper).insertIgnoreConflict(captor.capture());
        assertThat(captor.getValue().getCreatorUserId()).isEqualTo(5L);
        assertThat(captor.getValue().getStatus()).isEqualTo(TicketStatus.OPEN);
        verify(currentUserService, never()).getRequiredCurrentUser();
    }

    @Test
    @DisplayName("显式身份建单：系统管理员身份建单成功，且不读取 ThreadLocal")
    void shouldCreateTicketForWithExplicitAdminIdentity() {
        when(ticketMapper.selectOne(any())).thenReturn(null);
        when(ticketMapper.insertIgnoreConflict(any(Ticket.class))).thenReturn(1);

        CreateTicketResult result = service().createTicketFor(1L, SystemRole.ADMIN, validRequest());

        assertThat(result.alreadyExists()).isFalse();
        assertThat(result.ticket().creatorUserId()).isEqualTo(1L);
        verify(currentUserService, never()).getRequiredCurrentUser();
    }

    @Test
    @DisplayName("显式身份建单：非法用户 ID 应拒绝，且不读取 ThreadLocal、不写库")
    void shouldRejectCreateTicketForWithInvalidUserId() {
        assertThatThrownBy(() -> service().createTicketFor(null, SystemRole.USER, validRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户 ID");
        assertThatThrownBy(() -> service().createTicketFor(0L, SystemRole.USER, validRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户 ID");
        verify(ticketMapper, never()).insertIgnoreConflict(any(Ticket.class));
        verify(currentUserService, never()).getRequiredCurrentUser();
    }

    @Test
    @DisplayName("显式身份建单：非法角色应拒绝，且不写库")
    void shouldRejectCreateTicketForWithNullRole() {
        assertThatThrownBy(() -> service().createTicketFor(5L, null, validRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("角色");
        verify(ticketMapper, never()).insertIgnoreConflict(any(Ticket.class));
    }

    @Test
    @DisplayName("显式身份建单：复用与 createTicket 相同的幂等语义（同 key 同内容复用）")
    void shouldReuseIdempotencyForExplicitCreate() {
        when(ticketMapper.selectOne(any())).thenReturn(existingTicket(42L, 5L, "zhangsan"));

        CreateTicketResult result = service().createTicketFor(5L, SystemRole.USER, validRequest());

        assertThat(result.alreadyExists()).isTrue();
        assertThat(result.ticket().id()).isEqualTo(42L);
        verify(ticketMapper, never()).insertIgnoreConflict(any(Ticket.class));
    }

    @Test
    @DisplayName("创建人可查看自己的工单")
    void shouldAllowCreatorToViewOwnTicket() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user(5L));
        when(ticketMapper.selectById(42L)).thenReturn(existingTicket(42L, 5L, "zhangsan"));

        TicketDetailResponse detail = service().getTicketDetail(42L);

        assertThat(detail.id()).isEqualTo(42L);
        assertThat(detail.creatorUserId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("系统管理员可查看任意工单")
    void shouldAllowAdminToViewAnyTicket() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(admin(1L));
        when(ticketMapper.selectById(42L)).thenReturn(existingTicket(42L, 99L, "zhangsan"));

        TicketDetailResponse detail = service().getTicketDetail(42L);

        assertThat(detail.id()).isEqualTo(42L);
    }

    @Test
    @DisplayName("非创建人且非管理员查看应被拒绝")
    void shouldForbidNonCreatorFromViewing() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user(5L));
        when(ticketMapper.selectById(42L)).thenReturn(existingTicket(42L, 99L, "zhangsan"));

        assertThatThrownBy(() -> service().getTicketDetail(42L))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("工单不存在时应报业务异常")
    void shouldThrowWhenTicketNotFound() {
        when(ticketMapper.selectById(42L)).thenReturn(null);

        assertThatThrownBy(() -> service().getTicketDetail(42L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("普通用户列表仅返回自己创建的工单")
    void shouldListOnlyOwnTicketsForNormalUser() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user(5L));
        when(ticketMapper.selectList(any())).thenReturn(List.of(existingTicket(1L, 5L, "zhangsan")));

        List<TicketDetailResponse> tickets = service().listTickets();

        assertThat(tickets).hasSize(1);
        assertThat(tickets.getFirst().creatorUserId()).isEqualTo(5L);
        verify(ticketMapper).selectList(any());
    }

    @Test
    @DisplayName("系统管理员列表返回所有工单")
    void shouldListAllTicketsForAdmin() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(admin(1L));
        when(ticketMapper.selectList(any())).thenReturn(List.of(
                existingTicket(1L, 5L, "zhangsan"),
                existingTicket(2L, 9L, "lisi")));

        List<TicketDetailResponse> tickets = service().listTickets();

        assertThat(tickets).hasSize(2);
        verify(ticketMapper).selectList(any());
    }

    @Test
    @DisplayName("普通用户按创建人过滤，管理员不过滤")
    void shouldFilterByCreatorOnlyForNonAdmin() {
        assertThat(service().creatorFilterUserId(user(5L))).isEqualTo(5L);
        assertThat(service().creatorFilterUserId(admin(1L))).isNull();
    }

    @Test
    @DisplayName("显式身份列表过滤：普通用户返回 userId，管理员返回 null")
    void shouldFilterTicketListByExplicitRole() {
        assertThat(service().ticketListFilterUserId(5L, SystemRole.USER)).isEqualTo(5L);
        assertThat(service().ticketListFilterUserId(1L, SystemRole.ADMIN)).isNull();
    }

    @Test
    @DisplayName("显式身份只读列表：普通用户返回自己可见的工单摘要")
    void shouldListVisibleTicketsAsSummaryForUser() {
        when(ticketMapper.selectList(any())).thenReturn(List.of(existingTicket(1L, 5L, "zhangsan")));

        TicketListResult result = service().listVisibleTickets(5L, SystemRole.USER);

        assertThat(result.tickets()).hasSize(1);
        assertThat(result.hasMore()).isFalse();
        TicketSummaryVO summary = result.tickets().getFirst();
        assertThat(summary.id()).isEqualTo(1L);
        assertThat(summary.ticketNo()).isEqualTo("TK1");
        assertThat(summary.type()).isEqualTo(TicketType.ACCOUNT_PROVISIONING);
        assertThat(summary.status()).isEqualTo(TicketStatus.OPEN);
        assertThat(summary.systemName()).isEqualTo("erp");
        assertThat(summary.targetUsername()).isEqualTo("zhangsan");
        assertThat(summary.createdAt()).isNotNull();
        verify(ticketMapper).selectList(any());
    }

    @Test
    @DisplayName("显式身份只读列表：管理员返回全部工单摘要")
    void shouldListVisibleTicketsForAdmin() {
        when(ticketMapper.selectList(any())).thenReturn(List.of(
                existingTicket(1L, 5L, "zhangsan"),
                existingTicket(2L, 9L, "lisi")));

        TicketListResult result = service().listVisibleTickets(1L, SystemRole.ADMIN);

        assertThat(result.tickets()).hasSize(2);
        assertThat(result.hasMore()).isFalse();
        verify(ticketMapper).selectList(any());
    }

    @Test
    @DisplayName("普通用户列表超过上限：只返回前 50 条且 hasMore=true，不返回第 51 条")
    void shouldLimitUserListWhenOverLimit() {
        when(ticketMapper.selectList(any())).thenReturn(tickets(51));

        TicketListResult result = service().listVisibleTickets(5L, SystemRole.USER);

        assertThat(result.tickets()).hasSize(50);
        assertThat(result.hasMore()).isTrue();
        assertThat(result.tickets()).noneMatch(t -> t.id() == 51L);
    }

    @Test
    @DisplayName("普通用户列表恰好 50 条：全部返回且 hasMore=false")
    void shouldReturnAllWhenExactlyLimitForUser() {
        when(ticketMapper.selectList(any())).thenReturn(tickets(50));

        TicketListResult result = service().listVisibleTickets(5L, SystemRole.USER);

        assertThat(result.tickets()).hasSize(50);
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    @DisplayName("普通用户列表不足 50 条：全部返回且 hasMore=false")
    void shouldReturnAllWhenUnderLimitForUser() {
        when(ticketMapper.selectList(any())).thenReturn(tickets(3));

        TicketListResult result = service().listVisibleTickets(5L, SystemRole.USER);

        assertThat(result.tickets()).hasSize(3);
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    @DisplayName("管理员列表超过上限：只返回前 50 条且 hasMore=true，不返回第 51 条")
    void shouldLimitAdminListWhenOverLimit() {
        when(ticketMapper.selectList(any())).thenReturn(tickets(51));

        TicketListResult result = service().listVisibleTickets(1L, SystemRole.ADMIN);

        assertThat(result.tickets()).hasSize(50);
        assertThat(result.hasMore()).isTrue();
        assertThat(result.tickets()).noneMatch(t -> t.id() == 51L);
    }

    @Test
    @DisplayName("管理员列表恰好 50 条：全部返回且 hasMore=false")
    void shouldReturnAllWhenExactlyLimitForAdmin() {
        when(ticketMapper.selectList(any())).thenReturn(tickets(50));

        TicketListResult result = service().listVisibleTickets(1L, SystemRole.ADMIN);

        assertThat(result.tickets()).hasSize(50);
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    @DisplayName("管理员列表不足 50 条：全部返回且 hasMore=false")
    void shouldReturnAllWhenUnderLimitForAdmin() {
        when(ticketMapper.selectList(any())).thenReturn(tickets(3));

        TicketListResult result = service().listVisibleTickets(1L, SystemRole.ADMIN);

        assertThat(result.tickets()).hasSize(3);
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    @DisplayName("查询上限应通过 SQL 的 LIMIT 51 下发，而非仅靠内存裁剪")
    @SuppressWarnings("unchecked")
    void shouldIssueSqlLimitFiftyOne() {
        when(ticketMapper.selectList(any())).thenReturn(tickets(51));

        service().listVisibleTickets(5L, SystemRole.USER);

        ArgumentCaptor<LambdaQueryWrapper<Ticket>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(ticketMapper).selectList(captor.capture());
        assertThat(lastSqlSegment(captor.getValue())).contains("LIMIT 51");
    }

    /**
     * 读取 wrapper 的 .last() SQL 片段。
     * <p>MyBatis-Plus 3.5.15 的 {@code getSqlSegment()}/{@code getCustomSqlSegment()} 在渲染
     * 普通段时会解析 lambda 列缓存（{@code TableInfoHelper}），纯单元测试环境未初始化该缓存，
     * 会抛 {@code can not find lambda cache}；而 {@code lastSql} 是 {@code AbstractWrapper}
     * 的受保护字段，故这里用反射直接读取 {@code .last()} 写入的原始片段，避免触碰 lambda 解析。</p>
     */
    private static String lastSqlSegment(LambdaQueryWrapper<Ticket> wrapper) {
        try {
            Field field = AbstractWrapper.class.getDeclaredField("lastSql");
            field.setAccessible(true);
            SharedString lastSql = (SharedString) field.get(wrapper);
            return lastSql.getStringValue();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法读取 wrapper 的 lastSql 字段", e);
        }
    }

    @Test
    @DisplayName("显式身份只读列表：非法用户 ID 应拒绝")
    void shouldRejectInvalidViewerId() {
        assertThatThrownBy(() -> service().listVisibleTickets(null, SystemRole.USER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户 ID");
        assertThatThrownBy(() -> service().listVisibleTickets(0L, SystemRole.ADMIN))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户 ID");
        verify(ticketMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("创建人可查看自己工单的执行历史，且 VO 不暴露内部载荷")
    void shouldAllowCreatorToViewExecutions() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user(5L));
        when(ticketMapper.selectById(42L)).thenReturn(existingTicket(42L, 5L, "zhangsan"));
        TicketExecution first = executionRecord(100L, 1, ExecutionResult.FAILED, "目标系统拒绝");
        TicketExecution second = executionRecord(101L, 2, ExecutionResult.SUCCESS, null);
        when(ticketExecutionMapper.selectList(any())).thenReturn(List.of(first, second));

        List<ExecutionRecordVO> records = service().getTicketExecutions(42L);

        assertThat(records).hasSize(2);
        assertThat(records.get(0).attemptNo()).isEqualTo(1);
        assertThat(records.get(0).result()).isEqualTo(ExecutionResult.FAILED);
        assertThat(records.get(0).errorMessage()).isEqualTo("开通失败，请联系管理员");
        assertThat(records.get(1).attemptNo()).isEqualTo(2);
        assertThat(records.get(1).result()).isEqualTo(ExecutionResult.SUCCESS);
        assertThat(records.get(1).finishedAt()).isNotNull();
        // ExecutionRecordVO 记录类型无 requestPayload/responsePayload 访问器，天然不暴露内部载荷
        verify(ticketExecutionMapper).selectList(any());
    }

    @Test
    @DisplayName("系统管理员可查看任意工单的执行历史")
    void shouldAllowAdminToViewExecutions() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(admin(1L));
        when(ticketMapper.selectById(42L)).thenReturn(existingTicket(42L, 99L, "zhangsan"));
        when(ticketExecutionMapper.selectList(any())).thenReturn(List.of());

        List<ExecutionRecordVO> records = service().getTicketExecutions(42L);

        assertThat(records).isEmpty();
    }

    @Test
    @DisplayName("非创建人且非管理员查看执行历史应被拒绝")
    void shouldForbidNonCreatorFromViewingExecutions() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user(5L));
        when(ticketMapper.selectById(42L)).thenReturn(existingTicket(42L, 99L, "zhangsan"));

        assertThatThrownBy(() -> service().getTicketExecutions(42L))
                .isInstanceOf(ForbiddenException.class);
        verify(ticketExecutionMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("工单不存在时查询执行历史应报业务异常")
    void shouldThrowWhenTicketNotFoundForExecutions() {
        when(ticketMapper.selectById(42L)).thenReturn(null);

        assertThatThrownBy(() -> service().getTicketExecutions(42L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("执行记录：errorMessage 由受控状态派生，不回显历史自由文本")
    void shouldDeriveSafeErrorMessageInsteadOfEchoingStoredText() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user(5L));
        when(ticketMapper.selectById(42L)).thenReturn(existingTicket(42L, 5L, "zhangsan"));
        String leaked = "jdbc:postgresql://10.0.0.5:5432 secret=p@ss\n at com.argus.Stack";
        when(ticketExecutionMapper.selectList(any())).thenReturn(List.of(
                executionRecord(100L, 1, ExecutionResult.FAILED, leaked),
                executionRecord(101L, 2, ExecutionResult.UNCERTAIN, leaked),
                executionRecord(102L, 3, ExecutionResult.SUCCESS, leaked),
                executionRecord(103L, 4, ExecutionResult.PENDING, leaked)));

        List<ExecutionRecordVO> records = service().getTicketExecutions(42L);

        assertThat(records).hasSize(4);
        assertThat(records.get(0).errorMessage())
                .isEqualTo("开通失败，请联系管理员")
                .doesNotContain("jdbc", "secret", "p@ss");
        assertThat(records.get(1).errorMessage())
                .isEqualTo("开通结果不确定，请人工核实")
                .doesNotContain("jdbc", "secret", "p@ss");
        assertThat(records.get(2).errorMessage()).isNull();
        assertThat(records.get(3).errorMessage()).isNull();
    }

    // ─────────────────────────── 显式身份只读：详情 ───────────────────────────

    @Test
    @DisplayName("显式身份详情：创建人查询自己的工单成功，且不读取 ThreadLocal、不暴露内部字段")
    void shouldAllowCreatorToViewDetailFor() {
        when(ticketMapper.selectById(42L)).thenReturn(existingTicket(42L, 5L, "zhangsan"));

        TicketDetailToolVO detail = service().getTicketDetailFor(42L, 5L, SystemRole.USER);

        assertThat(detail.id()).isEqualTo(42L);
        assertThat(detail.ticketNo()).isEqualTo("TK42");
        assertThat(detail.status()).isEqualTo(TicketStatus.OPEN);
        assertThat(detail.systemName()).isEqualTo("erp");
        assertThat(detail.targetUsername()).isEqualTo("zhangsan");
        // TicketDetailToolVO 无 creatorUserId/assigneeUserId/approverUserId/version/resolution 访问器，天然脱敏
        verify(currentUserService, never()).getRequiredCurrentUser();
    }

    @Test
    @DisplayName("显式身份详情：系统管理员查询任意工单成功，且不读取 ThreadLocal")
    void shouldAllowAdminToViewDetailFor() {
        when(ticketMapper.selectById(42L)).thenReturn(existingTicket(42L, 99L, "zhangsan"));

        TicketDetailToolVO detail = service().getTicketDetailFor(42L, 1L, SystemRole.ADMIN);

        assertThat(detail.id()).isEqualTo(42L);
        assertThat(detail.description()).isEqualTo("需要只读账号");
        verify(currentUserService, never()).getRequiredCurrentUser();
    }

    @Test
    @DisplayName("显式身份详情：非创建人且非管理员被拒绝，且不读取 ThreadLocal")
    void shouldForbidNonCreatorFromViewingDetailFor() {
        when(ticketMapper.selectById(42L)).thenReturn(existingTicket(42L, 99L, "zhangsan"));

        assertThatThrownBy(() -> service().getTicketDetailFor(42L, 5L, SystemRole.USER))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("无权查看该工单");
        verify(currentUserService, never()).getRequiredCurrentUser();
    }

    @Test
    @DisplayName("显式身份详情：工单不存在报业务异常")
    void shouldThrowWhenTicketNotFoundForDetailFor() {
        when(ticketMapper.selectById(42L)).thenReturn(null);

        assertThatThrownBy(() -> service().getTicketDetailFor(42L, 5L, SystemRole.USER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
        verify(currentUserService, never()).getRequiredCurrentUser();
    }

    // ─────────────────────────── 显式身份只读：执行记录 ───────────────────────────

    @Test
    @DisplayName("显式身份执行记录：创建人查询自己的执行历史成功，且不读取 ThreadLocal")
    void shouldAllowCreatorToViewExecutionsFor() {
        when(ticketMapper.selectById(42L)).thenReturn(existingTicket(42L, 5L, "zhangsan"));
        when(ticketExecutionMapper.selectList(any())).thenReturn(List.of(
                executionRecord(100L, 1, ExecutionResult.FAILED, "目标系统拒绝")));

        List<ExecutionRecordVO> records = service().getTicketExecutionsFor(42L, 5L, SystemRole.USER);

        assertThat(records).hasSize(1);
        assertThat(records.getFirst().attemptNo()).isEqualTo(1);
        assertThat(records.getFirst().result()).isEqualTo(ExecutionResult.FAILED);
        verify(ticketExecutionMapper).selectList(any());
        verify(currentUserService, never()).getRequiredCurrentUser();
    }

    @Test
    @DisplayName("显式身份执行记录：系统管理员查询任意工单执行历史成功")
    void shouldAllowAdminToViewExecutionsFor() {
        when(ticketMapper.selectById(42L)).thenReturn(existingTicket(42L, 99L, "zhangsan"));
        when(ticketExecutionMapper.selectList(any())).thenReturn(List.of());

        List<ExecutionRecordVO> records = service().getTicketExecutionsFor(42L, 1L, SystemRole.ADMIN);

        assertThat(records).isEmpty();
        verify(ticketExecutionMapper).selectList(any());
        verify(currentUserService, never()).getRequiredCurrentUser();
    }

    @Test
    @DisplayName("显式身份执行记录：非创建人且非管理员被拒绝，且不加载执行记录")
    void shouldForbidNonCreatorFromViewingExecutionsFor() {
        when(ticketMapper.selectById(42L)).thenReturn(existingTicket(42L, 99L, "zhangsan"));

        assertThatThrownBy(() -> service().getTicketExecutionsFor(42L, 5L, SystemRole.USER))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("无权查看该工单");
        // 关键：可见性校验必须先于执行记录查询，越权时不得触碰执行记录
        verify(ticketExecutionMapper, never()).selectList(any());
        verify(currentUserService, never()).getRequiredCurrentUser();
    }

    @Test
    @DisplayName("显式身份执行记录：工单不存在报业务异常，且不加载执行记录")
    void shouldThrowWhenTicketNotFoundForExecutionsFor() {
        when(ticketMapper.selectById(42L)).thenReturn(null);

        assertThatThrownBy(() -> service().getTicketExecutionsFor(42L, 5L, SystemRole.USER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
        verify(ticketExecutionMapper, never()).selectList(any());
        verify(currentUserService, never()).getRequiredCurrentUser();
    }

    // ─────────────────────────── 关闭与取消 ───────────────────────────

    @Test
    @DisplayName("创建人取消 OPEN 工单成功：OPEN -> CANCELLED")
    void shouldAllowCreatorToCancelOpenTicket() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user(5L));
        when(ticketMapper.selectById(42L)).thenReturn(ticketWithStatus(42L, 5L, TicketStatus.OPEN));
        when(ticketMapper.updateTicketStatus(42L, "OPEN", "CANCELLED")).thenReturn(1);

        service().cancel(42L);

        verify(ticketMapper).updateTicketStatus(42L, "OPEN", "CANCELLED");
    }

    @Test
    @DisplayName("创建人取消 PENDING_APPROVAL 工单成功：PENDING_APPROVAL -> CANCELLED")
    void shouldAllowCreatorToCancelPendingApprovalTicket() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user(5L));
        when(ticketMapper.selectById(42L)).thenReturn(ticketWithStatus(42L, 5L, TicketStatus.PENDING_APPROVAL));
        when(ticketMapper.updateTicketStatus(42L, "PENDING_APPROVAL", "CANCELLED")).thenReturn(1);

        service().cancel(42L);

        verify(ticketMapper).updateTicketStatus(42L, "PENDING_APPROVAL", "CANCELLED");
    }

    @Test
    @DisplayName("非创建人取消工单被拒绝（403）")
    void shouldRejectCancelByNonCreator() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user(9L));
        when(ticketMapper.selectById(42L)).thenReturn(ticketWithStatus(42L, 5L, TicketStatus.OPEN));

        assertThatThrownBy(() -> service().cancel(42L))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("创建人");
        verify(ticketMapper, never()).updateTicketStatus(any(), any(), any());
    }

    @Test
    @DisplayName("管理员取消他人工单被拒绝（403）")
    void shouldRejectCancelByAdminOfOthersTicket() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(admin(1L));
        when(ticketMapper.selectById(42L)).thenReturn(ticketWithStatus(42L, 5L, TicketStatus.OPEN));

        assertThatThrownBy(() -> service().cancel(42L))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("创建人");
        verify(ticketMapper, never()).updateTicketStatus(any(), any(), any());
    }

    @Test
    @DisplayName("创建人关闭 RESOLVED 工单成功：RESOLVED -> CLOSED")
    void shouldAllowCreatorToCloseResolvedTicket() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user(5L));
        when(ticketMapper.selectById(42L)).thenReturn(ticketWithStatus(42L, 5L, TicketStatus.RESOLVED));
        when(ticketMapper.updateTicketStatus(42L, "RESOLVED", "CLOSED")).thenReturn(1);

        service().close(42L);

        verify(ticketMapper).updateTicketStatus(42L, "RESOLVED", "CLOSED");
    }

    @Test
    @DisplayName("管理员关闭他人的 RESOLVED 工单成功")
    void shouldAllowAdminToCloseOthersResolvedTicket() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(admin(1L));
        when(ticketMapper.selectById(42L)).thenReturn(ticketWithStatus(42L, 99L, TicketStatus.RESOLVED));
        when(ticketMapper.updateTicketStatus(42L, "RESOLVED", "CLOSED")).thenReturn(1);

        service().close(42L);

        verify(ticketMapper).updateTicketStatus(42L, "RESOLVED", "CLOSED");
    }

    @Test
    @DisplayName("管理员关闭自己的 RESOLVED 工单成功")
    void shouldAllowAdminToCloseOwnResolvedTicket() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(admin(1L));
        when(ticketMapper.selectById(42L)).thenReturn(ticketWithStatus(42L, 1L, TicketStatus.RESOLVED));
        when(ticketMapper.updateTicketStatus(42L, "RESOLVED", "CLOSED")).thenReturn(1);

        service().close(42L);

        verify(ticketMapper).updateTicketStatus(42L, "RESOLVED", "CLOSED");
    }

    @Test
    @DisplayName("非创建人且非管理员关闭工单被拒绝（403）")
    void shouldRejectCloseByNonCreatorNonAdmin() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user(9L));
        when(ticketMapper.selectById(42L)).thenReturn(ticketWithStatus(42L, 5L, TicketStatus.RESOLVED));

        assertThatThrownBy(() -> service().close(42L))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("创建人或系统管理员");
        verify(ticketMapper, never()).updateTicketStatus(any(), any(), any());
    }

    @Test
    @DisplayName("非法状态关闭工单被拒绝（非 RESOLVED）")
    void shouldRejectCloseWhenTicketNotResolved() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user(5L));
        when(ticketMapper.selectById(42L)).thenReturn(ticketWithStatus(42L, 5L, TicketStatus.APPROVED));

        assertThatThrownBy(() -> service().close(42L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不允许关闭");
        verify(ticketMapper, never()).updateTicketStatus(any(), any(), any());
    }

    @Test
    @DisplayName("非法状态取消工单被拒绝（非 OPEN / PENDING_APPROVAL）")
    void shouldRejectCancelWhenTicketNotCancellable() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user(5L));
        when(ticketMapper.selectById(42L)).thenReturn(ticketWithStatus(42L, 5L, TicketStatus.APPROVED));

        assertThatThrownBy(() -> service().cancel(42L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不允许取消");
        verify(ticketMapper, never()).updateTicketStatus(any(), any(), any());
    }

    @Test
    @DisplayName("关闭工单条件更新影响 0 行（并发）应报业务异常")
    void shouldThrowOnCloseWhenConditionalUpdateLost() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user(5L));
        when(ticketMapper.selectById(42L)).thenReturn(ticketWithStatus(42L, 5L, TicketStatus.RESOLVED));
        when(ticketMapper.updateTicketStatus(42L, "RESOLVED", "CLOSED")).thenReturn(0);

        assertThatThrownBy(() -> service().close(42L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重复关闭");
    }

    @Test
    @DisplayName("取消工单条件更新影响 0 行（并发）应报业务异常")
    void shouldThrowOnCancelWhenConditionalUpdateLost() {
        when(currentUserService.getRequiredCurrentUser()).thenReturn(user(5L));
        when(ticketMapper.selectById(42L)).thenReturn(ticketWithStatus(42L, 5L, TicketStatus.OPEN));
        when(ticketMapper.updateTicketStatus(42L, "OPEN", "CANCELLED")).thenReturn(0);

        assertThatThrownBy(() -> service().cancel(42L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重复取消");
    }

    @Test
    @DisplayName("关闭/取消不存在的工单报业务异常")
    void shouldThrowWhenTicketNotFoundForCloseOrCancel() {
        when(ticketMapper.selectById(42L)).thenReturn(null);

        assertThatThrownBy(() -> service().close(42L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
        assertThatThrownBy(() -> service().cancel(42L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
    }

    private TicketExecution executionRecord(Long id, int attemptNo, ExecutionResult result, String errorMessage) {
        TicketExecution record = new TicketExecution();
        record.setId(id);
        record.setTicketId(42L);
        record.setExecutionType(ExecutionType.PROVISION_ACCOUNT);
        record.setAttemptNo(attemptNo);
        record.setResult(result);
        record.setErrorMessage(errorMessage);
        record.setRequestPayload("{\"systemName\":\"erp\"}");
        record.setResponsePayload("{\"externalRef\":\"ext-1\"}");
        record.setStartedAt(LocalDateTime.now());
        record.setFinishedAt(result == ExecutionResult.SUCCESS ? LocalDateTime.now() : null);
        return record;
    }

    private CurrentUserService.CurrentUser user(long id) {
        return new CurrentUserService.CurrentUser(id, "u" + id, "用户" + id, SystemRole.USER, false);
    }

    private CurrentUserService.CurrentUser admin(long id) {
        return new CurrentUserService.CurrentUser(id, "u" + id, "管理员" + id, SystemRole.ADMIN, false);
    }

    private CreateTicketRequest validRequest() {
        return new CreateTicketRequest(
                "key-1", TicketType.ACCOUNT_PROVISIONING, "erp", "zhangsan",
                PermissionLevel.READ_ONLY, "需要只读账号", "账号开通申请", null);
    }

    private Ticket existingTicket(Long id, Long creatorUserId, String targetUsername) {
        Ticket ticket = new Ticket();
        ticket.setId(id);
        ticket.setTicketNo("TK" + id);
        ticket.setTitle("账号开通申请");
        ticket.setDescription("需要只读账号");
        ticket.setType(TicketType.ACCOUNT_PROVISIONING);
        ticket.setStatus(TicketStatus.OPEN);
        ticket.setCreatorUserId(creatorUserId);
        ticket.setSystemName("erp");
        ticket.setTargetUsername(targetUsername);
        ticket.setPermissionLevel(PermissionLevel.READ_ONLY);
        ticket.setVersion(0);
        ticket.setIdempotencyKey("key-1");
        ticket.setCreatedAt(LocalDateTime.now());
        ticket.setUpdatedAt(LocalDateTime.now());
        return ticket;
    }

    private Ticket ticketWithStatus(Long id, Long creatorUserId, TicketStatus status) {
        Ticket ticket = existingTicket(id, creatorUserId, "zhangsan");
        ticket.setStatus(status);
        return ticket;
    }

    private List<Ticket> tickets(int count) {
        List<Ticket> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            list.add(existingTicket((long) i, 5L, "user" + i));
        }
        return list;
    }
}
