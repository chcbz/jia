package cn.jia.agent.dao;

import cn.jia.agent.entity.AbilityEvaluationEntity;
import cn.jia.core.dao.IBaseDao;

import java.util.List;

public interface AbilityEvaluationDao extends IBaseDao<AbilityEvaluationEntity> {
    List<AbilityEvaluationEntity> findByAgentName(String agentName);

    AbilityEvaluationEntity findLatestByAgentName(String agentName);
}
