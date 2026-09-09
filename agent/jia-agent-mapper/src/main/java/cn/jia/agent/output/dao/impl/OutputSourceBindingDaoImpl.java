package cn.jia.agent.output.dao.impl;

import cn.jia.agent.output.dao.OutputSourceBindingDao;
import cn.jia.agent.output.entity.OutputSourceBindingEntity;
import cn.jia.agent.output.mapper.OutputSourceBindingMapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;

@Named
public class OutputSourceBindingDaoImpl implements OutputSourceBindingDao {
    @Inject
    private OutputSourceBindingMapper mapper;

    @Override
    public int insert(OutputSourceBindingEntity entity) {
        return mapper.insert(entity);
    }

    @Override
    public OutputSourceBindingEntity findExact(
            String tenantId, String clientId, String sourceType, String sourceId, boolean forUpdate) {
        return mapper.findExact(tenantId, clientId, sourceType, sourceId, forUpdate);
    }
}
