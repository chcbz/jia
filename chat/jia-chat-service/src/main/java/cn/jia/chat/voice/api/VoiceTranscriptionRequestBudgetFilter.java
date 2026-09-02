package cn.jia.chat.voice.api;

import cn.jia.core.entity.JsonResult;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Enforces the transcription envelope budget before multipart controller dispatch. */
@Component
@Order(-90)
public final class VoiceTranscriptionRequestBudgetFilter extends OncePerRequestFilter {
    public static final long MAX_REQUEST_BYTES = 6L * 1024 * 1024;
    private static final String PATH = "/chat/speech/transcriptions";

    private final ObjectMapper objectMapper;

    public VoiceTranscriptionRequestBudgetFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !PATH.equals(pathWithinApplication(request));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength < 0 || declaredLength > MAX_REQUEST_BYTES) {
            reject(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        VoiceErrorCode error = VoiceErrorCode.TOO_LARGE;
        JsonResult<VoiceErrorData> result = new JsonResult<>(
                new VoiceErrorData(null), error.message(), error.code(), error.status().value());
        byte[] body = objectMapper.writeValueAsBytes(result);
        response.reset();
        response.setStatus(error.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    private static String pathWithinApplication(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            return path.substring(contextPath.length());
        }
        return path;
    }
}
