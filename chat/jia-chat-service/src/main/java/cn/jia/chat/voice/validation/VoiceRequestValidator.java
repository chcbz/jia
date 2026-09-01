package cn.jia.chat.voice.validation;

import cn.jia.chat.voice.api.VoiceErrorCode;
import cn.jia.chat.voice.api.VoiceException;
import cn.jia.chat.voice.api.VoiceSynthesisRequest;
import cn.jia.chat.voice.config.VoiceSpeechProperties;

import java.util.Set;
import java.util.regex.Pattern;

public final class VoiceRequestValidator {
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{15,127}");
    private static final long MAX_CLIENT_DURATION_MS = 600_000;

    public String requestId(String value) {
        if (value == null || !REQUEST_ID.matcher(value).matches()) {
            throw VoiceException.of(VoiceErrorCode.INVALID_REQUEST, null);
        }
        return value;
    }

    public String language(String value, VoiceSpeechProperties.Transcription properties, String requestId) {
        String language = value == null || value.isEmpty() ? properties.getDefaultLanguage() : value;
        if (language == null || !properties.getLanguages().contains(language)) {
            throw VoiceException.of(VoiceErrorCode.INVALID_REQUEST, requestId);
        }
        return language;
    }

    public Long clientDuration(String value, String requestId) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            long duration = Long.parseLong(value);
            if (duration < 0 || duration > MAX_CLIENT_DURATION_MS) {
                throw VoiceException.of(VoiceErrorCode.INVALID_REQUEST, requestId);
            }
            return duration;
        } catch (NumberFormatException exception) {
            throw VoiceException.of(VoiceErrorCode.INVALID_REQUEST, requestId);
        }
    }

    public VoiceSynthesisRequest synthesis(
            VoiceSynthesisRequest request,
            VoiceSpeechProperties.Synthesis properties) {
        if (request == null) {
            throw VoiceException.of(VoiceErrorCode.INVALID_REQUEST, null);
        }
        String requestId = requestId(request.requestId());
        String text = request.text();
        if (text == null || text.isBlank() || text.codePointCount(0, text.length()) > 2_000) {
            throw VoiceException.of(VoiceErrorCode.INVALID_REQUEST, requestId);
        }
        if (!allowed(properties.getVoices(), request.voice())
                || !allowed(properties.getFormats(), request.format())
                || !"mp3".equals(request.format())) {
            throw VoiceException.of(VoiceErrorCode.INVALID_REQUEST, requestId);
        }
        return request;
    }

    private static boolean allowed(Set<String> values, String value) {
        return value != null && values != null && values.contains(value);
    }
}
