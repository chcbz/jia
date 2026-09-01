package cn.jia.chat.voice.config;

import cn.jia.chat.voice.SpeechSynthesisProvider;
import cn.jia.chat.voice.SpeechTranscriptionProvider;
import cn.jia.chat.voice.provider.DisabledSpeechSynthesisProvider;
import cn.jia.chat.voice.provider.DisabledSpeechTranscriptionProvider;
import cn.jia.chat.voice.provider.OpenAiCompatibleSpeechSynthesisProvider;
import cn.jia.chat.voice.provider.OpenAiCompatibleSpeechTranscriptionProvider;
import cn.jia.chat.voice.service.SpeechSynthesisService;
import cn.jia.chat.voice.service.SpeechTranscriptionService;
import cn.jia.chat.voice.state.RedisVoiceRequestCoordinator;
import cn.jia.chat.voice.state.UnavailableVoiceRequestCoordinator;
import cn.jia.chat.voice.state.VoiceDigests;
import cn.jia.chat.voice.state.VoicePayloadCipher;
import cn.jia.chat.voice.state.VoiceRequestCoordinator;
import cn.jia.chat.voice.validation.AudioDurationInspector;
import cn.jia.chat.voice.validation.VoiceAudioUploadFactory;
import cn.jia.chat.voice.validation.VoiceIdentityResolver;
import cn.jia.chat.voice.validation.VoiceRequestValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VoiceSpeechProperties.class)
public class VoiceSpeechConfiguration {
    @Bean
    public VoiceIdentityResolver voiceIdentityResolver() {
        return new VoiceIdentityResolver();
    }

    @Bean
    public VoiceRequestValidator voiceRequestValidator() {
        return new VoiceRequestValidator();
    }

    @Bean
    public AudioDurationInspector audioDurationInspector() {
        return new AudioDurationInspector();
    }

    @Bean
    public VoiceAudioUploadFactory voiceAudioUploadFactory(AudioDurationInspector inspector) {
        return new VoiceAudioUploadFactory(inspector);
    }

    @Bean
    public VoicePayloadCipher voicePayloadCipher(VoiceSpeechProperties properties) {
        return new VoicePayloadCipher(properties);
    }

    @Bean
    public VoiceDigests voiceDigests(VoiceSpeechProperties properties) {
        return new VoiceDigests(properties);
    }

    @Bean
    public VoiceRequestCoordinator voiceRequestCoordinator(
            ObjectProvider<RedisConnectionFactory> connectionFactory,
            VoiceSpeechProperties properties,
            VoicePayloadCipher cipher) {
        RedisConnectionFactory factory = connectionFactory.getIfAvailable();
        if (factory == null || !cipher.available()) {
            return new UnavailableVoiceRequestCoordinator();
        }
        return new RedisVoiceRequestCoordinator(factory, properties, cipher);
    }

    @Bean
    @ConditionalOnMissingBean(SpeechTranscriptionProvider.class)
    public SpeechTranscriptionProvider speechTranscriptionProvider(
            VoiceSpeechProperties properties, ObjectMapper objectMapper) {
        if ("openai-compatible".equals(properties.getTranscription().getProvider())) {
            return new OpenAiCompatibleSpeechTranscriptionProvider(properties, objectMapper);
        }
        return new DisabledSpeechTranscriptionProvider();
    }

    @Bean
    @ConditionalOnMissingBean(SpeechSynthesisProvider.class)
    public SpeechSynthesisProvider speechSynthesisProvider(
            VoiceSpeechProperties properties, ObjectMapper objectMapper) {
        if ("openai-compatible".equals(properties.getSynthesis().getProvider())) {
            return new OpenAiCompatibleSpeechSynthesisProvider(properties, objectMapper);
        }
        return new DisabledSpeechSynthesisProvider();
    }

    @Bean
    public SpeechTranscriptionService speechTranscriptionService(
            VoiceSpeechProperties properties,
            SpeechTranscriptionProvider provider,
            VoiceRequestCoordinator coordinator,
            VoiceDigests digests,
            ObjectMapper objectMapper) {
        return new SpeechTranscriptionService(properties, provider, coordinator, digests, objectMapper);
    }

    @Bean
    public SpeechSynthesisService speechSynthesisService(
            VoiceSpeechProperties properties,
            SpeechSynthesisProvider provider,
            VoiceRequestCoordinator coordinator,
            VoiceDigests digests) {
        return new SpeechSynthesisService(properties, provider, coordinator, digests);
    }
}
