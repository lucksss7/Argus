package com.argus.rag.ticket.model.entity;

import com.argus.rag.ticket.model.enums.DispositionType;
import com.argus.rag.ticket.model.enums.ExecutionVerification;
import com.argus.rag.ticket.model.enums.TicketStatus;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V2 迁移新增字段 / 新表与实体之间的映射校验（不连库）。
 * <p>通过反射锁定 {@code @TableName}、自增主键策略与字段类型，避免表名 / 列名 / 类型
 * 只在真实数据库运行时才暴露不一致。</p>
 */
@DisplayName("V2 人工处置相关实体映射")
class ManualDispositionEntityMappingTest {

    @Test
    @DisplayName("Ticket 映射 tickets 表，extraAttempts 为 Integer 且默认 0")
    void ticketMapsExtraAttemptsWithZeroDefault() throws NoSuchFieldException {
        assertThat(Ticket.class.getAnnotation(TableName.class).value()).isEqualTo("tickets");
        assertThat(Ticket.class.getDeclaredField("extraAttempts").getType()).isEqualTo(Integer.class);
        assertThat(new Ticket().getExtraAttempts()).isZero();
    }

    @Test
    @DisplayName("TicketExecution 新增三个核验字段，类型与 V2 列一致")
    void ticketExecutionMapsVerificationColumns() throws NoSuchFieldException {
        assertThat(TicketExecution.class.getAnnotation(TableName.class).value()).isEqualTo("ticket_executions");
        assertThat(TicketExecution.class.getDeclaredField("verificationStatus").getType())
                .isEqualTo(ExecutionVerification.class);
        assertThat(TicketExecution.class.getDeclaredField("verifiedBy").getType()).isEqualTo(Long.class);
        assertThat(TicketExecution.class.getDeclaredField("verifiedAt").getType()).isEqualTo(LocalDateTime.class);
    }

    @Test
    @DisplayName("TicketDisposition 完整映射 ticket_dispositions 表（8 列 + 自增主键）")
    void ticketDispositionMapsAllColumns() throws NoSuchFieldException {
        assertThat(TicketDisposition.class.getAnnotation(TableName.class).value())
                .isEqualTo("ticket_dispositions");

        Field id = TicketDisposition.class.getDeclaredField("id");
        TableId tableId = id.getAnnotation(TableId.class);
        assertThat(tableId).isNotNull();
        assertThat(tableId.type()).isEqualTo(IdType.AUTO);

        assertThat(fieldType("ticketId")).isEqualTo(Long.class);
        assertThat(fieldType("executionId")).isEqualTo(Long.class);
        assertThat(fieldType("dispositionType")).isEqualTo(DispositionType.class);
        assertThat(fieldType("reason")).isEqualTo(String.class);
        assertThat(fieldType("operatorUserId")).isEqualTo(Long.class);
        assertThat(fieldType("fromStatus")).isEqualTo(TicketStatus.class);
        assertThat(fieldType("toStatus")).isEqualTo(TicketStatus.class);
        assertThat(fieldType("createdAt")).isEqualTo(LocalDateTime.class);
    }

    private static Class<?> fieldType(String name) throws NoSuchFieldException {
        return TicketDisposition.class.getDeclaredField(name).getType();
    }
}
