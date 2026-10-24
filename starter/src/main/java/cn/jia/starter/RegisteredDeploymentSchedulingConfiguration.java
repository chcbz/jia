package cn.jia.starter;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * LOCAL_TEST has no tenant-aware scheduled worker yet. Keep every scheduler
 * disabled instead of allowing a legacy whole-database scan to run locally.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!local-test-area")
@EnableScheduling
public class RegisteredDeploymentSchedulingConfiguration { }
