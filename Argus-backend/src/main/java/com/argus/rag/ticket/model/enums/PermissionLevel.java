package com.argus.rag.ticket.model.enums;

/** 账号权限级别（枚举白名单，后端按规则校验，不信任 Agent 提取值） */
public enum PermissionLevel {
    /** 只读 */
    READ_ONLY,
    /** 标准 */
    STANDARD,
    /** 管理员 */
    ADMIN
}
