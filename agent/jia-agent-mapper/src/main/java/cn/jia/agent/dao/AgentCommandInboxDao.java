package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentConsumerInboxEntity;
import cn.jia.agent.entity.AgentOutboxEventEntity;

public interface AgentCommandInboxDao {
    AgentCommandDeliveryEntity lockDelivery(String tenantId, String clientId, long deliveryId);

    AgentOutboxEventEntity lockOutbox(String tenantId, String clientId, String eventId);

    java.util.List<AgentOutboxEventEntity> lockPreviousAttemptOutboxes(
            String tenantId, String clientId, long deliveryId, int previousAttempt);

    AgentConsumerInboxEntity lockInbox(
            String tenantId, String clientId, String consumerName, String messageId);

    int insertInbox(AgentConsumerInboxEntity inbox);

    int updateDeliveryDisposition(
            String tenantId, String clientId, long deliveryId,
            String activeMessageId, int activeAttempt,
            String expectedStatus, long expectedVersion,
            String newStatus, Long nextRetryAt, String lastError, long now);

    int reclaimProcessingInbox(
            AgentConsumerInboxEntity inbox, String newLeaseOwner, long newLeaseUntil, long now);

    int reclaimRetryInbox(
            AgentConsumerInboxEntity inbox, String newLeaseOwner, long newLeaseUntil, long now);

    int expireRetryInbox(
            AgentConsumerInboxEntity inbox, long processedAt, String lastError, long now);

    int completeInbox(
            long inboxId, String tenantId, String clientId,
            String consumerName, String messageId,
            String leaseOwner, long leaseUntil, int activeAttempt, long expectedVersion,
            String newStatus, String resultStatus, Long nextRetryAt,
            long processedAt, String lastError, long now);
}
