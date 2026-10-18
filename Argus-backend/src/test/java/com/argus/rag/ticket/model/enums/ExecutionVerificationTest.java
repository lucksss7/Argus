package com.argus.rag.ticket.model.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("执行核验结论枚举")
class ExecutionVerificationTest {

    @Test
    @DisplayName("仅含确认成功与确认未执行，name() 与数据库字符串一致")
    void shouldExposeTwoValuesWithStableNames() {
        assertThat(ExecutionVerification.values())
                .extracting(ExecutionVerification::name)
                .containsExactly("CONFIRMED_SUCCESS", "CONFIRMED_NOT_EXECUTED");
    }
}
