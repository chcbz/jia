package cn.jia.agent.config;

import cn.jia.agent.service.AgentTaskArtifactStorage;
import cn.jia.agent.service.impl.DisabledAgentTaskArtifactStorage;
import cn.jia.agent.service.impl.FileSystemAgentTaskArtifactStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskArtifactStorageConfigurationF02Test {
    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
            .withUserConfiguration(AgentTaskArtifactStorageConfiguration.class);

    @TempDir
    Path temporaryDirectory;

    @Test
    void absentSwitchIsDisabledAndDoesNotCreateACandidateDirectory() {
        Path candidate = temporaryDirectory.resolve("must-not-exist");

        RUNNER.withPropertyValues(
                        "agent.task-artifact-storage.root-directory=" + candidate)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertInstanceOf(DisabledAgentTaskArtifactStorage.class,
                            context.getBean(AgentTaskArtifactStorage.class));
                    assertFalse(Files.exists(candidate));
                });
    }

    @Test
    void explicitEnableWithAbsoluteRootCreatesFileStore() {
        Path root = temporaryDirectory.resolve("artifact-root").toAbsolutePath();

        RUNNER.withPropertyValues(
                        "agent.task-artifact-storage.enabled=true",
                        "agent.task-artifact-storage.root-directory=" + root,
                        "agent.task-artifact-storage.max-content-bytes=1024",
                        "agent.task-artifact-storage.allowed-mime-types[0]=application/octet-stream")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertInstanceOf(FileSystemAgentTaskArtifactStorage.class,
                            context.getBean(AgentTaskArtifactStorage.class));
                    assertTrue(Files.isDirectory(root));
                });
    }

    @Test
    void enabledWithoutSafeRootOrWithEmptyAllowlistFailsStartup() {
        RUNNER.withPropertyValues("agent.task-artifact-storage.enabled=true")
                .run(context -> assertConfigurationFailure(context.getStartupFailure()));
        RUNNER.withPropertyValues(
                        "agent.task-artifact-storage.enabled=true",
                        "agent.task-artifact-storage.root-directory=relative/path")
                .run(context -> assertConfigurationFailure(context.getStartupFailure()));
        RUNNER.withPropertyValues(
                        "agent.task-artifact-storage.enabled=true",
                        "agent.task-artifact-storage.root-directory="
                                + temporaryDirectory.resolve("empty-mimes").toAbsolutePath(),
                        "agent.task-artifact-storage.allowed-mime-types[0]=")
                .run(context -> assertConfigurationFailure(context.getStartupFailure()));
    }

    private static void assertConfigurationFailure(Throwable failure) {
        assertNotNull(failure);
        assertTrue(failureChain(failure).contains(
                "Invalid agent.task-artifact-storage configuration"), failureChain(failure));
    }

    private static String failureChain(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append('\n');
            }
        }
        return messages.toString();
    }
}
