package cn.jia.chat.api;

import cn.jia.agent.security.AgentRuntimeAuthentication;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.chat.service.ChatDeliberationException;
import cn.jia.chat.service.ChatMaterialsInputService;
import cn.jia.core.entity.JsonResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
@ConditionalOnProperty(prefix = "chat.typed-deliberation", name = "enabled", havingValue = "true")
public final class AgentMaterialsInputController {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AgentMaterialsInputController.class);
    private static final String MANIFEST_HEADER = "X-Materials-Manifest-Digest";
    private final ChatMaterialsInputService authority;

    public AgentMaterialsInputController(ChatMaterialsInputService authority) {
        this.authority = Objects.requireNonNull(authority);
    }

    @GetMapping("/internal/agent/chat/requests/{requestId}/turns/{turnId}/materials/inputs/{sourceRefId}/content")
    public ResponseEntity<byte[]> content(@PathVariable String requestId,
            @PathVariable String turnId, @PathVariable String sourceRefId,
            Authentication authentication, HttpServletRequest request) {
        if (request.getQueryString() != null || request.getContentLengthLong() > 0 || request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null) throw unavailable();
        if (!(authentication instanceof AgentRuntimeAuthentication runtime) || !runtime.isAuthenticated()) {
            throw new RuntimeAccessException(authentication == null || !authentication.isAuthenticated()
                    ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN);
        }
        List<String> headers = Collections.list(request.getHeaders(MANIFEST_HEADER));
        if (headers.size() != 1) throw unavailable();
        var proof=AgentRuntimeAuthenticationService.requireProof(runtime);
        cn.jia.agent.service.PersonalWorkspaceExecutionService.RuntimeContent value = authority.read(proof, requestId,
                turnId, sourceRefId, headers.getFirst());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_TYPE, value.contentMimeType())
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
        JsonResult<Void> result = JsonResult.failure("MATERIAL_INPUT_" + failure.reason(),
                status == HttpStatus.NOT_FOUND
                        ? "Material input is unavailable" : failure.getMessage());

        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(result);
    }

    @ExceptionHandler(RuntimeAccessException.class)
    public ResponseEntity<JsonResult<Void>> forbidden(RuntimeAccessException failure) {
        JsonResult<Void> result = JsonResult.failure(
                failure.status == HttpStatus.UNAUTHORIZED
                        ? "AGENT_RUNTIME_UNAUTHENTICATED" : "AGENT_RUNTIME_FORBIDDEN",
                failure.status == HttpStatus.UNAUTHORIZED
                        ? "Runtime authentication is required" : "Runtime authentication is forbidden");

        return ResponseEntity.status(failure.status).contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(result);
    }


    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<JsonResult<Void>> runtime(RuntimeException failure, HttpServletRequest request) {
        // Preserve the failing code location without logging credentials, source bytes,
        // query parameters or exception messages (which can contain storage details).
        Throwable root = org.springframework.core.NestedExceptionUtils.getMostSpecificCause(failure);
        LOG.warn("Material input failure route={} exceptionType={} rootType={} stack={}",
                request.getRequestURI(), failure.getClass().getName(), root.getClass().getName(),
                java.util.Arrays.toString(root.getStackTrace()));
        JsonResult<Void> result = JsonResult.failure("MATERIAL_INPUT_UNAVAILABLE",
                "Material input is temporarily unavailable");

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(result);
    }

    private static ChatDeliberationException unavailable() {
        return new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,
                "Material input is unavailable");
    }

    private static final class RuntimeAccessException extends SecurityException {
        private final HttpStatus status;
        private RuntimeAccessException(HttpStatus status) { this.status = status; }
    }
}
