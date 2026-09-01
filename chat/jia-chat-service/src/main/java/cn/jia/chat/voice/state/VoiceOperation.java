package cn.jia.chat.voice.state;

public enum VoiceOperation {
    TRANSCRIPTION("stt"),
    SYNTHESIS("tts");

    private final String namespace;

    VoiceOperation(String namespace) {
        this.namespace = namespace;
    }

    public String namespace() {
        return namespace;
    }
}
