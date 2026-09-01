package cn.jia.chat.voice;

/** Provider-neutral transcription output. */
public record SpeechTranscriptionResult(String text, String detectedLanguage) {
}
