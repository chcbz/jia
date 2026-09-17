package cn.jia.agent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers only the read-only preview switch; no legacy economy gate is enabled here. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EconomyReadOnlyPreviewProperties.class)
public class EconomyReadOnlyPreviewConfiguration {
}
