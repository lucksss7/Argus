package com.argus.rag.ticket.config;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 工单模块配置属性，前缀 {@code argus.rag.ticket}。
 */
@Validated
@ConfigurationProperties(prefix = "argus.rag.ticket")
@Data
public class TicketProperties {

    /** 每张工单允许的最大执行尝试次数（{@code attempt_no} 上限，从 1 起计） */
    @Min(1)
    private int maxExecutionAttempts = 3;
}
