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

/** Browser JWT Hall draft adapter. Submit delegates only to the transactional application service. */
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
            .withCoercionConfig(tools.jackson.databind.type.LogicalType.Textual, config -> {
                for (var shape : List.of(tools.jackson.databind.cfg.CoercionInputShape.Integer,
                        tools.jackson.databind.cfg.CoercionInputShape.Float, tools.jackson.databind.cfg.CoercionInputShape.Boolean))
                    config.setCoercion(shape, tools.jackson.databind.cfg.CoercionAction.Fail);
            })
            .build();
    private final HallRequestDraftService service;

    public HallRequestDraftController(HallRequestDraftService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<cn.jia.core.entity.JsonResult<HallDraftWire.DraftView>> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request, Authentication authentication) {
        requireNoQuery(request);
        CreateRequest body = parse(readBounded(request), CreateRequest.class);
        HallRequestDraftService.DraftView view = service.create(scope(authentication), command(body),
                idempotencyKey);
        return cn.jia.core.config.ApiJsonResponses.success(ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .contentType(MediaType.APPLICATION_JSON).body(HallDraftWire.from(view)));
    }

    @GetMapping(value = "/{draftId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<cn.jia.core.entity.JsonResult<HallDraftWire.DraftView>> get(@PathVariable String draftId,
            HttpServletRequest request, Authentication authentication) {
        requireNoQuery(request);
        return cn.jia.core.config.ApiJsonResponses.success(ok(HallDraftWire.from(service.get(scope(authentication), draftId))));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<cn.jia.core.entity.JsonResult<HallDraftWire.DraftPage>> list(
            @RequestParam(required = false) String cursor,
            HttpServletRequest request, Authentication authentication) {
        requireOnlyCursorQuery(request);
        return cn.jia.core.config.ApiJsonResponses.success(ok(HallDraftWire.from(service.list(scope(authentication), cursor))));
    }

    @PutMapping(value = "/{draftId}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<cn.jia.core.entity.JsonResult<HallDraftWire.DraftView>> replace(@PathVariable String draftId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            HttpServletRequest request, Authentication authentication) {
        requireNoQuery(request);
        ReplaceRequest body = parse(readBounded(request), ReplaceRequest.class);
        return cn.jia.core.config.ApiJsonResponses.success(ok(HallDraftWire.from(service.replace(scope(authentication), draftId, revision(ifMatch), editable(body)))));
    }

    @PostMapping(value = "/{draftId}/discard", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<cn.jia.core.entity.JsonResult<HallDraftWire.DraftView>> discard(@PathVariable String draftId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request, Authentication authentication) {
        requireNoQuery(request);
        DiscardRequest body = parse(readBounded(request), DiscardRequest.class);
        if (body.expectedRevision() == null) throw new RequestFailure();
        return cn.jia.core.config.ApiJsonResponses.success(ok(HallDraftWire.from(service.discard(scope(authentication), draftId, positiveLong(body.expectedRevision()),
                idempotencyKey))));
    }

    @PostMapping(value = "/{draftId}/submit", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<cn.jia.core.entity.JsonResult<HallDraftWire.SubmissionReceipt>> submit(
            @PathVariable String draftId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request, Authentication authentication) {
        requireNoQuery(request);
        SubmitRequest body = parse(readBounded(request), SubmitRequest.class);
        if (body.expectedRevision() == null || body.authorizationAcknowledgement() == null) {
            throw new RequestFailure();
        }
        HallRequestDraftService.SubmissionReceipt receipt = service.submit(scope(authentication),
                draftId, positiveLong(body.expectedRevision()), body.authorizationAcknowledgement(), idempotencyKey);
        return cn.jia.core.config.ApiJsonResponses.success(ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .contentType(MediaType.APPLICATION_JSON).body(HallDraftWire.from(receipt)));
    }

    @ExceptionHandler(AuthenticationFailure.class)
    public ResponseEntity<cn.jia.core.entity.JsonResult<FailureDetails>> authentication(AuthenticationFailure failure) {
        return error(failure.forbidden ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED,
                failure.forbidden ? "HALL_DRAFT_FORBIDDEN" : "HALL_DRAFT_UNAUTHENTICATED",
                "Hall draft access is unavailable", false, Map.of());
    }

    @ExceptionHandler(HallRequestDraftService.Failure.class)
    public ResponseEntity<cn.jia.core.entity.JsonResult<FailureDetails>> failure(HallRequestDraftService.Failure failure) {
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
            case SUBMISSION_UNAVAILABLE -> error(HttpStatus.UNPROCESSABLE_ENTITY,
                    "HALL_SUBMISSION_KIND_UNAVAILABLE",
                    "This Hall submission kind is not available", false, Map.of());
            case EXECUTION_CONFLICT -> error(HttpStatus.CONFLICT,
                    "HALL_SUBMISSION_EXECUTION_CONFLICT",
                    "The referenced execution changed; reload before submitting", false, Map.of());
            case STORAGE_UNAVAILABLE -> error(HttpStatus.SERVICE_UNAVAILABLE,
                    "HALL_DRAFT_STORAGE_UNAVAILABLE",
                    "Hall draft storage is temporarily unavailable", true, Map.of());
        };
    }

    @ExceptionHandler({RequestFailure.class, IllegalArgumentException.class, org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<cn.jia.core.entity.JsonResult<FailureDetails>> malformed(Exception ignored) {
        return error(HttpStatus.BAD_REQUEST, "HALL_DRAFT_BAD_REQUEST",
                "Invalid Hall draft request", false, Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<cn.jia.core.entity.JsonResult<FailureDetails>> unexpected(Exception ignored) {
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
            return new HallRequestDraftService.InputSelection(input.fileId(), positiveInt(input.version()));
        }).toList();
    }

    private static HallRequestDraftService.SourceRef source(SourceRefRequest source) {
        return source == null ? null : new HallRequestDraftService.SourceRef(
                source.sourceType(), source.sourceId(), source.version()==null?null:positiveInt(source.version()));
    }

    private static HallRequestDraftService.SourceOutputRef sourceOutput(SourceOutputRefRequest source) {
        if (source == null) return null;
        if (source.fileVersion() == null) throw new RequestFailure();
        return new HallRequestDraftService.SourceOutputRef(source.executionId(), source.outputId(),
                source.fileId(), positiveInt(source.fileVersion()));
    }

    static HallRequestDraftService.OwnerScope scope(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwt)
                || !authentication.isAuthenticated()) throw new AuthenticationFailure(false);
        Map<String, Object> claims = jwt.getToken().getClaims();
        Object owner = claims.get("jiacn"); Object client = claims.get("client_id");
        if (!"0".equals(claims.get("tenant_id")) || !"cyf".equals(claims.get("data_domain_id")))
            throw new AuthenticationFailure(true);
        if (!(owner instanceof String ownerJiacn) || !(client instanceof String clientId)
                || !identity(ownerJiacn) || !identity(clientId) || "0".equals(ownerJiacn)) {
            throw new AuthenticationFailure(true);
        }
        // This legacy store is client-scoped and restricted to area 0.
        // Never reinterpret an authorized area 1 token as an area 0 owner.
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

    static void requireNoQuery(HttpServletRequest request) {
        if (request == null || !request.getParameterMap().isEmpty()) throw new RequestFailure();
    }

    private static void requireOnlyCursorQuery(HttpServletRequest request) {
        if (request == null || request.getParameterMap().keySet().stream()
                .anyMatch(key -> !"cursor".equals(key))
                || request.getParameterMap().values().stream().anyMatch(values -> values.length != 1)) {
            throw new RequestFailure();
        }
    }

    static byte[] readBounded(HttpServletRequest request) {
        if (request == null) throw new RequestFailure();
        try { return cn.jia.core.config.ApiJsonInput.readData(request, MAX_BODY_BYTES, false); }
        catch (IOException failure) { throw new RequestFailure(); }
    }

    static <T> T parse(byte[] body, Class<T> type) {
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

    static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .contentType(MediaType.APPLICATION_JSON).body(body);
    }

    static ResponseEntity<cn.jia.core.entity.JsonResult<FailureDetails>> error(HttpStatus status, String code, String message,
            boolean retryable, Map<String, String> details) {
        var result = cn.jia.core.entity.JsonResult.<FailureDetails>failure(code, message);
        result.setData(new FailureDetails(retryable, details == null || details.isEmpty() ? null : Map.copyOf(details)));
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8)).body(result);
    }

    public record CreateRequest(String kind, String originRef, SourceRefRequest sourceRef,
            String caseId, String taskId, String conversationId, String title, String instruction,
            String targetAgentId, String outputMime, List<InputRequest> inputs,
            SourceOutputRefRequest sourceOutputRef) { }
    public record ReplaceRequest(String title, String instruction, String targetAgentId,
            String outputMime, List<InputRequest> inputs) { }
    public record DiscardRequest(String expectedRevision) { }
    public record SubmitRequest(String expectedRevision, Boolean authorizationAcknowledgement) { }
    public record InputRequest(String fileId, String version) { }
    public record SourceRefRequest(String sourceType, String sourceId, String version) { }
    public record SourceOutputRefRequest(String executionId, String outputId,
            String fileId, String fileVersion) { }
    public record FailureDetails(boolean retryable, Map<String, String> details) { }
    public record ErrorBody(String code, String message, String traceId,
            boolean retryable, Map<String, String> details) { }

    private static long positiveLong(String value) {
        if (value == null || !value.matches("[1-9][0-9]*") || value.length()>19) throw new RequestFailure();
        try { return Long.parseLong(value); } catch (NumberFormatException invalid) { throw new RequestFailure(); }
    }
    private static int positiveInt(String value) {
        long parsed=positiveLong(value); if(parsed>Integer.MAX_VALUE) throw new RequestFailure(); return (int)parsed;
    }
    static final class RequestFailure extends RuntimeException { }
    static final class AuthenticationFailure extends RuntimeException {
        final boolean forbidden;
        private AuthenticationFailure(boolean forbidden) { this.forbidden = forbidden; }
    }
}
