package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.SkillExecutionDao;
import cn.jia.agent.entity.SkillExecutionEntity;
import cn.jia.agent.mapper.SkillExecutionMapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.util.Objects;

@Named
public final class SkillExecutionDaoImpl implements SkillExecutionDao {
    private final SkillExecutionMapper mapper;

    @Inject public SkillExecutionDaoImpl(SkillExecutionMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    public void insertReady(SkillExecutionEntity row) {
        Objects.requireNonNull(row);
        if (mapper.insertReady(row) != 1) throw new IllegalStateException("Skill attachment insert failed");
    }

    public SkillExecutionEntity find(String tenant, String client, String owner, String execution) {
        return mapper.find(tenant, client, owner, execution);
    }

    public SkillExecutionEntity lock(String tenant, String client, String owner, String execution) {
        return mapper.lock(tenant, client, owner, execution);
    }

    public int bindTaskSkill(String tenant,String client,String owner,String execution,String snapshot,long version,String binding,long now) {
        return mapper.bindTaskSkill(tenant,client,owner,execution,snapshot,version,binding,now);
    }
    public boolean compareAndSet(SkillExecutionEntity row, long expectedVersion, String expectedPhase) {
        Objects.requireNonNull(row);
        if (expectedVersion < 0) throw new IllegalArgumentException("Negative skill attachment version");
        return mapper.compareAndSet(row.getTenantId(), row.getClientId(), row.getOwnerJiacn(),
                row.getExecutionId(), expectedVersion, Objects.requireNonNull(expectedPhase), row) == 1;
    }
}
