package com.argus.rag.ticket.model.vo;

import com.argus.rag.ticket.model.enums.ExecutionResult;
import com.argus.rag.ticket.model.enums.ExecutionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("执行记录 VO 格式兜底")
class ExecutionRecordVOTest {

    /** 与 {@link ExecutionRecordVO} 的 MAX_ERROR_MESSAGE_LENGTH 保持一致 */
    private static final int MAX = 200;

    @Test
    @DisplayName("null 保持 null")
    void shouldKeepNullAsNull() {
        assertThat(vo(null).errorMessage()).isNull();
    }

    @Test
    @DisplayName("空字符串、纯空白与纯控制字符串统一为 null")
    void shouldNormalizeBlankToNull() {
        assertThat(vo("").errorMessage()).isNull();
        assertThat(vo("   ").errorMessage()).isNull();
        assertThat(vo("\u0000\u0001\u0002").errorMessage()).isNull();
    }

    @Test
    @DisplayName("正常中文与常见标点保持原样")
    void shouldPreserveNormalChineseText() {
        assertThat(vo("开通失败，请联系管理员。").errorMessage())
                .isEqualTo("开通失败，请联系管理员。");
    }

    @Test
    @DisplayName("控制字符被移除，其余字符顺序保持")
    void shouldStripControlCharacters() {
        assertThat(vo("账号\u0000开通\n失败\t").errorMessage())
                .isEqualTo("账号开通失败");
    }

    @Test
    @DisplayName("超长消息被截断到上限长度")
    void shouldCapOverlongMessage() {
        assertThat(vo("x".repeat(500)).errorMessage()).isEqualTo("x".repeat(MAX));
    }

    @Test
    @DisplayName("按码点截断，不产生不完整的代理字符对")
    void shouldNotSplitSurrogatePairOnTruncation() {
        // 199 个 ASCII + 1 个增补字符（emoji）= 200 码点，恰在上限内，emoji 完整保留
        ExecutionRecordVO within = vo("a".repeat(MAX - 1) + "😀");
        assertThat(within.errorMessage()).endsWith("😀");
        assertThat(within.errorMessage()).hasSize(MAX + 1); // 199 + 2 个代理字符

        // 200 个 ASCII + 1 个增补字符 = 201 码点，超出上限，emoji 整体丢弃，不留半个代理
        ExecutionRecordVO over = vo("a".repeat(MAX) + "😀");
        assertThat(over.errorMessage()).isEqualTo("a".repeat(MAX));
        assertThat(over.errorMessage()).doesNotContain("😀");
    }

    @Test
    @DisplayName("记录字段结构（JSON 结构）与其余字段保持不变")
    void shouldKeepRecordStructureAndOtherFieldsUnchanged() {
        // 记录组件即 Jackson 序列化属性，断言名称与数量不变 = 无 errorCode、无新增字段
        assertThat(ExecutionRecordVO.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("id", "attemptNo", "executionType", "result", "errorMessage",
                        "startedAt", "finishedAt", "verificationStatus", "verifiedAt");

        ExecutionRecordVO v = vo("开通失败，请联系管理员");
        assertThat(v.id()).isEqualTo(100L);
        assertThat(v.attemptNo()).isEqualTo(1);
        assertThat(v.executionType()).isEqualTo(ExecutionType.PROVISION_ACCOUNT);
        assertThat(v.result()).isEqualTo(ExecutionResult.FAILED);
        assertThat(v.startedAt()).isEqualTo(LocalDateTime.of(2026, 1, 2, 3, 4));
        assertThat(v.finishedAt()).isEqualTo(LocalDateTime.of(2026, 1, 2, 3, 5));
        assertThat(v.verificationStatus()).isNull();
        assertThat(v.verifiedAt()).isNull();
    }

    private static ExecutionRecordVO vo(String errorMessage) {
        return new ExecutionRecordVO(
                100L, 1, ExecutionType.PROVISION_ACCOUNT, ExecutionResult.FAILED,
                errorMessage,
                LocalDateTime.of(2026, 1, 2, 3, 4),
                LocalDateTime.of(2026, 1, 2, 3, 5),
                null, null);
    }
}
