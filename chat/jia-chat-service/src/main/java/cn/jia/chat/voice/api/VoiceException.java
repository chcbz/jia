package cn.jia.chat.voice.api;

public final class VoiceException extends RuntimeException {
    private final VoiceErrorCode error;
    private final String requestId;

    public VoiceException(VoiceErrorCode error, String requestId) {
        super(error.code(), null, false, false);
        this.error = error;
        this.requestId = requestId;
    }

    public VoiceErrorCode error() {
        return error;
    }

    public String requestId() {
        return requestId;
    }

    public static VoiceException of(VoiceErrorCode error, String requestId) {
        return new VoiceException(error, requestId);
    }
}
