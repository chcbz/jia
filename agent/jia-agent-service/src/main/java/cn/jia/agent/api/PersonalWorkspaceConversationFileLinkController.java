package cn.jia.agent.api;

import cn.jia.agent.service.PersonalWorkspaceConversationLinkService;
import cn.jia.agent.service.impl.PersonalWorkspaceConversationLinkServiceImpl;
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
@RequestMapping("/agent/conversations")
public class PersonalWorkspaceConversationFileLinkController {
    static final String CACHE_CONTROL = "private, no-store";
    private final PersonalWorkspaceConversationLinkService service;

    public PersonalWorkspaceConversationFileLinkController(PersonalWorkspaceConversationLinkService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @GetMapping(value = "/{conversationId}/file-links", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonalWorkspaceConversationLinkService.LinkListView> list(
            @PathVariable String conversationId, @RequestParam(required = false) String cursor,
            Authentication authentication) {
        PersonalWorkspaceConversationLinkService.Scope scope = scope(authentication);
        return json(service.list(scope, conversationId, cursor));
    }

    @PostMapping(value = "/{conversationId}/file-links", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonalWorkspaceConversationLinkService.LinkView> create(
            @PathVariable String conversationId, @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        PersonalWorkspaceConversationLinkService.Scope scope = scope(authentication);
        PersonalWorkspaceConversationLinkService.CreateCommand command =
                createCommand(body, idempotencyKey);
        PersonalWorkspaceConversationLinkService.LinkView link = service.create(scope, conversationId, command);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .eTag(PersonalWorkspaceConversationLinkServiceImpl.etag(link))
                .contentType(MediaType.APPLICATION_JSON).body(link);
    }

    @DeleteMapping(value = "/{conversationId}/file-links/{relationId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonalWorkspaceConversationLinkService.DetachView> detach(
            @PathVariable String conversationId, @PathVariable String relationId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        PersonalWorkspaceConversationLinkService.Scope scope = scope(authentication);
        PersonalWorkspaceConversationLinkService.DetachView result = service.detach(
                scope, conversationId, relationId, ifMatch, idempotencyKey);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .eTag(PersonalWorkspaceConversationLinkServiceImpl.etag(result.link()))
                .contentType(MediaType.APPLICATION_JSON).body(result);
    }

    @ExceptionHandler(AuthenticationFailure.class)
    public ResponseEntity<ErrorBody> authentication(AuthenticationFailure failure) {
        return error(failure.forbidden ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED,
                failure.forbidden ? "CONVERSATION_FILE_LINK_FORBIDDEN" : "UNAUTHENTICATED",
                failure.forbidden ? "Conversation file link scope is unavailable"
                        : "Authentication is required");
    }

    @ExceptionHandler(PersonalWorkspaceConversationLinkService.Failure.class)
    public ResponseEntity<ErrorBody> failure(PersonalWorkspaceConversationLinkService.Failure failure) {
        return switch (failure.getReason()) {
            case BAD_REQUEST -> error(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                    "Invalid conversation file link request");
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "CONVERSATION_FILE_LINK_NOT_FOUND",
                    "Conversation file link resource is unavailable");
            case IDEMPOTENCY_CONFLICT -> error(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "Idempotency key conflicts with a different request");
            case OUTPUT_MANAGED_BY_EXECUTION -> error(HttpStatus.CONFLICT,
                    "OUTPUT_LINK_MANAGED_BY_EXECUTION",
                    "Output links are managed by conversation execution");
            case PRECONDITION_REQUIRED -> error(HttpStatus.PRECONDITION_REQUIRED,
                    "PRECONDITION_REQUIRED", "If-Match is required");
            case LINK_CHANGED -> error(HttpStatus.PRECONDITION_FAILED, "CONVERSATION_FILE_LINK_CHANGED",
                    "Conversation file link changed");
            case UNAVAILABLE -> error(HttpStatus.SERVICE_UNAVAILABLE,
                    "CONVERSATION_FILE_LINK_UNAVAILABLE", "Conversation file links are temporarily unavailable");
        };
    }

    @ExceptionHandler({IllegalArgumentException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<ErrorBody> malformed(Exception ignored) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Invalid conversation file link request");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> unexpected(Exception failure, HttpServletRequest request) {
        log.error("Conversation file link request failed: uri={}", request.getRequestURI(), failure);
        return error(HttpStatus.SERVICE_UNAVAILABLE, "CONVERSATION_FILE_LINK_UNAVAILABLE",
                "Conversation file links are temporarily unavailable");
    }

    private static PersonalWorkspaceConversationLinkService.Scope scope(Authentication authentication) {
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
        return new PersonalWorkspaceConversationLinkService.Scope("0", clientId, ownerJiacn);
    }

    private static PersonalWorkspaceConversationLinkService.CreateCommand createCommand(
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
        return new PersonalWorkspaceConversationLinkService.CreateCommand(
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
