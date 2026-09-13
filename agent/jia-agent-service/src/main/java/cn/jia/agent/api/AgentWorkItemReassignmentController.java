package cn.jia.agent.api;

import cn.jia.agent.entity.AgentWorkItemReassignmentLeaseDTO;
import cn.jia.agent.entity.AgentWorkItemReassignmentLeaseRequestDTO;
import cn.jia.agent.entity.AgentWorkItemReassignmentRequestDTO;
import cn.jia.agent.entity.AgentWorkItemReassignmentResultDTO;
import cn.jia.agent.exception.AgentWorkItemReassignmentException;
import cn.jia.agent.service.AgentWorkItemReassignmentService;
import cn.jia.core.security.AllowSensitiveOutput;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** E05 authenticated operator mutation and target-only command-bound lease facade. */
@Slf4j
@RestController
@RequestMapping("/agent/tasks")
public class AgentWorkItemReassignmentController {
    static final String REASSIGN_AUTHORITY = "agent-work-item-reassign";
    static final String CACHE_CONTROL = "private, no-store";
    static final int MAX_BODY_BYTES = 32 * 1024;
    private static final ObjectMapper STRICT_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final AgentWorkItemReassignmentService service;

    public AgentWorkItemReassignmentController(AgentWorkItemReassignmentService service) {
        this.service = Objects.requireNonNull(service);
    }

    @PostMapping(value = "/{taskId}/work-items/{workItemId}/reassignments",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AgentWorkItemReassignmentResultDTO> reassign(
            @PathVariable String taskId, @PathVariable String workItemId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request, Authentication authentication) {
        Principal principal = principal(authentication, true);
        String actorAgentId = actor(request);
        requirePath(taskId, workItemId);
        requireIdempotencyKey(idempotencyKey);
        AgentWorkItemReassignmentRequestDTO command = parse(
                request, AgentWorkItemReassignmentRequestDTO.class);
        return ok(service.reassign(principal.tenantId(), principal.clientId(),
                principal.subject(), actorAgentId, taskId, workItemId,
                idempotencyKey, command));
    }

    @AllowSensitiveOutput(reason = "Exact target-only command-bound active lease credential readback")
    @PostMapping(value = "/{taskId}/work-items/{workItemId}/reassignments/{reassignmentId}/lease",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AgentWorkItemReassignmentLeaseDTO> readLease(
            @PathVariable String taskId, @PathVariable String workItemId,
            @PathVariable String reassignmentId,
            HttpServletRequest request, Authentication authentication) {
        Principal principal = principal(authentication, false);
        String actorAgentId = actor(request);
        requirePath(taskId, workItemId, reassignmentId);
        return ok(service.readLease(principal.tenantId(), principal.clientId(), actorAgentId,
                taskId, workItemId, reassignmentId,
                parse(request, AgentWorkItemReassignmentLeaseRequestDTO.class)));
    }

    @AllowSensitiveOutput(reason = "Exact target-only command-bound active lease start response")
    @PostMapping(value = "/{taskId}/work-items/{workItemId}/reassignments/{reassignmentId}/lease/start",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AgentWorkItemReassignmentLeaseDTO> startLease(
            @PathVariable String taskId, @PathVariable String workItemId,
            @PathVariable String reassignmentId,
            HttpServletRequest request, Authentication authentication) {
        Principal principal = principal(authentication, false);
        String actorAgentId = actor(request);
        requirePath(taskId, workItemId, reassignmentId);
        return ok(service.startLease(principal.tenantId(), principal.clientId(), actorAgentId,
                taskId, workItemId, reassignmentId,
                parse(request, AgentWorkItemReassignmentLeaseRequestDTO.class)));
    }

    @AllowSensitiveOutput(reason = "Exact target-only command-bound active lease heartbeat response")
    @PostMapping(value = "/{taskId}/work-items/{workItemId}/reassignments/{reassignmentId}/lease/heartbeat",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AgentWorkItemReassignmentLeaseDTO> heartbeatLease(
            @PathVariable String taskId, @PathVariable String workItemId,
            @PathVariable String reassignmentId,
            HttpServletRequest request, Authentication authentication) {
        Principal principal = principal(authentication, false);
        String actorAgentId = actor(request);
        requirePath(taskId, workItemId, reassignmentId);
        return ok(service.heartbeatLease(principal.tenantId(), principal.clientId(), actorAgentId,
                taskId, workItemId, reassignmentId,
                parse(request, AgentWorkItemReassignmentLeaseRequestDTO.class)));
    }

    @ExceptionHandler(AuthenticationFailure.class)
    public ResponseEntity<ErrorBody> authentication(AuthenticationFailure failure) {
        return error(failure.forbidden ? HttpStatus.FORBIDDEN : HttpStatus.UNAUTHORIZED,
                failure.forbidden ? "WORK_ITEM_REASSIGNMENT_FORBIDDEN"
                        : "WORK_ITEM_REASSIGNMENT_UNAUTHENTICATED",
                "Work item reassignment access is unavailable");
    }

    @ExceptionHandler(RequestFailure.class)
    public ResponseEntity<ErrorBody> badRequest(RequestFailure ignored) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                "Invalid work item reassignment request");
    }

    @ExceptionHandler(AgentWorkItemReassignmentException.class)
    public ResponseEntity<ErrorBody> domain(AgentWorkItemReassignmentException failure) {
        return switch (failure.getReason()) {
            case INVALID_REQUEST -> error(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                    "Invalid work item reassignment request");
            case NOT_FOUND_OR_FORBIDDEN -> error(HttpStatus.NOT_FOUND,
                    "WORK_ITEM_REASSIGNMENT_NOT_FOUND", "Reassignment resource is unavailable");
            case VERSION_CONFLICT -> error(HttpStatus.CONFLICT,
                    "WORK_ITEM_REASSIGNMENT_STALE", "Task or work item state changed");
            case IDEMPOTENCY_CONFLICT -> error(HttpStatus.CONFLICT,
                    "WORK_ITEM_REASSIGNMENT_CONFLICT",
                    "Idempotency key was already used differently");
            case LEASE_NOT_EXPIRED -> error(HttpStatus.CONFLICT,
                    "WORK_ITEM_LEASE_LIVE", "The current lease is still live");
            case DOMAIN_ATTEMPTS_EXHAUSTED -> error(HttpStatus.CONFLICT,
                    "WORK_ITEM_ATTEMPTS_EXHAUSTED", "No reassignment attempt remains");
            case INVALID_SOURCE_COMMAND -> error(HttpStatus.CONFLICT,
                    "WORK_ITEM_SOURCE_COMMAND_INVALID", "Source command is not current");
            case INVALID_PERSISTED_STATE -> error(HttpStatus.SERVICE_UNAVAILABLE,
                    "WORK_ITEM_REASSIGNMENT_UNAVAILABLE",
                    "Work item reassignment is temporarily unavailable");
        };
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> unexpected(Exception ignored, HttpServletRequest request) {
        log.error("Work item reassignment failed: uri={}", request.getRequestURI());
        return error(HttpStatus.SERVICE_UNAVAILABLE,
                "WORK_ITEM_REASSIGNMENT_UNAVAILABLE",
                "Work item reassignment is temporarily unavailable");
    }

    private static Principal principal(Authentication authentication, boolean requireAuthority) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication instanceof JwtAuthenticationToken jwt)) {
            throw new AuthenticationFailure(false);
        }
        Map<String, Object> claims = jwt.getToken().getClaims();
        Object tenant = claims.get("jiacn");
        Object client = claims.get("client_id");
        Object subject = claims.get("sub");
        if (!(tenant instanceof String tenantId) || !(client instanceof String clientId)
                || !(subject instanceof String operatorSubject)
                || !exact(tenantId, 50) || !exact(clientId, 50)
                || !exact(operatorSubject, 100)
                || !byteExact(authentication.getName(), operatorSubject)
                || requireAuthority && jwt.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .noneMatch(REASSIGN_AUTHORITY::equals)) {
            throw new AuthenticationFailure(true);
        }
        return new Principal(tenantId, clientId, operatorSubject);
    }

    private static String actor(HttpServletRequest request) {
        if (!request.getParameterMap().keySet().equals(Set.of("actorAgentId"))) {
            throw new RequestFailure();
        }
        String[] values = request.getParameterValues("actorAgentId");
        if (values == null || values.length != 1 || !exact(values[0], 100)) {
            throw new RequestFailure();
        }
        return values[0];
    }

    private static void requirePath(String... values) {
        for (String value : values) if (!exact(value, 100)) throw new RequestFailure();
    }

    private static void requireIdempotencyKey(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._~:/+\\-]{8,128}")) {
            throw new RequestFailure();
        }
    }

    private static <T> T parse(HttpServletRequest request, Class<T> type) {
        byte[] body = readBounded(request);
        try {
            T value = STRICT_JSON.readValue(body, type);
            if (value == null) throw new RequestFailure();
            return value;
        } catch (RequestFailure known) {
            throw known;
        } catch (Exception malformed) {
            throw new RequestFailure();
        }
    }

    private static byte[] readBounded(HttpServletRequest request) {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength == 0 || declaredLength > MAX_BODY_BYTES) {
            throw new RequestFailure();
        }
        int initial = declaredLength > 0 ? (int) declaredLength : 1024;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(initial)) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int count;
            while ((count = request.getInputStream().read(buffer)) != -1) {
                if (count == 0) continue;
                if (count > MAX_BODY_BYTES - total) throw new RequestFailure();
                output.write(buffer, 0, count);
                total += count;
            }
            if (total == 0) throw new RequestFailure();
            return output.toByteArray();
        } catch (RequestFailure known) {
            throw known;
        } catch (IOException unreadable) {
            throw new RequestFailure();
        }
    }

    private static boolean byteExact(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean exact(String value, int max) {
        return value != null && !value.isEmpty() && !hasUnpairedSurrogate(value)
                && value.equals(value.strip())
                && value.codePointCount(0, value.length()) <= max
                && value.chars().noneMatch(Character::isISOControl);
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index))) return true;
            } else if (Character.isLowSurrogate(unit)) {
                return true;
            }
        }
        return false;
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(body);
    }

    private static ResponseEntity<ErrorBody> error(
            HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                .body(new ErrorBody(code, message));
    }

    private record Principal(String tenantId, String clientId, String subject) { }
    public record ErrorBody(String code, String message) { }
    private static final class RequestFailure extends RuntimeException { }
    private static final class AuthenticationFailure extends RuntimeException {
        private final boolean forbidden;
        private AuthenticationFailure(boolean forbidden) { this.forbidden = forbidden; }
    }
}
