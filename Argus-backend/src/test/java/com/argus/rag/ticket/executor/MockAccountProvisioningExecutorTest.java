package com.argus.rag.ticket.executor;

import com.argus.rag.ticket.model.enums.PermissionLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("模拟账号开通执行器")
class MockAccountProvisioningExecutorTest {

    private final MockAccountProvisioningExecutor executor = new MockAccountProvisioningExecutor();

    @Test
    @DisplayName("普通用户名应开通成功并返回外部账号引用")
    void shouldProvisionSuccessfully() {
        ProvisionResult result = executor.execute(new ProvisionCommand("erp", "zhangsan", PermissionLevel.READ_ONLY));

        assertThat(result.outcome()).isEqualTo(ProvisionResult.Outcome.SUCCESS);
        assertThat(result.externalRef()).isNotBlank();
    }

    @Test
    @DisplayName("用户名含 fail 应明确失败")
    void shouldFailForFailUsername() {
        ProvisionResult result = executor.execute(new ProvisionCommand("erp", "zhangsan_fail", PermissionLevel.READ_ONLY));

        assertThat(result.outcome()).isEqualTo(ProvisionResult.Outcome.FAILED);
    }

    @Test
    @DisplayName("用户名含 uncertain 应结果不确定")
    void shouldBeUncertainForUncertainUsername() {
        ProvisionResult result = executor.execute(new ProvisionCommand("erp", "zhangsan_uncertain", PermissionLevel.READ_ONLY));

        assertThat(result.outcome()).isEqualTo(ProvisionResult.Outcome.UNCERTAIN);
    }

    @Test
    @DisplayName("同一目标重复开通应返回已存在（进程内幂等去重）")
    void shouldReturnExistingWhenAlreadyProvisioned() {
        ProvisionCommand command = new ProvisionCommand("erp", "zhangsan", PermissionLevel.READ_ONLY);

        ProvisionResult first = executor.execute(command);
        ProvisionResult second = executor.execute(command);

        assertThat(first.outcome()).isEqualTo(ProvisionResult.Outcome.SUCCESS);
        assertThat(second.outcome()).isEqualTo(ProvisionResult.Outcome.SUCCESS);
        assertThat(second.externalRef()).isEqualTo(first.externalRef());
        assertThat(second.message()).contains("已存在");
    }

    @Test
    @DisplayName("成功工厂：缺失或类别不匹配的错误码应降级为 PROVISIONED")
    void shouldDegradeSuccessFactoryToProvisioned() {
        assertThat(ProvisionResult.success(null, "ref").outcome())
                .isEqualTo(ProvisionResult.Outcome.SUCCESS);
        assertThat(ProvisionResult.success(null, "ref").message()).isEqualTo("账号开通成功");

        ProvisionResult mismatched = ProvisionResult.success(
                ProvisionResult.ProvisionErrorCode.TARGET_REFUSED, "ref");
        assertThat(mismatched.outcome()).isEqualTo(ProvisionResult.Outcome.SUCCESS);
        assertThat(mismatched.code()).isEqualTo(ProvisionResult.ProvisionErrorCode.PROVISIONED);
        assertThat(mismatched.message()).isEqualTo("账号开通成功");
    }

    @Test
    @DisplayName("失败工厂：缺失或类别不匹配的错误码应降级为 UNKNOWN_FAILURE")
    void shouldDegradeFailedFactoryToUnknownFailure() {
        assertThat(ProvisionResult.failed(null).outcome()).isEqualTo(ProvisionResult.Outcome.FAILED);
        assertThat(ProvisionResult.failed(null).message()).isEqualTo("开通失败，请联系管理员");
        assertThat(ProvisionResult.failed(null).externalRef()).isNull();

        ProvisionResult mismatched = ProvisionResult.failed(
                ProvisionResult.ProvisionErrorCode.TARGET_TIMEOUT);
        assertThat(mismatched.outcome()).isEqualTo(ProvisionResult.Outcome.FAILED);
        assertThat(mismatched.code()).isEqualTo(ProvisionResult.ProvisionErrorCode.UNKNOWN_FAILURE);
        assertThat(mismatched.message()).isEqualTo("开通失败，请联系管理员");
    }

    @Test
    @DisplayName("不确定工厂：缺失或类别不匹配的错误码应降级为 EXECUTOR_EXCEPTION")
    void shouldDegradeUncertainFactoryToExecutorException() {
        assertThat(ProvisionResult.uncertain(null, null).outcome()).isEqualTo(ProvisionResult.Outcome.UNCERTAIN);
        assertThat(ProvisionResult.uncertain(null, null).message())
                .isEqualTo("执行器调用异常，结果不确定，请联系管理员人工核实");

        ProvisionResult mismatched = ProvisionResult.uncertain(
                ProvisionResult.ProvisionErrorCode.TARGET_REFUSED, "ref");
        assertThat(mismatched.outcome()).isEqualTo(ProvisionResult.Outcome.UNCERTAIN);
        assertThat(mismatched.code()).isEqualTo(ProvisionResult.ProvisionErrorCode.EXECUTOR_EXCEPTION);
    }

    @Test
    @DisplayName("message() 恒为错误码映射的受控文案，与外部引用无关")
    void shouldReturnControlledMessageFromCode() {
        assertThat(ProvisionResult.failed(ProvisionResult.ProvisionErrorCode.TARGET_REFUSED).message())
                .isEqualTo("目标系统拒绝：账号开通失败");
        assertThat(ProvisionResult.uncertain(ProvisionResult.ProvisionErrorCode.TARGET_TIMEOUT, "x").message())
                .isEqualTo("目标系统无响应，无法确认账号是否已开通，请人工核实");
        assertThat(ProvisionResult.success(ProvisionResult.ProvisionErrorCode.ALREADY_PROVISIONED, "x").message())
                .isEqualTo("账号已存在，无需重复开通");
    }
}
