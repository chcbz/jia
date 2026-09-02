package cn.jia.chat.voice.config;

import cn.jia.chat.voice.SpeechSynthesisProvider;
import cn.jia.chat.voice.SpeechTranscriptionProvider;
import cn.jia.chat.voice.api.VoiceErrorCode;
import cn.jia.chat.voice.api.VoiceException;
import cn.jia.chat.voice.provider.DisabledSpeechSynthesisProvider;
import cn.jia.chat.voice.provider.DisabledSpeechTranscriptionProvider;
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
            assertEquals("disabled", properties.getSynthesis().getProvider());
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
}
