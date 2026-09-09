package cn.jia.agent.output.dao;

import cn.jia.agent.output.entity.OutputSourceBindingEntity;
public interface OutputSourceBindingDao {
    int insert(OutputSourceBindingEntity entity);

    OutputSourceBindingEntity findExact(
            String tenantId, String clientId, String sourceType, String sourceId, boolean forUpdate);
}
