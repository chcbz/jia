package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentOutboxEventEntity;

import java.util.List;

public interface AgentCommandTransportDao {
    AgentCommandDeliveryEntity lockDelivery(String tenantId, String clientId, String commandId);
    List<AgentOutboxEventEntity> lockActiveOutboxes(
            String tenantId, String clientId, long deliveryId, String messageId);
    int promoteShadowDelivery(AgentCommandDeliveryEntity delivery, String marker, long now);
    int promoteShadowOutbox(AgentOutboxEventEntity outbox, String marker, long now);
    int insertDelivery(AgentCommandDeliveryEntity delivery);
    int insertOutbox(AgentOutboxEventEntity outbox);
}
