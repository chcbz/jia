package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentOutboxEventEntity;

public interface AgentCommandTransportDao {
    AgentCommandDeliveryEntity lockDelivery(String tenantId, String clientId, String commandId);
    int insertDelivery(AgentCommandDeliveryEntity delivery);
    int insertOutbox(AgentOutboxEventEntity outbox);
}
