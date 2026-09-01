package cn.jia.chat.voice.api;

import cn.jia.core.entity.JsonResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMediaTypeNotSupportedException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Maps failures raised before a voice controller is selected to the frozen voice error contract.
 * All other paths and exception types remain available to the application's existing resolvers.
 */
@Component
public final class VoiceEarlyExceptionResolver implements HandlerExceptionResolver, Ordered {
    private static final Set<String> VOICE_PATHS = Set.of(
            "/chat/speech/transcriptions",
            "/chat/speech/synthesis");
    private static final Set<String> MULTIPART_LIMIT_CAUSES = Set.of(
            "org.apache.tomcat.util.http.fileupload.impl.FileSizeLimitExceededException",
            "org.apache.tomcat.util.http.fileupload.impl.SizeLimitExceededException");

    private final ObjectMapper objectMapper;

    public VoiceEarlyExceptionResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public ModelAndView resolveException(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception exception) {
        if (!isVoicePath(request)) {
            return null;
        }
        VoiceErrorCode error = errorFor(exception);
        if (error == null) {
            return null;
        }
        JsonResult<VoiceErrorData> result = new JsonResult<>(
                new VoiceErrorData(null), error.message(), error.code(), error.status().value());
        try {
            byte[] body = objectMapper.writeValueAsBytes(result);
            response.setStatus(error.status().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentLength(body.length);
            response.getOutputStream().write(body);
            return new ModelAndView();
        } catch (IOException ignored) {
            return null;
        }
    }

    private static boolean isVoicePath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return VOICE_PATHS.contains(path);
    }

    private static VoiceErrorCode errorFor(Throwable throwable) {
        if (hasCause(throwable, HttpMediaTypeNotSupportedException.class)) {
            return VoiceErrorCode.UNSUPPORTED_MEDIA;
        }
        if (hasCause(throwable, MaxUploadSizeExceededException.class)
                || hasCauseNamed(throwable, MULTIPART_LIMIT_CAUSES)) {
            return VoiceErrorCode.TOO_LARGE;
        }
        if (hasCause(throwable, MultipartException.class)) {
            return VoiceErrorCode.INVALID_REQUEST;
        }
        return null;
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean hasCauseNamed(Throwable throwable, Set<String> names) {
        Throwable current = throwable;
        while (current != null) {
            if (names.contains(current.getClass().getName())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
