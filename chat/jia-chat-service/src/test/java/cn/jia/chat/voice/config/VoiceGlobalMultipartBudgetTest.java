package cn.jia.chat.voice.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceGlobalMultipartBudgetTest {
    private static final List<String> STARTER_PROFILES = List.of(
            "chat/jia-chat-starter/src/main/resources/application-dev.properties",
            "starter/src/main/resources/application-dev.properties",
            "starter/src/main/resources/application-grey.properties",
            "starter/src/main/resources/application-prod.properties");

    @Test
    void defaultOffVoiceDoesNotReduceAggregateApplicationUploadBudgets() throws Exception {
        Path repository = repositoryRoot();
        for (String profile : STARTER_PROFILES) {
            String properties = Files.readString(repository.resolve(profile));
            assertTrue(properties.contains("jia.chat.voice.enabled=false"), profile);
            assertTrue(properties.contains("spring.servlet.multipart.max-file-size=10MB"), profile);
            assertTrue(properties.contains("spring.servlet.multipart.max-request-size=50MB"), profile);
        }
    }

    private Path repositoryRoot() throws IOException {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle"))
                    && Files.isRegularFile(candidate.resolve(STARTER_PROFILES.get(0)))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IOException("CYF repository root is unavailable");
    }
}
