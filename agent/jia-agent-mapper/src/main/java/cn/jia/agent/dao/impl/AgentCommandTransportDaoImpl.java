package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentCommandTransportDao;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.agent.mapper.AgentCommandTransportMapper;
import jakarta.inject.Named;
import lombok.RequiredArgsConstructor;

@Named
@RequiredArgsConstructor
public class AgentCommandTransportDaoImpl implements AgentCommandTransportDao {
    private final AgentCommandTransportMapper mapper;

    @Override
    public AgentCommandDeliveryEntity lockDelivery(
            String tenantId, String clientId, String commandId) {
        return mapper.selectDeliveryForUpdate(tenantId, clientId, commandId);
    }

    @Override
    public int insertDelivery(AgentCommandDeliveryEntity delivery) {
        return mapper.insertDelivery(delivery);
    }

    @Override
    public int insertOutbox(AgentOutboxEventEntity outbox) {
        return mapper.insertOutbox(outbox);
    }
}
