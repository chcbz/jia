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
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.openai.OpenAiAudioSpeechModel;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.Set;
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
    private static final String AUDIO_OVERRIDE_KEY = "sk-test-openai-audio-override-key";
    private static final String SPEECH_OVERRIDE_KEY = "sk-test-openai-speech-override-key";
    private static final String COMPATIBILITY_GATEWAY =
            "https://voice-gateway.example/openai/v1";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class,
                    OpenAiAudioTranscriptionAutoConfiguration.class,
                    OpenAiAudioSpeechAutoConfiguration.class))
            .withPropertyValues(
                    "spring.ai.model.audio.transcription=none",
                    "spring.ai.model.audio.speech=none")
            .withUserConfiguration(VoiceSpeechConfiguration.class);

    @Test
    void realBootJackson3ContextStartsDefaultOffWithoutRedisOrProviderSideEffects() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            context.getBean(ObjectMapper.class);
            assertEquals("none", context.getEnvironment().getProperty(
                    "spring.ai.model.audio.transcription"));
            assertEquals("none", context.getEnvironment().getProperty(
                    "spring.ai.model.audio.speech"));
            assertTrue(context.getBeansOfType(OpenAiAudioTranscriptionModel.class).isEmpty());
            assertTrue(context.getBeansOfType(OpenAiAudioSpeechModel.class).isEmpty());
            assertFalse(context.containsBean("openAiSdkAudioTranscriptionModel"));
            assertFalse(context.containsBean("openAiSdkAudioSpeechModel"));
            assertNotNull(context.getBean(SpringAiOpenAiVoiceFacade.class));

            VoiceSpeechProperties properties = context.getBean(VoiceSpeechProperties.class);
            assertFalse(properties.isEnabled());
            assertFalse(properties.getTranscription().isEnabled());
            assertFalse(properties.getSynthesis().isEnabled());
            assertEquals("disabled", properties.getTranscription().getProvider());
            assertEquals("whisper-1", properties.getTranscription().getModel());
            assertEquals("disabled", properties.getSynthesis().getProvider());
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
            assertEquals("whisper-1", properties.getTranscription().getModel());
            assertTrue(properties.getSynthesis().isEnabled());
            assertEquals("openai-compatible", properties.getSynthesis().getProvider());
            assertEquals("gpt-4o-mini-tts", properties.getSynthesis().getModel());
            assertEquals("alloy", properties.getSynthesis().getProviderVoice());
            assertInstanceOf(OpenAiCompatibleSpeechTranscriptionProvider.class,
                    context.getBean(SpeechTranscriptionProvider.class));
            assertInstanceOf(OpenAiCompatibleSpeechSynthesisProvider.class,
                    context.getBean(SpeechSynthesisProvider.class));
            assertTrue(context.getBeansOfType(OpenAiAudioTranscriptionModel.class).isEmpty());
            assertTrue(context.getBeansOfType(OpenAiAudioSpeechModel.class).isEmpty());
            assertFalse(context.containsBean("openAiSdkAudioTranscriptionModel"));
            assertFalse(context.containsBean("openAiSdkAudioSpeechModel"));
            assertNotNull(context.getBean(SpeechTranscriptionService.class));
            assertNotNull(context.getBean(SpeechSynthesisService.class));

            SpringAiOpenAiVoiceFacade facade = context.getBean(SpringAiOpenAiVoiceFacade.class);
            assertEquals(API_KEY, facade.transcription().apiKey());
            assertEquals(API_KEY, facade.synthesis().apiKey());
            String safeProperties = properties.toString();
            assertFalse(safeProperties.contains(API_KEY));
            assertFalse(safeProperties.contains(IDENTITY_HMAC));
            assertFalse(safeProperties.contains(CACHE_KEY));
            assertFalse(facade.toString().contains(API_KEY));
            assertFalse(facade.toString().contains(IDENTITY_HMAC));
            assertFalse(facade.toString().contains(CACHE_KEY));
        });
    }

    @Test
    void exactAllowlistedHttpsCompatibilityGatewayPassesActivationValidation() {
        runner.withPropertyValues(compatibilityGatewayActivationProperties()).run(context -> {
            assertNull(context.getStartupFailure());
            SpringAiOpenAiVoiceFacade facade = context.getBean(SpringAiOpenAiVoiceFacade.class);
            assertEquals(COMPATIBILITY_GATEWAY, facade.transcription().baseUrl());
            assertEquals(COMPATIBILITY_GATEWAY, facade.synthesis().baseUrl());
            assertTrue(context.getBeansOfType(OpenAiAudioTranscriptionModel.class).isEmpty());
            assertTrue(context.getBeansOfType(OpenAiAudioSpeechModel.class).isEmpty());
            assertInstanceOf(OpenAiCompatibleSpeechTranscriptionProvider.class,
                    context.getBean(SpeechTranscriptionProvider.class));
            assertInstanceOf(OpenAiCompatibleSpeechSynthesisProvider.class,
                    context.getBean(SpeechSynthesisProvider.class));
        });
    }

    @Test
    void gatewayValidatorRejectsAllowlistedUriConfusionShapes() {
        for (String gateway : List.of(
                "http://voice-gateway.example/openai/v1",
                "https://user@voice-gateway.example/openai/v1",
                "https://voice-gateway.example/openai/v1?target=/audio/speech",
                "https://voice-gateway.example/openai/v1#target",
                "https://voice-gateway.example/openai/v1/",
                "https://voice-gateway.example/openai/../v1",
                "https://voice-gateway.example/openai%2Fv1",
                "https://voice-gateway.example/openai//v1")) {
            assertFalse(VoiceActivationConfigurationValidator.isAllowedHttpsGateway(
                    gateway, Set.of(gateway)), gateway);
        }
    }


    @Test
    void springAiCommonCredentialsAreInheritedAndAudioOverridesTakePrecedenceWithoutLeaking() {
        runner.withPropertyValues(concat(validActivationProperties(),
                "spring.ai.openai.audio.transcription.base-url=https://api.openai.com/v1",
                "spring.ai.openai.audio.transcription.api-key=" + AUDIO_OVERRIDE_KEY,
                "spring.ai.openai.audio.speech.base-url=" + COMPATIBILITY_GATEWAY,
                "spring.ai.openai.audio.speech.api-key=" + SPEECH_OVERRIDE_KEY,
                "jia.chat.voice.compatibility-gateway-allowlist="
                        + VoiceActivationConfigurationValidator.OPENAI_BASE_URL + ","
                        + COMPATIBILITY_GATEWAY))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    SpringAiOpenAiVoiceFacade facade = context.getBean(SpringAiOpenAiVoiceFacade.class);
                    assertEquals(AUDIO_OVERRIDE_KEY, facade.transcription().apiKey());
                    assertEquals(VoiceActivationConfigurationValidator.OPENAI_BASE_URL,
                            facade.transcription().baseUrl());
                    assertEquals(SPEECH_OVERRIDE_KEY, facade.synthesis().apiKey());
                    assertEquals(COMPATIBILITY_GATEWAY, facade.synthesis().baseUrl());
                    assertTrue(facade.transcription().hasExplicitGateway());
                    assertTrue(facade.synthesis().hasExplicitGateway());
                    assertFalse(facade.toString().contains(AUDIO_OVERRIDE_KEY));
                    assertFalse(facade.toString().contains(SPEECH_OVERRIDE_KEY));
                    assertFalse(facade.toString().contains(API_KEY));
                });
    }

    @Test
    void rejectsImplicitOrUnallowlistedSpringAiGatewayBeforeProviderCreation() {
        runner.withPropertyValues(concat(validActivationProperties(),
                "spring.ai.openai.base-url=https://attacker.example/v1"))
                .run(context -> assertNotNull(context.getStartupFailure()));

        String[] noGateway = java.util.Arrays.stream(validActivationProperties())
                .filter(value -> !value.startsWith("spring.ai.openai.base-url="))
                .toArray(String[]::new);
        runner.withPropertyValues(noGateway).run(context ->
                assertNotNull(context.getStartupFailure()));
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
                invalid("stt model", properties -> properties.getTranscription().setModel("whisper-test")),
                invalid("tts provider", properties -> properties.getSynthesis().setProvider("disabled")),
                invalid("tts model", properties -> properties.getSynthesis().setModel("tts-test")),
                invalid("tts voice", properties -> properties.getSynthesis().setProviderVoice("nova")),
                invalid("short hmac", properties -> properties.setIdentityHmacSecret("short")),
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
                    () -> new VoiceActivationConfigurationValidator(properties, facade()), invalidCase.name());
            assertFalse(failure.getMessage().contains(API_KEY), invalidCase.name());
            assertFalse(failure.getMessage().contains(IDENTITY_HMAC), invalidCase.name());
            assertFalse(failure.getMessage().contains(CACHE_KEY), invalidCase.name());
        }
    }


    private static SpringAiOpenAiVoiceFacade facade() {
        OpenAiCommonProperties common = new OpenAiCommonProperties();
        common.setBaseUrl(VoiceActivationConfigurationValidator.OPENAI_BASE_URL);
        common.setApiKey(API_KEY);
        return new SpringAiOpenAiVoiceFacade(common,
                new OpenAiAudioTranscriptionProperties(),
                new OpenAiAudioSpeechProperties(),
                new MockEnvironment().withProperty("spring.ai.openai.base-url",
                        VoiceActivationConfigurationValidator.OPENAI_BASE_URL));
    }

    private static String[] concat(String[] values, String... additional) {
        String[] result = java.util.Arrays.copyOf(values, values.length + additional.length);
        System.arraycopy(additional, 0, result, values.length, additional.length);
        return result;
    }

    private static String[] validActivationProperties() {
        return new String[]{
                "spring.ai.openai.base-url=https://api.openai.com/v1",
                "spring.ai.openai.api-key=" + API_KEY,
                "jia.chat.voice.enabled=true",
                "jia.chat.voice.identity-hmac-secret=" + IDENTITY_HMAC,
                "jia.chat.voice.cache-encryption-key=" + CACHE_KEY,
                "jia.chat.voice.transcription.enabled=true",
                "jia.chat.voice.transcription.provider=openai-compatible",
                "jia.chat.voice.transcription.model=whisper-1",
                "jia.chat.voice.synthesis.enabled=true",
                "jia.chat.voice.synthesis.provider=openai-compatible",
                "jia.chat.voice.synthesis.model=gpt-4o-mini-tts",
                "jia.chat.voice.synthesis.provider-voice=alloy"
        };
    }

    private static String[] compatibilityGatewayActivationProperties() {
        String[] values = validActivationProperties();
        for (int index = 0; index < values.length; index++) {
            if (values[index].startsWith("spring.ai.openai.base-url=")) {
                values[index] = "spring.ai.openai.base-url=" + COMPATIBILITY_GATEWAY;
            }
        }
        return concat(values, "jia.chat.voice.compatibility-gateway-allowlist="
                + COMPATIBILITY_GATEWAY);
    }

    private static VoiceSpeechProperties validProperties() {
        VoiceSpeechProperties properties = new VoiceSpeechProperties();
        properties.setEnabled(true);
        properties.setIdentityHmacSecret(IDENTITY_HMAC);
        properties.setCacheEncryptionKey(CACHE_KEY);
        properties.getTranscription().setEnabled(true);
        properties.getTranscription().setProvider("openai-compatible");
        properties.getSynthesis().setEnabled(true);
        properties.getSynthesis().setProvider("openai-compatible");
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
