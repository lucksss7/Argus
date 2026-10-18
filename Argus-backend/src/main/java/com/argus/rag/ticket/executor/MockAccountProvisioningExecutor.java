package com.argus.rag.ticket.executor;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模拟账号开通执行器，用于 v1 联调与测试，不接入真实账号系统。
 * <p>
 * 结果由输入稳定决定，便于覆盖三种结局：
 * <ul>
 *   <li>targetUsername 含 "uncertain" → {@link ProvisionResult.Outcome#UNCERTAIN}</li>
 *   <li>targetUsername 含 "fail" → {@link ProvisionResult.Outcome#FAILED}</li>
 *   <li>其余 → {@link ProvisionResult.Outcome#SUCCESS}</li>
 * </ul>
 * 幂等去重仅用内存 ConcurrentHashMap 模拟，只能代表当前进程内去重，
 * 不能代表真实外部系统的持久化幂等能力。
 * <p>
 * 去重键由 {@code systemName + targetUsername + permissionLevel} 推导（见 {@link #execute} 实现），
 * 与数据库执行记录的 {@code idempotency_key}（{@code PROVISION_ACCOUNT:{ticketId}}）不是同一个概念：
 * 前者按目标账号去重，后者按工单给多次尝试分组。该内存去重不具备跨进程重启的持久性，进程重启后去重状态会丢失。
 */
@Component
public class MockAccountProvisioningExecutor implements AccountProvisioningExecutor {

    private final Map<String, String> provisioned = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public ProvisionResult execute(ProvisionCommand command) {
        String username = command.targetUsername();
        if (username.contains("uncertain")) {
            return ProvisionResult.uncertain(ProvisionResult.ProvisionErrorCode.TARGET_TIMEOUT, null);
        }
        if (username.contains("fail")) {
            return ProvisionResult.failed(ProvisionResult.ProvisionErrorCode.TARGET_REFUSED);
        }

        String key = command.systemName() + ":" + username + ":" + command.permissionLevel().name();
        String newRef = "mock-ext-" + sequence.incrementAndGet();
        String existing = provisioned.putIfAbsent(key, newRef);
        if (existing != null) {
            return ProvisionResult.success(ProvisionResult.ProvisionErrorCode.ALREADY_PROVISIONED, existing);
        }
        return ProvisionResult.success(ProvisionResult.ProvisionErrorCode.PROVISIONED, newRef);
    }
}
