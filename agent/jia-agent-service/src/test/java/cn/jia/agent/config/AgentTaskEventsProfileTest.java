package cn.jia.agent.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentTaskEventsProfileTest {
    private static final String EXPLICIT_FALSE = "agent.task-events.enabled=false";

    @Test
    void bundledDevGreyProdAndTestProfilesExplicitlyRemainDisabled() throws Exception {
        Path root = apiRoot();
        for (String profile : List.of(
                "starter/src/main/resources/application-dev.properties",
                "starter/src/main/resources/application-grey.properties",
                "starter/src/main/resources/application-prod.properties",
                "starter/src/test/resources/application-test.properties")) {
            List<String> declarations = Files.readAllLines(
                            root.resolve(profile), StandardCharsets.UTF_8).stream()
                    .filter(line -> line.startsWith("agent.task-events.enabled="))
                    .toList();
            assertEquals(List.of(EXPLICIT_FALSE), declarations, profile);
        }
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
