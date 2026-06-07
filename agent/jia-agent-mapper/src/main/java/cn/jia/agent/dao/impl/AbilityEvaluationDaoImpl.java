package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AbilityEvaluationDao;
import cn.jia.agent.entity.AbilityEvaluationEntity;
import cn.jia.agent.mapper.AbilityEvaluationMapper;
import cn.jia.common.dao.BaseDaoImpl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.inject.Named;

import java.util.List;

@Named
public class AbilityEvaluationDaoImpl extends BaseDaoImpl<AbilityEvaluationMapper, AbilityEvaluationEntity>
        implements AbilityEvaluationDao {
    @Override
    public List<AbilityEvaluationEntity> findByAgentName(String agentName) {
        return baseMapper.selectList(new LambdaQueryWrapper<AbilityEvaluationEntity>()
                .eq(AbilityEvaluationEntity::getAgentName, agentName)
                .orderByDesc(AbilityEvaluationEntity::getEvaluationTime));
    }

    @Override
    public AbilityEvaluationEntity findLatestByAgentName(String agentName) {
        return baseMapper.selectOne(new LambdaQueryWrapper<AbilityEvaluationEntity>()
                .eq(AbilityEvaluationEntity::getAgentName, agentName)
                .orderByDesc(AbilityEvaluationEntity::getEvaluationTime)
                .last("limit 1"));
    }
}
