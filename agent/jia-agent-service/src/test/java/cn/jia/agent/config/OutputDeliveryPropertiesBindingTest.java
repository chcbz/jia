package cn.jia.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputDeliveryPropertiesBindingTest {
    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsThroughEnableConfigurationPropertiesAndAppliesSafeDefaults() {
        RUNNER.withPropertyValues(
                        "agent.output-delivery.enabled=true",
                        "agent.output-delivery.storage-endpoint=http://127.0.0.1:19000",
                        "agent.output-delivery.storage-access-key=access",
                        "agent.output-delivery.storage-secret-key=secret",
                        "agent.output-delivery.cursor-signing-key=cursor-key")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    OutputDeliveryProperties properties = context.getBean(OutputDeliveryProperties.class);
                    assertTrue(properties.enabled());
                    assertEquals("http://127.0.0.1:19000", properties.storageEndpoint());
                    assertEquals("cyf-agent-outputs", properties.storageBucket());
                    assertEquals("127.0.0.1", properties.scannerHost());
                    assertEquals(3310, properties.scannerPort());
                    assertEquals(10, properties.retryMultiplier());
                    assertEquals(50L * 1024 * 1024, properties.archiveMemberMaxBytes());
                    assertEquals(90L * 1024 * 1024, properties.archiveTreeMaxBytes());
                    assertFalse(properties.writesPaused());
                });
    }

    @Test
    void invalidInfrastructureLimitsFailApplicationContext() {
        RUNNER.withPropertyValues(
                        "agent.output-delivery.enabled=true",
                        "agent.output-delivery.scanner-port=0")
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertTrue(failureChain(context.getStartupFailure())
                            .contains("invalid output delivery infrastructure limits"));
                });
    }

    private static String failureChain(Throwable failure) {
        StringBuilder chain = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            chain.append(current.getClass().getName()).append(':')
                    .append(current.getMessage()).append('\n');
        }
        return chain.toString();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OutputDeliveryProperties.class)
    static class PropertiesConfiguration {
    }
}
