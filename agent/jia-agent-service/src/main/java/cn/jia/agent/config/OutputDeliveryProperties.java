package cn.jia.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.output-delivery")
public record OutputDeliveryProperties(boolean enabled) {
}
