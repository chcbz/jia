package cn.jia.chat.voice;

/** Provider-neutral synthesis input. */
public record SpeechSynthesisRequest(String text, String voice, String format) {
}
