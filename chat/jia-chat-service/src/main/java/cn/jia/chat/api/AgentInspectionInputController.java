package cn.jia.chat.api;

import cn.jia.agent.security.AgentRuntimeAuthentication;
import cn.jia.chat.service.ChatDeliberationException;
import cn.jia.chat.service.ChatInspectionAuthorityService;
import cn.jia.core.entity.JsonResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

@RestController
@ConditionalOnProperty(prefix = "chat.typed-inspection", name = "enabled", havingValue = "true")
public final class AgentInspectionInputController {
    private static final String MANIFEST_HEADER = "X-Inspection-Manifest-Digest";
    private final ChatInspectionAuthorityService authority;

    public AgentInspectionInputController(ChatInspectionAuthorityService authority) {
        this.authority = Objects.requireNonNull(authority);
    }

    @GetMapping("/internal/agent/chat/requests/{requestId}/turns/{turnId}/inspection/inputs/{sourceRefId}/content")
    public ResponseEntity<byte[]> content(@PathVariable String requestId,
            @PathVariable String turnId, @PathVariable String sourceRefId,
            Authentication authentication, HttpServletRequest request) {
        if (request.getQueryString() != null || request.getContentLengthLong() > 0) throw unavailable();
        if (!(authentication instanceof AgentRuntimeAuthentication runtime)) {
            throw new RuntimeAccessException(authentication == null || !authentication.isAuthenticated()
                    ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN);
        }
        List<String> headers = Collections.list(request.getHeaders(MANIFEST_HEADER));
        if (headers.size() != 1) throw unavailable();
        ChatInspectionAuthorityService.Content value = authority.read(runtime.getPrincipal(), requestId,
                turnId, sourceRefId, headers.getFirst());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_TYPE, value.mimeType())
                .contentLength(value.bytes().length).body(value.bytes());
    }

    @ExceptionHandler(ChatDeliberationException.class)
    public ResponseEntity<JsonResult<Void>> error(ChatDeliberationException failure) {
        HttpStatus status = switch (failure.reason()) {
            case INVALID_REQUEST, INVALID_ROUTE, GAP -> HttpStatus.UNPROCESSABLE_ENTITY;
            case NOT_FOUND_OR_FORBIDDEN -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case PERSISTENCE_ERROR -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        JsonResult<Void> result = JsonResult.failure("INSPECTION_INPUT_" + failure.reason(),
                status == HttpStatus.NOT_FOUND
                        ? "Inspection input is unavailable" : failure.getMessage());
        result.setStatus(status.value());
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(result);
    }

    @ExceptionHandler(RuntimeAccessException.class)
    public ResponseEntity<JsonResult<Void>> forbidden(RuntimeAccessException failure) {
        JsonResult<Void> result = JsonResult.failure(
                failure.status == HttpStatus.UNAUTHORIZED
                        ? "AGENT_RUNTIME_UNAUTHENTICATED" : "AGENT_RUNTIME_FORBIDDEN",
                failure.status == HttpStatus.UNAUTHORIZED
                        ? "Runtime authentication is required" : "Runtime authentication is forbidden");
        result.setStatus(failure.status.value());
        return ResponseEntity.status(failure.status).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(result);
    }


    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<JsonResult<Void>> runtime(RuntimeException failure) {
        JsonResult<Void> result = JsonResult.failure("INSPECTION_INPUT_UNAVAILABLE",
                "Inspection input is temporarily unavailable");
        result.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(result);
    }

    private static ChatDeliberationException unavailable() {
        return new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,
                "Inspection input is unavailable");
    }

    private static final class RuntimeAccessException extends SecurityException {
        private final HttpStatus status;
        private RuntimeAccessException(HttpStatus status) { this.status = status; }
    }
}
