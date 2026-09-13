package cn.jia.agent.service.impl;

import cn.jia.agent.entity.AgentCommandOperationStatusRow;
import cn.jia.agent.entity.AgentCommandOperationType;
import cn.jia.agent.entity.AgentCommandOperationV1View;
import cn.jia.agent.entity.AgentCommandOperationsException;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Fail-closed projection from immutable request/result audit rows to operation v1. */
final class AgentCommandOperationV1Projector {
    static final long TERMINAL_RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1_000L;
    static final String STATUS_PATH_PREFIX = "/agent/internal/command-operations/v1/operations/";

    AgentCommandOperationV1View project(
            List<AgentCommandOperationStatusRow> rows,
            String tenantId,
            String clientId,
            String requesterId,
            String operationId,
            long now) {
        if (rows == null || rows.size() > 2 || rows.stream().anyMatch(Objects::isNull)) {
            throw failure(AgentCommandOperationsException.Reason.OPERATION_CONFLICT);
        }
        if (rows.isEmpty()) {
            throw failure(AgentCommandOperationsException.Reason.NOT_FOUND_OR_FORBIDDEN);
        }

        AgentCommandOperationStatusRow request = rows.getFirst();
        validateCommon(request, tenantId, clientId, requesterId, operationId);
        if (!"REQUEST".equals(request.phase())
                || !"REQUESTED".equals(request.outcome())
                || request.requestedAt() <= 0
                || request.createdAt() != request.requestedAt()
                || request.completedAt() != null
                || request.errorCode() != null
                || request.newMessageId() != null
                || request.newAttempt() != null
                || request.sourceAttempt() != null && request.sourceAttempt() <= 0
                || now < request.requestedAt()) {
            throw failure(AgentCommandOperationsException.Reason.OPERATION_CONFLICT);
        }

        String statusUrl = STATUS_PATH_PREFIX + operationId;
        if (rows.size() == 1) {
            return new AgentCommandOperationV1View(
                    operationId, request.operationType(), "ACCEPTED", "0",
                    request.requestedAt(), null, null, request.createdAt(), statusUrl,
                    null, null, false);
        }

        AgentCommandOperationStatusRow result = rows.get(1);
        validateCommon(result, tenantId, clientId, requesterId, operationId);
        validateTerminal(request, result, now);
        if (now - result.completedAt() > TERMINAL_RETENTION_MILLIS) {
            throw failure(AgentCommandOperationsException.Reason.NOT_FOUND_OR_FORBIDDEN);
        }

        if ("SUCCEEDED".equals(result.outcome())) {
            return new AgentCommandOperationV1View(
                    operationId, request.operationType(), "SUCCEEDED", "1",
                    request.requestedAt(), null, result.completedAt(), result.completedAt(),
                    statusUrl, successResult(result), null, false);
        }

        AgentCommandOperationV1View.Error error = publicError(result.errorCode());
        return new AgentCommandOperationV1View(
                operationId, request.operationType(), "FAILED", "1",
                request.requestedAt(), null, result.completedAt(), result.completedAt(),
                statusUrl, null, error, error.retryable());
    }

    private void validateCommon(
            AgentCommandOperationStatusRow row,
            String tenantId,
            String clientId,
            String requesterId,
            String operationId) {
        boolean type = AgentCommandOperationType.BROKER_REDRIVE.name().equals(row.operationType())
                || AgentCommandOperationType.MANUAL_REISSUE.name().equals(row.operationType());
        if (row.id() <= 0
                || !operationId.equals(row.operationId())
                || !tenantId.equals(row.tenantId())
                || !clientId.equals(row.clientId())
                || !requesterId.equals(row.requesterId())
                || !uuid(row.operationId())
                || !type
                || row.deliveryId() <= 0
                || !exact(row.sourceMessageId(), 100)
                || row.createdAt() <= 0) {
            throw failure(AgentCommandOperationsException.Reason.OPERATION_CONFLICT);
        }
    }

    private void validateTerminal(
            AgentCommandOperationStatusRow request,
            AgentCommandOperationStatusRow result,
            long now) {
        boolean terminalOutcome = "SUCCEEDED".equals(result.outcome())
                || "FAILED".equals(result.outcome())
                || "REJECTED".equals(result.outcome());
        if (result.id() <= request.id()
                || !"RESULT".equals(result.phase())
                || !terminalOutcome
                || !request.operationType().equals(result.operationType())
                || request.deliveryId() != result.deliveryId()
                || !request.sourceMessageId().equals(result.sourceMessageId())
                || !Objects.equals(request.sourceAttempt(), result.sourceAttempt())
                || request.requestedAt() != result.requestedAt()
                || result.completedAt() == null
                || result.completedAt() < result.requestedAt()
                || result.createdAt() != result.completedAt()
                || result.completedAt() > now
                || result.sourceAttempt() != null && result.sourceAttempt() <= 0
                || "SUCCEEDED".equals(result.outcome()) && result.errorCode() != null
                || !"SUCCEEDED".equals(result.outcome())
                    && (result.errorCode() == null
                        || !result.errorCode().matches("[A-Z0-9_]{1,200}"))) {
            throw failure(AgentCommandOperationsException.Reason.OPERATION_CONFLICT);
        }

        boolean manual = AgentCommandOperationType.MANUAL_REISSUE.name()
                .equals(result.operationType());
        if ("SUCCEEDED".equals(result.outcome())) {
            if (result.sourceAttempt() == null
                    || manual && (!uuid(result.newMessageId())
                        || result.newAttempt() == null
                        || result.sourceAttempt() == Integer.MAX_VALUE
                        || result.newAttempt() != result.sourceAttempt() + 1)
                    || !manual && (result.newMessageId() != null || result.newAttempt() != null)) {
                throw failure(AgentCommandOperationsException.Reason.OPERATION_CONFLICT);
            }
        } else if (result.newMessageId() != null || result.newAttempt() != null) {
            throw failure(AgentCommandOperationsException.Reason.OPERATION_CONFLICT);
        }
    }

    private AgentCommandOperationV1View.Result successResult(
            AgentCommandOperationStatusRow result) {
        boolean manual = AgentCommandOperationType.MANUAL_REISSUE.name()
                .equals(result.operationType());
        return new AgentCommandOperationV1View.Result(
                Long.toString(result.deliveryId()),
                manual ? result.newMessageId() : result.sourceMessageId(),
                manual ? result.newAttempt() : result.sourceAttempt());
    }

    private AgentCommandOperationV1View.Error publicError(String rawCode) {
        if (rawCode.startsWith("RABBIT_") || "PUBLISH_FAILED".equals(rawCode)) {
            return new AgentCommandOperationV1View.Error(
                    "COMMAND_OPERATION_PUBLISH_FAILED",
                    "Command operation could not be published", true);
        }
        if ("OPERATION_DISABLED".equals(rawCode) || "AUDIT_UNAVAILABLE".equals(rawCode)) {
            return new AgentCommandOperationV1View.Error(
                    "COMMAND_OPERATIONS_UNAVAILABLE",
                    "Command operations are unavailable", true);
        }
        if ("NOT_FOUND_OR_FORBIDDEN".equals(rawCode)
                || "INVALID_REQUEST".equals(rawCode)
                || "SOURCE_FORBIDDEN".equals(rawCode)
                || "SOURCE_VALIDATION_FAILED".equals(rawCode)
                || "OPERATION_CONFLICT".equals(rawCode)
                || "REISSUE_REJECTED".equals(rawCode)) {
            return new AgentCommandOperationV1View.Error(
                    "COMMAND_OPERATION_REJECTED",
                    "Command operation was rejected", false);
        }
        return new AgentCommandOperationV1View.Error(
                "COMMAND_OPERATION_FAILED", "Command operation failed", false);
    }

    private boolean uuid(String value) {
        if (!exact(value, 36)) return false;
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private boolean exact(String value, int maxLength) {
        return value != null && !value.isEmpty()
                && value.codePointCount(0, value.length()) <= maxLength
                && !hasUnpairedSurrogate(value)
                && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    return true;
                }
            } else if (Character.isLowSurrogate(unit)) {
                return true;
            }
        }
        return false;
    }

    private AgentCommandOperationsException failure(AgentCommandOperationsException.Reason reason) {
        return new AgentCommandOperationsException(reason);
    }
}
