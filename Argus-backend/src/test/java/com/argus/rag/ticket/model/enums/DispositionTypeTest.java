package com.argus.rag.ticket.model.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("人工处置类型枚举")
class DispositionTypeTest {

    @Test
    @DisplayName("含四种处置类型，name() 与数据库字符串一致")
    void shouldExposeFourValuesWithStableNames() {
        assertThat(DispositionType.values())
                .extracting(DispositionType::name)
                .containsExactly("CONFIRM_SUCCESS", "CONFIRM_NOT_EXECUTED", "RETRY_AUTHORIZE", "TERMINATE");
    }
}
