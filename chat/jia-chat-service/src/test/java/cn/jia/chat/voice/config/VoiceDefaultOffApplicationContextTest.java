package cn.jia.chat.voice.config;

import cn.jia.chat.voice.SpeechSynthesisProvider;
import cn.jia.chat.voice.SpeechTranscriptionProvider;
import cn.jia.chat.voice.api.VoiceErrorCode;
import cn.jia.chat.voice.api.VoiceException;
import cn.jia.chat.voice.provider.DisabledSpeechSynthesisProvider;
import cn.jia.chat.voice.provider.DisabledSpeechTranscriptionProvider;
import cn.jia.chat.voice.provider.OpenAiCompatibleSpeechSynthesisProvider;
import cn.jia.chat.voice.provider.OpenAiCompatibleSpeechTranscriptionProvider;
import cn.jia.chat.voice.service.SpeechSynthesisService;
import cn.jia.chat.voice.service.SpeechTranscriptionService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceDefaultOffApplicationContextTest {
    private static final String REQUEST_ID = "01JVOICEDEFAULTOFF01";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(VoiceSpeechConfiguration.class);

    @Test
    void realBootJackson3ContextStartsDefaultOffWithoutRedisOrProviderSideEffects() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            context.getBean(ObjectMapper.class);

            VoiceSpeechProperties properties = context.getBean(VoiceSpeechProperties.class);
            assertFalse(properties.isEnabled());
            assertFalse(properties.getTranscription().isEnabled());
            assertFalse(properties.getSynthesis().isEnabled());
            assertEquals("disabled", properties.getTranscription().getProvider());
            assertEquals("https://api.openai.com/v1", properties.getTranscription().getBaseUrl());
            assertEquals("whisper-1", properties.getTranscription().getModel());
            assertEquals("disabled", properties.getSynthesis().getProvider());
            assertEquals("https://api.openai.com/v1", properties.getSynthesis().getBaseUrl());
            assertEquals("gpt-4o-mini-tts", properties.getSynthesis().getModel());
            assertEquals("alloy", properties.getSynthesis().getProviderVoice());
            assertInstanceOf(DisabledSpeechTranscriptionProvider.class,
                    context.getBean(SpeechTranscriptionProvider.class));
            assertInstanceOf(DisabledSpeechSynthesisProvider.class,
                    context.getBean(SpeechSynthesisProvider.class));

            VoiceException transcription = assertThrows(VoiceException.class,
                    () -> context.getBean(SpeechTranscriptionService.class)
                            .requireAvailable(REQUEST_ID));
            assertEquals(VoiceErrorCode.DISABLED, transcription.error());
            VoiceException synthesis = assertThrows(VoiceException.class,
                    () -> context.getBean(SpeechSynthesisService.class)
                            .requireAvailable(REQUEST_ID));
            assertEquals(VoiceErrorCode.DISABLED, synthesis.error());
        });
    }

    @Test
    void explicitPropertiesBindOpenAiCompatibleProvidersWithoutExposingCredentials() {
        String apiKey = "test-openai-key-must-not-appear-in-properties-toString";
        String identityHmac = "test-identity-hmac-must-not-appear-in-properties-toString";
        String cacheKey = "test-cache-key-must-not-appear-in-properties-toString";

        runner.withPropertyValues(
                "jia.chat.voice.enabled=true",
                "jia.chat.voice.identity-hmac-secret=" + identityHmac,
                "jia.chat.voice.cache-encryption-key=" + cacheKey,
                "jia.chat.voice.transcription.enabled=true",
                "jia.chat.voice.transcription.provider=openai-compatible",
                "jia.chat.voice.transcription.base-url=https://voice.example.test/v1",
                "jia.chat.voice.transcription.api-key=" + apiKey,
                "jia.chat.voice.transcription.model=whisper-test",
                "jia.chat.voice.synthesis.enabled=true",
                "jia.chat.voice.synthesis.provider=openai-compatible",
                "jia.chat.voice.synthesis.base-url=https://voice.example.test/v1",
                "jia.chat.voice.synthesis.api-key=" + apiKey,
                "jia.chat.voice.synthesis.model=gpt-4o-mini-tts-test",
                "jia.chat.voice.synthesis.provider-voice=alloy-test")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    VoiceSpeechProperties properties = context.getBean(VoiceSpeechProperties.class);

                    assertTrue(properties.isEnabled());
                    assertTrue(properties.getTranscription().isEnabled());
                    assertEquals("openai-compatible", properties.getTranscription().getProvider());
                    assertEquals("https://voice.example.test/v1", properties.getTranscription().getBaseUrl());
                    assertEquals(apiKey, properties.getTranscription().getApiKey());
                    assertEquals("whisper-test", properties.getTranscription().getModel());
                    assertTrue(properties.getSynthesis().isEnabled());
                    assertEquals("openai-compatible", properties.getSynthesis().getProvider());
                    assertEquals("https://voice.example.test/v1", properties.getSynthesis().getBaseUrl());
                    assertEquals(apiKey, properties.getSynthesis().getApiKey());
                    assertEquals("gpt-4o-mini-tts-test", properties.getSynthesis().getModel());
                    assertEquals("alloy-test", properties.getSynthesis().getProviderVoice());
                    assertInstanceOf(OpenAiCompatibleSpeechTranscriptionProvider.class,
                            context.getBean(SpeechTranscriptionProvider.class));
                    assertInstanceOf(OpenAiCompatibleSpeechSynthesisProvider.class,
                            context.getBean(SpeechSynthesisProvider.class));

                    String safeProperties = properties.toString();
                    assertFalse(safeProperties.contains(apiKey));
                    assertFalse(safeProperties.contains(identityHmac));
                    assertFalse(safeProperties.contains(cacheKey));
                });
    }

}
