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
import com.argus.rag.ticket.model.enums.PermissionLevel;
import com.argus.rag.ticket.model.enums.TicketStatus;
import com.argus.rag.ticket.model.enums.TicketType;
import com.argus.rag.ticket.model.vo.CreateTicketResult;
import com.argus.rag.ticket.model.vo.ExecutionRecordVO;
import com.argus.rag.ticket.model.vo.TicketDetailResponse;
import com.argus.rag.ticket.model.vo.TicketDetailToolVO;
import com.argus.rag.ticket.model.vo.TicketListResult;
import com.argus.rag.ticket.model.vo.TicketSummaryVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 工单核心服务，提供创建、详情查询与列表查询能力。
 * <p>
 * 创建人一律取自登录态（{@link CurrentUserService}），不信任请求参数或 Agent 传入的 userId。
 */
@Slf4j
@Service
public class TicketService {

    private static final DateTimeFormatter TICKET_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 工单列表单次最多返回条数，多查 1 条用于判断 hasMore */
    private static final int TICKET_LIST_MAX_RESULTS = 50;

    /** 工单号唯一约束冲突时，初次尝试之外最多额外重试的次数（总插入次数 = 1 + 该值） */
    private static final int MAX_TICKET_NO_RETRIES = 3;

    private final TicketMapper ticketMapper;
    private final TicketExecutionMapper ticketExecutionMapper;
    private final CurrentUserService currentUserService;

    public TicketService(TicketMapper ticketMapper,
                         TicketExecutionMapper ticketExecutionMapper,
                         CurrentUserService currentUserService) {
        this.ticketMapper = ticketMapper;
        this.ticketExecutionMapper = ticketExecutionMapper;
        this.currentUserService = currentUserService;
    }

    /** 创建工单，初始状态为 OPEN，并实现建单幂等 */
    @Transactional
    public CreateTicketResult createTicket(CreateTicketRequest request) {
        CurrentUserService.CurrentUser currentUser = currentUserService.getRequiredCurrentUser();
        return doCreateTicket(currentUser.userId(), request);
    }

    /**
     * 创建工单（显式身份版本，供 Agent 建单工具等以服务端可信身份调用）。
     * <p>身份由调用方从服务端可信上下文获取后显式传入，本方法不读取 ThreadLocal，
     * 也不信任任何可能来自模型/请求体的身份字段；建单校验、规范化、幂等处理与
     * 数据库写入与 {@link #createTicket(CreateTicketRequest)} 完全复用。</p>
     */
    @Transactional
    public CreateTicketResult createTicketFor(Long creatorUserId, SystemRole creatorRole, CreateTicketRequest request) {
        requireValidCreatorIdentity(creatorUserId, creatorRole);
        return doCreateTicket(creatorUserId, request);
    }

    /** 建单核心流程：规范化 → 幂等命中复用/拒绝 → 插入并处理唯一约束冲突 */
    private CreateTicketResult doCreateTicket(Long creatorUserId, CreateTicketRequest request) {
        CreateCommand command = validateAndNormalize(request);

        Ticket existing = findByIdempotencyKey(command.idempotencyKey());
        if (existing != null) {
            return reuseOrReject(existing, command);
        }

        Ticket ticket = buildTicket(command, creatorUserId);
        CreateTicketResult idempotentResult = insertHandlingDuplicateKey(ticket, command);
        if (idempotentResult != null) {
            return idempotentResult;
        }

        log.info("创建工单: ticketId={}, ticketNo={}, creatorUserId={}",
                ticket.getId(), ticket.getTicketNo(), creatorUserId);
        return new CreateTicketResult(toDetailResponse(ticket), false);
    }

    /** 建单身份校验（显式身份版本）：仅接受正 Long 用户 ID 与非空角色 */
    private void requireValidCreatorIdentity(Long creatorUserId, SystemRole creatorRole) {
        if (creatorUserId == null || creatorUserId <= 0) {
            throw new BusinessException("建单用户 ID 非法");
        }
        if (creatorRole == null) {
            throw new BusinessException("建单用户角色非法");
        }
    }

    /** 关闭工单：仅创建人本人或系统管理员可操作，RESOLVED -> CLOSED */
    @Transactional
    public void close(Long ticketId) {
        CurrentUserService.CurrentUser currentUser = currentUserService.getRequiredCurrentUser();
        Ticket ticket = loadTicket(ticketId);
        requireCloser(ticket, currentUser);
        if (ticket.getStatus() != TicketStatus.RESOLVED) {
            throw new BusinessException("工单当前状态不允许关闭");
        }

        int updated = ticketMapper.updateTicketStatus(
                ticketId, TicketStatus.RESOLVED.name(), TicketStatus.CLOSED.name());
        if (updated == 0) {
            throw new BusinessException("工单已被处理，请勿重复关闭");
        }
        log.info("关闭工单: ticketId={}, operatorUserId={}", ticketId, currentUser.userId());
    }

    /** 取消工单：仅创建人本人可操作，OPEN / PENDING_APPROVAL -> CANCELLED */
    @Transactional
    public void cancel(Long ticketId) {
        CurrentUserService.CurrentUser currentUser = currentUserService.getRequiredCurrentUser();
        Ticket ticket = loadTicket(ticketId);
        requireCanceller(ticket, currentUser);
        if (ticket.getStatus() != TicketStatus.OPEN && ticket.getStatus() != TicketStatus.PENDING_APPROVAL) {
            throw new BusinessException("工单当前状态不允许取消");
        }

        int updated = ticketMapper.updateTicketStatus(
                ticketId, ticket.getStatus().name(), TicketStatus.CANCELLED.name());
        if (updated == 0) {
            throw new BusinessException("工单已被处理，请勿重复取消");
        }
        log.info("取消工单: ticketId={}, operatorUserId={}", ticketId, currentUser.userId());
    }

    /** 查询工单详情，仅创建人本人或系统管理员可见 */
    public TicketDetailResponse getTicketDetail(Long ticketId) {
        Ticket ticket = loadTicket(ticketId);
        requireVisible(ticket, currentUserService.getRequiredCurrentUser());
        return toDetailResponse(ticket);
    }

    /**
     * 查询工单详情（显式身份版本，供 Agent 只读工具等以服务端可信身份调用）。
     * <p>身份由调用方从服务端可信上下文获取后显式传入，本方法不读取 ThreadLocal，
     * 也不信任任何可能来自模型/请求体的身份字段；仅返回脱敏的 {@link TicketDetailToolVO}。</p>
     */
    public TicketDetailToolVO getTicketDetailFor(Long ticketId, Long viewerUserId, SystemRole viewerRole) {
        Ticket ticket = loadTicket(ticketId);
        requireVisible(ticket, viewerUserId, viewerRole);
        return toDetailToolVo(ticket);
    }

    /** 查询工单的执行尝试历史（按 attempt_no 升序），仅创建人本人或系统管理员可见 */
    public List<ExecutionRecordVO> getTicketExecutions(Long ticketId) {
        Ticket ticket = loadTicket(ticketId);
        requireVisible(ticket, currentUserService.getRequiredCurrentUser());
        return loadExecutionRecords(ticketId).stream().map(this::toExecutionRecord).toList();
    }

    /**
     * 查询工单执行尝试历史（显式身份版本，供 Agent 只读工具等调用）。
     * <p>与 {@link #getTicketExecutions(Long)} 同权限规则（创建人本人或管理员），
     * 但身份由调用方显式传入，不读取 ThreadLocal；可见性校验先于执行记录查询。</p>
     */
    public List<ExecutionRecordVO> getTicketExecutionsFor(Long ticketId, Long viewerUserId, SystemRole viewerRole) {
        Ticket ticket = loadTicket(ticketId);
        requireVisible(ticket, viewerUserId, viewerRole);
        return loadExecutionRecords(ticketId).stream().map(this::toExecutionRecord).toList();
    }

    /** 查询当前用户可见的工单列表：普通用户返回自己创建的，管理员返回全部 */
    public List<TicketDetailResponse> listTickets() {
        CurrentUserService.CurrentUser currentUser = currentUserService.getRequiredCurrentUser();
        Long filterCreatorId = creatorFilterUserId(currentUser);
        LambdaQueryWrapper<Ticket> wrapper = new LambdaQueryWrapper<>();
        if (filterCreatorId != null) {
            wrapper.eq(Ticket::getCreatorUserId, filterCreatorId);
        }
        wrapper.orderByDesc(Ticket::getCreatedAt);
        return ticketMapper.selectList(wrapper).stream().map(this::toDetailResponse).toList();
    }

    /**
     * 查询指定用户可见的工单摘要列表（只读），供 Agent 工具等以显式身份复用现有过滤规则。
     * <p>普通用户返回自己创建的工单，系统管理员返回全部；排序按创建时间倒序。</p>
     * <p>最多返回 {@value #TICKET_LIST_MAX_RESULTS} 条，多查 1 条用于判断是否还有更多，
     * 避免管理员一次将全表加载进内存并传给模型。</p>
     * <p>身份由调用方从服务端可信上下文获取后显式传入，本方法不读取 ThreadLocal，
     * 也不信任任何可能来自模型/请求体的身份字段。</p>
     */
    public TicketListResult listVisibleTickets(Long viewerUserId, SystemRole viewerRole) {
        if (viewerUserId == null || viewerUserId <= 0) {
            throw new BusinessException("查询用户 ID 非法");
        }
        Long filterCreatorId = ticketListFilterUserId(viewerUserId, viewerRole);
        LambdaQueryWrapper<Ticket> wrapper = new LambdaQueryWrapper<>();
        if (filterCreatorId != null) {
            wrapper.eq(Ticket::getCreatorUserId, filterCreatorId);
        }
        wrapper.orderByDesc(Ticket::getCreatedAt).last("LIMIT " + (TICKET_LIST_MAX_RESULTS + 1));
        List<Ticket> rows = ticketMapper.selectList(wrapper);
        boolean hasMore = rows.size() > TICKET_LIST_MAX_RESULTS;
        List<TicketSummaryVO> tickets = rows.stream()
                .limit(TICKET_LIST_MAX_RESULTS)
                .map(this::toSummaryResponse)
                .toList();
        return new TicketListResult(tickets, hasMore);
    }

    /**
     * 列表过滤条件：普通用户只能看自己创建的工单，系统管理员看全部。
     * 返回 null 表示不按创建人过滤。
     */
    Long creatorFilterUserId(CurrentUserService.CurrentUser currentUser) {
        return ticketListFilterUserId(currentUser.userId(), currentUser.systemRole());
    }

    /** 列表过滤条件（显式身份版本）：管理员返回 null（不过滤），其余返回 userId。 */
    Long ticketListFilterUserId(Long userId, SystemRole role) {
        return role == SystemRole.ADMIN ? null : userId;
    }

    private Ticket buildTicket(CreateCommand command, Long creatorUserId) {
        Ticket ticket = new Ticket();
        ticket.setTicketNo(generateTicketNo());
        ticket.setTitle(command.title());
        ticket.setDescription(command.description());
        ticket.setType(command.type());
        ticket.setStatus(TicketStatus.OPEN);
        ticket.setPriority(command.priority());
        ticket.setCreatorUserId(creatorUserId);
        ticket.setSystemName(command.systemName());
        ticket.setTargetUsername(command.targetUsername());
        ticket.setPermissionLevel(command.permissionLevel());
        ticket.setVersion(0);
        ticket.setIdempotencyKey(command.idempotencyKey());
        LocalDateTime now = LocalDateTime.now();
        ticket.setCreatedAt(now);
        ticket.setUpdatedAt(now);
        return ticket;
    }

    private CreateCommand validateAndNormalize(CreateTicketRequest request) {
        if (request == null) {
            throw new BusinessException("请求不能为空");
        }
        String idempotencyKey = requireText(request.idempotencyKey(), "幂等键", 128);
        if (request.type() == null) {
            throw new BusinessException("工单类型不能为空");
        }
        String systemName = requireText(request.systemName(), "目标系统", 100);
        String targetUsername = requireText(request.targetUsername(), "目标用户名", 100);
        if (request.permissionLevel() == null) {
            throw new BusinessException("权限级别不能为空");
        }
        String description = requireText(request.description(), "申请原因", null);
        String title = requireText(request.title(), "标题", 200);
        String priority = normalizeOptionalText(request.priority(), "优先级", 16);
        return new CreateCommand(
                idempotencyKey, title, description, request.type(), priority,
                systemName, targetUsername, request.permissionLevel());
    }

    private Ticket findByIdempotencyKey(String idempotencyKey) {
        return ticketMapper.selectOne(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getIdempotencyKey, idempotencyKey));
    }

    private CreateTicketResult reuseOrReject(Ticket existing, CreateCommand command) {
        if (sameContent(existing, command)) {
            return new CreateTicketResult(toDetailResponse(existing), true);
        }
        throw new BusinessException("幂等键已存在，但请求内容不一致");
    }

    /**
     * 执行插入并处理唯一约束冲突。
     * <p>
     * 使用 {@code INSERT ... ON CONFLICT DO NOTHING} 而非「插入失败后捕获异常」，避免 PostgreSQL
     * 唯一约束冲突（23505）使事务进入 aborted 状态后，同事务内的重查/重试全部报 25P02。
     * 返回非 null 表示命中幂等键冲突（复用或拒绝已有工单）；返回 null 表示新建成功。
     * <p>
     * tickets 表只有 ticket_no 与 idempotency_key 两个唯一约束：插入返回 0 行时先按幂等键重查，
     * 命中说明是幂等键冲突（复用或拒绝）；未命中则只能是 ticket_no 冲突，换号有限重试，
     * 绝不把 ticket_no 冲突误判成幂等复用而返回另一张工单。
     */
    private CreateTicketResult insertHandlingDuplicateKey(Ticket ticket, CreateCommand command) {
        int retry = 0;
        while (true) {
            int inserted = ticketMapper.insertIgnoreConflict(ticket);
            if (inserted == 1) {
                return null;
            }
            // 冲突（0 行）：先按幂等键重查，区分「幂等键冲突」与「工单号撞号」。
            // 此归因依赖 PostgreSQL 默认 READ COMMITTED：insertIgnoreConflict 返回 0 行即冲突行已提交，
            // 同事务内的幂等键重查必可见；若改为 REPEATABLE READ / SERIALIZABLE 需重新评估。
            Ticket raced = findByIdempotencyKey(command.idempotencyKey());
            if (raced != null) {
                return reuseOrReject(raced, command);
            }
            // 幂等键未命中 → 只能是工单号唯一约束冲突；换号有限重试。
            if (retry >= MAX_TICKET_NO_RETRIES) {
                throw new BusinessException("工单号生成冲突，请稍后重试");
            }
            log.warn("工单号唯一约束冲突，重新生成工单号并重试: idempotencyKey={}, retry={}/{}",
                    command.idempotencyKey(), retry + 1, MAX_TICKET_NO_RETRIES);
            ticket.setTicketNo(generateTicketNo());
            retry++;
        }
    }

    /** 幂等内容一致性：type + systemName + targetUsername + permissionLevel + description + title + priority 全等 */
    private boolean sameContent(Ticket existing, CreateCommand command) {
        return existing.getType() == command.type()
                && Objects.equals(existing.getSystemName(), command.systemName())
                && Objects.equals(existing.getTargetUsername(), command.targetUsername())
                && existing.getPermissionLevel() == command.permissionLevel()
                && Objects.equals(existing.getDescription(), command.description())
                && Objects.equals(existing.getTitle(), command.title())
                && Objects.equals(existing.getPriority(), command.priority());
    }

    private Ticket loadTicket(Long ticketId) {
        if (ticketId == null || ticketId <= 0) {
            throw new BusinessException("工单 ID 非法");
        }
        Ticket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null) {
            throw new BusinessException("工单不存在");
        }
        return ticket;
    }

    private void requireVisible(Ticket ticket, CurrentUserService.CurrentUser currentUser) {
        requireVisible(ticket, currentUser.userId(), currentUser.systemRole());
    }

    /**
     * 可见性校验（显式身份版本）：系统管理员或创建人本人可见，其余拒绝。
     * <p>权限规则只在此处定义一份，ThreadLocal 版本与显式身份版本共用，避免复制或削弱校验。</p>
     */
    private void requireVisible(Ticket ticket, Long viewerUserId, SystemRole viewerRole) {
        if (viewerRole == SystemRole.ADMIN) {
            return;
        }
        if (viewerUserId != null && viewerUserId.equals(ticket.getCreatorUserId())) {
            return;
        }
        throw new ForbiddenException("无权查看该工单");
    }

    /** 取消权限：仅工单创建人本人可取消自己的工单，系统管理员不能取消他人工单 */
    private void requireCanceller(Ticket ticket, CurrentUserService.CurrentUser currentUser) {
        if (!currentUser.userId().equals(ticket.getCreatorUserId())) {
            throw new ForbiddenException("仅工单创建人可取消工单");
        }
    }

    /** 关闭权限：工单创建人本人或系统管理员可关闭 */
    private void requireCloser(Ticket ticket, CurrentUserService.CurrentUser currentUser) {
        if (currentUser.systemRole() == SystemRole.ADMIN) {
            return;
        }
        if (currentUser.userId().equals(ticket.getCreatorUserId())) {
            return;
        }
        throw new ForbiddenException("仅工单创建人或系统管理员可关闭工单");
    }

    /** 查询工单的执行尝试历史（按 attempt_no 升序），供详情/执行记录两个入口复用 */
    private List<TicketExecution> loadExecutionRecords(Long ticketId) {
        return ticketExecutionMapper.selectList(
                new LambdaQueryWrapper<TicketExecution>()
                        .eq(TicketExecution::getTicketId, ticketId)
                        .orderByAsc(TicketExecution::getAttemptNo));
    }

    private ExecutionRecordVO toExecutionRecord(TicketExecution record) {
        return new ExecutionRecordVO(
                record.getId(),
                record.getAttemptNo(),
                record.getExecutionType(),
                record.getResult(),
                safeErrorMessage(record.getResult()),
                record.getStartedAt(),
                record.getFinishedAt(),
                record.getVerificationStatus(),
                record.getVerifiedAt());
    }

    /**
     * 执行记录的面向调用方安全文案：由受控状态 {@link ExecutionResult} 派生，
     * 绝不回显数据库 {@code error_message} 历史自由文本（可能含 JDBC 连接串、凭据、异常堆栈等）。
     * 数据库中的原始 error_message 仅保留供内部审计，不对外暴露。
     */
    private String safeErrorMessage(ExecutionResult result) {
        if (result == null) {
            return null;
        }
        return switch (result) {
            case FAILED -> "开通失败，请联系管理员";
            case UNCERTAIN -> "开通结果不确定，请人工核实";
            case SUCCESS, PENDING -> null;
        };
    }

    private TicketSummaryVO toSummaryResponse(Ticket ticket) {
        return new TicketSummaryVO(
                ticket.getId(),
                ticket.getTicketNo(),
                ticket.getTitle(),
                ticket.getType(),
                ticket.getStatus(),
                ticket.getSystemName(),
                ticket.getTargetUsername(),
                ticket.getCreatedAt());
    }

    /** 工单详情脱敏映射（Agent 只读工具面向）：不暴露内部用户 ID、乐观锁版本与处理结果说明 */
    private TicketDetailToolVO toDetailToolVo(Ticket ticket) {
        return new TicketDetailToolVO(
                ticket.getId(),
                ticket.getTicketNo(),
                ticket.getTitle(),
                ticket.getDescription(),
                ticket.getType(),
                ticket.getStatus(),
                ticket.getPriority(),
                ticket.getSystemName(),
                ticket.getTargetUsername(),
                ticket.getPermissionLevel(),
                ticket.getCreatedAt(),
                ticket.getUpdatedAt());
    }

    private TicketDetailResponse toDetailResponse(Ticket ticket) {
        return new TicketDetailResponse(
                ticket.getId(),
                ticket.getTicketNo(),
                ticket.getTitle(),
                ticket.getDescription(),
                ticket.getType(),
                ticket.getStatus(),
                ticket.getPriority(),
                ticket.getCreatorUserId(),
                ticket.getAssigneeUserId(),
                ticket.getApproverUserId(),
                ticket.getSystemName(),
                ticket.getTargetUsername(),
                ticket.getPermissionLevel(),
                ticket.getResolution(),
                ticket.getVersion(),
                ticket.getCreatedAt(),
                ticket.getUpdatedAt());
    }

    private String generateTicketNo() {
        return "TK" + TICKET_NO_TIME.format(LocalDateTime.now())
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    private String requireText(String value, String fieldName, Integer maxLength) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(fieldName + "不能为空");
        }
        String trimmed = value.trim();
        if (maxLength != null && trimmed.length() > maxLength) {
            throw new BusinessException(fieldName + "长度不能超过 " + maxLength);
        }
        return trimmed;
    }

    private String normalizeOptionalText(String value, String fieldName, Integer maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        if (maxLength != null && trimmed.length() > maxLength) {
            throw new BusinessException(fieldName + "长度不能超过 " + maxLength);
        }
        return trimmed;
    }

    /** 创建工单的规范化命令（内部使用） */
    private record CreateCommand(
            String idempotencyKey,
            String title,
            String description,
            TicketType type,
            String priority,
            String systemName,
            String targetUsername,
            PermissionLevel permissionLevel
    ) {
    }
}
