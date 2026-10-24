package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentConsumerInboxEntity;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.agent.entity.AgentWaitingCommandCandidate;
import cn.jia.core.tenant.WorkerScope;

import java.util.List;

public interface AgentCommandRecoveryDao {
    /** Legacy source-compatible hook. Production discovery must use an explicit lane method. */
    default List<AgentWaitingCommandCandidate> findReconnectCandidates(
            String tenantId, String clientId, String targetAgentId,
            long now, long afterDeliveryId, int limit) {
        throw new UnsupportedOperationException("unscoped reconnect discovery is unavailable");
    }

    /** Legacy source-compatible hook. Production discovery must use an explicit lane method. */
    default List<AgentWaitingCommandCandidate> findDueCandidates(
            long now, long sentBefore, long afterDeliveryId, int limit) {
        throw new UnsupportedOperationException("unscoped due discovery is unavailable");
    }

    default List<AgentWaitingCommandCandidate> findV2ReconnectCandidates(
            WorkerScope scope, String clientId, String targetAgentId,
            long now, long afterDeliveryId, int limit) {
        throw new UnsupportedOperationException("v2 reconnect discovery is unavailable");
    }

    default List<AgentWaitingCommandCandidate> findV2DueCandidates(
            WorkerScope scope, long now, long sentBefore, long afterDeliveryId, int limit) {
        throw new UnsupportedOperationException("v2 due discovery is unavailable");
    }

    default List<AgentWaitingCommandCandidate> findLegacyReconnectCandidates(
            String tenantId, String clientId, String targetAgentId,
            long now, long afterDeliveryId, int limit) {
        if (!"0".equals(tenantId)) return List.of();
        return findReconnectCandidates(
                tenantId, clientId, targetAgentId, now, afterDeliveryId, limit);
    }

    default List<AgentWaitingCommandCandidate> findLegacyDueCandidates(
            long now, long sentBefore, long afterDeliveryId, int limit) {
        return findDueCandidates(now, sentBefore, afterDeliveryId, limit);
    }
    /** Production implementations must override with a non-locking provenance read. */
    default AgentCommandDeliveryEntity findDeliveryProtocolHint(
            String tenantId, String clientId, long deliveryId) {
        return lockDelivery(tenantId, clientId, deliveryId);
    }
    AgentCommandDeliveryEntity lockDelivery(String tenantId, String clientId, long deliveryId);
    AgentCommandDeliveryEntity lockDeliveryByCommand(String tenantId, String clientId, String commandId);
    List<AgentOutboxEventEntity> lockActiveOutboxes(
            String tenantId, String clientId, long deliveryId, String messageId);
    List<AgentOutboxEventEntity> lockPreviousAttemptOutboxes(
            String tenantId, String clientId, long deliveryId, int previousAttempt);
    AgentConsumerInboxEntity lockInbox(
            String tenantId, String clientId, String consumerName, String messageId);
    int reissueDelivery(AgentCommandDeliveryEntity delivery, String newMessageId,
            String requestedBy, String reason, String lastError, long now);
    default int manualReissueDelivery(AgentCommandDeliveryEntity delivery, String newMessageId,
            String requestedBy, String approverId, String reason, String lastError, long now) {
        throw new UnsupportedOperationException("manual reissue is unavailable");
    }
    int expireDelivery(AgentCommandDeliveryEntity delivery, String lastError, long now);
    int expireWaitingInbox(AgentConsumerInboxEntity inbox, String lastError, long now);
    default int expireRetryInbox(AgentConsumerInboxEntity inbox, String lastError, long now) {
        throw new UnsupportedOperationException("retry Inbox expiry is unavailable");
    }
    int insertOutbox(AgentOutboxEventEntity outbox);
    int advanceAck(AgentCommandDeliveryEntity delivery, String newStatus, String lastError, long now);
}
