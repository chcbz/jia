package cn.jia.chat.voice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.Set;

@Getter
@Setter
@ConfigurationProperties(prefix = "jia.chat.voice")
public class VoiceSpeechProperties {
    private boolean enabled = false;
    private String identityHmacSecret;
    private String cacheEncryptionKey;
    private int perMinute = 6;
    private int perHour = 60;
    private int globalConcurrency = 8;
    private long providerDeadlineMillis = 25_000;
    private long connectTimeoutMillis = 3_000;
    private final Transcription transcription = new Transcription();
    private final Synthesis synthesis = new Synthesis();

    @Getter
    @Setter
    public static class Transcription {
        private boolean enabled = false;
        private String provider = "disabled";
        private Set<String> languages = new LinkedHashSet<>(Set.of("zh-CN"));
        private String defaultLanguage = "zh-CN";
        private String baseUrl = "https://api.openai.com/v1";
        private String apiKey;
        private String model = "whisper-1";
    }

    @Getter
    @Setter
    public static class Synthesis {
        private boolean enabled = false;
        private String provider = "disabled";
        private Set<String> voices = new LinkedHashSet<>(Set.of("juyiting-default"));
        private Set<String> formats = new LinkedHashSet<>(Set.of("mp3"));
        private String baseUrl = "https://api.openai.com/v1";
        private String apiKey;
        private String model = "gpt-4o-mini-tts";
        private String providerVoice = "alloy";
    }

    @Override
    public String toString() {
        return "VoiceSpeechProperties[enabled=" + enabled
                + ", identityHmacSecret=<redacted>, cacheEncryptionKey=<redacted>"
                + ", transcription.provider=" + transcription.provider
                + ", synthesis.provider=" + synthesis.provider + "]";
    }
}
