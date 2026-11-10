package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentOutboxEventEntity;

public interface AgentCommandTransportDao {
    AgentCommandDeliveryEntity lockDelivery(
            String tenantId, String clientId, String ownerJiacn, String commandId);
    java.util.List<AgentOutboxEventEntity> lockActiveOutboxes(
            String tenantId, String clientId, long deliveryId, String messageId);
    int insertDelivery(AgentCommandDeliveryEntity delivery);
    int insertOutbox(AgentOutboxEventEntity outbox);
}
