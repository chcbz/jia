package cn.jia.agent.output.dao.impl;

import cn.jia.agent.output.dao.OutputRunBindingDao;
import cn.jia.agent.output.entity.OutputRunBindingEntity;
import cn.jia.agent.output.mapper.OutputRunBindingMapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;

@Named
public class OutputRunBindingDaoImpl implements OutputRunBindingDao {
    @Inject
    private OutputRunBindingMapper mapper;

    @Override
    public int insert(OutputRunBindingEntity entity) {
        return mapper.insert(entity);
    }

    @Override
    public OutputRunBindingEntity findExactByRun(
            String tenantId, String clientId, String runId, boolean forUpdate) {
        return mapper.findExactByRun(tenantId, clientId, runId, forUpdate);
    }

    @Override
    public OutputRunBindingEntity findExactByOrigin(
            String tenantId, String clientId, String sourceType, String sourceId,
            String producerAgentId, String originType, String originId) {
        return mapper.findExactByOrigin(tenantId, clientId, sourceType, sourceId,
                producerAgentId, originType, originId);
    }

}
