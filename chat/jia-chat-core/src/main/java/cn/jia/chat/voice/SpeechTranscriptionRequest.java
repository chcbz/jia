package cn.jia.chat.voice;

import java.nio.file.Path;

/** Provider-neutral, validated transcription input. */
public record SpeechTranscriptionRequest(
        Path audioPath,
        long audioBytes,
        String mediaType,
        String language,
        long durationMs) {
}
