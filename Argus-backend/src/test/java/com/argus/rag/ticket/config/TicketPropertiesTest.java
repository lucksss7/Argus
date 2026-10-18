package com.argus.rag.ticket.config;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("工单配置属性")
class TicketPropertiesTest {

    @Test
    @DisplayName("默认最大执行次数为 3")
    void shouldDefaultMaxExecutionAttemptsToThree() {
        assertThat(new TicketProperties().getMaxExecutionAttempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("max-execution-attempts 通过前缀 argus.rag.ticket 绑定")
    void shouldBindMaxExecutionAttempts() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("argus.rag.ticket.max-execution-attempts", "5");

        TicketProperties bound = Binder.get(environment)
                .bind("argus.rag.ticket", Bindable.of(TicketProperties.class))
                .get();

        assertThat(bound.getMaxExecutionAttempts()).isEqualTo(5);
    }

    @Test
    @DisplayName("非法值 0 触发 @Min(1) 校验失败")
    void shouldRejectMaxExecutionAttemptsBelowOne() {
        TicketProperties properties = new TicketProperties();
        properties.setMaxExecutionAttempts(0);

        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        Set<ConstraintViolation<TicketProperties>> violations = validator.validate(properties);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("maxExecutionAttempts");
    }
}
