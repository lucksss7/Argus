package com.argus.rag.ticket.executor;

/**
 * 账号开通执行结果（受控错误码模型）。
 * <p>
 * 执行器只返回一个 {@link ProvisionErrorCode} 与外部引用，不再携带自由文本 message。
 * 面向用户/Agent 的安全文案由错误码映射派生（见 {@link #message()}），
 * 从而在结构上杜绝执行器把 JDBC 连接串、凭据、异常堆栈等敏感信息写入结果。
 * <p>
 * {@link Outcome} 由 {@link ProvisionErrorCode#outcome()} 派生，保证错误码与结果类型一致；
 * 工厂方法对缺失或与预期类别不匹配的错误码做安全降级，降级后 outcome 仍符合工厂语义，
 * 不改变执行状态机。
 */
public record ProvisionResult(
        ProvisionErrorCode code,
        String externalRef
) {

    public ProvisionResult {
        if (code == null) {
            code = ProvisionErrorCode.UNKNOWN_FAILURE;
        }
    }

    public enum Outcome {
        /** 明确成功 */
        SUCCESS,
        /** 明确失败 */
        FAILED,
        /** 结果不确定，需人工核实 */
        UNCERTAIN
    }

    /**
     * 受控错误码：每个码绑定一个固定 {@link Outcome} 与一段固定安全文案。
     * 安全文案只在此枚举内声明，运行时不接受任何自由文本。
     */
    public enum ProvisionErrorCode {
        /** 明确成功 */
        PROVISIONED(Outcome.SUCCESS, "账号开通成功"),
        ALREADY_PROVISIONED(Outcome.SUCCESS, "账号已存在，无需重复开通"),
        /** 明确失败 */
        TARGET_REFUSED(Outcome.FAILED, "目标系统拒绝：账号开通失败"),
        TARGET_NOT_FOUND(Outcome.FAILED, "目标系统未找到目标账号"),
        /** 结果不确定 */
        TARGET_TIMEOUT(Outcome.UNCERTAIN, "目标系统无响应，无法确认账号是否已开通，请人工核实"),
        TARGET_UNDETERMINED(Outcome.UNCERTAIN, "开通结果不确定，请人工核实"),
        EXECUTOR_EXCEPTION(Outcome.UNCERTAIN, "执行器调用异常，结果不确定，请联系管理员人工核实"),
        /** 安全降级兜底（明确失败） */
        UNKNOWN_FAILURE(Outcome.FAILED, "开通失败，请联系管理员");

        private final Outcome outcome;
        private final String safeMessage;

        ProvisionErrorCode(Outcome outcome, String safeMessage) {
            this.outcome = outcome;
            this.safeMessage = safeMessage;
        }

        public Outcome outcome() {
            return outcome;
        }

        public String safeMessage() {
            return safeMessage;
        }
    }

    /**
     * 结果类型由错误码派生，保证错误码与结果类型一致。
     */
    public Outcome outcome() {
        return code.outcome();
    }

    /**
     * 面向用户/Agent 的安全文案，恒为 {@link ProvisionErrorCode#safeMessage()} 的受控映射，
     * 不接收、也不透传执行器自由文本。
     */
    public String message() {
        return code.safeMessage();
    }

    /**
     * 明确成功。错误码缺失或非 SUCCESS 类别时降级为 {@link ProvisionErrorCode#PROVISIONED}。
     */
    public static ProvisionResult success(ProvisionErrorCode code, String externalRef) {
        return new ProvisionResult(
                code != null && code.outcome() == Outcome.SUCCESS ? code : ProvisionErrorCode.PROVISIONED,
                externalRef);
    }

    /**
     * 明确失败，不携带外部引用。错误码缺失或非 FAILED 类别时降级为
     * {@link ProvisionErrorCode#UNKNOWN_FAILURE}。
     */
    public static ProvisionResult failed(ProvisionErrorCode code) {
        return new ProvisionResult(
                code != null && code.outcome() == Outcome.FAILED ? code : ProvisionErrorCode.UNKNOWN_FAILURE,
                null);
    }

    /**
     * 结果不确定，需人工核实。错误码缺失或非 UNCERTAIN 类别时降级为
     * {@link ProvisionErrorCode#EXECUTOR_EXCEPTION}。
     */
    public static ProvisionResult uncertain(ProvisionErrorCode code, String externalRef) {
        return new ProvisionResult(
                code != null && code.outcome() == Outcome.UNCERTAIN ? code : ProvisionErrorCode.EXECUTOR_EXCEPTION,
                externalRef);
    }
}
