package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentOutboxCandidate;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.core.tenant.WorkerScope;

import java.util.List;

public interface AgentOutboxRelayDao {
    default List<AgentOutboxCandidate> selectCorruptCandidates(
            WorkerScope scope, long now, int limit) { throw unsupported(); }
    default List<AgentOutboxCandidate> selectDueCandidates(
            WorkerScope scope, long now, int limit) { throw unsupported(); }
    default List<AgentOutboxCandidate> selectStaleCandidates(
            WorkerScope scope, long now, int limit) { throw unsupported(); }
    default AgentCommandDeliveryEntity lockDelivery(WorkerScope scope, long deliveryId) {
        throw unsupported();
    }
    default AgentOutboxEventEntity lockOutbox(WorkerScope scope, long outboxId) {
        throw unsupported();
    }
    default List<AgentOutboxEventEntity> lockPreviousAttemptOutboxes(
            WorkerScope scope, long deliveryId, int previousAttempt) { throw unsupported(); }
    default AgentOutboxEventEntity lockOutboxForQuarantine(
            WorkerScope scope, AgentOutboxCandidate candidate) { throw unsupported(); }
    default int claimDelivery(WorkerScope scope, AgentCommandDeliveryEntity delivery,
            String leaseOwner, long leaseUntil, String lastError, long now) { throw unsupported(); }
    default int claimOutbox(WorkerScope scope, AgentOutboxEventEntity outbox,
            String leaseOwner, long leaseUntil, String lastError, long now) { throw unsupported(); }
    default int disposeDelivery(WorkerScope scope, AgentCommandDeliveryEntity delivery,
            String newStatus, Long nextRetryAt, String lastError, long now) { throw unsupported(); }
    default int disposeOutbox(WorkerScope scope, AgentOutboxEventEntity outbox,
            String newStatus, Long nextRetryAt, String confirmStatus, Long confirmedAt,
            String confirmError, String returnStatus, Long returnedAt, Integer returnReplyCode,
            String returnReplyText, Long publishedAt, String lastError, long now) {
        throw unsupported();
    }
    default int quarantineDelivery(WorkerScope scope, AgentCommandDeliveryEntity delivery,
            String errorCode, long now) { throw unsupported(); }
    default int quarantineOutbox(WorkerScope scope, AgentOutboxEventEntity outbox,
            String errorCode, long now) { throw unsupported(); }

    List<AgentOutboxCandidate> selectCorruptCandidates(long now, int limit);

    List<AgentOutboxCandidate> selectDueCandidates(long now, int limit);

    List<AgentOutboxCandidate> selectStaleCandidates(long now, int limit);

    AgentCommandDeliveryEntity lockDelivery(String tenantId, String clientId, long deliveryId);

    AgentOutboxEventEntity lockOutbox(String tenantId, String clientId, long outboxId);

    List<AgentOutboxEventEntity> lockPreviousAttemptOutboxes(
            String tenantId, String clientId, long deliveryId, int previousAttempt);

    AgentOutboxEventEntity lockOutboxForQuarantine(AgentOutboxCandidate candidate);

    int claimDelivery(
            AgentCommandDeliveryEntity delivery, String leaseOwner, long leaseUntil,
            String lastError, long now);

    int claimOutbox(
            AgentOutboxEventEntity outbox, String leaseOwner, long leaseUntil,
            String lastError, long now);

    int disposeDelivery(
            AgentCommandDeliveryEntity delivery, String newStatus, Long nextRetryAt,
            String lastError, long now);

    int disposeOutbox(
            AgentOutboxEventEntity outbox, String newStatus, Long nextRetryAt,
            String confirmStatus, Long confirmedAt, String confirmError,
            String returnStatus, Long returnedAt, Integer returnReplyCode,
            String returnReplyText, Long publishedAt, String lastError, long now);

    int quarantineDelivery(
            AgentCommandDeliveryEntity delivery, String errorCode, long now);

    int quarantineOutbox(
            AgentOutboxEventEntity outbox, String errorCode, long now);

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("Scoped Agent relay DAO is required");
    }
}
