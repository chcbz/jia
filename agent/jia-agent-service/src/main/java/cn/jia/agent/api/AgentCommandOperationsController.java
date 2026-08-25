package cn.jia.agent.api;

import cn.jia.agent.entity.AgentCommandOperationRequest;
import cn.jia.agent.entity.AgentCommandOperationsException;
import cn.jia.agent.service.AgentCommandOperationsService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Separate privileged D09 API; normal Juyi Hall authorities cannot enter this boundary. */
@Slf4j
@RestController
@RequestMapping("/agent/internal/command-operations")
@ConditionalOnProperty(prefix = "agent.rabbit-operations", name = "read-enabled", havingValue = "true")
public class AgentCommandOperationsController {
    public static final String AUTHORITY_READ = "agent-command-ops-read";
    public static final String AUTHORITY_REDRIVE = "agent-command-ops-redrive";
    public static final String AUTHORITY_REISSUE = "agent-command-ops-reissue";
    static final String APPROVER_CLAIM = "agent_command_ops_approver";
    static final String CACHE_CONTROL = "private, no-store";
    private static final Set<String> OPERATION_BODY_FIELDS = Set.of(
            "taskId", "targetAgentId", "sourceMessageId", "reason", "ticketReference");
    private static final int MAX_OPERATION_BODY_BYTES = 8 * 1024;
    private static final ObjectMapper STRICT_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final AgentCommandOperationsService operationsService;
    private final Clock clock = Clock.systemUTC();

    public AgentCommandOperationsController(
            @Qualifier("agentCommandOperationsService")
            AgentCommandOperationsService operationsService) {
        this.operationsService = Objects.requireNonNull(operationsService, "operationsService");
    }

    @GetMapping(value = "/metrics", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> metrics(Authentication authentication) {
        Scope scope = requireScope(authentication, AUTHORITY_READ, false);
        return ok(operationsService.metrics(scope.tenantId(), scope.clientId(), now()));
    }

    @GetMapping(value = "/dlq", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> dlq(
            @RequestParam(defaultValue = "0") String afterDeliveryId,
            @RequestParam(defaultValue = "50") String limit,
            Authentication authentication) {
        Scope scope = requireScope(authentication, AUTHORITY_READ, false);
        return ok(operationsService.listDlq(scope.tenantId(), scope.clientId(),
                nonNegativeLong(afterDeliveryId), positiveInt(limit)));
    }

    @GetMapping(value = "/audit", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> audit(
            @RequestParam(defaultValue = "0") String afterId,
            @RequestParam(defaultValue = "50") String limit,
            Authentication authentication) {
        Scope scope = requireScope(authentication, AUTHORITY_READ, false);
        return ok(operationsService.listAudit(scope.tenantId(), scope.clientId(),
                nonNegativeLong(afterId), positiveInt(limit)));
    }

    @PostMapping(value = "/dlq/{deliveryId}/redrive",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> redrive(
            @PathVariable String deliveryId,
            @RequestBody byte[] rawBody,
            Authentication authentication) {
        Scope scope = requireScope(authentication, AUTHORITY_REDRIVE, false);
        OperationBody body = requireBody(rawBody);
        return ok(operationsService.brokerRedrive(command(
                scope, positiveLong(deliveryId), body, null), now()));
    }

    @PostMapping(value = "/deliveries/{deliveryId}/reissue",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> reissue(
            @PathVariable String deliveryId,
            @RequestBody byte[] rawBody,
            Authentication authentication) {
        Scope scope = requireScope(authentication, AUTHORITY_REISSUE, true);
        OperationBody body = requireBody(rawBody);
        return ok(operationsService.manualReissue(command(
                scope, positiveLong(deliveryId), body, scope.approverId()), now()));
    }

    @ExceptionHandler(AuthenticationFailure.class)
    public ResponseEntity<ErrorBody> authentication(AuthenticationFailure failure) {
        return error(failure.forbidden ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED,
                failure.forbidden ? "COMMAND_OPS_FORBIDDEN" : "COMMAND_OPS_UNAUTHENTICATED",
                "Command operations access is unavailable");
    }

    @ExceptionHandler(RequestFailure.class)
    public ResponseEntity<ErrorBody> badRequest(RequestFailure ignored) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Invalid command operations request");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorBody> unreadableBody(HttpMessageNotReadableException ignored) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Invalid command operations request");
    }

    @ExceptionHandler(AgentCommandOperationsException.class)
    public ResponseEntity<ErrorBody> operationFailure(AgentCommandOperationsException failure) {
        return switch (failure.reason()) {
            case NOT_FOUND_OR_FORBIDDEN -> error(HttpStatus.NOT_FOUND,
                    "COMMAND_OPERATION_NOT_FOUND", "Command operation source is unavailable");
            case INVALID_REQUEST -> error(HttpStatus.BAD_REQUEST,
                    "BAD_REQUEST", "Invalid command operations request");
            case SOURCE_FORBIDDEN, OPERATION_CONFLICT -> error(HttpStatus.CONFLICT,
                    "COMMAND_OPERATION_REJECTED", "Command operation was rejected");
            case OPERATION_DISABLED -> error(HttpStatus.SERVICE_UNAVAILABLE,
                    "COMMAND_OPERATIONS_DISABLED", "Command operations are unavailable");
            case AUDIT_UNAVAILABLE, PUBLISH_FAILED -> error(HttpStatus.SERVICE_UNAVAILABLE,
                    "COMMAND_OPERATION_FAILED", "Command operation did not complete");
        };
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> unexpected(Exception ignored, HttpServletRequest request) {
        log.error("Privileged command operation failed: uri={}", request.getRequestURI());
        return error(HttpStatus.SERVICE_UNAVAILABLE,
                "COMMAND_OPERATION_FAILED", "Command operation did not complete");
    }

    private Scope requireScope(
            Authentication authentication, String requiredAuthority, boolean approverRequired) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication instanceof JwtAuthenticationToken jwt)) {
            throw new AuthenticationFailure(false);
        }
        if (authentication.getAuthorities() == null || authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .noneMatch(requiredAuthority::equals)) {
            throw new AuthenticationFailure(true);
        }
        Map<String, Object> claims = jwt.getToken().getClaims();
        String tenantId = requiredClaim(claims, "jiacn", 50);
        String clientId = requiredClaim(claims, "client_id", 50);
        String requesterId = requiredClaim(claims, "sub", 100);
        if (!Objects.equals(authentication.getName(), requesterId)) {
            throw new AuthenticationFailure(true);
        }
        String approverId = null;
        if (approverRequired) {
            approverId = requiredClaim(claims, APPROVER_CLAIM, 100);
            if (requesterId.equals(approverId)) throw new AuthenticationFailure(true);
        }
        return new Scope(tenantId, clientId, requesterId, approverId);
    }

    private String requiredClaim(Map<String, Object> claims, String name, int maxLength) {
        Object value = claims.get(name);
        if (!(value instanceof String text) || !exact(text, maxLength)) {
            throw new AuthenticationFailure(true);
        }
        return text;
    }

    private AgentCommandOperationRequest command(
            Scope scope, long deliveryId, OperationBody body, String approverId) {
        return new AgentCommandOperationRequest(
                scope.tenantId(), scope.clientId(), deliveryId, body.taskId(),
                body.targetAgentId(), body.sourceMessageId(), scope.requesterId(),
                approverId, body.reason(), body.ticketReference());
    }

    private OperationBody requireBody(byte[] raw) {
        if (raw == null || raw.length == 0 || raw.length > MAX_OPERATION_BODY_BYTES) {
            throw new RequestFailure();
        }
        JsonNode root;
        try {
            root = STRICT_JSON.readTree(raw);
        } catch (Exception malformed) {
            throw new RequestFailure();
        }
        if (root == null || !root.isObject() || root.size() != OPERATION_BODY_FIELDS.size()
                || OPERATION_BODY_FIELDS.stream().anyMatch(name ->
                    !root.has(name) || !root.get(name).isTextual())) {
            throw new RequestFailure();
        }
        OperationBody body = new OperationBody(
                root.get("taskId").textValue(), root.get("targetAgentId").textValue(),
                root.get("sourceMessageId").textValue(), root.get("reason").textValue(),
                root.get("ticketReference").textValue());
        if (!exact(body.taskId(), 100) || !exact(body.targetAgentId(), 100)
                || !exact(body.sourceMessageId(), 100) || !exact(body.reason(), 1000)
                || !exact(body.ticketReference(), 200)) {
            throw new RequestFailure();
        }
        return body;
    }

    private boolean exact(String value, int maxLength) {
        return value != null && !value.isEmpty()
                && !hasUnpairedSurrogate(value)
                && value.codePointCount(0, value.length()) <= maxLength
                && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) return true;
            } else if (Character.isLowSurrogate(unit)) {
                return true;
            }
        }
        return false;
    }

    private long positiveLong(String value) {
        long parsed = nonNegativeLong(value);
        if (parsed == 0) throw new RequestFailure();
        return parsed;
    }

    private long nonNegativeLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0 || !Long.toString(parsed).equals(value)) throw new RequestFailure();
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new RequestFailure();
        }
    }

    private int positiveInt(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0 || !Integer.toString(parsed).equals(value)) throw new RequestFailure();
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new RequestFailure();
        }
    }

    private long now() {
        long now = clock.millis();
        if (now <= 0) throw new IllegalStateException("clock unavailable");
        return now;
    }

    private ResponseEntity<?> ok(Object body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(body);
    }

    private ResponseEntity<ErrorBody> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .contentType(MediaType.APPLICATION_JSON).body(new ErrorBody(code, message));
    }

    public record OperationBody(
            String taskId,
            String targetAgentId,
            String sourceMessageId,
            String reason,
            String ticketReference) {
    }

    public record ErrorBody(String code, String message) {
    }

    private record Scope(String tenantId, String clientId, String requesterId, String approverId) {
    }

    private static final class AuthenticationFailure extends RuntimeException {
        private final boolean forbidden;
        private AuthenticationFailure(boolean forbidden) { this.forbidden = forbidden; }
    }

    private static final class RequestFailure extends RuntimeException {
    }
}
