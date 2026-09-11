package cn.jia.agent.api;

import cn.jia.core.util.JsonUtil;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/** Frozen output-delivery HTTP envelopes, independent of the legacy JsonResult wire shape. */
public final class OutputHttpEnvelope {
    public static final String REQUEST_ID_HEADER = "X-Request-ID";
    private static final String REQUEST_ID_ATTRIBUTE = OutputHttpEnvelope.class.getName() + ".requestId";

    public record Success<T>(String code, T data) {
        public Success(T data) { this("E0", data); }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Error(String code, String message, boolean retryable,
                        String requestId, Map<String, Object> details) { }

    public static ResponseEntity<Error> error(HttpServletRequest request, String code,
                                               String message, int status, boolean retryable) {
        String requestId = requestId(request);
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(REQUEST_ID_HEADER, requestId)
                .body(new Error(code, message, retryable, requestId, null));
    }

    public static void writeError(HttpServletRequest request, HttpServletResponse response,
                                  String code, String message, int status,
                                  boolean retryable) throws IOException {
        String requestId = requestId(request);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(REQUEST_ID_HEADER, requestId);
        response.getWriter().write(JsonUtil.toJson(
                new Error(code, message, retryable, requestId, null)));
    }

    public static String requestId(HttpServletRequest request) {
        Object existing = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        if (existing instanceof String value) return value;
        String supplied = request.getHeader(REQUEST_ID_HEADER);
        String value = validRequestId(supplied)
                ? supplied : UUID.randomUUID().toString().replace("-", "");
        request.setAttribute(REQUEST_ID_ATTRIBUTE, value);
        return value;
    }

    private static boolean validRequestId(String value) {
        return value != null && !value.isEmpty() && value.length() <= 100
                && value.codePoints().noneMatch(Character::isWhitespace)
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private OutputHttpEnvelope() { }
}
