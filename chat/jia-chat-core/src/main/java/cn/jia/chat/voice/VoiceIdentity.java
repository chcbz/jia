package cn.jia.chat.voice;

/** Immutable, byte-exact identity accepted by the voice boundary. */
public record VoiceIdentity(String jiacn, String clientId, String subject) {
}
