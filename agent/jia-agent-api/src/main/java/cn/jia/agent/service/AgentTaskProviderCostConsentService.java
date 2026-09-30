package cn.jia.agent.service;

import cn.jia.agent.entity.AgentTaskProviderCostConsentDTO;
import cn.jia.agent.entity.AgentTaskProviderCostConsentIssueDTO;

/**
 * Owner consent and internal lifecycle for one controlled, unpriced external image request attempt.
 * This core does not populate execution grants or invoke a Provider.
 */
public interface AgentTaskProviderCostConsentService {
    Result issue(Scope scope, String taskId, String idempotencyKey,
            AgentTaskProviderCostConsentIssueDTO request);
    AgentTaskProviderCostConsentDTO get(Scope scope, String taskId, String consentId);
    AgentTaskProviderCostConsentDTO getByIdempotencyKey(
            Scope scope, String taskId, String idempotencyKey);
    AgentTaskProviderCostConsentDTO revoke(Scope scope, String taskId, String consentId,
            String idempotencyKey, long expectedVersion);

    AgentTaskProviderCostConsentDTO bind(Scope scope, String taskId, String consentId,
            BindCommand command);
    AgentTaskProviderCostConsentDTO reserve(Scope scope, String taskId, String consentId,
            ReserveCommand command);
    AgentTaskProviderCostConsentDTO consume(Scope scope, String taskId, String consentId,
            ConsumeCommand command);

    record Scope(String tenantId, String clientId, String ownerJiacn) { }
    record Result(AgentTaskProviderCostConsentDTO receipt, boolean replay) { }

    record BindCommand(long expectedVersion, String assignmentBaseHash,
            String inputSnapshotDigest, String grantId, long grantVersion,
            long assignmentRevision) { }
    record ReserveCommand(long expectedVersion, String assignmentBaseHash,
            String inputSnapshotDigest, String grantId, long grantVersion,
            long assignmentRevision, String executionId, String runId) { }
    record ConsumeCommand(long expectedVersion, String assignmentBaseHash,
            String inputSnapshotDigest, String grantId, long grantVersion,
            long assignmentRevision, String executionId, String runId, String leaseId) { }

    final class Failure extends RuntimeException {
        private final Reason reason;
        public Failure(Reason reason) { super(reason.name()); this.reason = reason; }
        public Failure(Reason reason, Throwable cause) {
            super(reason.name(), cause); this.reason = reason;
        }
        public Reason reason() { return reason; }
    }

    enum Reason { BAD_REQUEST, FORBIDDEN, NOT_FOUND, CONFLICT, SOURCE_UNAVAILABLE }
}
