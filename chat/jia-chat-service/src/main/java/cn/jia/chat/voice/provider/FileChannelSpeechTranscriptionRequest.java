package cn.jia.chat.voice.provider;

import java.nio.channels.FileChannel;

/** Internal handle-bound transcription request; it never carries a reopenable pathname. */
public record FileChannelSpeechTranscriptionRequest(
        FileChannel audioChannel,
        long audioBytes,
        String mediaType,
        String language,
        long durationMs) {
}
