package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentCommandRecoveryDao;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentConsumerInboxEntity;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.agent.entity.AgentWaitingCommandCandidate;
import cn.jia.agent.mapper.AgentCommandRecoveryMapper;
import jakarta.inject.Named;
import lombok.RequiredArgsConstructor;

import java.util.List;

@Named
@RequiredArgsConstructor
public class AgentCommandRecoveryDaoImpl implements AgentCommandRecoveryDao {
    private final AgentCommandRecoveryMapper mapper;

    @Override
    public List<AgentWaitingCommandCandidate> findReconnectCandidates(
            String tenantId, String clientId, String targetAgentId,
            long now, long afterDeliveryId, int limit) {
        return mapper.selectReconnectCandidates(
                tenantId, clientId, targetAgentId, now, afterDeliveryId, limit);
    }

    @Override
    public List<AgentWaitingCommandCandidate> findDueCandidates(
            long now, long sentBefore, long afterDeliveryId, int limit) {
        return mapper.selectDueCandidates(now, sentBefore, afterDeliveryId, limit);
    }

    @Override
    public AgentCommandDeliveryEntity lockDelivery(
            String tenantId, String clientId, long deliveryId) {
        return mapper.selectDeliveryForUpdate(tenantId, clientId, deliveryId);
    }

    @Override
    public AgentCommandDeliveryEntity lockDeliveryByCommand(
            String tenantId, String clientId, String commandId) {
        return mapper.selectDeliveryByCommandForUpdate(tenantId, clientId, commandId);
    }

    @Override
    public List<AgentOutboxEventEntity> lockActiveOutboxes(
            String tenantId, String clientId, long deliveryId, String messageId) {
        return mapper.selectActiveOutboxesForUpdate(tenantId, clientId, deliveryId, messageId);
    }

    @Override
    public List<AgentOutboxEventEntity> lockPreviousAttemptOutboxes(
            String tenantId, String clientId, long deliveryId, int previousAttempt) {
        return mapper.selectPreviousAttemptOutboxesForUpdate(
                tenantId, clientId, deliveryId, previousAttempt);
    }

    @Override
    public AgentConsumerInboxEntity lockInbox(
            String tenantId, String clientId, String consumerName, String messageId) {
        return mapper.selectInboxForUpdate(tenantId, clientId, consumerName, messageId);
    }

    @Override
    public int reissueDelivery(AgentCommandDeliveryEntity delivery, String newMessageId,
            String requestedBy, String reason, String lastError, long now) {
        return mapper.reissueDelivery(delivery, newMessageId, delivery.getActiveMessageId(),
                requestedBy, reason, lastError, now);
    }

    @Override
    public int manualReissueDelivery(AgentCommandDeliveryEntity delivery, String newMessageId,
            String requestedBy, String approverId, String reason, String lastError, long now) {
        return mapper.manualReissueDelivery(delivery, newMessageId, delivery.getActiveMessageId(),
                requestedBy, approverId, reason, lastError, now);
    }

    @Override
    public int expireDelivery(
            AgentCommandDeliveryEntity delivery, String lastError, long now) {
        return mapper.expireDelivery(delivery, lastError, now);
    }

    @Override
    public int expireWaitingInbox(
            AgentConsumerInboxEntity inbox, String lastError, long now) {
        return mapper.expireWaitingInbox(inbox, lastError, now);
    }

    @Override
    public int insertOutbox(AgentOutboxEventEntity outbox) {
        return mapper.insertOutbox(outbox);
    }

    @Override
    public int advanceAck(
            AgentCommandDeliveryEntity delivery, String newStatus, String lastError, long now) {
        return mapper.advanceAck(delivery, newStatus, lastError, now);
    }
}
