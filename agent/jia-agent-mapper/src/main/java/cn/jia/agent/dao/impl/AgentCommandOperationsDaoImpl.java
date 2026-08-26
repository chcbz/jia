package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentCommandOperationsDao;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentCommandDlqEntry;
import cn.jia.agent.entity.AgentCommandMetricCount;
import cn.jia.agent.entity.AgentCommandOperationAuditEntity;
import cn.jia.agent.entity.AgentCommandOperationAuditEntry;
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
    @Override public long countDlq(String tenantId, String clientId, long now) { return mapper.countDlq(tenantId, clientId, now); }
    @Override public long countWaitingDue(String tenantId, String clientId, long now) { return mapper.countWaitingDue(tenantId, clientId, now); }
    @Override public long countSentUnacknowledged(String tenantId, String clientId) { return mapper.countSentUnacknowledged(tenantId, clientId); }
    @Override public long countReconnectQueueDepth(String tenantId, String clientId, long now) { return mapper.countReconnectQueueDepth(tenantId, clientId, now); }
    @Override public long countExpiryProximity(String tenantId, String clientId, long now, long before) { return mapper.countExpiryProximity(tenantId, clientId, now, before); }
    @Override public List<AgentCommandDlqEntry> listDlq(String tenantId, String clientId, long afterDeliveryId, long now, int limit) { return mapper.listDlq(tenantId, clientId, afterDeliveryId, now, limit); }
    @Override public List<AgentCommandOperationAuditEntry> listAudit(String tenantId, String clientId, long afterId, int limit) { return mapper.listAudit(tenantId, clientId, afterId, limit); }
    @Override public AgentCommandDeliveryEntity lockDelivery(String tenantId, String clientId, long deliveryId) { return mapper.lockDelivery(tenantId, clientId, deliveryId); }
    @Override public List<AgentOutboxEventEntity> lockActiveOutboxes(String tenantId, String clientId, long deliveryId, String messageId) { return mapper.lockActiveOutboxes(tenantId, clientId, deliveryId, messageId); }
    @Override public List<AgentOutboxEventEntity> lockPreviousAttemptOutboxes(String tenantId, String clientId, long deliveryId, int previousAttempt) { return mapper.lockPreviousAttemptOutboxes(tenantId, clientId, deliveryId, previousAttempt); }
    @Override public AgentConsumerInboxEntity lockInbox(String tenantId, String clientId, String consumerName, String messageId) { return mapper.lockInbox(tenantId, clientId, consumerName, messageId); }
    @Override public int insertAudit(AgentCommandOperationAuditEntity audit) { return mapper.insertAudit(audit); }
}
