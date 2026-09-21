package cn.jia.agent.api;

import cn.jia.agent.service.HallRequestDraftService;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Browser JWT DRAFT-v1 adapter. It exposes persistence only and has no submit/run route. */
@RestController
@RequestMapping("/agent/hall/drafts")
public class HallRequestDraftController {
    static final String CACHE_CONTROL = "private, no-store";
    static final int MAX_BODY_BYTES = 256 * 1024;
    private static final ObjectMapper STRICT_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();
    private final HallRequestDraftService service;

    public HallRequestDraftController(HallRequestDraftService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HallRequestDraftService.DraftView> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request, Authentication authentication) {
        requireNoQuery(request);
        CreateRequest body = parse(readBounded(request), CreateRequest.class);
        HallRequestDraftService.DraftView view = service.create(scope(authentication), command(body),
                idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .contentType(MediaType.APPLICATION_JSON).body(view);
    }

    @GetMapping(value = "/{draftId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HallRequestDraftService.DraftView> get(@PathVariable String draftId,
            HttpServletRequest request, Authentication authentication) {
        requireNoQuery(request);
        return ok(service.get(scope(authentication), draftId));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HallRequestDraftService.DraftPage> list(
            @RequestParam(required = false) String cursor,
            HttpServletRequest request, Authentication authentication) {
        requireOnlyCursorQuery(request);
        return ok(service.list(scope(authentication), cursor));
    }

    @PutMapping(value = "/{draftId}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HallRequestDraftService.DraftView> replace(@PathVariable String draftId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            HttpServletRequest request, Authentication authentication) {
        requireNoQuery(request);
        ReplaceRequest body = parse(readBounded(request), ReplaceRequest.class);
        return ok(service.replace(scope(authentication), draftId, revision(ifMatch), editable(body)));
    }

    @PostMapping(value = "/{draftId}/discard", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HallRequestDraftService.DraftView> discard(@PathVariable String draftId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request, Authentication authentication) {
        requireNoQuery(request);
        DiscardRequest body = parse(readBounded(request), DiscardRequest.class);
        if (body.expectedRevision() == null) throw new RequestFailure();
        return ok(service.discard(scope(authentication), draftId, body.expectedRevision(),
                idempotencyKey));
    }

    @ExceptionHandler(AuthenticationFailure.class)
    public ResponseEntity<ErrorBody> authentication(AuthenticationFailure failure) {
        return error(failure.forbidden ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED,
                failure.forbidden ? "HALL_DRAFT_FORBIDDEN" : "HALL_DRAFT_UNAUTHENTICATED",
                "Hall draft access is unavailable", false, Map.of());
    }

    @ExceptionHandler(HallRequestDraftService.Failure.class)
    public ResponseEntity<ErrorBody> failure(HallRequestDraftService.Failure failure) {
        return switch (failure.reason()) {
            case BAD_REQUEST -> error(HttpStatus.BAD_REQUEST, "HALL_DRAFT_BAD_REQUEST",
                    "Invalid Hall draft request", false, failure.safeDetails());
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "HALL_DRAFT_NOT_FOUND",
                    "Hall draft is unavailable", false, Map.of());
            case IDEMPOTENCY_CONFLICT -> error(HttpStatus.CONFLICT, "HALL_DRAFT_IDEMPOTENCY_CONFLICT",
                    "Operation key conflicts with a different request", false, Map.of());
            case STATE_CONFLICT -> error(HttpStatus.CONFLICT, "HALL_DRAFT_STATE_CONFLICT",
                    "Hall draft is no longer editable", false, Map.of());
            case REVISION_CHANGED -> error(HttpStatus.PRECONDITION_FAILED, "HALL_DRAFT_REVISION_CHANGED",
                    "Hall draft changed; reload before saving", false, Map.of());
            case SOURCE_UNAVAILABLE -> error(HttpStatus.UNPROCESSABLE_ENTITY,
                    "HALL_DRAFT_SOURCE_UNAVAILABLE",
                    "Referenced source is unavailable or not opened for Hall drafts", false,
                    failure.safeDetails());
            case STORAGE_UNAVAILABLE -> error(HttpStatus.SERVICE_UNAVAILABLE,
                    "HALL_DRAFT_STORAGE_UNAVAILABLE",
                    "Hall draft storage is temporarily unavailable", true, Map.of());
        };
    }

    @ExceptionHandler({RequestFailure.class, IllegalArgumentException.class})
    public ResponseEntity<ErrorBody> malformed(Exception ignored) {
        return error(HttpStatus.BAD_REQUEST, "HALL_DRAFT_BAD_REQUEST",
                "Invalid Hall draft request", false, Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> unexpected(Exception ignored) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "HALL_DRAFT_STORAGE_UNAVAILABLE",
                "Hall draft storage is temporarily unavailable", true, Map.of());
    }

    private static HallRequestDraftService.CreateCommand command(CreateRequest request) {
        if (request == null || request.inputs() == null) throw new RequestFailure();
        return new HallRequestDraftService.CreateCommand(request.kind(), request.originRef(),
                source(request.sourceRef()), request.caseId(), request.taskId(), request.conversationId(),
                new HallRequestDraftService.EditableFields(request.title(), request.instruction(),
                        request.targetAgentId(), request.outputMime(), inputs(request.inputs())),
                sourceOutput(request.sourceOutputRef()));
    }

    private static HallRequestDraftService.EditableFields editable(ReplaceRequest request) {
        if (request == null || request.inputs() == null) throw new RequestFailure();
        return new HallRequestDraftService.EditableFields(request.title(), request.instruction(),
                request.targetAgentId(), request.outputMime(), inputs(request.inputs()));
    }

    private static List<HallRequestDraftService.InputSelection> inputs(List<InputRequest> inputs) {
        if (inputs == null) throw new RequestFailure();
        return inputs.stream().map(input -> {
            if (input == null || input.version() == null) throw new RequestFailure();
            return new HallRequestDraftService.InputSelection(input.fileId(), input.version());
        }).toList();
    }

    private static HallRequestDraftService.SourceRef source(SourceRefRequest source) {
        return source == null ? null : new HallRequestDraftService.SourceRef(
                source.sourceType(), source.sourceId(), source.version());
    }

    private static HallRequestDraftService.SourceOutputRef sourceOutput(SourceOutputRefRequest source) {
        if (source == null) return null;
        if (source.fileVersion() == null) throw new RequestFailure();
        return new HallRequestDraftService.SourceOutputRef(source.executionId(), source.outputId(),
                source.fileId(), source.fileVersion());
    }

    private static HallRequestDraftService.OwnerScope scope(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwt)
                || !authentication.isAuthenticated()) throw new AuthenticationFailure(false);
        Map<String, Object> claims = jwt.getToken().getClaims();
        Object owner = claims.get("jiacn"); Object client = claims.get("client_id");
        if (!(owner instanceof String ownerJiacn) || !(client instanceof String clientId)
                || !identity(ownerJiacn) || !identity(clientId) || "0".equals(ownerJiacn)) {
            throw new AuthenticationFailure(true);
        }
        return new HallRequestDraftService.OwnerScope("0", clientId, ownerJiacn);
    }

    private static boolean identity(String value) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.codePointCount(0, value.length()) <= 50
                && !value.chars().anyMatch(Character::isISOControl);
    }

    private static long revision(String ifMatch) {
        if (ifMatch == null || !ifMatch.matches("\"[1-9][0-9]{0,15}\"")) {
            throw new RequestFailure();
        }
        try {
            long revision = Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
            if (revision > 9_007_199_254_740_991L) throw new RequestFailure();
            return revision;
        } catch (NumberFormatException malformed) {
            throw new RequestFailure();
        }
    }

    private static void requireNoQuery(HttpServletRequest request) {
        if (request == null || !request.getParameterMap().isEmpty()) throw new RequestFailure();
    }

    private static void requireOnlyCursorQuery(HttpServletRequest request) {
        if (request == null || request.getParameterMap().keySet().stream()
                .anyMatch(key -> !"cursor".equals(key))
                || request.getParameterMap().values().stream().anyMatch(values -> values.length != 1)) {
            throw new RequestFailure();
        }
    }

    private static byte[] readBounded(HttpServletRequest request) {
        if (request == null) throw new RequestFailure();
        int declared = request.getContentLength();
        if (declared == 0 || declared > MAX_BODY_BYTES) throw new RequestFailure();
        try (ServletInputStream input = request.getInputStream();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            for (int read; (read = input.read(buffer)) != -1;) {
                total += read;
                if (total > MAX_BODY_BYTES) throw new RequestFailure();
                output.write(buffer, 0, read);
            }
            if (total == 0) throw new RequestFailure();
            return output.toByteArray();
        } catch (RequestFailure failure) {
            throw failure;
        } catch (IOException failure) {
            throw new RequestFailure();
        }
    }

    private static <T> T parse(byte[] body, Class<T> type) {
        try {
            T parsed = STRICT_JSON.readValue(body, type);
            if (parsed == null) throw new RequestFailure();
            return parsed;
        } catch (RequestFailure failure) {
            throw failure;
        } catch (Exception malformed) {
            throw new RequestFailure();
        }
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static ResponseEntity<ErrorBody> error(HttpStatus status, String code, String message,
            boolean retryable, Map<String, String> details) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                .body(new ErrorBody(code, message, UUID.randomUUID().toString(), retryable,
                        details == null || details.isEmpty() ? null : Map.copyOf(details)));
    }

    public record CreateRequest(String kind, String originRef, SourceRefRequest sourceRef,
            String caseId, String taskId, String conversationId, String title, String instruction,
            String targetAgentId, String outputMime, List<InputRequest> inputs,
            SourceOutputRefRequest sourceOutputRef) { }
    public record ReplaceRequest(String title, String instruction, String targetAgentId,
            String outputMime, List<InputRequest> inputs) { }
    public record DiscardRequest(Long expectedRevision) { }
    public record InputRequest(String fileId, Integer version) { }
    public record SourceRefRequest(String sourceType, String sourceId, Integer version) { }
    public record SourceOutputRefRequest(String executionId, String outputId,
            String fileId, Integer fileVersion) { }
    public record ErrorBody(String code, String message, String traceId,
            boolean retryable, Map<String, String> details) { }

    private static final class RequestFailure extends RuntimeException { }
    private static final class AuthenticationFailure extends RuntimeException {
        private final boolean forbidden;
        private AuthenticationFailure(boolean forbidden) { this.forbidden = forbidden; }
    }
}
