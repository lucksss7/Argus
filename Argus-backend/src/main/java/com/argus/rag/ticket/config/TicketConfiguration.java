package com.argus.rag.ticket.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 工单模块配置，注册工单配置属性。
 */
@Configuration
@EnableConfigurationProperties(TicketProperties.class)
public class TicketConfiguration {
}
