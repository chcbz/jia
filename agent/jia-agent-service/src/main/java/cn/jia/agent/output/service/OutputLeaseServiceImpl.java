package cn.jia.agent.output.service;

import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentWorkItemLeaseCommandDTO;
import cn.jia.agent.entity.AgentWorkItemLeaseDTO;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.exception.AgentTaskStateException;
import cn.jia.agent.output.OutputAuthorizationException;
import cn.jia.agent.output.OutputConstants;
import cn.jia.agent.output.OutputLeaseHttpResult;
import cn.jia.agent.output.OutputLeaseService;
import cn.jia.agent.output.OutputRunAuthorizationService;
import cn.jia.agent.output.OutputTicketAuthorization;
import cn.jia.agent.output.dao.OutputUploadDao;
import cn.jia.agent.output.dto.OutputLeaseDTO;
import cn.jia.agent.output.dto.OutputLeaseRequestDTO;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentWorkItemLeaseService;
import cn.jia.core.util.JsonUtil;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

@Service
public class OutputLeaseServiceImpl implements OutputLeaseService {
    private static final String ACTOR_KIND = "RUN";
    private static final Set<String> ACTIONS = Set.of("claim", "start", "heartbeat", "release");

    private final OutputRunAuthorizationService authorization;
    private final AgentTaskMutationTransaction taskTransactions;
    private final AgentWorkItemLeaseService leases;
    private final OutputUploadDao receipts;

    public OutputLeaseServiceImpl(
            OutputRunAuthorizationService authorization,
            AgentTaskMutationTransaction taskTransactions,
            AgentWorkItemLeaseService leases,
            OutputUploadDao receipts) {
        this.authorization = Objects.requireNonNull(authorization);
        this.taskTransactions = Objects.requireNonNull(taskTransactions);
        this.leases = Objects.requireNonNull(leases);
        this.receipts = Objects.requireNonNull(receipts);
    }

    @Override
    public OutputLeaseHttpResult mutate(
            OutputTicketAuthorization projected, String bearer, String key,
            String taskId, String workItemId, String action,
            OutputLeaseRequestDTO request, String requestId) {
        requireProjected(projected, taskId, workItemId);
        requireKey(key);
        requireAction(action);
        RequiredRequest required = requireRequest(action, request);
        requireRequestId(requestId);
        byte[] hash = sha256(canonical(taskId, workItemId, action, request));
        try {
            return taskTransactions.executeWithLockedTaskRoot(
                    projected.tenantId(), projected.clientId(), taskId,
                    root -> mutateLocked(root, projected, rawBearer(bearer), key,
                            taskId, workItemId, action, required, hash, requestId));
        } catch (AgentTaskCollaborationException missing) {
            if (missing.getReason() == AgentTaskCollaborationException.Reason.NOT_FOUND) {
                return error(404, "OUTPUT_LEASE_NOT_FOUND", "Work lease is unavailable", requestId);
            }
            throw missing;
        }
    }

    @Override
    public OutputLeaseHttpResult recover(
            OutputTicketAuthorization projected, String bearer,
            String taskId, String workItemId, String requestId) {
        requireProjected(projected, taskId, workItemId);
        requireRequestId(requestId);
        try {
            return taskTransactions.executeWithLockedTaskRoot(
                    projected.tenantId(), projected.clientId(), taskId,
                    root -> {
                        OutputTicketAuthorization current = authorizeAndMatch(
                                projected, rawBearer(bearer), taskId, workItemId);
                        AgentWorkItemLeaseCommandDTO command = new AgentWorkItemLeaseCommandDTO();
                        command.setAgentId(current.producerAgentId());
                        command.setRunId(current.runId());
                        AgentWorkItemLeaseDTO result = leases.recoverWithLockedTaskRoot(
                                root, current.tenantId(), current.clientId(), taskId, workItemId, command);
                        return success(result);
                    });
        } catch (AgentTaskCollaborationException missing) {
            if (missing.getReason() == AgentTaskCollaborationException.Reason.NOT_FOUND) {
                return error(404, "OUTPUT_LEASE_NOT_FOUND", "Work lease is unavailable", requestId);
            }
            throw missing;
        } catch (AgentTaskStateException rejected) {
            return stateError(rejected, requestId);
        }
    }

    private OutputLeaseHttpResult mutateLocked(
            AgentTaskMetaEntity root, OutputTicketAuthorization projected,
            String rawBearer, String key, String taskId, String workItemId,
            String action, RequiredRequest required, byte[] hash, String requestId) {
        requirePolicyRoot(root, projected, taskId);
        OutputTicketAuthorization current = authorizeAndMatch(
                projected, rawBearer, taskId, workItemId);
        if (!current.runId().equals(required.runId())) {
            throw forbidden("Lease request run does not match the authorized output ticket");
        }
        String operation = "lease." + action;
        OutputUploadDao.Receipt prior = receipts.lockReceipt(
                current.tenantId(), current.clientId(), ACTOR_KIND,
                current.runId(), operation, key);
        if (prior != null) {
            if (!MessageDigest.isEqual(hash, prior.requestHash())) {
                return error(409, "OUTPUT_IDEMPOTENCY_CONFLICT",
                        "Idempotency-Key is already bound to another request", requestId);
            }
            if (prior.httpStatus() < 400 || validRetainedError(prior.responseJson())) {
                return new OutputLeaseHttpResult(prior.httpStatus(), prior.responseJson());
            }
            throw unavailable("Persisted lease receipt is invalid");
        }

        OutputLeaseHttpResult response;
        try {
            AgentWorkItemLeaseCommandDTO command = new AgentWorkItemLeaseCommandDTO();
            command.setAgentId(current.producerAgentId());
            command.setRunId(current.runId());
            command.setExpectedVersion(required.expectedVersion());
            command.setLeaseToken(required.leaseToken());
            command.setLeaseDurationMillis(required.leaseDurationMillis());
            response = success(leases.mutateWithLockedTaskRoot(
                    root, current.tenantId(), current.clientId(), taskId, workItemId,
                    action, command));
        } catch (AgentTaskStateException rejected) {
            response = stateError(rejected, requestId);
            if (response.status() >= 500) throw rejected;
        }
        long now = System.currentTimeMillis();
        if (current.recoveryUntil() < now) {
            throw unavailable("Lease run recovery retention is invalid");
        }
        long retainUntil = Math.addExact(
                current.recoveryUntil(), OutputConstants.RECEIPT_POST_RECOVERY_MILLIS);
        if (receipts.insertReceipt(current.tenantId(), current.clientId(), ACTOR_KIND,
                current.runId(), operation, key, hash, response.status(),
                response.responseJson(), retainUntil, now) != 1) {
            throw unavailable("Lease receipt could not be persisted");
        }
        return response;
    }

    private OutputTicketAuthorization authorizeAndMatch(
            OutputTicketAuthorization projected, String rawBearer,
            String taskId, String workItemId) {
        OutputTicketAuthorization current = authorization.authorizeTicket(
                rawBearer, OutputConstants.OP_LEASE, false);
        if (!sameAuthorization(projected, current)
                || current.policyVersion() != 1
                || !current.currentRuntimeTicket()
                || !OutputConstants.SOURCE_TASK.equals(current.sourceType())
                || !taskId.equals(current.sourceId())
                || !workItemId.equals(current.workItemId())) {
            throw forbidden("Output ticket does not authorize this work lease");
        }
        return current;
    }

    private static void requirePolicyRoot(
            AgentTaskMetaEntity root, OutputTicketAuthorization projected, String taskId) {
        if (root == null || !projected.tenantId().equals(root.getTenantId())
                || !projected.clientId().equals(root.getClientId())
                || !taskId.equals(root.getTaskId())
                || !Integer.valueOf(1).equals(root.getDeliveryPolicyVersion())) {
            throw forbidden("Task does not permit ticket-bound work leases");
        }
    }

    private static boolean sameAuthorization(
            OutputTicketAuthorization left, OutputTicketAuthorization right) {
        return left != null && right != null
                && Objects.equals(left.tenantId(), right.tenantId())
                && Objects.equals(left.clientId(), right.clientId())
                && Objects.equals(left.runId(), right.runId())
                && Objects.equals(left.sourceType(), right.sourceType())
                && Objects.equals(left.sourceId(), right.sourceId())
                && Objects.equals(left.producerAgentId(), right.producerAgentId())
                && Objects.equals(left.bindingId(), right.bindingId())
                && Objects.equals(left.runtimeInstanceId(), right.runtimeInstanceId())
                && Objects.equals(left.workItemId(), right.workItemId())
                && left.policyVersion() == right.policyVersion();
    }

    private static RequiredRequest requireRequest(String action, OutputLeaseRequestDTO request) {
        if (request == null || request.runId() == null
                || !request.runId().matches("[0-9a-f]{32}")) {
            throw bad("runId is invalid");
        }
        long version = decimal(request.expectedVersion(), true, "expectedVersion");
        Long duration = request.leaseDurationMillis() == null ? null
                : decimal(request.leaseDurationMillis(), false, "leaseDurationMillis");
        boolean tokenPresent = request.leaseToken() != null;
        if (tokenPresent && (!exact(request.leaseToken(), 100))) {
            throw bad("leaseToken is invalid");
        }
        switch (action) {
            case "claim" -> {
                if (duration == null || tokenPresent) throw bad("claim requires only a duration");
            }
            case "start", "release" -> {
                if (!tokenPresent || duration != null) throw bad(action + " requires only a leaseToken");
            }
            case "heartbeat" -> {
                if (!tokenPresent || duration == null) {
                    throw bad("heartbeat requires a leaseToken and duration");
                }
            }
            default -> throw bad("Lease action is unsupported");
        }
        return new RequiredRequest(request.runId(), version, request.leaseToken(), duration);
    }

    private static OutputLeaseHttpResult success(AgentWorkItemLeaseDTO value) {
        if (value == null || value.getVersion() == null || value.getVersion() < 0
                || value.getWorkItemId() == null || value.getStatus() == null) {
            throw unavailable("Lease service returned an invalid result");
        }
        OutputLeaseDTO lease = new OutputLeaseDTO(
                value.getWorkItemId(), value.getStatus(), Long.toString(value.getVersion()),
                value.getLeaseToken(), value.getLeaseUntil() == null
                        ? null : Long.toString(value.getLeaseUntil()));
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("code", "E0");
        envelope.put("data", lease);
        return new OutputLeaseHttpResult(200, JsonUtil.toJson(envelope));
    }

    private static OutputLeaseHttpResult stateError(
            AgentTaskStateException failure, String requestId) {
        return switch (failure.getReason()) {
            case INVALID_REQUEST -> error(400, "OUTPUT_REQUEST_INVALID",
                    "Invalid work lease request", requestId);
            case NOT_FOUND -> error(404, "OUTPUT_LEASE_NOT_FOUND",
                    "Work lease is unavailable", requestId);
            case VERSION_CONFLICT -> versionConflict(failure, requestId);
            case INVALID_TRANSITION, RESERVED_FOR_CLAIM_PROTOCOL,
                    LEASE_INVALID -> error(409, "OUTPUT_LEASE_CONFLICT",
                    "Work lease state changed or no longer permits this operation", requestId);
            case INVALID_PERSISTED_STATE -> error(503, "OUTPUT_LEASE_UNAVAILABLE",
                    "Work lease is unavailable", requestId);
        };
    }

    private static OutputLeaseHttpResult versionConflict(
            AgentTaskStateException failure, String requestId) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (failure.getCurrentVersion() != null && failure.getCurrentVersion() >= 0) {
            details.put("currentVersion", Long.toString(failure.getCurrentVersion()));
        }
        return error(409, "OUTPUT_VERSION_CONFLICT",
                "Work lease version changed", requestId, details);
    }

    private static OutputLeaseHttpResult error(
            int status, String code, String message, String requestId) {
        return error(status, code, message, requestId, Map.of());
    }

    private static OutputLeaseHttpResult error(
            int status, String code, String message, String requestId,
            Map<String, Object> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("retryable", status == 503);
        body.put("requestId", requestId);
        if (details != null && !details.isEmpty()) body.put("details", details);
        return new OutputLeaseHttpResult(status, JsonUtil.toJson(body));
    }

    private static boolean validRetainedError(String value) {
        try {
            Object parsed = JsonUtil.getMapper().readValue(value, Object.class);
            return parsed instanceof Map<?, ?> map
                    && map.get("code") instanceof String
                    && map.get("requestId") instanceof String;
        } catch (Exception invalid) {
            return false;
        }
    }

    private static byte[] canonical(
            String taskId, String workItemId, String action, OutputLeaseRequestDTO request) {
        Map<String, Object> fields = new TreeMap<>();
        fields.put("action", action);
        fields.put("expectedVersion", request.expectedVersion());
        fields.put("leaseDurationMillis", request.leaseDurationMillis());
        fields.put("leaseToken", request.leaseToken());
        fields.put("runId", request.runId());
        fields.put("taskId", taskId);
        fields.put("workItemId", workItemId);
        return JsonUtil.toJson(fields).getBytes(StandardCharsets.UTF_8);
    }

    private static long decimal(String value, boolean zeroAllowed, String field) {
        try {
            String pattern = zeroAllowed ? "0|[1-9][0-9]{0,18}" : "[1-9][0-9]{0,18}";
            if (value == null || !value.matches(pattern)) throw new NumberFormatException();
            long parsed = Long.parseLong(value);
            if (parsed == Long.MAX_VALUE) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException invalid) {
            throw bad(field + " is invalid");
        }
    }

    private static void requireProjected(
            OutputTicketAuthorization projected, String taskId, String workItemId) {
        if (projected == null || !exact(taskId, 100) || !exact(workItemId, 100)
                || !OutputConstants.SOURCE_TASK.equals(projected.sourceType())
                || !taskId.equals(projected.sourceId())
                || !workItemId.equals(projected.workItemId())) {
            throw forbidden("Output ticket route is unavailable");
        }
    }

    private static void requireAction(String action) {
        if (!ACTIONS.contains(action)) throw bad("Lease action is unsupported");
    }

    private static void requireKey(String value) {
        if (value == null || !value.matches("[\\x21-\\x7e]{16,100}")) {
            throw bad("Idempotency-Key is invalid");
        }
    }

    private static void requireRequestId(String value) {
        if (!exact(value, 100)) throw bad("requestId is invalid");
    }

    private static String rawBearer(String bearer) {
        if (bearer == null || !bearer.startsWith("Bearer ") || bearer.length() < 48) {
            throw new OutputAuthorizationException(
                    "OUTPUT_AUTH_UNAUTHORIZED", "Output ticket is required");
        }
        return bearer.substring(7);
    }

    private static boolean exact(String value, int max) {
        return value != null && !value.isEmpty() && value.length() <= max
                && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static AgentTaskStateException bad(String message) {
        return new AgentTaskStateException(
                AgentTaskStateException.Reason.INVALID_REQUEST, message);
    }

    private static OutputAuthorizationException forbidden(String message) {
        return new OutputAuthorizationException("OUTPUT_AUTH_FORBIDDEN", message);
    }

    private static IllegalStateException unavailable(String message) {
        return new IllegalStateException(message);
    }

    private record RequiredRequest(
            String runId, long expectedVersion, String leaseToken, Long leaseDurationMillis) {
    }
}
