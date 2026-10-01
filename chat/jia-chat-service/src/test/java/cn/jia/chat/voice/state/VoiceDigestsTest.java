package cn.jia.chat.voice.state;

import cn.jia.chat.voice.config.VoiceSpeechProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class VoiceDigestsTest {
    private static final String LEGACY_STT_B298 =
            "10c97a4ed1ce43a62a97779abb6c5062ebbf5738442c8f11c46bfdc265c7c6fa";
    private static final String LEGACY_TTS_B298 =
            "d56734ae949b28def4a25b5cce40713b1b7ae6997a328e473f589fdef18ee1cd";
    private static final String REALTIME_STT_B298 =
            "41da41131a82316ae7ec2bd6e95b155ebf47e845b70722217191fa7b1df812da";
    private static final String REALTIME_TTS_B298 =
            "880c4f8af4ab8e15b6f1ffc6b55455cfc95d12c9097f903aa44fef2a4847230d";
    private static final String REALTIME_STT_MODEL_ASSISTED_V2 =
            "1d8de214e57c51b1b851d5991f2c43ac3f1fcbadf39de3b37ff29b8c6d19f4f5";
    private static final String REALTIME_STT_NATIVE_V1 =
            "a64674a1de4b77db34f2307b23e8643d44b9d9574f860b8e4da83f05b4b2d2e9";
    private static final String REALTIME_TTS_SEMANTICS_V2 =
            "54d48e1598f9f970387ac3eb5e717d83186ff8d1f1e2981104435d5f2759ad31";

    private final VoiceDigests digests = new VoiceDigests(properties());

    @Test
    void openAiCompatibleDigestsRemainByteExactWithFrozenB298() {
        byte[] audioDigest = new byte[32];

        assertEquals(LEGACY_STT_B298, digests.transcription(
                "openai-compatible", "whisper-1", "zh-CN",
                "audio/webm;codecs=opus", audioDigest));
        assertEquals(LEGACY_TTS_B298, digests.synthesis(
                "openai-compatible", "gpt-4o-mini-tts", "alloy",
                "林冲领命。", "juyiting-default", "mp3"));
    }

    @Test
    void cliproxyRealtimeDigestsSeparateCorrectedSemanticsFromFrozenB298() {
        byte[] audioDigest = new byte[32];
        String transcription = digests.transcription(
                "cliproxy-realtime", "gpt-realtime", "zh-CN",
                "audio/wav", audioDigest);
        String synthesis = digests.synthesis(
                "cliproxy-realtime", "gpt-realtime", "alloy",
                "语音验收成功。", "juyiting-default", "wav");

        assertNotEquals(REALTIME_STT_B298, transcription);
        assertNotEquals(REALTIME_STT_MODEL_ASSISTED_V2, transcription);
        assertEquals(REALTIME_STT_NATIVE_V1, transcription);
        assertNotEquals(REALTIME_TTS_B298, synthesis);
        assertEquals(REALTIME_TTS_SEMANTICS_V2, synthesis);
        assertEquals(transcription, digests.transcription(
                "cliproxy-realtime", "gpt-realtime", "zh-CN",
                "audio/wav", audioDigest));
        assertEquals(synthesis, digests.synthesis(
                "cliproxy-realtime", "gpt-realtime", "alloy",
                "语音验收成功。", "juyiting-default", "wav"));
    }

    private static VoiceSpeechProperties properties() {
        VoiceSpeechProperties properties = new VoiceSpeechProperties();
        properties.setIdentityHmacSecret("01234567890123456789012345678901");
        return properties;
    }
}
