package cn.jia.agent.service.impl;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskWorkspaceFixtureManifestTest {
    @Test
    void c04FixtureManifestIsSortedUniqueCompleteAndHasExecutableDigestCommand()
            throws Exception {
        Path root = apiRoot();
        Path directory = root.resolve("agent/jia-agent-service/src/test/resources/c04");
        List<String> entries = Files.readAllLines(
                        directory.resolve("fixture-files.txt"), StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank() && !line.stripLeading().startsWith("#"))
                .toList();
        assertEquals(new TreeSet<>(entries).stream().toList(), entries);
        assertEquals(entries.size(), new TreeSet<>(entries).size());
        entries.forEach(entry -> assertTrue(Files.isRegularFile(root.resolve(entry)), entry));
        Path command = directory.resolve("compute-fixture-digest.sh");
        assertTrue(Files.isExecutable(command));
        String script = Files.readString(command, StandardCharsets.UTF_8);
        assertTrue(script.contains("printf '%s\\0%s\\n'"));
        assertTrue(script.contains("sha256sum"));
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
