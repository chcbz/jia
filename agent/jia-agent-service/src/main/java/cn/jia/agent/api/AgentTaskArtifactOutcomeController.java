package cn.jia.agent.api;

import cn.jia.agent.config.AgentTaskEventsGate;
import cn.jia.agent.entity.AgentTaskArtifactAcceptDTO;
import cn.jia.agent.entity.AgentTaskArtifactOutcomeViewDTO;
import cn.jia.agent.entity.AgentTaskArtifactRefDTO;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.service.AgentTaskArtifactOutcomeService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
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
 * F06 authenticated HTTP catalog.
 *
 * <p>POST /agent/tasks/{taskId}/artifact-outcomes/accept accepts the exact closed body
 * {@code {acceptedArtifact,supersededArtifacts}} and requires {@code Idempotency-Key}.
 * GET /agent/tasks/{taskId}/artifact-outcomes/accepted accepts only optional
 * {@code workItemId} and {@code limit} query keys. JWT {@code jiacn}, {@code client_id}, and
 * {@code sub} are the sole tenant/client/actor authority; request data cannot override them.
 * The existing outcome service remains the only ACL, lock, transaction, idempotency, and
 * optimistic-version boundary.</p>
 */
@Slf4j
@RestController
@RequestMapping("/agent/tasks")
public class AgentTaskArtifactOutcomeController {
    static final String CACHE_CONTROL = "private, no-store";
    static final int MAX_BODY_BYTES = 64 * 1024;
    private static final int BODY_BUFFER_BYTES = 8192;
    static final int MAX_SUPERSEDED_ARTIFACTS = 100;
    static final int MAX_LIST_LIMIT = 100;

    private static final Set<String> ACCEPT_BODY_FIELDS = Set.of(
            "acceptedArtifact", "supersededArtifacts");
    private static final Set<String> ARTIFACT_REF_FIELDS = Set.of(
            "artifactId", "artifactVersion", "expectedOutcomeVersion");
    private static final Set<String> LIST_QUERY_FIELDS = Set.of("workItemId", "limit");
    private static final Set<String> ARTIFACT_TYPES = Set.of(
            "summary", "document", "patch", "commit", "test_report",
            "analysis", "dataset", "link");
    private static final Set<String> VISIBILITIES = Set.of(
            "task_members", "reviewer", "private");
    private static final ObjectMapper STRICT_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final AgentTaskArtifactOutcomeService outcomeService;
    private final AgentTaskEventsGate taskEventsGate;

    public AgentTaskArtifactOutcomeController(
            AgentTaskArtifactOutcomeService outcomeService,
            AgentTaskEventsGate taskEventsGate) {
        this.outcomeService = Objects.requireNonNull(outcomeService, "outcomeService");
        this.taskEventsGate = Objects.requireNonNull(taskEventsGate, "taskEventsGate");
    }

    @PostMapping(value = "/{taskId}/artifact-outcomes/accept",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AgentTaskArtifactOutcomeResponse> accept(
            @PathVariable String taskId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request,
            Authentication authentication) {
        Scope scope = requireJwtScope(authentication);
        requireExact(taskId, 100);
        requireNoQuery(request);
        String decisionId = requireIdempotencyKey(idempotencyKey);
        AgentTaskArtifactAcceptDTO command = parseAccept(readBounded(request), decisionId);
        requireEnabled(scope);

        AgentTaskArtifactOutcomeViewDTO accepted = outcomeService.accept(
                scope.tenantId(), scope.clientId(), taskId, scope.actorAgentId(), command);
        AgentTaskArtifactRefDTO expected = command.getAcceptedArtifact();
        return ok(requireAcceptedResponse(accepted, taskId, null,
                new ExpectedArtifact(expected.getArtifactId(), expected.getArtifactVersion(),
                        expected.getExpectedOutcomeVersion() + 1),
                decisionId, scope.actorAgentId()));
    }

    @GetMapping(value = "/{taskId}/artifact-outcomes/accepted",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<AgentTaskArtifactOutcomeResponse>> accepted(
            @PathVariable String taskId,
            HttpServletRequest request,
            Authentication authentication) {
        Scope scope = requireJwtScope(authentication);
        requireExact(taskId, 100);
        ListQuery query = requireListQuery(request);
        requireEnabled(scope);

        List<AgentTaskArtifactOutcomeViewDTO> rows = outcomeService.listAuthoritativeAccepted(
                scope.tenantId(), scope.clientId(), taskId, scope.actorAgentId(),
                query.workItemId(), query.limit());
        if (rows == null || rows.size() > (query.limit() == null ? MAX_LIST_LIMIT : query.limit())) {
            throw new IllegalStateException("Accepted outcome result is inconsistent");
        }
        List<AgentTaskArtifactOutcomeResponse> response = new ArrayList<>(rows.size());
        Set<ArtifactKey> unique = new HashSet<>();
        for (AgentTaskArtifactOutcomeViewDTO row : rows) {
            AgentTaskArtifactOutcomeResponse item = requireAcceptedResponse(
                    row, taskId, query.workItemId(), null, null, null);
            if (!unique.add(new ArtifactKey(item.artifactId(), item.artifactVersion()))) {
                throw new IllegalStateException("Accepted outcome result is inconsistent");
            }
            response.add(item);
        }
        return ok(List.copyOf(response));
    }

    @ExceptionHandler(AuthenticationFailure.class)
    public ResponseEntity<ErrorBody> authentication(AuthenticationFailure failure) {
        return error(failure.forbidden ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED,
                failure.forbidden
                        ? "ARTIFACT_OUTCOME_FORBIDDEN"
                        : "ARTIFACT_OUTCOME_UNAUTHENTICATED",
                "Artifact outcome access is unavailable");
    }

    @ExceptionHandler(RequestFailure.class)
    public ResponseEntity<ErrorBody> badRequest(RequestFailure ignored) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Invalid artifact outcome request");
    }

    @ExceptionHandler(FeatureUnavailable.class)
    public ResponseEntity<ErrorBody> featureUnavailable(FeatureUnavailable ignored) {
        return unavailable();
    }

    @ExceptionHandler(AgentTaskCollaborationException.class)
    public ResponseEntity<ErrorBody> collaborationFailure(
            AgentTaskCollaborationException failure) {
        return switch (failure.getReason()) {
            case INVALID_REQUEST -> error(HttpStatus.BAD_REQUEST,
                    "BAD_REQUEST", "Invalid artifact outcome request");
            case NOT_FOUND, FORBIDDEN -> error(HttpStatus.NOT_FOUND,
                    "ARTIFACT_OUTCOME_NOT_FOUND", "Artifact outcome is unavailable");
            case VERSION_CONFLICT -> error(HttpStatus.CONFLICT,
                    "ARTIFACT_OUTCOME_VERSION_CONFLICT", "Artifact outcome version changed");
            case INVALID_TRANSITION -> error(HttpStatus.CONFLICT,
                    "ARTIFACT_OUTCOME_CONFLICT", "Artifact outcome decision was rejected");
            case INVALID_PERSISTED_STATE, RESERVED_FOR_LEASE_PROTOCOL -> unavailable();
            default -> unavailable();
        };
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> unexpected(Exception ignored) {
        log.error("Artifact outcome HTTP request failed");
        return unavailable();
    }

    private static Scope requireJwtScope(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication instanceof JwtAuthenticationToken jwt)) {
            throw new AuthenticationFailure(false);
        }
        Map<String, Object> claims = jwt.getToken().getClaims();
        String tenantId = requiredClaim(claims, "jiacn", 50);
        String clientId = requiredClaim(claims, "client_id", 50);
        String actorAgentId = requiredClaim(claims, "sub", 100);
        if (!byteExact(authentication.getName(), actorAgentId)) {
            throw new AuthenticationFailure(true);
        }
        return new Scope(tenantId, clientId, actorAgentId);
    }

    private static String requiredClaim(
            Map<String, Object> claims, String name, int maxLength) {
        Object value = claims.get(name);
        if (!(value instanceof String text) || !exact(text, maxLength)) {
            throw new AuthenticationFailure(true);
        }
        return text;
    }

    private void requireEnabled(Scope scope) {
        if (!taskEventsGate.allows(scope.tenantId(), scope.clientId())) {
            throw new FeatureUnavailable();
        }
    }

    private static void requireNoQuery(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) {
            throw new RequestFailure();
        }
    }

    private static ListQuery requireListQuery(HttpServletRequest request) {
        if (!LIST_QUERY_FIELDS.containsAll(request.getParameterMap().keySet())) {
            throw new RequestFailure();
        }
        String workItemId = singleOptionalQuery(request, "workItemId");
        if (workItemId != null) {
            requireExact(workItemId, 100);
        }
        String rawLimit = singleOptionalQuery(request, "limit");
        Integer limit = rawLimit == null ? null : positiveLimit(rawLimit);
        return new ListQuery(workItemId, limit);
    }

    private static String singleOptionalQuery(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null) {
            return null;
        }
        if (values.length != 1) {
            throw new RequestFailure();
        }
        return values[0];
    }

    private static int positiveLimit(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,2}")) {
            throw new RequestFailure();
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed > MAX_LIST_LIMIT || !Integer.toString(parsed).equals(value)) {
                throw new RequestFailure();
            }
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new RequestFailure();
        }
    }

    private static String requireIdempotencyKey(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._~:/+\\-]{8,100}")) {
            throw new RequestFailure();
        }
        return value;
    }

    private static byte[] readBounded(HttpServletRequest request) {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength == 0 || declaredLength > MAX_BODY_BYTES) {
            throw new RequestFailure();
        }
        int initialSize = declaredLength > 0
                ? (int) Math.min(declaredLength, BODY_BUFFER_BYTES)
                : BODY_BUFFER_BYTES;
        try (ByteArrayOutputStream result = new ByteArrayOutputStream(initialSize)) {
            byte[] buffer = new byte[BODY_BUFFER_BYTES];
            int total = 0;
            int count;
            while ((count = request.getInputStream().read(buffer)) != -1) {
                if (count == 0) {
                    continue;
                }
                if (count > MAX_BODY_BYTES - total) {
                    throw new RequestFailure();
                }
                result.write(buffer, 0, count);
                total += count;
            }
            if (total == 0) {
                throw new RequestFailure();
            }
            return result.toByteArray();
        } catch (RequestFailure failure) {
            throw failure;
        } catch (IOException failure) {
            throw new RequestFailure();
        }
    }

    private static AgentTaskArtifactAcceptDTO parseAccept(byte[] body, String decisionId) {
        if (body == null || body.length == 0 || body.length > MAX_BODY_BYTES) {
            throw new RequestFailure();
        }
        final JsonNode root;
        try {
            root = STRICT_JSON.readTree(body);
        } catch (Exception malformed) {
            throw new RequestFailure();
        }
        if (!exactObject(root, ACCEPT_BODY_FIELDS)
                || !root.get("supersededArtifacts").isArray()
                || root.get("supersededArtifacts").size() > MAX_SUPERSEDED_ARTIFACTS) {
            throw new RequestFailure();
        }

        AgentTaskArtifactRefDTO accepted = requireArtifactRef(root.get("acceptedArtifact"));
        List<AgentTaskArtifactRefDTO> superseded = new ArrayList<>(
                root.get("supersededArtifacts").size());
        Set<ArtifactKey> unique = new HashSet<>();
        unique.add(new ArtifactKey(accepted.getArtifactId(), accepted.getArtifactVersion()));
        for (JsonNode value : root.get("supersededArtifacts")) {
            AgentTaskArtifactRefDTO ref = requireArtifactRef(value);
            if (!unique.add(new ArtifactKey(ref.getArtifactId(), ref.getArtifactVersion()))) {
                throw new RequestFailure();
            }
            superseded.add(ref);
        }

        AgentTaskArtifactAcceptDTO command = new AgentTaskArtifactAcceptDTO();
        command.setDecisionId(decisionId);
        command.setAcceptedArtifact(accepted);
        command.setSupersededArtifacts(List.copyOf(superseded));
        return command;
    }

    private static AgentTaskArtifactRefDTO requireArtifactRef(JsonNode value) {
        if (!exactObject(value, ARTIFACT_REF_FIELDS)) {
            throw new RequestFailure();
        }
        JsonNode artifactId = value.get("artifactId");
        JsonNode artifactVersion = value.get("artifactVersion");
        JsonNode expectedOutcomeVersion = value.get("expectedOutcomeVersion");
        if (!artifactId.isTextual() || !exact(artifactId.textValue(), 100)
                || !artifactVersion.isIntegralNumber() || !artifactVersion.canConvertToInt()
                || artifactVersion.intValue() < 1
                || !expectedOutcomeVersion.isIntegralNumber()
                || !expectedOutcomeVersion.canConvertToLong()
                || expectedOutcomeVersion.longValue() < 0
                || expectedOutcomeVersion.longValue() == Long.MAX_VALUE) {
            throw new RequestFailure();
        }
        AgentTaskArtifactRefDTO ref = new AgentTaskArtifactRefDTO();
        ref.setArtifactId(artifactId.textValue());
        ref.setArtifactVersion(artifactVersion.intValue());
        ref.setExpectedOutcomeVersion(expectedOutcomeVersion.longValue());
        return ref;
    }

    private static boolean exactObject(JsonNode value, Set<String> fields) {
        if (value == null || !value.isObject() || value.size() != fields.size()) {
            return false;
        }
        for (String field : fields) {
            if (!value.has(field) || value.get(field) == null || value.get(field).isNull()) {
                return false;
            }
        }
        return true;
    }

    private static AgentTaskArtifactOutcomeResponse requireAcceptedResponse(
            AgentTaskArtifactOutcomeViewDTO row,
            String taskId,
            String requestedWorkItemId,
            ExpectedArtifact expectedArtifact,
            String decisionId,
            String decidedByAgentId) {
        if (row == null
                || !Objects.equals(taskId, row.getTaskId())
                || !exact(row.getArtifactId(), 100)
                || expectedArtifact != null
                && (!expectedArtifact.artifactId().equals(row.getArtifactId())
                || row.getArtifactVersion() == null
                || expectedArtifact.artifactVersion() != row.getArtifactVersion()
                || row.getOutcomeVersion() == null
                || expectedArtifact.outcomeVersion() != row.getOutcomeVersion())
                || row.getWorkItemId() != null && !exact(row.getWorkItemId(), 100)
                || requestedWorkItemId != null
                && !requestedWorkItemId.equals(row.getWorkItemId())
                || !exact(row.getProducerAgentId(), 100)
                || !ARTIFACT_TYPES.contains(row.getArtifactType())
                || !validTitle(row.getTitle())
                || row.getContentHash() == null
                || !row.getContentHash().matches("[0-9a-f]{64}")
                || row.getArtifactVersion() == null || row.getArtifactVersion() < 1
                || !VISIBILITIES.contains(row.getVisibility())
                || row.getCreatedAt() == null || row.getCreatedAt() <= 0
                || !"accepted".equals(row.getOutcomeState())
                || row.getOutcomeVersion() == null || row.getOutcomeVersion() < 1
                || !exact(row.getDecisionId(), 100)
                || decisionId != null && !decisionId.equals(row.getDecisionId())
                || !exact(row.getDecidedByAgentId(), 100)
                || decidedByAgentId != null
                && !decidedByAgentId.equals(row.getDecidedByAgentId())
                || row.getDecidedAt() == null || row.getDecidedAt() <= 0) {
            throw new IllegalStateException("Accepted outcome result is inconsistent");
        }
        return new AgentTaskArtifactOutcomeResponse(
                row.getArtifactId(), row.getTaskId(), row.getWorkItemId(),
                row.getProducerAgentId(), row.getArtifactType(), row.getTitle(),
                row.getContentHash(), row.getArtifactVersion(), row.getVisibility(),
                row.getCreatedAt(), row.getOutcomeState(), row.getOutcomeVersion(),
                row.getDecisionId(), row.getDecidedByAgentId(), row.getDecidedAt());
    }

    private static boolean validTitle(String value) {
        return value != null && !value.isBlank() && !hasUnpairedSurrogate(value)
                && value.codePointCount(0, value.length()) <= 255;
    }

    private static void requireExact(String value, int maxLength) {
        if (!exact(value, maxLength)) {
            throw new RequestFailure();
        }
    }

    private static boolean exact(String value, int maxLength) {
        return value != null && !value.isEmpty() && !hasUnpairedSurrogate(value)
                && value.codePointCount(0, value.length()) <= maxLength
                && !isPadding(value.codePointAt(0))
                && !isPadding(value.codePointBefore(value.length()))
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index))) {
                    return true;
                }
            } else if (Character.isLowSurrogate(unit)) {
                return true;
            }
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

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private static ResponseEntity<ErrorBody> unavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE,
                "ARTIFACT_OUTCOME_UNAVAILABLE",
                "Artifact outcome service is temporarily unavailable");
    }

    private static ResponseEntity<ErrorBody> error(
            HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                .body(new ErrorBody(code, message));
    }

    private record Scope(String tenantId, String clientId, String actorAgentId) {
    }

    private record ListQuery(String workItemId, Integer limit) {
    }

    private record ArtifactKey(String artifactId, int artifactVersion) {
    }

    private record ExpectedArtifact(
            String artifactId, int artifactVersion, long outcomeVersion) {
    }

    public record ErrorBody(String code, String message) {
    }

    private static final class AuthenticationFailure extends RuntimeException {
        private final boolean forbidden;

        private AuthenticationFailure(boolean forbidden) {
            this.forbidden = forbidden;
        }
    }

    private static final class RequestFailure extends RuntimeException {
    }

    private static final class FeatureUnavailable extends RuntimeException {
    }
}
