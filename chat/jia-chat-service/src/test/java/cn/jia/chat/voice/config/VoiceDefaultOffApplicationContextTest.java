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

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceDefaultOffApplicationContextTest {
    private static final String REQUEST_ID = "01JVOICEDEFAULTOFF01";
    private static final String IDENTITY_HMAC = "01234567890123456789012345678901";
    private static final String CACHE_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final String API_KEY = "sk-test-openai-voice-key";

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
    void fullyPinnedOpenAiActivationStartsWithoutExposingCredentials() {
        runner.withPropertyValues(validActivationProperties()).run(context -> {
            assertNull(context.getStartupFailure());
            VoiceSpeechProperties properties = context.getBean(VoiceSpeechProperties.class);

            assertTrue(properties.isEnabled());
            assertTrue(properties.getTranscription().isEnabled());
            assertEquals("openai-compatible", properties.getTranscription().getProvider());
            assertEquals("https://api.openai.com/v1", properties.getTranscription().getBaseUrl());
            assertEquals(API_KEY, properties.getTranscription().getApiKey());
            assertEquals("whisper-1", properties.getTranscription().getModel());
            assertTrue(properties.getSynthesis().isEnabled());
            assertEquals("openai-compatible", properties.getSynthesis().getProvider());
            assertEquals("https://api.openai.com/v1", properties.getSynthesis().getBaseUrl());
            assertEquals(API_KEY, properties.getSynthesis().getApiKey());
            assertEquals("gpt-4o-mini-tts", properties.getSynthesis().getModel());
            assertEquals("alloy", properties.getSynthesis().getProviderVoice());
            assertInstanceOf(OpenAiCompatibleSpeechTranscriptionProvider.class,
                    context.getBean(SpeechTranscriptionProvider.class));
            assertInstanceOf(OpenAiCompatibleSpeechSynthesisProvider.class,
                    context.getBean(SpeechSynthesisProvider.class));

            String safeProperties = properties.toString();
            assertFalse(safeProperties.contains(API_KEY));
            assertFalse(safeProperties.contains(IDENTITY_HMAC));
            assertFalse(safeProperties.contains(CACHE_KEY));
        });
    }

    @Test
    void masterOrEitherOperationFlagTriggersStartupValidation() {
        for (String activation : List.of(
                "jia.chat.voice.enabled=true",
                "jia.chat.voice.transcription.enabled=true",
                "jia.chat.voice.synthesis.enabled=true")) {
            runner.withPropertyValues(activation).run(context ->
                    assertNotNull(context.getStartupFailure(), activation));
        }
    }

    @Test
    void activationValidationRejectsEveryUnpinnedOrUnsafeCredentialShape() {
        List<InvalidMutation> invalid = List.of(
                invalid("stt provider", properties -> properties.getTranscription().setProvider("disabled")),
                invalid("stt endpoint", properties -> properties.getTranscription()
                        .setBaseUrl("https://attacker.example/v1")),
                invalid("stt endpoint slash", properties -> properties.getTranscription()
                        .setBaseUrl("https://api.openai.com/v1/")),
                invalid("stt model", properties -> properties.getTranscription().setModel("whisper-test")),
                invalid("stt key blank", properties -> properties.getTranscription().setApiKey(" ")),
                invalid("stt key control", properties -> properties.getTranscription()
                        .setApiKey("sk-test\u0000key")),
                invalid("tts provider", properties -> properties.getSynthesis().setProvider("disabled")),
                invalid("tts endpoint", properties -> properties.getSynthesis()
                        .setBaseUrl("https://attacker.example/v1")),
                invalid("tts model", properties -> properties.getSynthesis().setModel("tts-test")),
                invalid("tts voice", properties -> properties.getSynthesis().setProviderVoice("nova")),
                invalid("tts key blank", properties -> properties.getSynthesis().setApiKey("")),
                invalid("tts key control", properties -> properties.getSynthesis()
                        .setApiKey("sk-test\rkey")),
                invalid("short hmac", properties -> properties.setIdentityHmacSecret("short")),
                invalid("hmac equals stt api", properties -> {
                    properties.setIdentityHmacSecret(IDENTITY_HMAC);
                    properties.getTranscription().setApiKey(IDENTITY_HMAC);
                }),
                invalid("hmac equals tts api", properties -> {
                    properties.setIdentityHmacSecret(IDENTITY_HMAC);
                    properties.getSynthesis().setApiKey(IDENTITY_HMAC);
                }),
                invalid("cache malformed", properties -> properties.setCacheEncryptionKey("not-base64")),
                invalid("cache 31 bytes", properties -> properties.setCacheEncryptionKey(
                        "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQQ==")),
                invalid("cache 33 bytes", properties -> properties.setCacheEncryptionKey(
                        "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFB")),
                invalid("hmac equals encoded aes", properties -> properties.setIdentityHmacSecret(
                        CACHE_KEY)),
                invalid("hmac equals decoded aes", properties -> properties.setCacheEncryptionKey(
                        "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=")));

        for (InvalidMutation invalidCase : invalid) {
            VoiceSpeechProperties properties = validProperties();
            invalidCase.mutation().accept(properties);
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> new VoiceActivationConfigurationValidator(properties), invalidCase.name());
            assertFalse(failure.getMessage().contains(API_KEY), invalidCase.name());
            assertFalse(failure.getMessage().contains(IDENTITY_HMAC), invalidCase.name());
            assertFalse(failure.getMessage().contains(CACHE_KEY), invalidCase.name());
        }
    }

    private static String[] validActivationProperties() {
        return new String[]{
                "jia.chat.voice.enabled=true",
                "jia.chat.voice.identity-hmac-secret=" + IDENTITY_HMAC,
                "jia.chat.voice.cache-encryption-key=" + CACHE_KEY,
                "jia.chat.voice.transcription.enabled=true",
                "jia.chat.voice.transcription.provider=openai-compatible",
                "jia.chat.voice.transcription.base-url=https://api.openai.com/v1",
                "jia.chat.voice.transcription.api-key=" + API_KEY,
                "jia.chat.voice.transcription.model=whisper-1",
                "jia.chat.voice.synthesis.enabled=true",
                "jia.chat.voice.synthesis.provider=openai-compatible",
                "jia.chat.voice.synthesis.base-url=https://api.openai.com/v1",
                "jia.chat.voice.synthesis.api-key=" + API_KEY,
                "jia.chat.voice.synthesis.model=gpt-4o-mini-tts",
                "jia.chat.voice.synthesis.provider-voice=alloy"
        };
    }

    private static VoiceSpeechProperties validProperties() {
        VoiceSpeechProperties properties = new VoiceSpeechProperties();
        properties.setEnabled(true);
        properties.setIdentityHmacSecret(IDENTITY_HMAC);
        properties.setCacheEncryptionKey(CACHE_KEY);
        properties.getTranscription().setEnabled(true);
        properties.getTranscription().setProvider("openai-compatible");
        properties.getTranscription().setApiKey(API_KEY);
        properties.getSynthesis().setEnabled(true);
        properties.getSynthesis().setProvider("openai-compatible");
        properties.getSynthesis().setApiKey(API_KEY);
        return properties;
    }

    private static InvalidMutation invalid(
            String name, Consumer<VoiceSpeechProperties> mutation) {
        return new InvalidMutation(name, mutation);
    }

    private record InvalidMutation(
            String name, Consumer<VoiceSpeechProperties> mutation) {
    }
}
