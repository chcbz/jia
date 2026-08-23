package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentCommandInboxDao;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentConsumerInboxEntity;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.agent.mapper.AgentCommandInboxMapper;
import jakarta.inject.Named;
import lombok.RequiredArgsConstructor;

@Named
@RequiredArgsConstructor
public class AgentCommandInboxDaoImpl implements AgentCommandInboxDao {
    private final AgentCommandInboxMapper mapper;

    @Override
    public AgentCommandDeliveryEntity lockDelivery(String tenantId, String clientId, long deliveryId) {
        return mapper.selectDeliveryForUpdate(tenantId, clientId, deliveryId);
    }

    @Override
    public AgentOutboxEventEntity lockOutbox(String tenantId, String clientId, String eventId) {
        return mapper.selectOutboxForUpdate(tenantId, clientId, eventId);
    }

    @Override
    public AgentConsumerInboxEntity lockInbox(
            String tenantId, String clientId, String consumerName, String messageId) {
        return mapper.selectInboxForUpdate(tenantId, clientId, consumerName, messageId);
    }

    @Override
    public int insertInbox(AgentConsumerInboxEntity inbox) {
        return mapper.insertInbox(inbox);
    }

    @Override
    public int updateDeliveryDisposition(
            String tenantId, String clientId, long deliveryId,
            String activeMessageId, int activeAttempt,
            String expectedStatus, long expectedVersion,
            String newStatus, Long nextRetryAt, String lastError, long now) {
        return mapper.updateDeliveryDisposition(
                tenantId, clientId, deliveryId, activeMessageId, activeAttempt,
                expectedStatus, expectedVersion, newStatus, nextRetryAt, lastError, now);
    }

    @Override
    public int reclaimProcessingInbox(
            AgentConsumerInboxEntity inbox, String newLeaseOwner, long newLeaseUntil, long now) {
        return mapper.reclaimProcessingInbox(
                inbox.getId(), inbox.getTenantId(), inbox.getClientId(),
                inbox.getConsumerName(), inbox.getMessageId(),
                inbox.getLeaseOwner(), inbox.getLeaseUntil(),
                inbox.getActiveAttempt(), inbox.getVersion(),
                newLeaseOwner, newLeaseUntil, now);
    }

    @Override
    public int reclaimRetryInbox(
            AgentConsumerInboxEntity inbox, String newLeaseOwner, long newLeaseUntil, long now) {
        return mapper.reclaimRetryInbox(
                inbox.getId(), inbox.getTenantId(), inbox.getClientId(),
                inbox.getConsumerName(), inbox.getMessageId(), inbox.getNextRetryAt(),
                inbox.getActiveAttempt(), inbox.getVersion(), newLeaseOwner, newLeaseUntil, now);
    }

    @Override
    public int expireRetryInbox(
            AgentConsumerInboxEntity inbox, long processedAt, String lastError, long now) {
        return mapper.expireRetryInbox(
                inbox.getId(), inbox.getTenantId(), inbox.getClientId(),
                inbox.getConsumerName(), inbox.getMessageId(), inbox.getNextRetryAt(),
                inbox.getActiveAttempt(), inbox.getVersion(), processedAt, lastError, now);
    }

    @Override
    public int completeInbox(
            long inboxId, String tenantId, String clientId,
            String consumerName, String messageId,
            String leaseOwner, long leaseUntil, int activeAttempt, long expectedVersion,
            String newStatus, String resultStatus, Long nextRetryAt,
            long processedAt, String lastError, long now) {
        return mapper.completeInbox(
                inboxId, tenantId, clientId, consumerName, messageId,
                leaseOwner, leaseUntil, activeAttempt, expectedVersion,
                newStatus, resultStatus, nextRetryAt, processedAt, lastError, now);
    }
}
