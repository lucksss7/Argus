package com.argus.rag.ticket.model.entity;

import com.argus.rag.ticket.model.enums.ExecutionResult;
import com.argus.rag.ticket.model.enums.ExecutionType;
import com.argus.rag.ticket.model.enums.ExecutionVerification;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单执行记录实体，映射 ticket_executions 表。
 * 每次执行尝试独立一行，不覆盖失败记录。
 */
@TableName("ticket_executions")
@Data
public class TicketExecution {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属工单 ID */
    private Long ticketId;

    /** 执行类型 */
    private ExecutionType executionType;

    /** 执行结果 */
    private ExecutionResult result;

    /** 失败信息 */
    private String errorMessage;

    /** 请求载荷（JSON，敏感信息需脱敏） */
    private String requestPayload;

    /** 响应载荷 */
    private String responsePayload;

    /** 第几次尝试（从 1 起） */
    private Integer attemptNo;

    /** 业务操作幂等键 */
    private String idempotencyKey;

    /** 触发执行的人用户 ID */
    private Long operatorUserId;

    /** 开始时间 */
    private LocalDateTime startedAt;

    /** 结束时间 */
    private LocalDateTime finishedAt;

    /** 人工核验结论（null = 未核验），与执行器原始 result 分离 */
    private ExecutionVerification verificationStatus;

    /** 核验操作人用户 ID */
    private Long verifiedBy;

    /** 核验时间 */
    private LocalDateTime verifiedAt;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
