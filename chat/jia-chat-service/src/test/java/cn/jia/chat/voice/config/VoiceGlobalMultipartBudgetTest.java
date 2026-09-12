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
            assertTrue(properties.contains(
                    "jia.chat.voice.enabled=${JIA_CHAT_VOICE_ENABLED:false}"), profile);
            assertTrue(properties.contains(
                    "jia.chat.voice.transcription.enabled=${JIA_CHAT_VOICE_TRANSCRIPTION_ENABLED:false}"),
                    profile);
            assertTrue(properties.contains(
                    "jia.chat.voice.transcription.provider=${JIA_CHAT_VOICE_TRANSCRIPTION_PROVIDER:disabled}"),
                    profile);
            assertTrue(properties.contains(
                    "jia.chat.voice.synthesis.enabled=${JIA_CHAT_VOICE_SYNTHESIS_ENABLED:false}"),
                    profile);
            assertTrue(properties.contains(
                    "jia.chat.voice.synthesis.provider=${JIA_CHAT_VOICE_SYNTHESIS_PROVIDER:disabled}"),
                    profile);
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
