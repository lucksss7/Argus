package com.argus.rag.ticket.mapper;

import com.argus.rag.ticket.model.entity.TicketExecution;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 工单执行记录 Mapper。
 */
@Mapper
public interface TicketExecutionMapper extends BaseMapper<TicketExecution> {

    /** 统计某工单尚未人工核验的 UNCERTAIN 执行记录数（result = UNCERTAIN 且 verification_status 为空） */
    Long countUncertainByTicketId(@Param("ticketId") Long ticketId);

    /** 查询某工单已存在的最大尝试序号（无记录时返回 0） */
    Integer selectMaxAttemptNoByTicketId(@Param("ticketId") Long ticketId);

    /** 基于当前状态的条件更新执行结果（仅当 result = PENDING 时生效），防止同一尝试被重复写入 */
    int updateExecutionResult(@Param("id") Long id,
                              @Param("result") String result,
                              @Param("errorMessage") String errorMessage,
                              @Param("responsePayload") String responsePayload,
                              @Param("finishedAt") LocalDateTime finishedAt);

    /**
     * 条件写入人工核验结论（仅当 result = UNCERTAIN 且尚未核验时生效），防止重复核验。
     * <p>只更新 verification_status / verified_by / verified_at，绝不覆盖原始 result。</p>
     */
    int updateVerification(@Param("id") Long id,
                           @Param("verificationStatus") String verificationStatus,
                           @Param("verifiedBy") Long verifiedBy,
                           @Param("verifiedAt") LocalDateTime verifiedAt);
}
