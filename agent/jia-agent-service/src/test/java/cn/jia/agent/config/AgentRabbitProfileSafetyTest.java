package cn.jia.agent.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRabbitProfileSafetyTest {
    private static final List<String> M3_FALSE_DEFAULTS = List.of(
            "agent.command-outbox.enabled=false",
            "agent.rabbit-topology.enabled=false",
            "agent.rabbit-publish.enabled=false",
            "agent.rabbit-consume.enabled=false",
            "agent.rabbit-dispatch.enabled=false");
    private static final String RABBIT_AUTO_CONFIGURATION =
            "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration";

    @Test
    void devGreyProdAndTestProfilesExplicitlyDisableEveryM3Flag() throws Exception {
        Path root = apiRoot();
        for (String profile : starterProfiles()) {
            List<String> lines = Files.readAllLines(root.resolve(profile), StandardCharsets.UTF_8);
            for (String expected : M3_FALSE_DEFAULTS) {
                assertEquals(1, lines.stream().filter(expected::equals).count(),
                        profile + " -> " + expected);
            }
            assertFalse(lines.stream().anyMatch(line -> line.startsWith("agent.rabbit-broker.")),
                    profile + " must not embed an M3 broker endpoint");
        }
    }

    @Test
    void dangerousTestProfilesHaveNoFixedRabbitEndpointAndDisableAutoConfiguration()
            throws Exception {
        Path root = apiRoot();
        for (String profile : List.of(
                "starter/src/test/resources/application-test.properties",
                "sms/jia-sms-mapper/src/test/resources/application-test.properties",
                "sms/jia-sms-service/src/test/resources/application-test.properties")) {
            List<String> lines = Files.readAllLines(root.resolve(profile), StandardCharsets.UTF_8);
            assertFalse(lines.stream().anyMatch(line -> line.startsWith("spring.rabbitmq.")),
                    profile);
            assertTrue(lines.stream()
                    .filter(line -> line.startsWith("spring.autoconfigure.exclude="))
                    .anyMatch(line -> line.contains(RABBIT_AUTO_CONFIGURATION)), profile);
        }
    }

    @Test
    void existingSmsProductionAndDevelopmentRabbitConfigurationRemainsUntouched() throws Exception {
        Path root = apiRoot();
        assertTrue(Files.readString(root.resolve(
                "sms/jia-sms-starter/src/main/resources/application-dev.properties"))
                .contains("spring.rabbitmq.host=47.106.106.57"));
        assertTrue(Files.readString(root.resolve(
                "sms/jia-sms-starter/src/main/resources/application-prod.properties"))
                .contains("spring.rabbitmq.host=127.0.0.1"));
        assertTrue(Files.readString(root.resolve(
                "sms/jia-sms-service/src/main/java/cn/jia/sms/config/RabbitConfig.java"))
                .contains("new Queue(\"jia.sms\")"));
    }

    private static List<String> starterProfiles() {
        return List.of(
                "starter/src/main/resources/application-dev.properties",
                "starter/src/main/resources/application-grey.properties",
                "starter/src/main/resources/application-prod.properties",
                "starter/src/test/resources/application-test.properties");
    }

    private static Path apiRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle"))
                    && Files.isDirectory(candidate.resolve("agent"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Cannot locate API worktree root from " + current);
    }
}
