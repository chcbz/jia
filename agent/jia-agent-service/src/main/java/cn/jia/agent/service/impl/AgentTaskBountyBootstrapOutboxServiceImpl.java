package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskBountyBootstrapOutboxDao;
import cn.jia.agent.entity.AgentTaskBountyBootstrapClaimDTO;
import cn.jia.agent.entity.AgentTaskBountyBootstrapOutboxEntity;
import cn.jia.agent.entity.AgentTaskBountyBootstrapReconcileDTO;
import cn.jia.agent.entity.AgentTaskBountyBootstrapReconcileResultDTO;
import cn.jia.agent.service.AgentTaskBountyBootstrapOutboxService;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Short fenced claim/reconcile transactions; no Chat, Rabbit, Agent, or Provider I/O. */
@Named
public final class AgentTaskBountyBootstrapOutboxServiceImpl
        implements AgentTaskBountyBootstrapOutboxService {
    // Recovery lease only. Expiry never cancels Chat work; Chat must consume by stable action id.
    static final long CLAIM_LEASE_MILLIS = 300_000L;
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,99}");

    private final AgentTaskBountyBootstrapOutboxDao outbox;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;

    @Inject
    public AgentTaskBountyBootstrapOutboxServiceImpl(AgentTaskBountyBootstrapOutboxDao outbox,
            ObjectMapper json, PlatformTransactionManager transactionManager) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.json = Objects.requireNonNull(json, "json");
        Objects.requireNonNull(transactionManager, "transactionManager");
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public AgentTaskBountyBootstrapClaimDTO claimNext(AgentTaskExecutionGrantService.Scope scope,
            String consumerId, long now) {
        validateScope(scope);
        exact(consumerId, "consumerId", 100);
        if (now <= 0) throw new IllegalArgumentException("now is invalid");
        Long leaseUntil;
        try {
            leaseUntil = Math.addExact(now, CLAIM_LEASE_MILLIS);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("claim lease time overflow", overflow);
        }
        return transaction.execute(ignored -> claimLocked(scope, consumerId, now, leaseUntil));
    }

    private AgentTaskBountyBootstrapClaimDTO claimLocked(
            AgentTaskExecutionGrantService.Scope scope, String consumerId,
            long now, long leaseUntil) {
        AgentTaskBountyBootstrapOutboxEntity row = outbox.findClaimableForUpdate(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), now);
        if (row == null) return null;
        requireScope(scope, row);
        if (row.getAttemptCount() == null || row.getAttemptCount() < 0
                || row.getAttemptCount() == Integer.MAX_VALUE
                || row.getVersion() == null || row.getVersion() < 0
                || row.getVersion() == Long.MAX_VALUE) {
            throw new IllegalStateException("Bootstrap claim fence is invalid");
        }
        if (!outbox.claim(row, consumerId, leaseUntil, now)) {
            throw new IllegalStateException("Bootstrap claim CAS lost under row lock");
        }
        row.setStatus("CLAIMED").setAttemptCount(row.getAttemptCount() + 1)
                .setNextRetryAt(null).setLeaseOwner(consumerId).setLeaseUntil(leaseUntil)
                .setLastErrorCode(null).setVersion(row.getVersion() + 1);
        try {
            List<AgentTaskBountyBootstrapClaimDTO.ReferenceSummary> references =
                    AgentTaskBountyBootstrapPayload.validateAndRead(row, json);
            return new AgentTaskBountyBootstrapClaimDTO(row.getBootstrapId(), row.getTenantId(),
                    row.getClientId(), row.getOwnerJiacn(), row.getTaskId(),
                    row.getSourceBusinessActionId(), row.getRequirementRevision(),
                    row.getRequirementAnchor(), row.getAssignmentRevision(), row.getTargetAgentId(),
                    row.getGrantId(), row.getGrantVersion(), row.getPermittedOperation(), references,
                    row.getReferenceSummarySha256(), consumerId, leaseUntil,
                    row.getAttemptCount(), row.getVersion());
        } catch (RuntimeException corrupt) {
            if (!outbox.reconcile(row, "DEAD", null, null, null,
                    "CORRUPT_BOOTSTRAP_INTENT", now, now)) {
                throw new IllegalStateException("Corrupt bootstrap quarantine CAS failed", corrupt);
            }
            row.setStatus("DEAD").setLeaseOwner(null).setLeaseUntil(null)
                    .setLastErrorCode("CORRUPT_BOOTSTRAP_INTENT").setReconciledAt(now)
                    .setVersion(row.getVersion() + 1);
            return null;
        }
    }

    @Override
    public AgentTaskBountyBootstrapReconcileResultDTO reconcile(
            AgentTaskExecutionGrantService.Scope scope,
            AgentTaskBountyBootstrapReconcileDTO command, long now) {
        validateScope(scope);
        validateCommand(command);
        if (now <= 0) throw new IllegalArgumentException("now is invalid");
        return transaction.execute(ignored -> reconcileLocked(scope, command, now));
    }

    private AgentTaskBountyBootstrapReconcileResultDTO reconcileLocked(
            AgentTaskExecutionGrantService.Scope scope,
            AgentTaskBountyBootstrapReconcileDTO command, long now) {
        AgentTaskBountyBootstrapOutboxEntity row = outbox.findByBootstrapForUpdate(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), command.bootstrapId());
        if (row == null) throw new IllegalStateException("Bootstrap intent was not found");
        requireScope(scope, row);
        if (isExactReplay(row, command)) return result(row);
        if (!"CLAIMED".equals(row.getStatus())
                || !Objects.equals(command.expectedOutboxVersion(), row.getVersion())
                || !Objects.equals(command.claimAttempt(), row.getAttemptCount())
                || !constantEquals(command.leaseOwner(), row.getLeaseOwner())) {
            throw new IllegalStateException("Bootstrap claim fence is stale");
        }
        AgentTaskBountyBootstrapPayload.validateAndRead(row, json);

        String status;
        Long nextRetryAt = null;
        String conversationId = null;
        String requestId = null;
        String errorCode = null;
        Long reconciledAt = null;
        switch (command.outcome()) {
            case ADMITTED -> {
                exact(command.conversationId(), "conversationId", 100);
                exact(command.initialRequestId(), "initialRequestId", 100);
                if (command.errorCode() != null) {
                    throw new IllegalArgumentException("ADMITTED cannot carry errorCode");
                }
                status = "ADMITTED";
                conversationId = command.conversationId();
                requestId = command.initialRequestId();
                reconciledAt = now;
            }
            case RETRYABLE_FAILURE -> {
                rejectIds(command);
                errorCode = errorCode(command.errorCode());
                status = "RETRY";
                nextRetryAt = now;
            }
            case TERMINAL_FAILURE -> {
                rejectIds(command);
                errorCode = errorCode(command.errorCode());
                status = "DEAD";
                reconciledAt = now;
            }
            default -> throw new IllegalArgumentException("outcome is invalid");
        }
        if (!outbox.reconcile(row, status, nextRetryAt, conversationId, requestId,
                errorCode, reconciledAt, now)) {
            throw new IllegalStateException("Bootstrap reconcile CAS lost under row lock");
        }
        row.setStatus(status).setNextRetryAt(nextRetryAt).setLeaseOwner(null).setLeaseUntil(null)
                .setAdmittedConversationId(conversationId).setAdmittedRequestId(requestId)
                .setLastErrorCode(errorCode).setReconciledAt(reconciledAt)
                .setVersion(row.getVersion() + 1);
        return result(row);
    }

    private static boolean isExactReplay(AgentTaskBountyBootstrapOutboxEntity row,
            AgentTaskBountyBootstrapReconcileDTO command) {
        if (row.getVersion() == null || row.getVersion() != command.expectedOutboxVersion() + 1
                || !Objects.equals(row.getAttemptCount(), command.claimAttempt())) return false;
        return switch (command.outcome()) {
            case ADMITTED -> "ADMITTED".equals(row.getStatus())
                    && Objects.equals(row.getAdmittedConversationId(), command.conversationId())
                    && Objects.equals(row.getAdmittedRequestId(), command.initialRequestId())
                    && command.errorCode() == null;
            case RETRYABLE_FAILURE -> "RETRY".equals(row.getStatus())
                    && Objects.equals(row.getLastErrorCode(), command.errorCode())
                    && command.conversationId() == null && command.initialRequestId() == null;
            case TERMINAL_FAILURE -> "DEAD".equals(row.getStatus())
                    && Objects.equals(row.getLastErrorCode(), command.errorCode())
                    && command.conversationId() == null && command.initialRequestId() == null;
        };
    }

    private static AgentTaskBountyBootstrapReconcileResultDTO result(
            AgentTaskBountyBootstrapOutboxEntity row) {
        return new AgentTaskBountyBootstrapReconcileResultDTO(row.getBootstrapId(),
                row.getStatus(), row.getVersion(), row.getAdmittedConversationId(),
                row.getAdmittedRequestId());
    }

    private static void validateCommand(AgentTaskBountyBootstrapReconcileDTO command) {
        if (command == null || command.outcome() == null
                || command.expectedOutboxVersion() < 1 || command.claimAttempt() < 1) {
            throw new IllegalArgumentException("reconcile command is invalid");
        }
        exact(command.bootstrapId(), "bootstrapId", 100);
        exact(command.leaseOwner(), "leaseOwner", 100);
    }

    private static void rejectIds(AgentTaskBountyBootstrapReconcileDTO command) {
        if (command.conversationId() != null || command.initialRequestId() != null) {
            throw new IllegalArgumentException("Failed reconcile cannot carry Chat identifiers");
        }
    }

    private static String errorCode(String value) {
        if (value == null || !ERROR_CODE.matcher(value).matches()) {
            throw new IllegalArgumentException("errorCode is invalid");
        }
        return value;
    }

    private static void requireScope(AgentTaskExecutionGrantService.Scope scope,
            AgentTaskBountyBootstrapOutboxEntity row) {
        if (!constantEquals(scope.tenantId(), row.getTenantId())
                || !constantEquals(scope.clientId(), row.getClientId())
                || !constantEquals(scope.ownerJiacn(), row.getOwnerJiacn())) {
            throw new IllegalStateException("Bootstrap row escaped owner scope");
        }
    }

    private static void validateScope(AgentTaskExecutionGrantService.Scope scope) {
        if (scope == null || !"0".equals(scope.tenantId())) {
            throw new IllegalArgumentException("scope is invalid");
        }
        exact(scope.clientId(), "clientId", 50);
        exact(scope.ownerJiacn(), "ownerJiacn", 50);
        if ("0".equals(scope.ownerJiacn())) throw new IllegalArgumentException("scope is invalid");
    }

    private static void exact(String value, String name, int max) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.codePointCount(0, value.length()) > max
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    private static boolean constantEquals(String left, String right) {
        return AgentTaskBountyBootstrapPayload.constantEquals(left, right);
    }
}
