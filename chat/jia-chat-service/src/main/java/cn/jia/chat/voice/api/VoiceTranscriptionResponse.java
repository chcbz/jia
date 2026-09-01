package cn.jia.chat.voice.api;

public record VoiceTranscriptionResponse(
        String requestId,
        String text,
        String detectedLanguage,
        long durationMs) {
}
