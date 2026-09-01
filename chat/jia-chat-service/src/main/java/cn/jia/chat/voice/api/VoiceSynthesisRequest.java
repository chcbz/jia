package cn.jia.chat.voice.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class VoiceSynthesisRequest {
    private final String requestId;
    private final String text;
    private final String voice;
    private final String format;

    @JsonCreator
    public VoiceSynthesisRequest(
            @JsonProperty("requestId") String requestId,
            @JsonProperty("text") String text,
            @JsonProperty("voice") String voice,
            @JsonProperty("format") String format) {
        this.requestId = requestId;
        this.text = text;
        this.voice = voice;
        this.format = format;
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw VoiceException.of(VoiceErrorCode.INVALID_REQUEST, safeRequestId());
    }

    public String requestId() {
        return requestId;
    }

    public String text() {
        return text;
    }

    public String voice() {
        return voice;
    }

    public String format() {
        return format;
    }

    private String safeRequestId() {
        return requestId != null && requestId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{15,127}")
                ? requestId : null;
    }
}
