package com.argus.rag.ticket.model.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("工单状态枚举")
class TicketStatusTest {

    @Test
    @DisplayName("既有状态顺序与语义不变，并在末尾追加 ABORTED")
    void shouldAppendAbortedWithoutChangingExistingStates() {
        assertThat(TicketStatus.values())
                .containsExactly(
                        TicketStatus.OPEN,
                        TicketStatus.PENDING_APPROVAL,
                        TicketStatus.APPROVED,
                        TicketStatus.IN_PROGRESS,
                        TicketStatus.RESOLVED,
                        TicketStatus.CLOSED,
                        TicketStatus.REJECTED,
                        TicketStatus.CANCELLED,
                        TicketStatus.ABORTED);

        assertThat(TicketStatus.ABORTED.name()).isEqualTo("ABORTED");
    }
}
