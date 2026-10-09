package cn.jia.agent.api;

import cn.jia.agent.entity.AgentWorkItemResultCommitDTO;
import cn.jia.agent.entity.AgentWorkItemResultCommitViewDTO;
import cn.jia.agent.entity.AgentWorkItemReassignmentLeaseDTO;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.exception.AgentTaskStateException;
import cn.jia.agent.exception.AgentWorkItemReassignmentException;
import cn.jia.agent.security.AgentRuntimeAuthentication;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.AgentWorkItemReassignmentService;
import cn.jia.agent.service.AgentWorkItemResultCommitService;
import cn.jia.core.security.AllowSensitiveOutput;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Objects;

/** E05 internal wiring only: original DTO/result semantics, current installation-derived proof.
 * Servlet/storage I/O is outside all fences. Only short admission/final/readback transactions are fenced. */
@RestController
@RequestMapping("/internal/agent/tasks/{taskId}/work-items/{workItemId}/reassignments/{reassignmentId}/commands/{commandId}")
public class AgentWorkItemRuntimeResultController {
    private static final String CACHE_CONTROL = "private, no-store";
    private static final ObjectMapper STRICT_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final AgentWorkItemResultCommitService results;
    private final AgentWorkItemReassignmentService reassignments;
    private final AgentRuntimeAuthenticationService authentication;

    public AgentWorkItemRuntimeResultController(AgentWorkItemResultCommitService results,
            AgentWorkItemReassignmentService reassignments, AgentRuntimeAuthenticationService authentication) {
        this.results = Objects.requireNonNull(results);
        this.reassignments = Objects.requireNonNull(reassignments);
        this.authentication = Objects.requireNonNull(authentication);
    }

    @AllowSensitiveOutput(reason = "Exact authenticated E05 producer original business result, never installation credentials")
    @PostMapping(value = "/result-commit", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AgentWorkItemResultCommitViewDTO> commit(@PathVariable String taskId,
            @PathVariable String workItemId, @PathVariable String reassignmentId, @PathVariable String commandId,
            HttpServletRequest request, Authentication principal) {
        var scope = scope(principal, request, taskId, workItemId, reassignmentId, commandId);
        var command = parse(request);
        if (!workItemId.equals(command.getWorkItemId()) || !scope.agentId().equals(command.getProducerAgentId())
                || command.getArtifact() != null && !scope.agentId().equals(command.getArtifact().getProducerAgentId()))
            throw new Forbidden();
        var prior = authentication.withNativeFence(principal, () -> results.preflightRuntimeResult(scope.tenantId(),
                scope.clientId(), scope.ownerJiacn(), taskId, workItemId, scope.agentId(), reassignmentId, commandId, command));
        if (prior != null) return ok(prior);
        var prepared = results.prepareRuntimeResult(scope.tenantId(), scope.clientId(), scope.ownerJiacn(), taskId,
                workItemId, scope.agentId(), reassignmentId, commandId, command);
        return ok(authentication.withNativeFence(principal, () -> results.commitPreparedRuntimeResult(scope.tenantId(),
                scope.clientId(), scope.ownerJiacn(), taskId, workItemId, scope.agentId(), reassignmentId, commandId, prepared)));
    }

    @AllowSensitiveOutput(reason = "Exact authenticated E05 producer uniquely proved persisted original result readback")
    @GetMapping(value = "/result-commit", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AgentWorkItemResultCommitViewDTO> readResult(@PathVariable String taskId,
            @PathVariable String workItemId, @PathVariable String reassignmentId, @PathVariable String commandId,
            HttpServletRequest request, Authentication principal) {
        var scope = scope(principal, request, taskId, workItemId, reassignmentId, commandId);
        requireNoBody(request);
        return ok(authentication.withNativeFence(principal, () -> results.readRuntimeResult(scope.tenantId(),
                scope.clientId(), scope.ownerJiacn(), taskId, workItemId, scope.agentId(), reassignmentId, commandId)));
    }

    @AllowSensitiveOutput(reason = "Exact authenticated E05 target original receipt live lease credential and confirmed version readback")
    @GetMapping(value = "/lease", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AgentWorkItemReassignmentLeaseDTO> readLease(@PathVariable String taskId,
            @PathVariable String workItemId, @PathVariable String reassignmentId, @PathVariable String commandId,
            HttpServletRequest request, Authentication principal) {
        var scope = scope(principal, request, taskId, workItemId, reassignmentId, commandId);
        requireNoBody(request);
        return ok(authentication.withNativeFence(principal, () -> reassignments.readCurrentRuntimeLease(scope.tenantId(),
                scope.clientId(), scope.ownerJiacn(), scope.agentId(), taskId, workItemId, reassignmentId, commandId)));
    }

    @ExceptionHandler(Forbidden.class)
    public ResponseEntity<ErrorBody> forbidden(Forbidden ignored) { return error(HttpStatus.FORBIDDEN, "WORK_ITEM_RESULT_FORBIDDEN"); }
    @ExceptionHandler({BadRequest.class, IOException.class})
    public ResponseEntity<ErrorBody> malformed(Exception ignored) { return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST"); }
    @ExceptionHandler(AgentWorkItemResultCommitService.RuntimeFailure.class)
    public ResponseEntity<ErrorBody> recovery(AgentWorkItemResultCommitService.RuntimeFailure failure) {
        return failure.getReason() == AgentWorkItemResultCommitService.RuntimeFailureReason.NOT_FOUND
                ? error(HttpStatus.NOT_FOUND, "WORK_ITEM_RESULT_NOT_FOUND")
                : error(HttpStatus.CONFLICT, "WORK_ITEM_RESULT_RECOVERY_REQUIRED");
    }
    @ExceptionHandler(AgentTaskCollaborationException.class)
    public ResponseEntity<ErrorBody> artifact(AgentTaskCollaborationException failure) {
        return switch (failure.getReason()) {
            case INVALID_REQUEST -> error(HttpStatus.BAD_REQUEST, "BAD_REQUEST");
            case FORBIDDEN -> error(HttpStatus.FORBIDDEN, "WORK_ITEM_RESULT_FORBIDDEN");
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "WORK_ITEM_RESULT_NOT_FOUND");
            case INVALID_TRANSITION, VERSION_CONFLICT, RESERVED_FOR_LEASE_PROTOCOL -> error(HttpStatus.CONFLICT, "WORK_ITEM_RESULT_STALE");
            case INVALID_PERSISTED_STATE -> error(HttpStatus.SERVICE_UNAVAILABLE, "WORK_ITEM_RESULT_UNAVAILABLE");
        };
    }
    @ExceptionHandler(AgentTaskStateException.class)
    public ResponseEntity<ErrorBody> lease(AgentTaskStateException failure) {
        return switch (failure.getReason()) {
            case INVALID_REQUEST -> error(HttpStatus.BAD_REQUEST, "BAD_REQUEST");
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "WORK_ITEM_RESULT_NOT_FOUND");
            case INVALID_PERSISTED_STATE -> error(HttpStatus.SERVICE_UNAVAILABLE, "WORK_ITEM_RESULT_UNAVAILABLE");
            default -> error(HttpStatus.CONFLICT, "WORK_ITEM_RESULT_STALE");
        };
    }
    @ExceptionHandler(AgentWorkItemReassignmentException.class)
    public ResponseEntity<ErrorBody> source(AgentWorkItemReassignmentException failure, HttpServletRequest request) {
        boolean leaseRead = request.getRequestURI().endsWith("/lease");
        return switch (failure.getReason()) {
            case INVALID_REQUEST -> error(HttpStatus.BAD_REQUEST, "BAD_REQUEST");
            case NOT_FOUND_OR_FORBIDDEN -> error(HttpStatus.NOT_FOUND,
                    leaseRead ? "WORK_ITEM_REASSIGNMENT_NOT_FOUND" : "WORK_ITEM_RESULT_NOT_FOUND");
            case TRANSPORT_DISABLED, INVALID_PERSISTED_STATE -> error(HttpStatus.SERVICE_UNAVAILABLE, "WORK_ITEM_RESULT_UNAVAILABLE");
            default -> error(HttpStatus.CONFLICT, "WORK_ITEM_RESULT_STALE");
        };
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorBody> retired(IllegalArgumentException failure) {
        return "AGENT_RUNTIME_UNAUTHENTICATED".equals(failure.getMessage())
                ? error(HttpStatus.UNAUTHORIZED, "AGENT_RUNTIME_UNAUTHENTICATED")
                : error(HttpStatus.SERVICE_UNAVAILABLE, "WORK_ITEM_RESULT_UNAVAILABLE");
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> unavailable(Exception ignored) { return error(HttpStatus.SERVICE_UNAVAILABLE, "WORK_ITEM_RESULT_UNAVAILABLE"); }

    private static AgentRuntimeAuthentication.Scope scope(Authentication principal, HttpServletRequest request, String... ids) {
        if (request == null || request.getQueryString() != null || !request.getParameterMap().isEmpty()) throw new Forbidden();
        for (String id : ids) if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")) throw new BadRequest();
        try { return AgentRuntimeAuthenticationService.requireProof(principal).scope(); }
        catch (IllegalArgumentException denied) { throw new Forbidden(); }
    }
    private static void requireNoBody(HttpServletRequest request) {
        try { if (request.getInputStream().read() != -1) throw new BadRequest(); }
        catch (IOException unreadable) { throw new BadRequest(); }
    }
    private static AgentWorkItemResultCommitDTO parse(HttpServletRequest request) {
        try {
            JsonNode node = STRICT_JSON.readTree(request.getInputStream());
            if (node == null || !node.isObject() || !integer(node.path("expectedWorkItemVersion"), Long.MAX_VALUE - 1)) throw new BadRequest();
            var artifact = node.path("artifact");
            if (!artifact.isObject() || !integer(artifact.path("artifactVersion"), Integer.MAX_VALUE)
                    || !integer(artifact.path("expectedPreviousVersion"), Integer.MAX_VALUE - 1)
                    || artifact.hasNonNull("contentByteLength") && !integer(artifact.path("contentByteLength"), Long.MAX_VALUE)) throw new BadRequest();
            return STRICT_JSON.treeToValue(node, AgentWorkItemResultCommitDTO.class);
        } catch (Exception malformed) { throw new BadRequest(); }
    }
    private static boolean integer(JsonNode value, long max) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0 && value.longValue() <= max;
    }
    private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(body); }
    private static ResponseEntity<ErrorBody> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .body(new ErrorBody(code, "Runtime work item result is unavailable"));
    }
    public record ErrorBody(String code, String message) { }
    private static final class Forbidden extends RuntimeException { }
    private static final class BadRequest extends RuntimeException { }
}
