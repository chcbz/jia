package cn.jia.agent.dao;

import cn.jia.agent.entity.SkillExecutionEntity;

/** Data access only; runtime fencing, ACL and paired PWE transaction belong to the service. */
public interface SkillExecutionDao {
    void insertReady(SkillExecutionEntity row);
    SkillExecutionEntity find(String tenant, String client, String owner, String execution);
    SkillExecutionEntity lock(String tenant, String client, String owner, String execution);
    boolean compareAndSet(SkillExecutionEntity row, long expectedVersion, String expectedPhase);
}
