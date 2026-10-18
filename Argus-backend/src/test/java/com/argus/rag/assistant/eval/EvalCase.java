package com.argus.rag.assistant.eval;

import java.util.Map;

/**
 * 工单 Agent 固定评测集的单条用例及其期望行为。
 *
 * <p>与 {@code src/test/resources/eval/eval-cases.json} 一一对应，由 Jackson 反序列化。
 * 期望字段全部可选：只对显式声明的维度做断言，未声明的维度不评分。</p>
 *
 * @param id       用例唯一标识
 * @param category 场景类别（normal-create / missing-info / similar-ticket /
 *                 insufficient-knowledge / permission-limited / approval-reject /
 *                 duplicate-request / tool-failure）
 * @param mode     Agent 工具模式（{@code AssistantToolMode.name()} 字符串）
 * @param input    用户自然语言输入
 * @param expect   期望行为
 */
public record EvalCase(
        String id,
        String category,
        String mode,
        String input,
        EvalExpect expect
) {
    /**
     * 期望行为。所有字段均可为 {@code null}，表示不对此维度做断言。
     *
     * @param tool                 期望调用的工具名（{@code null} 表示不应调用任何工单工具）
     * @param params               期望抽取的业务参数子集（键为参数名，值为期望字符串；仅做子集匹配）
     * @param askForMore           是否应当追问补充信息而非直接调用工具
     * @param shouldCreateTicket   是否应当真实创建工单（端到端结果）
     * @param shouldRefuse         是否应当拒答/转人工（不调用业务工具）
     * @param toolReturnsFailure   工具是否应返回失败（用于工具失败场景）
     * @param shouldNotClaimSuccess 是否不得把未完成操作谎报为成功
     * @param replyContains        最终回复中应包含的子串（{@code null} 表示不检查）
     */
    public record EvalExpect(
            String tool,
            Map<String, String> params,
            Boolean askForMore,
            Boolean shouldCreateTicket,
            Boolean shouldRefuse,
            Boolean toolReturnsFailure,
            Boolean shouldNotClaimSuccess,
            String replyContains
    ) {
    }
}
