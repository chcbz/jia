package cn.jia.agent.output.dao;

import cn.jia.agent.output.entity.OutputAccessTicketEntity;

import java.util.List;

public interface OutputAccessTicketDao {
    int insert(OutputAccessTicketEntity entity);

    OutputAccessTicketEntity findByHash(byte[] ticketHash, boolean forUpdate);

    List<byte[]> lockRecentHashesForBinding(
            String tenantId, String clientId, String bindingId, long since);
}
