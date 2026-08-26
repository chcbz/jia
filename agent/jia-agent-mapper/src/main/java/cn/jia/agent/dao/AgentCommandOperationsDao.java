package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentCommandDlqEntry;
import cn.jia.agent.entity.AgentCommandMetricCount;
import cn.jia.agent.entity.AgentCommandOperationAuditEntity;
import cn.jia.agent.entity.AgentCommandOperationAuditEntry;
import cn.jia.agent.entity.AgentCommandRedriveOperationEntity;
import cn.jia.agent.entity.AgentCommandRedriveOperationState;
import cn.jia.agent.entity.AgentConsumerInboxEntity;
import cn.jia.agent.entity.AgentOutboxEventEntity;

import java.util.List;

public interface AgentCommandOperationsDao {
    List<AgentCommandMetricCount> countDeliveryStatuses(String tenantId, String clientId);
    List<AgentCommandMetricCount> countInboxStatuses(String tenantId, String clientId);
    List<AgentCommandMetricCount> countInboxResults(String tenantId, String clientId);
    List<AgentCommandMetricCount> countOutboxStatuses(String tenantId, String clientId);
    Double averageAckLatencySeconds(String tenantId, String clientId);
    List<AgentCommandMetricCount> countOperationOutcomes(String tenantId, String clientId);
    long countOutboxBacklog(String tenantId, String clientId);
    Long oldestOutboxEpoch(String tenantId, String clientId);
    long countPublishFailures(String tenantId, String clientId);
    long countDlq(String tenantId, String clientId, long now);
    long countWaitingDue(String tenantId, String clientId, long now);
    long countSentUnacknowledged(String tenantId, String clientId);
    long countReconnectQueueDepth(String tenantId, String clientId, long now);
    long countExpiryProximity(String tenantId, String clientId, long now, long before);
    List<AgentCommandDlqEntry> listDlq(
            String tenantId, String clientId, long afterDeliveryId, long now, int limit);
    List<AgentCommandOperationAuditEntry> listAudit(
            String tenantId, String clientId, long afterId, int limit);
    AgentCommandDeliveryEntity lockDelivery(String tenantId, String clientId, long deliveryId);
    List<AgentOutboxEventEntity> lockActiveOutboxes(
            String tenantId, String clientId, long deliveryId, String messageId);
    List<AgentOutboxEventEntity> lockPreviousAttemptOutboxes(
            String tenantId, String clientId, long deliveryId, int previousAttempt);
    AgentConsumerInboxEntity lockInbox(
            String tenantId, String clientId, String consumerName, String messageId);
    int insertPendingRedriveOperation(AgentCommandRedriveOperationEntity operation);
    AgentCommandRedriveOperationEntity lockRedriveOperation(
            String tenantId, String clientId, String operationId);
    List<AgentCommandRedriveOperationEntity> lockActiveRedriveOperations(
            String tenantId, String clientId, long deliveryId, String sourceMessageId,
            int sourceAttempt);
    List<AgentCommandRedriveOperationEntity> lockPendingRedriveOperations(
            String tenantId, String clientId, long requestedBefore, long afterId, int limit);
    int compareAndSetRedriveOperationTerminal(
            String tenantId, String clientId, String operationId,
            AgentCommandRedriveOperationState terminalState, String errorCode,
            long completedAt, long expectedVersion);
    int insertAudit(AgentCommandOperationAuditEntity audit);
}
