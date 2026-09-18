package cn.jia.agent.api;

import cn.jia.agent.service.PersonalWorkspaceTaskLinkService;
import cn.jia.agent.service.impl.PersonalWorkspaceTaskLinkServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Owner-only browser API. Scope comes exclusively from the current JWT. */
@Slf4j
@RestController
@RequestMapping("/agent/tasks")
public class PersonalWorkspaceTaskFileLinkController {
    static final String CACHE_CONTROL = "private, no-store";
    private final PersonalWorkspaceTaskLinkService service;

    public PersonalWorkspaceTaskFileLinkController(PersonalWorkspaceTaskLinkService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @GetMapping(value = "/{taskId}/file-links", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonalWorkspaceTaskLinkService.LinkListView> list(
            @PathVariable String taskId, @RequestParam(required = false) String cursor,
            Authentication authentication) {
        PersonalWorkspaceTaskLinkService.Scope scope = scope(authentication);
        return json(service.list(scope, taskId, cursor));
    }

    @PostMapping(value = "/{taskId}/file-links", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonalWorkspaceTaskLinkService.LinkView> create(
            @PathVariable String taskId, @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        PersonalWorkspaceTaskLinkService.Scope scope = scope(authentication);
        PersonalWorkspaceTaskLinkService.CreateCommand command =
                createCommand(body, idempotencyKey);
        PersonalWorkspaceTaskLinkService.LinkView link = service.create(scope, taskId, command);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .eTag(PersonalWorkspaceTaskLinkServiceImpl.etag(link))
                .contentType(MediaType.APPLICATION_JSON).body(link);
    }

    @DeleteMapping(value = "/{taskId}/file-links/{relationId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonalWorkspaceTaskLinkService.DetachView> detach(
            @PathVariable String taskId, @PathVariable String relationId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        PersonalWorkspaceTaskLinkService.Scope scope = scope(authentication);
        PersonalWorkspaceTaskLinkService.DetachView result = service.detach(
                scope, taskId, relationId, ifMatch, idempotencyKey);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .eTag(PersonalWorkspaceTaskLinkServiceImpl.etag(result.link()))
                .contentType(MediaType.APPLICATION_JSON).body(result);
    }

    @ExceptionHandler(AuthenticationFailure.class)
    public ResponseEntity<ErrorBody> authentication(AuthenticationFailure failure) {
        return error(failure.forbidden ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED,
                failure.forbidden ? "TASK_FILE_LINK_FORBIDDEN" : "UNAUTHENTICATED",
                failure.forbidden ? "Task file link scope is unavailable"
                        : "Authentication is required");
    }

    @ExceptionHandler(PersonalWorkspaceTaskLinkService.Failure.class)
    public ResponseEntity<ErrorBody> failure(PersonalWorkspaceTaskLinkService.Failure failure) {
        return switch (failure.getReason()) {
            case BAD_REQUEST -> error(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                    "Invalid task file link request");
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "TASK_FILE_LINK_NOT_FOUND",
                    "Task file link resource is unavailable");
            case IDEMPOTENCY_CONFLICT -> error(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "Idempotency key conflicts with a different request");
            case OUTPUT_MANAGED_BY_EXECUTION -> error(HttpStatus.CONFLICT,
                    "OUTPUT_LINK_MANAGED_BY_EXECUTION",
                    "Output links are managed by task execution");
            case PRECONDITION_REQUIRED -> error(HttpStatus.PRECONDITION_REQUIRED,
                    "PRECONDITION_REQUIRED", "If-Match is required");
            case LINK_CHANGED -> error(HttpStatus.PRECONDITION_FAILED, "TASK_FILE_LINK_CHANGED",
                    "Task file link changed");
            case UNAVAILABLE -> error(HttpStatus.SERVICE_UNAVAILABLE,
                    "TASK_FILE_LINK_UNAVAILABLE", "Task file links are temporarily unavailable");
        };
    }

    @ExceptionHandler({IllegalArgumentException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<ErrorBody> malformed(Exception ignored) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Invalid task file link request");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> unexpected(Exception failure, HttpServletRequest request) {
        log.error("Task file link request failed: uri={}", request.getRequestURI(), failure);
        return error(HttpStatus.SERVICE_UNAVAILABLE, "TASK_FILE_LINK_UNAVAILABLE",
                "Task file links are temporarily unavailable");
    }

    private static PersonalWorkspaceTaskLinkService.Scope scope(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication instanceof JwtAuthenticationToken jwt)) {
            throw new AuthenticationFailure(false);
        }
        Map<String, Object> claims = jwt.getToken().getClaims();
        Object owner = claims.get("jiacn");
        Object client = claims.get("client_id");
        if (!(owner instanceof String ownerJiacn) || !(client instanceof String clientId)
                || !valid(ownerJiacn, 50) || !valid(clientId, 50) || "0".equals(ownerJiacn)) {
            throw new AuthenticationFailure(true);
        }
        return new PersonalWorkspaceTaskLinkService.Scope("0", clientId, ownerJiacn);
    }

    private static PersonalWorkspaceTaskLinkService.CreateCommand createCommand(
            Map<String, Object> body, String idempotencyKey) {
        if (body == null || !body.keySet().equals(Set.of("fileId", "version", "role"))
                || !(body.get("fileId") instanceof String fileId)
                || !(body.get("role") instanceof String role)
                || !(body.get("version") instanceof Number rawVersion)) {
            throw new IllegalArgumentException("body");
        }
        long version = rawVersion.longValue();
        if (version < 1 || version > Integer.MAX_VALUE
                || rawVersion.doubleValue() != (double) version) {
            throw new IllegalArgumentException("version");
        }
        return new PersonalWorkspaceTaskLinkService.CreateCommand(
                fileId, (int) version, role, idempotencyKey);
    }

    private static boolean valid(String value, int max) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.codePointCount(0, value.length()) <= max
                && value.chars().noneMatch(Character::isISOControl);
    }

    private static <T> ResponseEntity<T> json(T body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static ResponseEntity<ErrorBody> error(
            HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                .body(new ErrorBody(code, message));
    }

    public record ErrorBody(String code, String message) { }

    private static final class AuthenticationFailure extends RuntimeException {
        private final boolean forbidden;
        private AuthenticationFailure(boolean forbidden) { this.forbidden = forbidden; }
    }
}
