package cn.jia.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Independent default-off switch for the v1.7 read-only economy surface. */
@ConfigurationProperties(prefix = "economy.read-only-preview")
public record EconomyReadOnlyPreviewProperties(boolean enabled) {
}
