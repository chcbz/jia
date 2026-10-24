package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentHallCommandTransportDao;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.agent.mapper.AgentCommandTransportMapper;
import jakarta.inject.Named;
import lombok.RequiredArgsConstructor;

import java.util.List;

@Named
@RequiredArgsConstructor
public class AgentCommandTransportDaoImpl implements AgentHallCommandTransportDao {
    private final AgentCommandTransportMapper mapper;

    @Override
    public AgentCommandDeliveryEntity lockDelivery(
            String tenantId, String clientId, String ownerJiacn, String commandId) {
        return mapper.selectDeliveryForUpdate(tenantId, clientId, ownerJiacn, commandId);
    }


    @Override
    public AgentCommandDeliveryEntity lockDeliveryV2(
            String tenantId, String clientId, String ownerJiacn, String commandId) {
        return mapper.selectDeliveryForUpdateV2(tenantId, clientId, ownerJiacn, commandId);
    }

    @Override
    public List<AgentOutboxEventEntity> lockActiveOutboxes(
            String tenantId, String clientId, long deliveryId, String messageId) {
        return mapper.selectActiveOutboxesForUpdate(
                tenantId, clientId, deliveryId, messageId);
    }

    @Override
    public int promoteShadowDelivery(
            AgentCommandDeliveryEntity delivery, String marker, long now) {
        return mapper.promoteShadowDelivery(delivery, marker, now);
    }

    @Override
    public int promoteShadowOutbox(
            AgentOutboxEventEntity outbox, String marker, long now) {
        return mapper.promoteShadowOutbox(outbox, marker, now);
    }

    @Override
    public int insertDelivery(AgentCommandDeliveryEntity delivery) {
        return mapper.insertDelivery(delivery);
    }


    @Override
    public int insertDeliveryV2(AgentCommandDeliveryEntity delivery) {
        return mapper.insertDeliveryV2(delivery);
    }

    @Override
    public int insertOutbox(AgentOutboxEventEntity outbox) {
        return mapper.insertOutbox(outbox);
    }


    @Override
    public int insertOutboxV2(AgentOutboxEventEntity outbox) {
        return mapper.insertOutboxV2(outbox);
    }
}
