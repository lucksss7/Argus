package com.argus.rag.ticket.executor;

/**
 * 账号开通执行器。真实实现接入外部账号系统，当前 v1 使用模拟实现。
 * <p>
 * 注意区分两个「幂等键」概念，二者不是同一个东西：
 * <ul>
 *   <li>数据库 {@code ticket_executions.idempotency_key}：值为 {@code PROVISION_ACCOUNT:{ticketId}}，
 *       用于把同一工单的多次执行尝试关联起来，配合 {@code attempt_no} 区分不同尝试；
 *       其 {@code UNIQUE(idempotency_key, attempt_no)} 只保证每次尝试各记一行。</li>
 *   <li>执行器业务去重键：由目标账号身份推导（当前 mock 为
 *       {@code systemName + targetUsername + permissionLevel}），
 *       用于模拟同一目标账号的重复开通去重。</li>
 * </ul>
 * 数据库唯一约束不保证外部副作用只发生一次，真正的去重依赖执行器以稳定
 * 业务幂等键到外部系统去重。
 */
public interface AccountProvisioningExecutor {

    ProvisionResult execute(ProvisionCommand command);
}
