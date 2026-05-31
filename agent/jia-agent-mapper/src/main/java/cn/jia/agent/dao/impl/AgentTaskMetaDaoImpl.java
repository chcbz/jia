package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.mapper.AgentTaskMetaMapper;
import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.core.util.StringUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.inject.Named;

import java.util.List;

@Named
public class AgentTaskMetaDaoImpl extends BaseDaoImpl<AgentTaskMetaMapper, AgentTaskMetaEntity> implements AgentTaskMetaDao {
    @Override
    public AgentTaskMetaEntity findByTaskId(String taskId) {
        return baseMapper.selectOne(new LambdaQueryWrapper<AgentTaskMetaEntity>()
                .eq(AgentTaskMetaEntity::getTaskId, taskId)
                .last("limit 1"));
    }

    @Override
    public List<AgentTaskMetaEntity> findByAgentId(String agentId) {
        return baseMapper.selectList(new LambdaQueryWrapper<AgentTaskMetaEntity>()
                .eq(AgentTaskMetaEntity::getAssignedAgentId, agentId)
                .orderByDesc(AgentTaskMetaEntity::getUpdateTime));
    }

    @Override
    public List<AgentTaskMetaEntity> search(String status, String ability) {
        LambdaQueryWrapper<AgentTaskMetaEntity> wrapper = new LambdaQueryWrapper<>();
        if (!StringUtil.isBlank(status)) {
            wrapper.eq(AgentTaskMetaEntity::getRewardStatus, status);
        }
        if (!StringUtil.isBlank(ability)) {
            wrapper.like(AgentTaskMetaEntity::getRequiredAbilities, "\"" + ability + "\"");
        }
        wrapper.orderByDesc(AgentTaskMetaEntity::getUpdateTime);
        return baseMapper.selectList(wrapper);
    }
}
