package cn.jia.chat.voice.api;

import org.springframework.http.HttpStatus;

public enum VoiceErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "VOICE_INVALID_REQUEST", "语音请求无效"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "VOICE_UNAUTHORIZED", "语音身份认证失败"),
    IN_PROGRESS(HttpStatus.CONFLICT, "VOICE_IN_PROGRESS", "已有语音请求正在处理"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "VOICE_IDEMPOTENCY_CONFLICT", "语音请求幂等键冲突"),
    RESULT_UNKNOWN(HttpStatus.CONFLICT, "VOICE_RESULT_UNKNOWN", "语音请求结果状态未知"),
    TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "VOICE_TOO_LARGE", "录音超过大小限制"),
    UNSUPPORTED_MEDIA(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "VOICE_UNSUPPORTED_MEDIA", "不支持的录音格式"),
    INVALID_AUDIO(HttpStatus.UNPROCESSABLE_ENTITY, "VOICE_INVALID_AUDIO", "录音文件无效"),
    NO_SPEECH(HttpStatus.UNPROCESSABLE_ENTITY, "VOICE_NO_SPEECH", "未识别到有效语音"),
    TOO_LONG(HttpStatus.UNPROCESSABLE_ENTITY, "VOICE_TOO_LONG", "录音超过允许时长"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "VOICE_RATE_LIMITED", "语音请求过于频繁"),
    PROVIDER_ERROR(HttpStatus.BAD_GATEWAY, "VOICE_PROVIDER_ERROR", "语音服务处理失败"),
    DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "VOICE_DISABLED", "语音服务未启用"),
    UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "VOICE_UNAVAILABLE", "语音服务暂不可用"),
    PROVIDER_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "VOICE_PROVIDER_TIMEOUT", "语音服务处理超时");

    private final HttpStatus status;
    private final String code;
    private final String message;

    VoiceErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
