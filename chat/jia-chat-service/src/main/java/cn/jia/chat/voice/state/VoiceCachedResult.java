package cn.jia.chat.voice.state;

import java.util.Arrays;

public record VoiceCachedResult(byte[] payload, String contentType) {
    public VoiceCachedResult {
        payload = payload == null ? null : Arrays.copyOf(payload, payload.length);
    }

    @Override
    public byte[] payload() {
        return payload == null ? null : Arrays.copyOf(payload, payload.length);
    }
}
