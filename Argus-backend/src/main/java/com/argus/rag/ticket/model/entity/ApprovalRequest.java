package com.argus.rag.ticket.model.entity;

import com.argus.rag.ticket.model.enums.ApprovalResult;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审批记录实体，映射 approval_requests 表（审计用：谁、何时、批了什么）。
 */
@TableName("approval_requests")
@Data
public class ApprovalRequest {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属工单 ID */
    private Long ticketId;

    /** 审批人用户 ID */
    private Long approverUserId;

    /** 审批结果 */
    private ApprovalResult result;

    /** 审批意见 */
    private String comment;

    /** 审批决定时间 */
    private LocalDateTime decidedAt;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
