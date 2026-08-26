package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentOutboxRelayDao;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentOutboxCandidate;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.agent.mapper.AgentOutboxRelayMapper;
import jakarta.inject.Named;
import lombok.RequiredArgsConstructor;

import java.util.List;

@Named
@RequiredArgsConstructor
public class AgentOutboxRelayDaoImpl implements AgentOutboxRelayDao {
    private final AgentOutboxRelayMapper mapper;

    @Override
    public List<AgentOutboxCandidate> selectCorruptCandidates(long now, int limit) {
        return mapper.selectCorruptCandidates(now, limit);
    }

    @Override
    public List<AgentOutboxCandidate> selectDueCandidates(long now, int limit) {
        return mapper.selectDueCandidates(now, limit);
    }

    @Override
    public List<AgentOutboxCandidate> selectStaleCandidates(long now, int limit) {
        return mapper.selectStaleCandidates(now, limit);
    }

    @Override
    public AgentCommandDeliveryEntity lockDelivery(
            String tenantId, String clientId, long deliveryId) {
        return mapper.selectDeliveryForUpdate(tenantId, clientId, deliveryId);
    }

    @Override
    public AgentOutboxEventEntity lockOutbox(
            String tenantId, String clientId, long outboxId) {
        return mapper.selectOutboxForUpdate(tenantId, clientId, outboxId);
    }

    @Override
    public List<AgentOutboxEventEntity> lockPreviousAttemptOutboxes(
            String tenantId, String clientId, long deliveryId, int previousAttempt) {
        return mapper.selectPreviousAttemptOutboxesForUpdate(
                tenantId, clientId, deliveryId, previousAttempt);
    }

    @Override
    public AgentOutboxEventEntity lockOutboxForQuarantine(AgentOutboxCandidate candidate) {
        return mapper.selectOutboxForQuarantine(
                candidate.outboxId(), candidate.deliveryId(), candidate.outboxVersion(),
                candidate.outboxStatus(), candidate.tenantId(), candidate.clientId());
    }

    @Override
    public int claimDelivery(
            AgentCommandDeliveryEntity delivery, String leaseOwner, long leaseUntil,
            String lastError, long now) {
        return mapper.claimDelivery(delivery, leaseOwner, leaseUntil, lastError, now);
    }

    @Override
    public int claimOutbox(
            AgentOutboxEventEntity outbox, String leaseOwner, long leaseUntil,
            String lastError, long now) {
        return mapper.claimOutbox(outbox, leaseOwner, leaseUntil, lastError, now);
    }

    @Override
    public int disposeDelivery(
            AgentCommandDeliveryEntity delivery, String newStatus, Long nextRetryAt,
            String lastError, long now) {
        return mapper.disposeDelivery(delivery, newStatus, nextRetryAt, lastError, now);
    }

    @Override
    public int disposeOutbox(
            AgentOutboxEventEntity outbox, String newStatus, Long nextRetryAt,
            String confirmStatus, Long confirmedAt, String confirmError,
            String returnStatus, Long returnedAt, Integer returnReplyCode,
            String returnReplyText, Long publishedAt, String lastError, long now) {
        return mapper.disposeOutbox(outbox, newStatus, nextRetryAt,
                confirmStatus, confirmedAt, confirmError, returnStatus, returnedAt,
                returnReplyCode, returnReplyText, publishedAt, lastError, now);
    }

    @Override
    public int quarantineDelivery(
            AgentCommandDeliveryEntity delivery, String errorCode, long now) {
        return mapper.quarantineDelivery(delivery, errorCode, now);
    }

    @Override
    public int quarantineOutbox(
            AgentOutboxEventEntity outbox, String errorCode, long now) {
        return mapper.quarantineOutbox(outbox, errorCode, now);
    }
}
