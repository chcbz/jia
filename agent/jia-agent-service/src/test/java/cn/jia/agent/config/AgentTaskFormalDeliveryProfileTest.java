package cn.jia.agent.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentTaskFormalDeliveryProfileTest {
    @Test
    void productionProfileExplicitlyEnablesFormalDeliveries() throws Exception {
        List<String> declarations = Files.readAllLines(
                        apiRoot().resolve("starter/src/main/resources/application-prod.properties"),
                        StandardCharsets.UTF_8).stream()
                .filter(line -> line.startsWith("jia.agent.formal-delivery.enabled="))
                .toList();

        assertEquals(List.of("jia.agent.formal-delivery.enabled=true"), declarations);
    }

    private static Path apiRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle"))
                    && Files.isDirectory(candidate.resolve("starter"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Cannot locate API worktree root from " + current);
    }
}
