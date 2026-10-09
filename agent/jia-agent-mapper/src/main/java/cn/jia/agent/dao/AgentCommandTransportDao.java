package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentOutboxEventEntity;

public interface AgentCommandTransportDao {
    AgentCommandDeliveryEntity lockDelivery(
            String tenantId, String clientId, String ownerJiacn, String commandId);
    default AgentCommandDeliveryEntity lockDeliveryV2(
            String tenantId, String clientId, String ownerJiacn, String commandId) {
        throw new UnsupportedOperationException("protocol v2 transport is unavailable");
    }
    int insertDelivery(AgentCommandDeliveryEntity delivery);
    default int insertDeliveryV2(AgentCommandDeliveryEntity delivery) {
        throw new UnsupportedOperationException("protocol v2 transport is unavailable");
    }
    int insertOutbox(AgentOutboxEventEntity outbox);
    default int insertOutboxV2(AgentOutboxEventEntity outbox) {
        throw new UnsupportedOperationException("protocol v2 transport is unavailable");
    }
}
