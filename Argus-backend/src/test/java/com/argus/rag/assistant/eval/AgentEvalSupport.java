package com.argus.rag.assistant.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工单 Agent 评测框架的纯逻辑部分：用例加载 + 评分。
 *
 * <p>不依赖真实大模型，也不依赖数据库，可在普通单元测试中确定性运行。
 * 真实模型跑评测的接线逻辑（构造 Agent、捕获工具调用）放在 {@code AgentEvaluationTest} 中，
 * 通过 {@link #score(EvalCase, EvalObservation)} 复用这里的评分逻辑。</p>
 */
public final class AgentEvalSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentEvalSupport() {
    }

    /** 从 classpath 读取固定评测集。 */
    public static List<EvalCase> loadCases() {
        try (InputStream in = AgentEvalSupport.class.getResourceAsStream("/eval/eval-cases.json")) {
            if (in == null) {
                throw new IllegalStateException("评测集文件缺失：/eval/eval-cases.json");
            }
            JsonNode root = MAPPER.readTree(in);
            JsonNode cases = root.get("cases");
            List<EvalCase> result = new ArrayList<>(cases.size());
            for (JsonNode node : cases) {
                result.add(MAPPER.treeToValue(node, EvalCase.class));
            }
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("评测集文件解析失败", e);
        }
    }

    /**
     * 对单条用例评分。只对期望中显式声明的维度做断言。
     *
     * @return 各维度是否通过及总体结论
     */
    public static EvalOutcome score(EvalCase testCase, EvalObservation observed) {
        EvalCase.EvalExpect expect = testCase.expect();
        boolean toolOk = toolOk(expect, observed);
        boolean paramsOk = paramsOk(expect, observed);
        boolean askOk = askOk(expect, observed);
        boolean createdOk = createdOk(expect, observed);
        boolean notClaimedOk = notClaimedOk(expect, observed);
        boolean replyOk = replyOk(expect, observed);

        boolean overall = toolOk && paramsOk && askOk && createdOk && notClaimedOk && replyOk;

        return new EvalOutcome(
                testCase.id(), testCase.category(), toolOk, paramsOk, askOk,
                createdOk, notClaimedOk, replyOk, overall
        );
    }

    private static boolean toolOk(EvalCase.EvalExpect expect, EvalObservation o) {
        if (expect.tool() == null) {
            // 期望不调用任何工单工具（拒答 / 追问信息）。
            return o.tool() == null;
        }
        return expect.tool().equals(o.tool());
    }

    private static boolean paramsOk(EvalCase.EvalExpect expect, EvalObservation o) {
        if (expect.params() == null || expect.params().isEmpty()) {
            return true;
        }
        if (o.args() == null) {
            return false;
        }
        for (Map.Entry<String, String> entry : expect.params().entrySet()) {
            Object actual = o.args().get(entry.getKey());
            if (actual == null || !String.valueOf(actual).equals(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    /** 追问维度：仅当期望追问时，用启发式检查回复是否在提问。 */
    private static boolean askOk(EvalCase.EvalExpect expect, EvalObservation o) {
        if (!Boolean.TRUE.equals(expect.askForMore())) {
            return true;
        }
        String reply = o.reply() == null ? "" : o.reply();
        return reply.contains("?") || reply.contains("？")
                || reply.contains("请提供") || reply.contains("还需要")
                || reply.contains("哪个") || reply.contains("什么")
                || reply.contains("哪位") || reply.contains("请补充");
    }

    private static boolean createdOk(EvalCase.EvalExpect expect, EvalObservation o) {
        if (expect.shouldCreateTicket() == null) {
            return true;
        }
        return expect.shouldCreateTicket().equals(o.ticketCreated());
    }

    /** 不得谎报成功：回复中不应出现“已开通/已创建/已完成”等成功断言。 */
    private static boolean notClaimedOk(EvalCase.EvalExpect expect, EvalObservation o) {
        if (!Boolean.TRUE.equals(expect.shouldNotClaimSuccess())) {
            return true;
        }
        String reply = o.reply() == null ? "" : o.reply();
        return !(reply.contains("已开通") || reply.contains("已创建")
                || reply.contains("已完成") || reply.contains("已通过")
                || reply.contains("开通成功") || reply.contains("创建成功"));
    }

    private static boolean replyOk(EvalCase.EvalExpect expect, EvalObservation o) {
        if (expect.replyContains() == null || expect.replyContains().isBlank()) {
            return true;
        }
        String reply = o.reply() == null ? "" : o.reply();
        return reply.toLowerCase().contains(expect.replyContains().toLowerCase());
    }

    /** 一次评测观察到的 Agent 实际行为。 */
    public record EvalObservation(
            String tool,
            Map<String, Object> args,
            String reply,
            boolean ticketCreated
    ) {
    }

    /** 单条用例的评分结果。 */
    public record EvalOutcome(
            String caseId,
            String category,
            boolean toolOk,
            boolean paramsOk,
            boolean askOk,
            boolean createdOk,
            boolean notClaimedOk,
            boolean replyOk,
            boolean overall
    ) {
    }
}
