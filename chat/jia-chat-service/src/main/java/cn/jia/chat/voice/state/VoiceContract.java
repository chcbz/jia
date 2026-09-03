package cn.jia.chat.voice.state;

import java.nio.charset.StandardCharsets;

final class VoiceContract {
    static final String VERSION = "cyf-voice-v1";

    private VoiceContract() {
    }

    static byte[] versionBytes() {
        return VERSION.getBytes(StandardCharsets.UTF_8);
    }
}
