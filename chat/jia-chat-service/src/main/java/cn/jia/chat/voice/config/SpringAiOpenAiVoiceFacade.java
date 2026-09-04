package cn.jia.chat.voice.config;

import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAutoConfigurationUtil;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.core.env.Environment;

/**
 * Resolves Spring AI OpenAI common connection properties and audio overrides for the
 * product-owned bounded audio transport. This facade intentionally does not invoke Spring AI's
 * native audio models because the pinned implementation buffers complete audio payloads.
 */
public final class SpringAiOpenAiVoiceFacade {
    private static final String COMMON_BASE_URL = "spring.ai.openai.base-url";
    private static final String TRANSCRIPTION_BASE_URL =
            "spring.ai.openai.audio.transcription.base-url";
    private static final String SYNTHESIS_BASE_URL = "spring.ai.openai.audio.speech.base-url";

    private final Connection transcription;
    private final Connection synthesis;

    public SpringAiOpenAiVoiceFacade(
            OpenAiCommonProperties common,
            OpenAiAudioTranscriptionProperties transcription,
            OpenAiAudioSpeechProperties synthesis,
            Environment environment) {
        this.transcription = resolve(common, transcription, environment, TRANSCRIPTION_BASE_URL);
        this.synthesis = resolve(common, synthesis, environment, SYNTHESIS_BASE_URL);
    }

    public Connection transcription() {
        return transcription;
    }

    public Connection synthesis() {
        return synthesis;
    }

    @Override
    public String toString() {
        return "SpringAiOpenAiVoiceFacade[transcription=" + transcription
                + ", synthesis=" + synthesis + "]";
    }

    private static Connection resolve(
            OpenAiCommonProperties common,
            org.springframework.ai.model.openai.autoconfigure.AbstractOpenAiProperties audio,
            Environment environment,
            String audioBaseUrlProperty) {
        OpenAiAutoConfigurationUtil.ResolvedConnectionProperties resolved =
                OpenAiAutoConfigurationUtil.resolveCommonProperties(common, audio);
        boolean explicitGateway = environment.containsProperty(COMMON_BASE_URL)
                || environment.containsProperty(audioBaseUrlProperty);
        return new Connection(resolved.getBaseUrl(), resolved.getApiKey(), explicitGateway);
    }

    public static final class Connection {
        private final String baseUrl;
        private final String apiKey;
        private final boolean explicitGateway;

        private Connection(String baseUrl, String apiKey, boolean explicitGateway) {
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.explicitGateway = explicitGateway;
        }

        public String baseUrl() {
            return baseUrl;
        }

        public String apiKey() {
            return apiKey;
        }

        public boolean hasExplicitGateway() {
            return explicitGateway;
        }

        @Override
        public String toString() {
            return "Connection[baseUrl=" + baseUrl + ", apiKey=<redacted>, explicitGateway="
                    + explicitGateway + "]";
        }
    }
}
