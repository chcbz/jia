package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentOutboxCandidate;
import cn.jia.agent.entity.AgentOutboxEventEntity;

import java.util.List;

public interface AgentOutboxRelayDao {
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
}
