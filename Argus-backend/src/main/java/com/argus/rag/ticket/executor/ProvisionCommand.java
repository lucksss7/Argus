package com.argus.rag.ticket.executor;

import com.argus.rag.ticket.model.enums.PermissionLevel;

/**
 * 账号开通执行命令，由工单映射而来。
 * <p>
 * 执行器只依赖业务字段，不感知工单本身。
 */
public record ProvisionCommand(
        String systemName,
        String targetUsername,
        PermissionLevel permissionLevel
) {
}
