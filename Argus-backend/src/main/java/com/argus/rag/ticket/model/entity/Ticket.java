package com.argus.rag.ticket.model.entity;

import com.argus.rag.ticket.model.enums.PermissionLevel;
import com.argus.rag.ticket.model.enums.TicketStatus;
import com.argus.rag.ticket.model.enums.TicketType;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单实体，映射 tickets 表。
 */
@TableName("tickets")
@Data
public class Ticket {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 工单号，唯一 */
    private String ticketNo;

    /** 标题 */
    private String title;

    /** 申请原因等描述 */
    private String description;

    /** 工单类型 */
    private TicketType type;

    /** 状态 */
    private TicketStatus status;

    /** 优先级 */
    private String priority;

    /** 创建人用户 ID（来自登录态，不信任 Agent 传入） */
    private Long creatorUserId;

    /** 处理人用户 ID，未分配为 null */
    private Long assigneeUserId;

    /** 审批人用户 ID，待定为 null */
    private Long approverUserId;

    /** 目标系统 */
    private String systemName;

    /** 要开通账号的目标用户名 */
    private String targetUsername;

    /** 权限级别 */
    private PermissionLevel permissionLevel;

    /** 处理结果说明 */
    private String resolution;

    /** 乐观锁版本号 */
    private Integer version;

    /** 受控重试授权额度（仅人工处置可递增，默认 0，与数据库列 NOT NULL DEFAULT 0 一致） */
    private Integer extraAttempts = 0;

    /** 建单幂等键（全局唯一） */
    private String idempotencyKey;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
