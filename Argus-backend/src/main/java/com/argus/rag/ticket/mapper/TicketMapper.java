package com.argus.rag.ticket.mapper;

import com.argus.rag.ticket.model.entity.Ticket;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 工单 Mapper。
 */
@Mapper
public interface TicketMapper extends BaseMapper<Ticket> {

    /** 基于当前状态的条件更新工单状态（仅当 status = fromStatus 时才更新），用于防止重复状态流转 */
    int updateTicketStatus(@Param("id") Long id, @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus);

    /** 基于当前状态的条件更新工单状态并记录审批人（审批通过/拒绝用） */
    int updateTicketDecision(@Param("id") Long id, @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus, @Param("approverUserId") Long approverUserId);

    /**
     * 插入工单，遇到唯一约束冲突（幂等键 / 工单号）时静默跳过并返回 0 行，而非抛出异常。
     * <p>用于并发建单幂等兜底：返回 1 表示新建成功，返回 0 表示命中唯一约束冲突，
     * 调用方随后按幂等键重查以区分是幂等键冲突还是工单号冲突。仅 PostgreSQL 有效（ON CONFLICT）。
     */
    int insertIgnoreConflict(Ticket ticket);

    /**
     * 原子递增工单的额外执行额度（仅当 status = APPROVED 时生效，返回 1 表示成功、0 表示状态已变化）。
     * <p>用于 RETRY_AUTHORIZE：与执行的 APPROVED -> IN_PROGRESS 条件更新对同一行串行化，避免并发竞态。</p>
     */
    int incrementExtraAttempts(@Param("id") Long id);
}
