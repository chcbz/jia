package cn.jia;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Normal deployments retain scheduling; a fixed-tenant grey process cannot run shared background scans. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "cyf.security.fixed-tenant.enabled", havingValue = "false", matchIfMissing = true)
public class BackgroundSchedulingConfiguration {
}
