package cn.jia.agent.api;

import cn.jia.agent.service.AgentTaskDeliberationOperationReadService;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.core.entity.JsonResult;
import com.fasterxml.jackson.annotation.JsonInclude;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Owner JWT-only read edge for one original assign-and-start operation. */
@Slf4j
@RestController
@RequestMapping("/agent/tasks")
@ConditionalOnProperty(prefix = "agent.task-deliberation-operation", name = "read-enabled",
        havingValue = "true")
public final class AgentTaskDeliberationOperationController {
    static final String CACHE_CONTROL = "private, no-store";
    private final AgentTaskDeliberationOperationReadService operations;

    public AgentTaskDeliberationOperationController(
            AgentTaskDeliberationOperationReadService operations) {
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    @GetMapping(value = "/{taskId}/assignment-operation",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonResult<AssignmentOperationView>> read(
            @PathVariable String taskId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request, Authentication authentication) {
        AgentTaskExecutionGrantService.Scope scope = requireOwnerJwt(authentication);
        requireExact(taskId, 100);
        requireExact(idempotencyKey, 100);
        List<String> suppliedKeys = Collections.list(request.getHeaders("Idempotency-Key"));
        if (suppliedKeys.size() != 1 || !Objects.equals(idempotencyKey, suppliedKeys.getFirst())
                || !request.getParameterMap().isEmpty()) throw new InvalidRequest();
        AgentTaskDeliberationOperationReadService.Operation operation =
                operations.read(scope, taskId, idempotencyKey);
        AssignmentOperationView body = view(operation, taskId);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .contentType(MediaType.APPLICATION_JSON).body(JsonResult.success(body));
    }

    @ExceptionHandler(AuthenticationFailure.class)
    public ResponseEntity<JsonResult<Void>> authentication(AuthenticationFailure ignored) {
        return error(HttpStatus.UNAUTHORIZED, "ASSIGNMENT_OPERATION_UNAUTHENTICATED",
                "Authentication is required");
    }

    @ExceptionHandler({InvalidRequest.class, IllegalArgumentException.class})
    public ResponseEntity<JsonResult<Void>> badRequest(Exception ignored) {
        return error(HttpStatus.BAD_REQUEST, "ASSIGNMENT_OPERATION_BAD_REQUEST",
                "Invalid assignment operation request");
    }

    @ExceptionHandler(AgentTaskDeliberationOperationReadService.ReadException.class)
    public ResponseEntity<JsonResult<Void>> readFailure(
            AgentTaskDeliberationOperationReadService.ReadException failure) {
        return switch (failure.reason()) {
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "ASSIGNMENT_OPERATION_UNAVAILABLE",
                    "Assignment operation is unavailable in the requested scope");
            case INTEGRITY_ERROR -> {
                log.error("Assignment operation read failed: reason=integrity_error");
                yield error(HttpStatus.INTERNAL_SERVER_ERROR,
                        "ASSIGNMENT_OPERATION_INTEGRITY_ERROR",
                        "Assignment operation integrity validation failed");
            }
            case SOURCE_UNAVAILABLE -> {
                log.error("Assignment operation read failed: reason=source_unavailable type={}",
                        causeType(failure));
                yield unavailable();
            }
        };
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<JsonResult<Void>> unexpected(Exception failure) {
        log.error("Assignment operation read failed: reason=unexpected type={}",
                failure.getClass().getName());
        return unavailable();
    }

    private static AssignmentOperationView view(
            AgentTaskDeliberationOperationReadService.Operation value, String taskId) {
        if (value == null || !Objects.equals(taskId, value.taskId())
                || value.requirementRevision() < 1 || value.assignmentRevision() < 0
                || value.taskVersion() < 0 || value.grantVersion() < 1
                || value.stateVersion() < 0 || value.permittedOperations() == null
                || value.inputs() == null) {
            throw new AgentTaskDeliberationOperationReadService.ReadException(
                    AgentTaskDeliberationOperationReadService.ReadException.Reason.INTEGRITY_ERROR);
        }
        List<InputView> inputs = value.inputs().stream().map(input -> new InputView(
                input.fileId(), input.version(), input.purpose(), input.contentMimeType(),
                Long.toString(input.byteLength()), input.contentHash())).toList();
        return new AssignmentOperationView(1, value.taskId(), value.targetAgentId(),
                Long.toString(value.requirementRevision()),
                Long.toString(value.assignmentRevision()), Long.toString(value.taskVersion()),
                value.grantId(), Long.toString(value.grantVersion()), value.grantState(),
                List.copyOf(value.permittedOperations()), inputs, value.bootstrapId(),
                value.bootstrapState(), Long.toString(value.stateVersion()),
                value.initialOperation(), value.conversationId(), value.initialRequestId(),
                value.currentAssignment());
    }

    private static AgentTaskExecutionGrantService.Scope requireOwnerJwt(
            Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication instanceof JwtAuthenticationToken jwt)) {
            throw new AuthenticationFailure();
        }
        Map<String,Object> claims = jwt.getToken().getClaims();
        Object owner = claims.get("jiacn");
        Object client = claims.get("client_id");
        if (!(owner instanceof String ownerJiacn) || !(client instanceof String clientId)
                || !validExact(ownerJiacn, 50) || "0".equals(ownerJiacn)
                || !validExact(clientId, 50)) {
            throw new AuthenticationFailure();
        }
        return new AgentTaskExecutionGrantService.Scope("0", clientId, ownerJiacn);
    }

    private static void requireExact(String value, int maxCodePoints) {
        if (!validExact(value, maxCodePoints)) throw new InvalidRequest();
    }

    private static boolean validExact(String value, int maxCodePoints) {
        return value != null && !value.isEmpty() && !hasUnpairedSurrogate(value)
                && value.codePointCount(0, value.length()) <= maxCodePoints
                && !isPadding(value.codePointAt(0))
                && !isPadding(value.codePointBefore(value.length()))
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    return true;
                }
            } else if (Character.isLowSurrogate(unit)) return true;
        }
        return false;
    }

    private static boolean isPadding(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static ResponseEntity<JsonResult<Void>> unavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE,
                "ASSIGNMENT_OPERATION_SOURCE_UNAVAILABLE",
                "Assignment operation source is temporarily unavailable");
    }

    private static ResponseEntity<JsonResult<Void>> error(
            HttpStatus status, String code, String message) {
        JsonResult<Void> body = JsonResult.failure(code, message);
        body.setStatus(status.value());
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                .body(body);
    }

    private static String causeType(Throwable failure) {
        Throwable cause = failure.getCause();
        return cause == null ? failure.getClass().getName() : cause.getClass().getName();
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AssignmentOperationView(int schemaVersion, String taskId, String targetAgentId,
            String requirementRevision, String assignmentRevision, String taskVersion,
            String grantId, String grantVersion, String grantState,
            List<String> permittedOperations, List<InputView> inputs, String bootstrapId,
            String bootstrapState, String stateVersion, String initialOperation,
            String conversationId, String initialRequestId, boolean currentAssignment) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record InputView(String fileId, int version, String purpose, String contentMimeType,
            String byteLength, String contentHash) { }

    private static final class AuthenticationFailure extends RuntimeException { }
    private static final class InvalidRequest extends RuntimeException { }
}
