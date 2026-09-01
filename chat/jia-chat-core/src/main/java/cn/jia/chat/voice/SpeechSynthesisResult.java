package cn.jia.chat.voice;

import java.util.Arrays;

/** Provider-neutral bounded audio output. */
public record SpeechSynthesisResult(byte[] audio, String mediaType) {
    public SpeechSynthesisResult {
        audio = audio == null ? null : Arrays.copyOf(audio, audio.length);
    }

    @Override
    public byte[] audio() {
        return audio == null ? null : Arrays.copyOf(audio, audio.length);
    }
}
