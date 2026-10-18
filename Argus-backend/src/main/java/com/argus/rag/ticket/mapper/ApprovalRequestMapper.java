package com.argus.rag.ticket.mapper;

import com.argus.rag.ticket.model.entity.ApprovalRequest;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 审批记录 Mapper。
 */
@Mapper
public interface ApprovalRequestMapper extends BaseMapper<ApprovalRequest> {

    /** 统计某工单审批通过的记录数，用于校验执行前存在有效的审批通过记录 */
    Long countApprovedByTicketId(@Param("ticketId") Long ticketId);
}
