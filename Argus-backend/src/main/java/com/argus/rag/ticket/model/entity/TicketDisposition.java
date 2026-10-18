package com.argus.rag.ticket.model.entity;

import com.argus.rag.ticket.model.enums.DispositionType;
import com.argus.rag.ticket.model.enums.TicketStatus;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 人工处置审计实体，映射 ticket_dispositions 表。
 * <p>不可变追加记录：谁、何时、做了什么、原因、处置前后工单状态，只增不改。</p>
 */
@TableName("ticket_dispositions")
@Data
public class TicketDisposition {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属工单 ID */
    private Long ticketId;

    /** 关联执行记录 ID（仅 CONFIRM_* 指向被核验记录，其余为 null） */
    private Long executionId;

    /** 处置类型 */
    private DispositionType dispositionType;

    /** 处置原因（必填） */
    private String reason;

    /** 操作人用户 ID（无 FK：users 表不在 Flyway 管理范围） */
    private Long operatorUserId;

    /** 处置前工单状态 */
    private TicketStatus fromStatus;

    /** 处置后工单状态 */
    private TicketStatus toStatus;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
