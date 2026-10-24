package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentOutboxRelayDao;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentOutboxCandidate;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.agent.mapper.AgentOutboxRelayMapper;
import cn.jia.core.tenant.WorkerScope;
import jakarta.inject.Named;
import lombok.RequiredArgsConstructor;

import java.util.List;

@Named
@RequiredArgsConstructor
public class AgentOutboxRelayDaoImpl implements AgentOutboxRelayDao {
    private final AgentOutboxRelayMapper mapper;

    @Override
    public List<AgentOutboxCandidate> selectCorruptCandidates(WorkerScope s, long now, int limit) {
        return mapper.selectCorruptCandidates(tenant(s), domain(s), s.executionPoolId(),
                s.admissionEpoch(), s.moduleId(), s.migrationEpoch(), s.deploymentId(),
                s.workerGeneration(), now, limit);
    }

    @Override
    public List<AgentOutboxCandidate> selectDueCandidates(WorkerScope s, long now, int limit) {
        return mapper.selectDueCandidates(tenant(s), domain(s), s.executionPoolId(),
                s.admissionEpoch(), s.moduleId(), s.migrationEpoch(), s.deploymentId(),
                s.workerGeneration(), now, limit);
    }

    @Override
    public List<AgentOutboxCandidate> selectStaleCandidates(WorkerScope s, long now, int limit) {
        return mapper.selectStaleCandidates(tenant(s), domain(s), s.executionPoolId(),
                s.admissionEpoch(), s.moduleId(), s.migrationEpoch(), s.deploymentId(),
                s.workerGeneration(), now, limit);
    }

    @Override
    public AgentCommandDeliveryEntity lockDelivery(WorkerScope s, long deliveryId) {
        return mapper.selectDeliveryForUpdate(tenant(s), domain(s), s.executionPoolId(),
                s.admissionEpoch(), s.moduleId(), s.migrationEpoch(), deliveryId);
    }

    @Override
    public AgentOutboxEventEntity lockOutbox(WorkerScope s, long outboxId) {
        return mapper.selectOutboxForUpdate(tenant(s), domain(s), s.executionPoolId(),
                s.admissionEpoch(), s.moduleId(), s.migrationEpoch(), outboxId);
    }

    @Override
    public List<AgentOutboxEventEntity> lockPreviousAttemptOutboxes(
            WorkerScope s, long deliveryId, int previousAttempt) {
        return mapper.selectPreviousAttemptOutboxesForUpdate(tenant(s), domain(s),
                s.executionPoolId(), s.admissionEpoch(), s.moduleId(), s.migrationEpoch(),
                deliveryId, previousAttempt);
    }

    @Override
    public AgentOutboxEventEntity lockOutboxForQuarantine(
            WorkerScope s, AgentOutboxCandidate candidate) {
        return mapper.selectOutboxForQuarantine(tenant(s), domain(s), s.executionPoolId(),
                s.admissionEpoch(), s.moduleId(), s.migrationEpoch(), s.deploymentId(),
                s.workerGeneration(), candidate);
    }

    @Override
    public int claimDelivery(WorkerScope s, AgentCommandDeliveryEntity delivery,
            String leaseOwner, long leaseUntil, String lastError, long now) {
        return mapper.claimDelivery(tenant(s), domain(s), s.executionPoolId(), s.admissionEpoch(),
                s.moduleId(), s.migrationEpoch(), s.deploymentId(), s.workerGeneration(),
                delivery, leaseOwner, leaseUntil, lastError, now);
    }

    @Override
    public int claimOutbox(WorkerScope s, AgentOutboxEventEntity outbox,
            String leaseOwner, long leaseUntil, String lastError, long now) {
        return mapper.claimOutbox(tenant(s), domain(s), s.executionPoolId(), s.admissionEpoch(),
                s.moduleId(), s.migrationEpoch(), s.deploymentId(), s.workerGeneration(),
                outbox, leaseOwner, leaseUntil, lastError, now);
    }

    @Override
    public int disposeDelivery(WorkerScope s, AgentCommandDeliveryEntity delivery,
            String newStatus, Long nextRetryAt, String lastError, long now) {
        return mapper.disposeDelivery(tenant(s), domain(s), s.executionPoolId(), s.admissionEpoch(),
                s.moduleId(), s.migrationEpoch(), s.deploymentId(), s.workerGeneration(),
                delivery, newStatus, nextRetryAt, lastError, now);
    }

    @Override
    public int disposeOutbox(WorkerScope s, AgentOutboxEventEntity outbox,
            String newStatus, Long nextRetryAt, String confirmStatus, Long confirmedAt,
            String confirmError, String returnStatus, Long returnedAt, Integer returnReplyCode,
            String returnReplyText, Long publishedAt, String lastError, long now) {
        return mapper.disposeOutbox(tenant(s), domain(s), s.executionPoolId(), s.admissionEpoch(),
                s.moduleId(), s.migrationEpoch(), s.deploymentId(), s.workerGeneration(), outbox,
                newStatus, nextRetryAt, confirmStatus, confirmedAt, confirmError, returnStatus,
                returnedAt, returnReplyCode, returnReplyText, publishedAt, lastError, now);
    }

    @Override
    public int quarantineDelivery(WorkerScope s, AgentCommandDeliveryEntity delivery,
            String errorCode, long now) {
        return mapper.quarantineDelivery(tenant(s), domain(s), s.executionPoolId(),
                s.admissionEpoch(), s.moduleId(), s.migrationEpoch(), s.deploymentId(),
                s.workerGeneration(), delivery, errorCode, now);
    }

    @Override
    public int quarantineOutbox(WorkerScope s, AgentOutboxEventEntity outbox,
            String errorCode, long now) {
        return mapper.quarantineOutbox(tenant(s), domain(s), s.executionPoolId(),
                s.admissionEpoch(), s.moduleId(), s.migrationEpoch(), s.deploymentId(),
                s.workerGeneration(), outbox, errorCode, now);
    }

    private static String tenant(WorkerScope scope) { return scope.tenantId().value(); }
    private static String domain(WorkerScope scope) { return scope.dataDomainId().value(); }

    @Override public List<AgentOutboxCandidate> selectCorruptCandidates(long now, int limit) { throw legacy(); }
    @Override public List<AgentOutboxCandidate> selectDueCandidates(long now, int limit) { throw legacy(); }
    @Override public List<AgentOutboxCandidate> selectStaleCandidates(long now, int limit) { throw legacy(); }
    @Override public AgentCommandDeliveryEntity lockDelivery(String t, String c, long id) { throw legacy(); }
    @Override public AgentOutboxEventEntity lockOutbox(String t, String c, long id) { throw legacy(); }
    @Override public List<AgentOutboxEventEntity> lockPreviousAttemptOutboxes(String t, String c, long id, int a) { throw legacy(); }
    @Override public AgentOutboxEventEntity lockOutboxForQuarantine(AgentOutboxCandidate c) { throw legacy(); }
    @Override public int claimDelivery(AgentCommandDeliveryEntity d, String o, long u, String e, long n) { throw legacy(); }
    @Override public int claimOutbox(AgentOutboxEventEntity d, String o, long u, String e, long n) { throw legacy(); }
    @Override public int disposeDelivery(AgentCommandDeliveryEntity d, String s, Long r, String e, long n) { throw legacy(); }
    @Override public int disposeOutbox(AgentOutboxEventEntity o, String s, Long r, String c, Long ca, String ce, String rs, Long ra, Integer rc, String rt, Long p, String e, long n) { throw legacy(); }
    @Override public int quarantineDelivery(AgentCommandDeliveryEntity d, String e, long n) { throw legacy(); }
    @Override public int quarantineOutbox(AgentOutboxEventEntity o, String e, long n) { throw legacy(); }

    private static UnsupportedOperationException legacy() {
        return new UnsupportedOperationException("Unscoped Agent relay DAO is disabled");
    }
}
