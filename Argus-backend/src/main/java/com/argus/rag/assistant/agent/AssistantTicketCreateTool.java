package com.argus.rag.assistant.agent;

import com.argus.rag.assistant.model.vo.tool.TicketCreateToolResponse;
import com.argus.rag.common.enums.SystemRole;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.ForbiddenException;
import com.argus.rag.common.exception.UnauthorizedException;
import com.argus.rag.ticket.model.dto.CreateTicketRequest;
import com.argus.rag.ticket.model.enums.PermissionLevel;
import com.argus.rag.ticket.model.enums.TicketType;
import com.argus.rag.ticket.model.vo.CreateTicketResult;
import com.argus.rag.ticket.model.vo.TicketDetailResponse;
import com.argus.rag.ticket.model.vo.TicketDetailToolVO;
import com.argus.rag.ticket.service.TicketService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Agent 工单创建工具：以自然语言参数创建工单。
 * <p>
 * 模型只能提供建单业务字段（类型、目标系统、目标用户名、权限级别、申请原因、标题、优先级），
 * 创建人身份（userId / systemRole）与消息 ID（messageId）均来自服务端注入的 {@link ToolContext}，
 * 模型无法覆盖；幂等键由服务端基于「消息 ID + 业务字段 SHA-256」生成，不依赖模型提供。
 * </p>
 * <p>建单后返回脱敏的 {@link TicketDetailToolVO}；该工具只能创建工单，不能提交审批、
 * 审批、执行、关闭或取消工单。</p>
 */
@Slf4j
@Component
public class AssistantTicketCreateTool extends AbstractAssistantTicketTool {

    public static final String TOOL_NAME = "createTicket";

    /** 建单业务性失败（参数非法 / 身份缺失 / 幂等不一致等）的统一失败码 */
    public static final String REASON_CODE_CREATE_FAILED = "CREATE_FAILED";

    private static final String MESSAGE_CREATE_FAILED = "工单创建失败，请稍后重试或联系管理员";
    private static final String MESSAGE_INTERNAL_ERROR = "系统内部错误，请稍后重试";

    private final TicketService ticketService;

    public AssistantTicketCreateTool(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @Tool(name = TOOL_NAME, description = "创建一张工单（初始状态为 OPEN）。当前仅支持账号开通（ACCOUNT_PROVISIONING）类型；"
            + "创建人身份由服务端自动确定，无需也不得传入用户 ID 或角色。该工具只能创建工单，"
            + "不能提交审批、审批、执行、关闭或取消工单。同一建单意图只应调用一次，"
            + "成功后可据返回的 ticketNo / id 如实告知用户。")
    public TicketCreateToolResponse create(
            @ToolParam(description = "工单类型，当前仅支持 ACCOUNT_PROVISIONING（账号开通）") TicketType type,
            @ToolParam(description = "目标系统名称，例如 erp、crm 等") String systemName,
            @ToolParam(description = "要开通账号的目标用户名") String targetUsername,
            @ToolParam(description = "申请的权限级别，取值 READ_ONLY / STANDARD / ADMIN") PermissionLevel permissionLevel,
            @ToolParam(description = "申请原因说明") String description,
            @ToolParam(description = "工单标题") String title,
            @ToolParam(description = "优先级，可选，例如 LOW / MEDIUM / HIGH") String priority,
            ToolContext toolContext) {
        try {
            Long userId = readUserId(toolContext);
            SystemRole systemRole = readSystemRole(toolContext);
            Long messageId = readMessageId(toolContext);
            CreateTicketRequest request = new CreateTicketRequest(
                    buildIdempotencyKey(messageId, type, systemName, targetUsername,
                            permissionLevel, description, title, priority),
                    type,
                    systemName,
                    targetUsername,
                    permissionLevel,
                    description,
                    title,
                    priority);
            CreateTicketResult result = ticketService.createTicketFor(userId, systemRole, request);
            return TicketCreateToolResponse.ok(toDetailToolVo(result.ticket()));
        } catch (BusinessException | ForbiddenException | UnauthorizedException e) {
            log.warn("createTicket 业务异常: {}", e.getMessage());
            return TicketCreateToolResponse.failed(REASON_CODE_CREATE_FAILED, MESSAGE_CREATE_FAILED);
        } catch (RuntimeException e) {
            log.error("createTicket 工具意外异常", e);
            return TicketCreateToolResponse.failed(REASON_CODE_INTERNAL_ERROR, MESSAGE_INTERNAL_ERROR);
        }
    }

    /** 将完整建单结果映射为脱敏 VO：不向模型暴露内部用户 ID、乐观锁版本与处理结果说明 */
    private TicketDetailToolVO toDetailToolVo(TicketDetailResponse response) {
        return new TicketDetailToolVO(
                response.id(),
                response.ticketNo(),
                response.title(),
                response.description(),
                response.type(),
                response.status(),
                response.priority(),
                response.systemName(),
                response.targetUsername(),
                response.permissionLevel(),
                response.createdAt(),
                response.updatedAt());
    }

    /**
     * 生成 Agent 建单的消息级幂等键：{@code assistant:{messageId}:{sha256(canonicalPayload)}}。
     * <p>messageId 为服务端保存用户消息后生成的消息主键，同一用户消息在 Agent 内部被
     * 重复执行时保持不变；不同用户消息（即使内容相同）messageId 不同，不会误判复用。</p>
     */
    private static String buildIdempotencyKey(Long messageId, TicketType type, String systemName,
                                              String targetUsername, PermissionLevel permissionLevel,
                                              String description, String title, String priority) {
        return "assistant:" + messageId + ":" + sha256Hex(canonicalPayload(
                type, systemName, targetUsername, permissionLevel, description, title, priority));
    }

    /**
     * 规范化业务字段的确定性序列化。
     * <p>按固定顺序覆盖与 {@link TicketService#sameContent} 比较完全一致的七个字段：
     * type、systemName、targetUsername、permissionLevel、description、title、priority。
     * 每个字段采用「长度前缀 + {@code :} + 值 + {@code ;}」编码，避免值中出现分隔符造成歧义；
     * 文本字段先 trim、空/空白归一为空串，枚举用 {@code name()}，与
     * {@code validateAndNormalize} 的 trim/空值规则保持一致。</p>
     */
    private static String canonicalPayload(TicketType type, String systemName, String targetUsername,
                                           PermissionLevel permissionLevel, String description,
                                           String title, String priority) {
        List<String> fields = List.of(
                type == null ? "" : type.name(),
                normalizeCanonicalText(systemName),
                normalizeCanonicalText(targetUsername),
                permissionLevel == null ? "" : permissionLevel.name(),
                normalizeCanonicalText(description),
                normalizeCanonicalText(title),
                normalizeCanonicalText(priority));
        StringBuilder sb = new StringBuilder();
        for (String field : fields) {
            sb.append(field.length()).append(':').append(field).append(';');
        }
        return sb.toString();
    }

    /** 文本字段规范化：与建单逻辑一致地 trim，null/空白统一归一为空串 */
    private static String normalizeCanonicalText(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? "" : trimmed;
    }

    /** SHA-256 十六进制摘要（固定 64 字符） */
    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
