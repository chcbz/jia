package cn.jia.agent.output.dao.impl;

import cn.jia.agent.output.dao.OutputAccessTicketDao;
import cn.jia.agent.output.entity.OutputAccessTicketEntity;
import cn.jia.agent.output.mapper.OutputAccessTicketMapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.util.List;

@Named
public class OutputAccessTicketDaoImpl implements OutputAccessTicketDao {
    @Inject
    private OutputAccessTicketMapper mapper;

    @Override
    public int insert(OutputAccessTicketEntity entity) {
        return mapper.insert(entity);
    }

    @Override
    public OutputAccessTicketEntity findByHash(byte[] ticketHash, boolean forUpdate) {
        return mapper.findByHash(ticketHash, forUpdate);
    }

    @Override
    public List<byte[]> lockRecentHashesForBinding(
            String tenantId, String clientId, String bindingId, long since) {
        return mapper.lockRecentHashesForBinding(tenantId, clientId, bindingId, since);
    }
}
