package com.argus.rag.ticket.model.dto;

import com.argus.rag.ticket.model.enums.PermissionLevel;
import com.argus.rag.ticket.model.enums.TicketType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建工单请求。
 */
public record CreateTicketRequest(
        /** 建单幂等键（全局唯一） */
        @NotBlank(message = "幂等键不能为空")
        @Size(max = 128, message = "幂等键长度不能超过 128")
        String idempotencyKey,

        /** 工单类型 */
        @NotNull(message = "工单类型不能为空")
        TicketType type,

        /** 目标系统 */
        @NotBlank(message = "目标系统不能为空")
        @Size(max = 100, message = "目标系统长度不能超过 100")
        String systemName,

        /** 要开通账号的目标用户名 */
        @NotBlank(message = "目标用户名不能为空")
        @Size(max = 100, message = "目标用户名长度不能超过 100")
        String targetUsername,

        /** 权限级别（白名单：READ_ONLY / STANDARD / ADMIN） */
        @NotNull(message = "权限级别不能为空")
        PermissionLevel permissionLevel,

        /** 申请原因 */
        @NotBlank(message = "申请原因不能为空")
        String description,

        /** 标题 */
        @NotBlank(message = "标题不能为空")
        @Size(max = 200, message = "标题长度不能超过 200")
        String title,

        /** 优先级（可选） */
        @Size(max = 16, message = "优先级长度不能超过 16")
        String priority
) {
}
