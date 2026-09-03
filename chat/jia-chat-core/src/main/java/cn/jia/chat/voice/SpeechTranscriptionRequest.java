package cn.jia.chat.voice;

import java.nio.channels.FileChannel;

/** Provider-neutral, validated transcription input backed by the hashed open file handle. */
public record SpeechTranscriptionRequest(
        FileChannel audioChannel,
        long audioBytes,
        String mediaType,
        String language,
        long durationMs) {
}
