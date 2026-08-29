package cn.jia.chat.archive.http;

import cn.jia.chat.archive.config.ArchiveQuestionAccessPolicy;
import cn.jia.chat.archive.config.ArchiveReaderAccessPolicy;
import cn.jia.chat.archive.dto.ArchiveQuestionDTO;
import cn.jia.chat.archive.model.ArchiveOwnerScope;
import cn.jia.chat.archive.service.ArchiveMutationResult;
import cn.jia.chat.archive.service.ArchivePersonalDataException;
import cn.jia.chat.archive.service.ArchiveQuestionService;
import cn.jia.chat.archive.service.ArchiveQuestionSseService;
import cn.jia.core.entity.JsonResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@ConditionalOnProperty(prefix = "archive.question", name = "enabled", havingValue = "true")
@RestController
@RequestMapping("/archive/v1/me/questions")
public class ArchiveQuestionController {
    private static final String CACHE_CONTROL = "private, no-store";
    private final ArchiveQuestionService service;
    private final ArchiveQuestionSseService streams;
    private final ArchiveQuestionAccessPolicy accessPolicy;

    public ArchiveQuestionController(ArchiveQuestionService service, ArchiveQuestionSseService streams,
                                     ArchiveQuestionAccessPolicy accessPolicy) {
        this.service = Objects.requireNonNull(service, "service");
        this.streams = Objects.requireNonNull(streams, "streams");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
    }

    @PutMapping("/{questionId}")
    public ResponseEntity<?> create(@PathVariable String questionId,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                    @RequestBody(required = false) byte[] body,
                                    HttpServletRequest request, Authentication authentication) {
        Authorization authorization = authorize(authentication);
        if (authorization.error() != null) return authorization.error();
        ResponseEntity<JsonResult<?>> syntax = mutationSyntax(questionId, key, request);
        if (syntax != null) return syntax;
        if (!allowed(authorization.owner())) return notFound();
        return mutate(() -> service.create(authorization.owner(), questionId,
                "/archive/v1/me/questions/" + questionId, key, body));
    }

    @GetMapping("/{questionId}")
    public ResponseEntity<JsonResult<?>> get(@PathVariable String questionId, Authentication authentication) {
        Authorization authorization = authorize(authentication);
        if (authorization.error() != null) return authorization.error();
        if (!lowercaseUuid(questionId) || !allowed(authorization.owner())) return notFound();
        try {
            ArchiveQuestionDTO question = service.get(authorization.owner(), questionId);
            return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                    .body(JsonResult.success(question));
        } catch (ArchivePersonalDataException failure) {
            return error(failure);
        }
    }

    @GetMapping(value = "/{questionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<?> events(@PathVariable String questionId,
                                    @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                                    Authentication authentication) {
        Authorization authorization = authorize(authentication);
        if (authorization.error() != null) return authorization.error();
        if (!lowercaseUuid(questionId)) return notFound();
        Long cursor = eventCursor(lastEventId);
        if (cursor == null) return invalidEventCursor();
        if (!allowed(authorization.owner())) return notFound();
        try {
            SseEmitter emitter = streams.open(authorization.owner(), questionId, cursor);
            return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                    .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(emitter);
        } catch (ArchivePersonalDataException failure) {
            return error(failure);
        }
    }

    @PostMapping("/{questionId}/retry")
    public ResponseEntity<?> retry(@PathVariable String questionId,
                                   @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                   @RequestBody(required = false) byte[] body,
                                   HttpServletRequest request, Authentication authentication) {
        Authorization authorization = authorize(authentication);
        if (authorization.error() != null) return authorization.error();
        ResponseEntity<JsonResult<?>> syntax = mutationSyntax(questionId, key, request);
        if (syntax != null) return syntax;
        if (!allowed(authorization.owner())) return notFound();
        return mutate(() -> service.retry(authorization.owner(), questionId,
                "/archive/v1/me/questions/" + questionId + "/retry", key, body));
    }

    private ResponseEntity<?> mutate(Mutation mutation) {
        try {
            ArchiveMutationResult result = mutation.run();
            return ResponseEntity.status(result.status())
                    .contentType(MediaType.parseMediaType(result.contentType()))
                    .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(result.body());
        } catch (ArchivePersonalDataException failure) {
            return error(failure);
        }
    }

    private Authorization authorize(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwt)) return Authorization.failure(authIncomplete());
        Object tenantClaim = jwt.getToken().getClaims().get("jiacn");
        Object clientClaim = jwt.getToken().getClaims().get("client_id");
        if (!(tenantClaim instanceof String tenantId) || !(clientClaim instanceof String clientId)
                || !ArchiveReaderAccessPolicy.validRequestClaim(tenantId)
                || !ArchiveReaderAccessPolicy.validRequestClaim(clientId)) {
            return Authorization.failure(authIncomplete());
        }
        return new Authorization(new ArchiveOwnerScope(tenantId, clientId, tenantId), null);
    }

    private ResponseEntity<JsonResult<?>> mutationSyntax(String questionId, String key, HttpServletRequest request) {
        if (!lowercaseUuid(questionId)) return notFound();
        if (request.getQueryString() != null) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST_JSON",
                    "Archive mutation routes do not accept query strings", null);
        }
        if (!visibleAsciiKey(key)) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be 1..128 visible ASCII bytes", null);
        }
        return null;
    }

    private Long eventCursor(String value) {
        if (value == null) return 0L;
        if (!value.matches("0|[1-9][0-9]{0,18}")) return null;
        try {
            long parsed = Long.parseLong(value);
            return parsed < 0 ? null : parsed;
        } catch (NumberFormatException ignored) { return null; }
    }

    private boolean allowed(ArchiveOwnerScope owner) {
        return accessPolicy.allows(owner.tenantId(), owner.clientId());
    }
    private boolean lowercaseUuid(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) return false;
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException ignored) { return false; }
    }
    private boolean visibleAsciiKey(String value) {
        if (value == null || value.length() < 1 || value.length() > 128) return false;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 0x21 || value.charAt(i) > 0x7e) return false;
        return true;
    }


    private ResponseEntity<JsonResult<?>> invalidEventCursor() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_EVENT_CURSOR",
                "Last-Event-ID must be a canonical nonnegative decimal", null);
    }
    private ResponseEntity<JsonResult<?>> authIncomplete() {
        return error(HttpStatus.UNAUTHORIZED, "AUTH_CONTEXT_INCOMPLETE",
                "Archive authentication context is incomplete", null);
    }
    private ResponseEntity<JsonResult<?>> notFound() {
        return error(HttpStatus.NOT_FOUND, "ARCHIVE_RESOURCE_NOT_FOUND",
                "Archive resource is not available", null);
    }
    private ResponseEntity<JsonResult<?>> error(ArchivePersonalDataException failure) {
        return error(HttpStatus.valueOf(failure.status()), failure.code(), failure.getMessage(), failure.currentVersion());
    }
    private ResponseEntity<JsonResult<?>> error(HttpStatus status, String code, String message, String currentVersion) {
        JsonResult<Object> result = JsonResult.failure(code, message);
        if (currentVersion != null) result.setData(Map.of("currentVersion", currentVersion));
        result.setStatus(status.value());
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(result);
    }

    private record Authorization(ArchiveOwnerScope owner, ResponseEntity<JsonResult<?>> error) {
        static Authorization failure(ResponseEntity<JsonResult<?>> error) { return new Authorization(null, error); }
    }
    @FunctionalInterface private interface Mutation { ArchiveMutationResult run(); }
}
