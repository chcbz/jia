package cn.jia.agent.api;

import cn.jia.agent.entity.AgentTaskFormalDeliveryDecisionDTO;
import cn.jia.agent.entity.AgentTaskFormalDeliveryItemDTO;
import cn.jia.agent.entity.AgentTaskFormalDeliverySubmitDTO;
import cn.jia.agent.entity.AgentTaskFormalDeliveryViewDTO;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.service.AgentTaskFormalDeliveryDecisionService;
import cn.jia.agent.service.AgentTaskFormalDeliveryReadService;
import cn.jia.agent.service.AgentTaskFormalDeliveryService;
import cn.jia.agent.service.AgentTaskReworkExecutionService;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Opt-in R2 formal-delivery HTTP adapter.
 *
 * <p>JWT {@code jiacn}, {@code client_id}, and {@code sub} are the only scope/actor authority.
 * The stable public Idempotency-Key becomes an internal deterministic delivery id; neither the
 * key nor a lease token is returned, logged, or copied to an event.</p>
 */
@Slf4j
@RestController
@RequestMapping("/agent/tasks")
@ConditionalOnProperty(prefix = "jia.agent.formal-delivery", name = "enabled", havingValue = "true")
public class AgentTaskFormalDeliveryController {
    static final String CACHE_CONTROL = "private, no-store";
    static final int MAX_BODY_BYTES = 64 * 1024;
    private static final int BODY_BUFFER_BYTES = 8192;
    private static final int MAX_ITEMS = 100;
    private static final Set<String> SUBMIT_FIELDS = Set.of("runId", "workItemId", "leaseToken",
            "expectedTaskVersion", "expectedWorkItemVersion", "summary", "manifestArtifactId",
            "manifestArtifactVersion", "items");
    private static final Set<String> ITEM_FIELDS = Set.of(
            "artifactId", "artifactVersion", "contentHash", "purpose");
    private static final Set<String> DECISION_FIELDS = Set.of(
            "expectedTaskVersion", "expectedDeliveryVersion", "decision", "reviewReason");
    private static final Set<String> REWORK_FIELDS = Set.of(
            "expectedDecisionVersion", "conversationId", "targetAgentId", "sourceOutputId",
            "sourceFileId", "sourceFileVersion", "instruction", "outputContentMimeType");
    private static final ObjectMapper STRICT_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final AgentTaskFormalDeliveryService submissionService;
    private final AgentTaskFormalDeliveryDecisionService decisionService;
    private final AgentTaskFormalDeliveryReadService readService;
    private final AgentTaskReworkExecutionService reworkService;

    public AgentTaskFormalDeliveryController(AgentTaskFormalDeliveryService submissionService,
            AgentTaskFormalDeliveryDecisionService decisionService,
            AgentTaskFormalDeliveryReadService readService,
            AgentTaskReworkExecutionService reworkService) {
        this.submissionService = Objects.requireNonNull(submissionService, "submissionService");
        this.decisionService = Objects.requireNonNull(decisionService, "decisionService");
        this.readService = Objects.requireNonNull(readService, "readService");
        this.reworkService = Objects.requireNonNull(reworkService, "reworkService");
    }

    @GetMapping(value = "/{taskId}/formal-deliveries", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FormalDeliveryListResponse> list(@PathVariable String taskId,
            HttpServletRequest request, Authentication authentication) {
        Scope scope = requireJwtScope(authentication);
        requireExact(taskId, 100);
        requireNoQuery(request);
        List<AgentTaskFormalDeliveryViewDTO> rows = readService.listForTaskOwner(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), taskId);
        if (rows == null || rows.size() > MAX_ITEMS) {
            throw new IllegalStateException("Formal delivery result is inconsistent");
        }
        List<FormalDeliveryResponse> items = new ArrayList<>(rows.size());
        long previousRevision = Long.MAX_VALUE;
        for (AgentTaskFormalDeliveryViewDTO row : rows) {
            FormalDeliveryResponse response = response(row, taskId, previousRevision);
            previousRevision = response.revision();
            items.add(response);
        }
        return ok(new FormalDeliveryListResponse(List.copyOf(items)));
    }

    @PostMapping(value = "/{taskId}/formal-deliveries", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FormalDeliveryResponse> submit(@PathVariable String taskId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request, Authentication authentication) {
        Scope scope = requireJwtScope(authentication);
        requireExact(taskId, 100);
        requireNoQuery(request);
        String key = requireIdempotencyKey(idempotencyKey);
        String deliveryId = deliveryId(scope, taskId, key);
        AgentTaskFormalDeliverySubmitDTO command = parseSubmit(readBounded(request), deliveryId);
        AgentTaskFormalDeliveryViewDTO view = submissionService.submit(scope.tenantId(),
                scope.clientId(), scope.ownerJiacn(), taskId, scope.actorId(), command);
        return ok(response(view, taskId, Long.MAX_VALUE));
    }

    @PostMapping(value = "/{taskId}/formal-deliveries/{deliveryId}/decision",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FormalDeliveryResponse> decide(@PathVariable String taskId,
            @PathVariable String deliveryId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request, Authentication authentication) {
        Scope scope = requireJwtScope(authentication);
        requireExact(taskId, 100);
        requireExact(deliveryId, 100);
        requireNoQuery(request);
        requireIdempotencyKey(idempotencyKey);
        AgentTaskFormalDeliveryDecisionDTO command = parseDecision(readBounded(request), deliveryId);
        AgentTaskFormalDeliveryViewDTO view = decisionService.decide(scope.tenantId(),
                scope.clientId(), taskId, scope.ownerJiacn(), command);
        return ok(response(view, taskId, Long.MAX_VALUE));
    }

    @PostMapping(value = "/{taskId}/formal-deliveries/{deliveryId}/rework-executions",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersonalWorkspaceExecutionService.ExecutionView> createReworkExecution(
            @PathVariable String taskId, @PathVariable String deliveryId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request, Authentication authentication) {
        Scope scope = requireJwtScope(authentication);
        requireExact(taskId, 100); requireExact(deliveryId, 100); requireNoQuery(request);
        String key = requireIdempotencyKey(idempotencyKey);
        AgentTaskReworkExecutionService.ReworkCommand command = parseRework(
                readBounded(request), taskId, deliveryId);
        PersonalWorkspaceExecutionService.ExecutionView view = reworkService.create(
                new PersonalWorkspaceExecutionService.OwnerScope(
                        scope.tenantId(), scope.clientId(), scope.ownerJiacn()), command, key);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .contentType(MediaType.APPLICATION_JSON).body(view);
    }

    @ExceptionHandler(AuthenticationFailure.class)
    public ResponseEntity<ErrorBody> authentication(AuthenticationFailure failure) {
        return error(failure.forbidden ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED,
                failure.forbidden ? "FORMAL_DELIVERY_FORBIDDEN" : "FORMAL_DELIVERY_UNAUTHENTICATED",
                "Formal delivery access is unavailable");
    }

    @ExceptionHandler(RequestFailure.class)
    public ResponseEntity<ErrorBody> badRequest(RequestFailure ignored) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Invalid formal delivery request");
    }

    @ExceptionHandler(PersonalWorkspaceExecutionService.Failure.class)
    public ResponseEntity<ErrorBody> reworkFailure(PersonalWorkspaceExecutionService.Failure failure) {
        return switch (failure.getReason()) {
            case BAD_REQUEST -> error(HttpStatus.BAD_REQUEST,
                    "BAD_REQUEST", "Invalid formal delivery rework request");
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND,
                    "FORMAL_DELIVERY_NOT_FOUND", "Formal delivery rework source is unavailable");
            case IDEMPOTENCY_CONFLICT -> error(HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONFLICT", "Operation key conflicts with a different rework request");
            case TASK_CONFLICT, GRANT_CHANGED, GRANT_REVOKED -> error(HttpStatus.CONFLICT,
                    "FORMAL_DELIVERY_CONFLICT", "Formal delivery changed; refresh before trying again");
            case CAPABILITY_UNAVAILABLE -> error(HttpStatus.UNPROCESSABLE_ENTITY,
                    "CAPABILITY_UNAVAILABLE", "Execution capability is not available");
            case OUTPUT_CONFLICT, OUTPUT_MISSING, STORAGE_UNAVAILABLE -> unavailable();
        };
    }

    @ExceptionHandler(AgentTaskCollaborationException.class)
    public ResponseEntity<ErrorBody> collaborationFailure(AgentTaskCollaborationException failure) {
        return switch (failure.getReason()) {
            case INVALID_REQUEST -> error(HttpStatus.BAD_REQUEST,
                    "BAD_REQUEST", "Invalid formal delivery request");
            case NOT_FOUND, FORBIDDEN -> error(HttpStatus.NOT_FOUND,
                    "FORMAL_DELIVERY_NOT_FOUND", "Formal delivery is unavailable");
            case VERSION_CONFLICT, INVALID_TRANSITION -> error(HttpStatus.CONFLICT,
                    "FORMAL_DELIVERY_CONFLICT", "Formal delivery changed; refresh before trying again");
            case INVALID_PERSISTED_STATE, RESERVED_FOR_LEASE_PROTOCOL -> unavailable();
        };
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> unexpected(Exception ignored, HttpServletRequest request) {
        log.error("Formal delivery HTTP request failed: uri={}", request.getRequestURI());
        return unavailable();
    }

    private static Scope requireJwtScope(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication instanceof JwtAuthenticationToken jwt)) {
            throw new AuthenticationFailure(false);
        }
        Map<String, Object> claims = jwt.getToken().getClaims();
        String ownerJiacn = requiredClaim(claims, "jiacn", 50);
        String clientId = requiredClaim(claims, "client_id", 50);
        String actorId = requiredClaim(claims, "sub", 100);
        if ("0".equals(ownerJiacn) || "0".equals(clientId)
                || !byteExact(authentication.getName(), actorId)) {
            throw new AuthenticationFailure(true);
        }
        return new Scope("0", clientId, ownerJiacn, actorId);
    }

    private static String requiredClaim(Map<String, Object> claims, String field, int maxLength) {
        Object value = claims.get(field);
        if (!(value instanceof String text) || !exact(text, maxLength)) {
            throw new AuthenticationFailure(true);
        }
        return text;
    }

    private static String deliveryId(Scope scope, String taskId, String idempotencyKey) {
        String source = "formal-delivery-r2\n" + scope.tenantId().length() + ':' + scope.tenantId()
                + '\n' + scope.clientId().length() + ':' + scope.clientId()
                + '\n' + taskId.length() + ':' + taskId
                + '\n' + idempotencyKey.length() + ':' + idempotencyKey;
        return "fd_" + sha256(source.getBytes(StandardCharsets.UTF_8));
    }

    private static AgentTaskFormalDeliverySubmitDTO parseSubmit(byte[] body, String deliveryId) {
        JsonNode root = parseObject(body, SUBMIT_FIELDS);
        AgentTaskFormalDeliverySubmitDTO command = new AgentTaskFormalDeliverySubmitDTO();
        command.setDeliveryId(deliveryId);
        command.setRunId(text(root, "runId", 100));
        command.setWorkItemId(text(root, "workItemId", 100));
        command.setLeaseToken(text(root, "leaseToken", 100));
        command.setExpectedTaskVersion(version(root, "expectedTaskVersion"));
        command.setExpectedWorkItemVersion(version(root, "expectedWorkItemVersion"));
        command.setSummary(text(root, "summary", 4_000));
        command.setManifestArtifactId(text(root, "manifestArtifactId", 100));
        command.setManifestArtifactVersion(positiveInt(root, "manifestArtifactVersion"));
        JsonNode sourceItems = root.get("items");
        if (!sourceItems.isArray() || sourceItems.isEmpty() || sourceItems.size() > MAX_ITEMS) {
            throw new RequestFailure();
        }
        List<AgentTaskFormalDeliveryItemDTO> items = new ArrayList<>(sourceItems.size());
        Set<ArtifactKey> exactItems = new HashSet<>();
        for (JsonNode source : sourceItems) {
            if (!exactObject(source, ITEM_FIELDS)) throw new RequestFailure();
            AgentTaskFormalDeliveryItemDTO item = new AgentTaskFormalDeliveryItemDTO();
            item.setArtifactId(text(source, "artifactId", 100));
            item.setArtifactVersion(positiveInt(source, "artifactVersion"));
            item.setContentHash(sha256Text(source, "contentHash"));
            item.setPurpose(text(source, "purpose", 255));
            if (!exactItems.add(new ArtifactKey(item.getArtifactId(), item.getArtifactVersion()))) {
                throw new RequestFailure();
            }
            items.add(item);
        }
        command.setItems(List.copyOf(items));
        return command;
    }

    private static AgentTaskFormalDeliveryDecisionDTO parseDecision(byte[] body, String deliveryId) {
        JsonNode root = parseDecisionObject(body);
        AgentTaskFormalDeliveryDecisionDTO command = new AgentTaskFormalDeliveryDecisionDTO();
        command.setDeliveryId(deliveryId);
        command.setExpectedTaskVersion(version(root, "expectedTaskVersion"));
        command.setExpectedDeliveryVersion(version(root, "expectedDeliveryVersion"));
        String decision = text(root, "decision", 32);
        if (!"accepted".equals(decision) && !"changes_requested".equals(decision)) {
            throw new RequestFailure();
        }
        command.setDecision(decision);
        JsonNode reason = root.get("reviewReason");
        if ("accepted".equals(decision)) {
            if (reason != null && !reason.isNull()) throw new RequestFailure();
        } else {
            if (reason == null || !reason.isTextual() || !exact(reason.textValue(), 4_000)) {
                throw new RequestFailure();
            }
            command.setReviewReason(reason.textValue());
        }
        return command;
    }

    private static AgentTaskReworkExecutionService.ReworkCommand parseRework(
            byte[] body, String taskId, String deliveryId) {
        JsonNode root = parseObject(body, REWORK_FIELDS);
        long expectedDecisionVersion = version(root, "expectedDecisionVersion");
        if (expectedDecisionVersion < 1) throw new RequestFailure();
        return new AgentTaskReworkExecutionService.ReworkCommand(taskId, deliveryId,
                expectedDecisionVersion, text(root, "conversationId", 100),
                text(root, "targetAgentId", 100), text(root, "sourceOutputId", 100),
                text(root, "sourceFileId", 100), positiveInt(root, "sourceFileVersion"),
                text(root, "instruction", 4_000), text(root, "outputContentMimeType", 127));
    }

    private static JsonNode parseDecisionObject(byte[] body) {
        if (body == null || body.length == 0 || body.length > MAX_BODY_BYTES) {
            throw new RequestFailure();
        }
        try {
            JsonNode root = STRICT_JSON.readTree(body);
            Set<String> required = Set.of("expectedTaskVersion", "expectedDeliveryVersion", "decision");
            if (root == null || !root.isObject() || root.size() < required.size()
                    || root.size() > DECISION_FIELDS.size()) {
                throw new RequestFailure();
            }
            for (String field : root.propertyNames()) {
                if (!DECISION_FIELDS.contains(field)) throw new RequestFailure();
            }
            for (String field : required) {
                if (!root.has(field)) throw new RequestFailure();
            }
            return root;
        } catch (RequestFailure failure) {
            throw failure;
        } catch (Exception malformed) {
            throw new RequestFailure();
        }
    }

    private static JsonNode parseObject(byte[] body, Set<String> fields) {
        if (body == null || body.length == 0 || body.length > MAX_BODY_BYTES) {
            throw new RequestFailure();
        }
        try {
            JsonNode root = STRICT_JSON.readTree(body);
            if (!exactObject(root, fields)) throw new RequestFailure();
            return root;
        } catch (RequestFailure failure) {
            throw failure;
        } catch (Exception malformed) {
            throw new RequestFailure();
        }
    }

    private static boolean exactObject(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject() || node.size() != fields.size()) return false;
        for (String field : fields) {
            if (!node.has(field)) return false;
        }
        return true;
    }

    private static String text(JsonNode root, String field, int maxLength) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || !exact(value.textValue(), maxLength)) {
            throw new RequestFailure();
        }
        return value.textValue();
    }

    private static String sha256Text(JsonNode root, String field) {
        String value = text(root, field, 64);
        if (!value.matches("[0-9a-f]{64}")) throw new RequestFailure();
        return value;
    }

    private static long version(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < 0 || value.longValue() == Long.MAX_VALUE) {
            throw new RequestFailure();
        }
        return value.longValue();
    }

    private static int positiveInt(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < 1) {
            throw new RequestFailure();
        }
        return value.intValue();
    }

    private static FormalDeliveryResponse response(AgentTaskFormalDeliveryViewDTO view,
            String expectedTaskId, long previousRevision) {
        if (view == null || !expectedTaskId.equals(view.getTaskId())
                || !exact(view.getWorkItemId(), 100) || !exact(view.getDeliveryId(), 100)
                || view.getRevision() == null || view.getRevision() < 1
                || view.getDeliveryVersion() == null || view.getDeliveryVersion() < 0
                || view.getRevision() >= previousRevision || !validState(view.getState())
                || !exact(view.getRunId(), 100) || !exact(view.getProducerAgentId(), 100)
                || !exact(view.getSummary(), 4_000) || !exact(view.getManifestArtifactId(), 100)
                || view.getManifestArtifactVersion() == null || view.getManifestArtifactVersion() < 1
                || view.getSubmittedAt() == null || view.getSubmittedAt() <= 0
                || view.getTaskVersion() == null || view.getTaskVersion() < 0
                || view.getWorkItemVersion() == null || view.getWorkItemVersion() < 0
                || view.getItems() == null || view.getItems().isEmpty() || view.getItems().size() > MAX_ITEMS) {
            throw new IllegalStateException("Formal delivery response is inconsistent");
        }
        boolean submitted = "submitted".equals(view.getState());
        boolean accepted = "accepted".equals(view.getState());
        if (submitted && (view.getDeliveryVersion() != 0
                        || view.getReviewedAt() != null || view.getReviewReason() != null)
                || accepted && (view.getDeliveryVersion() < 1
                        || view.getReviewedAt() == null || view.getReviewedAt() <= 0
                        || view.getReviewReason() != null)
                || "changes_requested".equals(view.getState())
                        && (view.getDeliveryVersion() < 1
                                || view.getReviewedAt() == null || view.getReviewedAt() <= 0
                                || !exact(view.getReviewReason(), 4_000))) {
            throw new IllegalStateException("Formal delivery review is inconsistent");
        }
        List<FormalDeliveryItemResponse> items = new ArrayList<>(view.getItems().size());
        Set<ArtifactKey> unique = new HashSet<>();
        for (AgentTaskFormalDeliveryItemDTO item : view.getItems()) {
            if (item == null || !exact(item.getArtifactId(), 100)
                    || item.getArtifactVersion() == null || item.getArtifactVersion() < 1
                    || item.getContentHash() == null || !item.getContentHash().matches("[0-9a-f]{64}")
                    || !exact(item.getPurpose(), 255)
                    || !unique.add(new ArtifactKey(item.getArtifactId(), item.getArtifactVersion()))) {
                throw new IllegalStateException("Formal delivery item response is inconsistent");
            }
            items.add(new FormalDeliveryItemResponse(item.getArtifactId(), item.getArtifactVersion(),
                    item.getContentHash(), item.getPurpose()));
        }
        return new FormalDeliveryResponse(view.getTaskId(), view.getWorkItemId(), view.getDeliveryId(),
                view.getRevision(), view.getDeliveryVersion(), view.getState(), view.getRunId(),
                view.getProducerAgentId(),
                view.getSummary(), view.getManifestArtifactId(), view.getManifestArtifactVersion(),
                view.getSubmittedAt(), view.getReviewedAt(), view.getReviewReason(), view.getTaskVersion(),
                view.getWorkItemVersion(), List.copyOf(items));
    }

    private static boolean validState(String state) {
        return "submitted".equals(state) || "accepted".equals(state)
                || "changes_requested".equals(state);
    }

    private static byte[] readBounded(HttpServletRequest request) {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength == 0 || declaredLength > MAX_BODY_BYTES) throw new RequestFailure();
        int initial = declaredLength > 0 ? (int) Math.min(declaredLength, BODY_BUFFER_BYTES)
                : BODY_BUFFER_BYTES;
        try (ByteArrayOutputStream result = new ByteArrayOutputStream(initial)) {
            byte[] buffer = new byte[BODY_BUFFER_BYTES];
            int total = 0;
            int count;
            while ((count = request.getInputStream().read(buffer)) != -1) {
                if (count == 0) continue;
                if (count > MAX_BODY_BYTES - total) throw new RequestFailure();
                result.write(buffer, 0, count);
                total += count;
            }
            if (total == 0) throw new RequestFailure();
            return result.toByteArray();
        } catch (RequestFailure failure) {
            throw failure;
        } catch (IOException failure) {
            throw new RequestFailure();
        }
    }

    private static void requireNoQuery(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw new RequestFailure();
    }

    private static String requireIdempotencyKey(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._~:/+\\-]{8,100}")) {
            throw new RequestFailure();
        }
        return value;
    }

    private static void requireExact(String value, int maxLength) {
        if (!exact(value, maxLength)) throw new RequestFailure();
    }

    private static boolean exact(String value, int maxLength) {
        return value != null && !value.isEmpty() && !hasUnpairedSurrogate(value)
                && value.codePointCount(0, value.length()) <= maxLength
                && !isPadding(value.codePointAt(0)) && !isPadding(value.codePointBefore(value.length()))
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) return true;
            } else if (Character.isLowSurrogate(unit)) return true;
        }
        return false;
    }

    private static boolean isPadding(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static boolean byteExact(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] input) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static ResponseEntity<ErrorBody> unavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "FORMAL_DELIVERY_UNAVAILABLE",
                "Formal delivery is temporarily unavailable");
    }

    private static ResponseEntity<ErrorBody> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                .body(new ErrorBody(code, message));
    }

    private record Scope(String tenantId, String clientId, String ownerJiacn, String actorId) { }
    private record ArtifactKey(String artifactId, int artifactVersion) { }
    public record FormalDeliveryListResponse(List<FormalDeliveryResponse> items) { }
    /**
     * {@code deliveryVersion} is the exact formal-decision CAS value. After a
     * {@code changes_requested} decision, send this value unchanged as
     * {@code expectedDecisionVersion} when creating a rework execution.
     */
    public record FormalDeliveryResponse(String taskId, String workItemId, String deliveryId,
            long revision, long deliveryVersion, String state, String runId,
            String producerAgentId, String summary,
            String manifestArtifactId, int manifestArtifactVersion, long submittedAt,
            Long reviewedAt, String reviewReason, long taskVersion, long workItemVersion,
            List<FormalDeliveryItemResponse> items) { }
    public record FormalDeliveryItemResponse(String artifactId, int artifactVersion,
            String contentHash, String purpose) { }
    public record ErrorBody(String code, String message) { }

    private static final class AuthenticationFailure extends RuntimeException {
        private final boolean forbidden;
        private AuthenticationFailure(boolean forbidden) { this.forbidden = forbidden; }
    }
    private static final class RequestFailure extends RuntimeException { }
}
