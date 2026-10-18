package com.argus.rag.assistant.eval;

import com.argus.rag.assistant.eval.AgentEvalSupport.EvalObservation;
import com.argus.rag.assistant.eval.AgentEvalSupport.EvalOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评测框架的确定性自检：不依赖真实大模型，验证用例加载与评分逻辑本身正确。
 */
class AgentEvalSupportTest {

    @Test
    void 加载评测集_数量与类别齐全() {
        List<EvalCase> cases = AgentEvalSupport.loadCases();

        assertThat(cases).hasSize(100);
        assertThat(cases).allSatisfy(c -> assertThat(c.id()).isNotBlank());
        assertThat(cases).allSatisfy(c -> assertThat(c.input()).isNotBlank());
        assertThat(cases).allSatisfy(c -> assertThat(c.expect()).isNotNull());

        Set<String> categories = cases.stream()
                .map(EvalCase::category)
                .collect(Collectors.toSet());
        assertThat(categories).containsExactlyInAnyOrder(
                "normal-create", "missing-info", "similar-ticket",
                "insufficient-knowledge", "permission-limited",
                "approval-reject", "duplicate-request", "tool-failure"
        );

        // id 全局唯一，避免用例被覆盖或重复计分。
        assertThat(cases.stream().map(EvalCase::id).collect(Collectors.toSet()))
                .hasSize(100);
    }

    @Test
    void 正常建单_工具与参数都对_整体通过() {
        EvalCase c = byId("NORMAL-001");

        EvalOutcome outcome = AgentEvalSupport.score(c, new AgentEvalSupport.EvalObservation(
                "createTicket",
                Map.of("systemName", "erp", "targetUsername", "张三", "permissionLevel", "READ_ONLY"),
                "已为你创建工单",
                true
        ));

        assertThat(outcome.toolOk()).isTrue();
        assertThat(outcome.paramsOk()).isTrue();
        assertThat(outcome.createdOk()).isTrue();
        assertThat(outcome.overall()).isTrue();
    }

    @Test
    void 正常建单_选错工具_整体失败() {
        EvalCase c = byId("NORMAL-001");

        EvalOutcome outcome = AgentEvalSupport.score(c, new AgentEvalSupport.EvalObservation(
                "queryTickets",
                Map.of(),
                "我查了一下",
                false
        ));

        assertThat(outcome.toolOk()).isFalse();
        assertThat(outcome.overall()).isFalse();
    }

    @Test
    void 正常建单_参数抽取错误_参数维度失败() {
        EvalCase c = byId("NORMAL-003");

        EvalOutcome outcome = AgentEvalSupport.score(c, new AgentEvalSupport.EvalObservation(
                "createTicket",
                Map.of("systemName", "erp", "targetUsername", "王五", "permissionLevel", "READ_ONLY"),
                "已创建",
                true
        ));

        assertThat(outcome.toolOk()).isTrue();
        assertThat(outcome.paramsOk()).isFalse();
        assertThat(outcome.overall()).isFalse();
    }

    @Test
    void 信息缺失_应追问_未调用工具且回复在提问_通过() {
        EvalCase c = byId("MISS-001");

        EvalOutcome outcome = AgentEvalSupport.score(c, new AgentEvalSupport.EvalObservation(
                null,
                null,
                "请问需要为哪个系统开通账号？",
                false
        ));

        assertThat(outcome.toolOk()).isTrue();
        assertThat(outcome.askOk()).isTrue();
        assertThat(outcome.overall()).isTrue();
    }

    @Test
    void 信息缺失_错误地直接建单_失败() {
        EvalCase c = byId("MISS-002");

        EvalOutcome outcome = AgentEvalSupport.score(c, new AgentEvalSupport.EvalObservation(
                "createTicket",
                Map.of("targetUsername", "张三"),
                "已创建",
                true
        ));

        assertThat(outcome.toolOk()).isFalse();
        assertThat(outcome.overall()).isFalse();
    }

    @Test
    void 权限受限_应拒答_却调用工具_失败() {
        EvalCase c = byId("PERM-001");

        EvalOutcome outcome = AgentEvalSupport.score(c, new AgentEvalSupport.EvalObservation(
                "createTicket",
                Map.of("targetUsername", "所有用户"),
                "已创建",
                true
        ));

        assertThat(outcome.toolOk()).isFalse();
        assertThat(outcome.overall()).isFalse();
    }

    @Test
    void 工具失败_不得谎报成功_回复出现成功字眼则失败() {
        EvalCase c = byId("FAIL-001");

        EvalOutcome lie = AgentEvalSupport.score(c, new AgentEvalSupport.EvalObservation(
                "queryTickets",
                Map.of(),
                "已为你查到并已开通账号",
                false
        ));
        assertThat(lie.notClaimedOk()).isFalse();
        assertThat(lie.overall()).isFalse();

        EvalOutcome honest = AgentEvalSupport.score(c, new AgentEvalSupport.EvalObservation(
                "queryTickets",
                Map.of(),
                "抱歉，查询失败了，请稍后重试",
                false
        ));
        assertThat(honest.toolOk()).isTrue();
        assertThat(honest.notClaimedOk()).isTrue();
        assertThat(honest.overall()).isTrue();
    }

    @Test
    void 知识检索模式_期望调用knowledgeSearch() {
        EvalCase c = byId("KNOW-005");

        EvalOutcome outcome = AgentEvalSupport.score(c, new AgentEvalSupport.EvalObservation(
                "knowledgeSearch",
                Map.of("query", "如何重启公司路由器"),
                "已检索到知识库内容",
                false
        ));

        assertThat(outcome.toolOk()).isTrue();
        assertThat(outcome.overall()).isTrue();
    }

    private EvalCase byId(String id) {
        return AgentEvalSupport.loadCases().stream()
                .filter(c -> c.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到用例：" + id));
    }
}
