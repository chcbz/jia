package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentOutboxEventEntity;


/** D08 Hall extension for bounded MQ-shadow promotion; DB-shadow remains capture-only. */
public interface AgentHallCommandTransportDao extends AgentCommandTransportDao {
    int promoteShadowDelivery(AgentCommandDeliveryEntity delivery, String marker, long now);
    int promoteShadowOutbox(AgentOutboxEventEntity outbox, String marker, long now);
}
