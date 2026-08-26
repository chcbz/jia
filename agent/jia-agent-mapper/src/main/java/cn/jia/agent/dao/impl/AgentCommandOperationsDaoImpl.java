package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentCommandOperationsDao;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentCommandMetricCount;
import cn.jia.agent.entity.AgentCommandOperationAuditEntity;
import cn.jia.agent.entity.AgentCommandOperationAuditEntry;
import cn.jia.agent.entity.AgentCommandRedriveOperationEntity;
import cn.jia.agent.entity.AgentCommandRedriveOperationState;
import cn.jia.agent.entity.AgentConsumerInboxEntity;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.agent.mapper.AgentCommandOperationsMapper;

import java.util.List;
import java.util.Objects;

public class AgentCommandOperationsDaoImpl implements AgentCommandOperationsDao {
    private final AgentCommandOperationsMapper mapper;

    public AgentCommandOperationsDaoImpl(AgentCommandOperationsMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override public List<AgentCommandMetricCount> countDeliveryStatuses(String tenantId, String clientId) { return mapper.countDeliveryStatuses(tenantId, clientId); }
    @Override public List<AgentCommandMetricCount> countInboxStatuses(String tenantId, String clientId) { return mapper.countInboxStatuses(tenantId, clientId); }
    @Override public List<AgentCommandMetricCount> countInboxResults(String tenantId, String clientId) { return mapper.countInboxResults(tenantId, clientId); }
    @Override public List<AgentCommandMetricCount> countOutboxStatuses(String tenantId, String clientId) { return mapper.countOutboxStatuses(tenantId, clientId); }
    @Override public Double averageAckLatencySeconds(String tenantId, String clientId) { return mapper.averageAckLatencySeconds(tenantId, clientId); }
    @Override public List<AgentCommandMetricCount> countOperationOutcomes(String tenantId, String clientId) { return mapper.countOperationOutcomes(tenantId, clientId); }
    @Override public long countOutboxBacklog(String tenantId, String clientId) { return mapper.countOutboxBacklog(tenantId, clientId); }
    @Override public Long oldestOutboxEpoch(String tenantId, String clientId) { return mapper.oldestOutboxEpoch(tenantId, clientId); }
    @Override public long countPublishFailures(String tenantId, String clientId) { return mapper.countPublishFailures(tenantId, clientId); }
    @Override public long countDlqBroad(String tenantId, String clientId, long now) { return mapper.countDlqBroad(tenantId, clientId, now); }
    @Override public long countWaitingDue(String tenantId, String clientId, long now) { return mapper.countWaitingDue(tenantId, clientId, now); }
    @Override public long countSentUnacknowledged(String tenantId, String clientId) { return mapper.countSentUnacknowledged(tenantId, clientId); }
    @Override public long countReconnectQueueDepth(String tenantId, String clientId, long now) { return mapper.countReconnectQueueDepth(tenantId, clientId, now); }
    @Override public long countExpiryProximity(String tenantId, String clientId, long now, long before) { return mapper.countExpiryProximity(tenantId, clientId, now, before); }
    @Override public List<AgentCommandDeliveryEntity> listDlqBroad(String tenantId, String clientId, long afterDeliveryId, long now, int limit) { return mapper.listDlqBroad(tenantId, clientId, afterDeliveryId, now, limit); }
    @Override public List<AgentOutboxEventEntity> selectActiveOutboxes(String tenantId, String clientId, long deliveryId, String messageId) { return mapper.selectActiveOutboxes(tenantId, clientId, deliveryId, messageId); }
    @Override public List<AgentOutboxEventEntity> selectCurrentAttemptOutboxes(String tenantId, String clientId, long deliveryId, int activeAttempt) { return mapper.selectCurrentAttemptOutboxes(tenantId, clientId, deliveryId, activeAttempt); }
    @Override public List<AgentOutboxEventEntity> selectPreviousAttemptOutboxes(String tenantId, String clientId, long deliveryId, int previousAttempt) { return mapper.selectPreviousAttemptOutboxes(tenantId, clientId, deliveryId, previousAttempt); }
    @Override public AgentConsumerInboxEntity selectInbox(String tenantId, String clientId, String consumerName, String messageId) { return mapper.selectInbox(tenantId, clientId, consumerName, messageId); }
    @Override public List<AgentCommandRedriveOperationEntity> selectActiveRedriveOperations(String tenantId, String clientId, long deliveryId, String sourceMessageId, int sourceAttempt) { return mapper.selectActiveRedriveOperations(tenantId, clientId, deliveryId, sourceMessageId, sourceAttempt); }
    @Override public List<AgentCommandOperationAuditEntry> listAudit(String tenantId, String clientId, long afterId, int limit) { return mapper.listAudit(tenantId, clientId, afterId, limit); }
    @Override public AgentCommandDeliveryEntity lockDelivery(String tenantId, String clientId, long deliveryId) { return mapper.lockDelivery(tenantId, clientId, deliveryId); }
    @Override public List<AgentOutboxEventEntity> lockActiveOutboxes(String tenantId, String clientId, long deliveryId, String messageId) { return mapper.lockActiveOutboxes(tenantId, clientId, deliveryId, messageId); }
    @Override public List<AgentOutboxEventEntity> lockCurrentAttemptOutboxes(String tenantId, String clientId, long deliveryId, int activeAttempt) { return mapper.lockCurrentAttemptOutboxes(tenantId, clientId, deliveryId, activeAttempt); }
    @Override public List<AgentOutboxEventEntity> lockPreviousAttemptOutboxes(String tenantId, String clientId, long deliveryId, int previousAttempt) { return mapper.lockPreviousAttemptOutboxes(tenantId, clientId, deliveryId, previousAttempt); }
    @Override public AgentConsumerInboxEntity lockInbox(String tenantId, String clientId, String consumerName, String messageId) { return mapper.lockInbox(tenantId, clientId, consumerName, messageId); }
    @Override public int insertPendingRedriveOperation(AgentCommandRedriveOperationEntity operation) { return mapper.insertPendingRedriveOperation(operation); }
    @Override public AgentCommandRedriveOperationEntity lockRedriveOperation(String tenantId, String clientId, String operationId) { return mapper.lockRedriveOperation(tenantId, clientId, operationId); }
    @Override public List<AgentCommandRedriveOperationEntity> lockActiveRedriveOperations(String tenantId, String clientId, long deliveryId, String sourceMessageId, int sourceAttempt) { return mapper.lockActiveRedriveOperations(tenantId, clientId, deliveryId, sourceMessageId, sourceAttempt); }
    @Override public List<AgentCommandRedriveOperationEntity> lockPendingRedriveOperations(String tenantId, String clientId, long requestedBefore, long afterId, int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Redrive recovery limit must be between 1 and 100");
        }
        return mapper.lockPendingRedriveOperations(tenantId, clientId, requestedBefore, afterId, limit);
    }
    @Override public int compareAndSetRedriveOperationTerminal(String tenantId, String clientId, String operationId, AgentCommandRedriveOperationState terminalState, String errorCode, long completedAt, long expectedVersion) {
        Objects.requireNonNull(terminalState, "terminalState");
        if (!terminalState.terminal()) {
            throw new IllegalArgumentException("Redrive terminal CAS requires a terminal state");
        }
        if (AgentCommandRedriveOperationState.SUCCEEDED.equals(terminalState)) {
            if (errorCode != null) {
                throw new IllegalArgumentException("Successful redrive terminal state must not have an error code");
            }
        } else if (errorCode == null || errorCode.isBlank() || errorCode.codePointCount(0, errorCode.length()) > 200) {
            throw new IllegalArgumentException("Failed redrive terminal state requires a bounded error code");
        }
        return mapper.compareAndSetRedriveOperationTerminal(
                tenantId, clientId, operationId, terminalState.outcome().name(),
                terminalState.settlement().name(), errorCode, completedAt, expectedVersion);
    }
    @Override public int insertAudit(AgentCommandOperationAuditEntity audit) { return mapper.insertAudit(audit); }
}
