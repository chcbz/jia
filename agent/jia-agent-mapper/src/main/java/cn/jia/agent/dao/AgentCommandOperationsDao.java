package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentCommandDlqEntry;
import cn.jia.agent.entity.AgentCommandMetricCount;
import cn.jia.agent.entity.AgentCommandOperationAuditEntity;
import cn.jia.agent.entity.AgentCommandOperationAuditEntry;
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
    long countDlq(String tenantId, String clientId);
    long countWaitingDue(String tenantId, String clientId, long now);
    long countSentUnacknowledged(String tenantId, String clientId);
    long countReconnectQueueDepth(String tenantId, String clientId, long now);
    long countExpiryProximity(String tenantId, String clientId, long now, long before);
    List<AgentCommandDlqEntry> listDlq(
            String tenantId, String clientId, long afterDeliveryId, int limit);
    List<AgentCommandOperationAuditEntry> listAudit(
            String tenantId, String clientId, long afterId, int limit);
    AgentCommandDeliveryEntity lockDelivery(String tenantId, String clientId, long deliveryId);
    List<AgentOutboxEventEntity> lockActiveOutboxes(
            String tenantId, String clientId, long deliveryId, String messageId);
    List<AgentOutboxEventEntity> lockPreviousAttemptOutboxes(
            String tenantId, String clientId, long deliveryId, int previousAttempt);
    AgentConsumerInboxEntity lockInbox(
            String tenantId, String clientId, String consumerName, String messageId);
    int insertAudit(AgentCommandOperationAuditEntity audit);
}
