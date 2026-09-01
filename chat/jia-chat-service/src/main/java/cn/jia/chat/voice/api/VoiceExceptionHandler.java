package cn.jia.chat.voice.api;

import cn.jia.core.entity.JsonResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {
        SpeechTranscriptionController.class,
        SpeechSynthesisController.class
})
public class VoiceExceptionHandler {
    @ExceptionHandler(VoiceException.class)
    public ResponseEntity<JsonResult<VoiceErrorData>> voice(VoiceException exception) {
        VoiceErrorCode error = exception.error();
        JsonResult<VoiceErrorData> result = new JsonResult<>(
                new VoiceErrorData(exception.requestId()),
                error.message(), error.code(), error.status().value());
        log.warn("Voice request failed code={} status={}", error.code(), error.status().value());
        return ResponseEntity.status(error.status()).body(result);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<JsonResult<VoiceErrorData>> unsupportedMedia() {
        return response(VoiceErrorCode.UNSUPPORTED_MEDIA, null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<JsonResult<VoiceErrorData>> tooLarge() {
        return response(VoiceErrorCode.TOO_LARGE, null);
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<JsonResult<VoiceErrorData>> malformedMultipart() {
        return response(VoiceErrorCode.INVALID_REQUEST, null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<JsonResult<VoiceErrorData>> malformedJson(
            HttpMessageNotReadableException exception) {
        VoiceException voiceException = findVoiceException(exception);
        return voiceException == null
                ? response(VoiceErrorCode.INVALID_REQUEST, null)
                : voice(voiceException);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<JsonResult<VoiceErrorData>> unexpected() {
        log.error("Unexpected voice endpoint failure; request details suppressed");
        return response(VoiceErrorCode.UNAVAILABLE, null);
    }

    private static VoiceException findVoiceException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof VoiceException voiceException) {
                return voiceException;
            }
            current = current.getCause();
        }
        return null;
    }

    private static ResponseEntity<JsonResult<VoiceErrorData>> response(
            VoiceErrorCode error, String requestId) {
        JsonResult<VoiceErrorData> result = new JsonResult<>(
                new VoiceErrorData(requestId), error.message(), error.code(), error.status().value());
        return ResponseEntity.status(error.status()).body(result);
    }
}
