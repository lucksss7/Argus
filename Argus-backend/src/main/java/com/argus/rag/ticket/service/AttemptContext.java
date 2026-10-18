package com.argus.rag.ticket.service;

import com.argus.rag.ticket.executor.ProvisionCommand;

/**
 * 一次执行尝试的上下文，在事务开始（begin）与事务结束（finalize）之间传递。
 *
 * @param executionRecordId 执行记录 ID，用于最终写入该次尝试的结果
 * @param ticketId          工单 ID
 * @param attemptNo         尝试序号（从 1 起）
 * @param command           传给执行器的开通命令
 */
public record AttemptContext(
        Long executionRecordId,
        Long ticketId,
        int attemptNo,
        ProvisionCommand command
) {
}
