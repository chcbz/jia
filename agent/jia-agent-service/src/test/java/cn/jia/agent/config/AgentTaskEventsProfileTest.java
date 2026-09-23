package cn.jia.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskEventsProfileTest {
    private static final String PREFIX = "agent.task-events.";
    private static final String PRODUCTION_CLIENT = "jiafewnnv58ec2379c";
    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
            .withUserConfiguration(AgentTaskEventsConfiguration.class);

    @Test
    void bundledDevGreyAndTestProfilesExplicitlyRemainDisabled() throws Exception {
        Path root = apiRoot();
        for (String profile : List.of(
                "starter/src/main/resources/application-dev.properties",
                "starter/src/main/resources/application-grey.properties",
                "starter/src/test/resources/application-test.properties")) {
            List<String> declarations = taskEventDeclarations(root.resolve(profile));
            assertEquals(List.of("agent.task-events.enabled=false"), declarations, profile);
            RUNNER.withPropertyValues(declarations.toArray(String[]::new)).run(context -> {
                assertNull(context.getStartupFailure(), profile);
                assertFalse(context.getBean(AgentTaskEventsGate.class)
                        .allows("0", PRODUCTION_CLIENT), profile);
            });
        }
    }

    @Test
    void bundledProductionProfileEnablesOnlyTheObservedExactTenantClientScope()
            throws Exception {
        Path profile = apiRoot().resolve(
                "starter/src/main/resources/application-prod.properties");
        List<String> declarations = taskEventDeclarations(profile);
        assertEquals(List.of(
                "agent.task-events.enabled=true",
                "agent.task-events.allowed-scopes[0].tenant-id=0",
                "agent.task-events.allowed-scopes[0].client-id=" + PRODUCTION_CLIENT),
                declarations);

        RUNNER.withPropertyValues(declarations.toArray(String[]::new)).run(context -> {
            assertNull(context.getStartupFailure());
            AgentTaskEventsGate gate = context.getBean(AgentTaskEventsGate.class);
            assertTrue(gate.allows("0", PRODUCTION_CLIENT));
            assertFalse(gate.allows("0", PRODUCTION_CLIENT.toUpperCase()));
            assertFalse(gate.allows("chcbz", PRODUCTION_CLIENT));
            assertFalse(gate.allows("0", PRODUCTION_CLIENT + "-other"));
            assertFalse(gate.allows("0", "*"));
        });
    }

    private static List<String> taskEventDeclarations(Path profile) throws Exception {
        return Files.readAllLines(profile, StandardCharsets.UTF_8).stream()
                .filter(line -> line.startsWith(PREFIX))
                .toList();
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
