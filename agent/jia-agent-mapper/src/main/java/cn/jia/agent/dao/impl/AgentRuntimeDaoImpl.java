package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.mapper.AgentRuntimeMapper;
import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.core.util.StringUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.inject.Named;

import java.util.List;

@Named
public class AgentRuntimeDaoImpl extends BaseDaoImpl<AgentRuntimeMapper, AgentRuntimeEntity> implements AgentRuntimeDao {
    @Override
    public AgentRuntimeEntity findByAgentId(String agentId) {
        return baseMapper.selectOne(new LambdaQueryWrapper<AgentRuntimeEntity>()
                .eq(AgentRuntimeEntity::getAgentId, agentId)
                .last("limit 1"));
    }

    @Override
    public List<AgentRuntimeEntity> findByStatusAndAbility(String status, String ability) {
        LambdaQueryWrapper<AgentRuntimeEntity> wrapper = new LambdaQueryWrapper<>();
        if (!StringUtil.isBlank(status)) {
            wrapper.eq(AgentRuntimeEntity::getStatus, status);
        }
        if (!StringUtil.isBlank(ability)) {
            wrapper.like(AgentRuntimeEntity::getAbilities, "\"" + ability + "\"");
        }
        wrapper.orderByDesc(AgentRuntimeEntity::getLastSeenAt);
        return baseMapper.selectList(wrapper);
    }

    @Override
    public List<AgentRuntimeEntity> findRosterByOwner(String clientId, String jiacn, String status, String ability) {
        LambdaQueryWrapper<AgentRuntimeEntity> wrapper = new LambdaQueryWrapper<AgentRuntimeEntity>()
                .eq(AgentRuntimeEntity::getClientId, clientId)
                .eq(AgentRuntimeEntity::getOwnerJiacn, jiacn);
        if (!StringUtil.isBlank(status)) {
            wrapper.eq(AgentRuntimeEntity::getStatus, status);
        }
        if (!StringUtil.isBlank(ability)) {
            wrapper.like(AgentRuntimeEntity::getAbilities, "\"" + ability + "\"");
        }
        wrapper.orderByAsc(AgentRuntimeEntity::getPersonaCode);
        return baseMapper.selectList(wrapper);
    }

    @Override
    public List<AgentRuntimeEntity> findMapVisible(String clientId) {
        return baseMapper.selectList(new LambdaQueryWrapper<AgentRuntimeEntity>()
                .eq(AgentRuntimeEntity::getClientId, clientId)
                .in(AgentRuntimeEntity::getStatus, AgentConstants.STATUS_ONLINE, AgentConstants.STATUS_BUSY)
                .orderByDesc(AgentRuntimeEntity::getLastSeenAt));
    }

    @Override
    public List<AgentRuntimeEntity> findHeartbeatTimedOut(long cutoffTime) {
        return baseMapper.selectList(new LambdaQueryWrapper<AgentRuntimeEntity>()
                .ne(AgentRuntimeEntity::getStatus, "offline")
                .ne(AgentRuntimeEntity::getAgentId, AgentConstants.BUILTIN_SONGJIANG_AGENT_ID)
                .and(wrapper -> wrapper
                        .isNull(AgentRuntimeEntity::getLastSeenAt)
                        .or()
                        .lt(AgentRuntimeEntity::getLastSeenAt, cutoffTime))
                .orderByAsc(AgentRuntimeEntity::getLastSeenAt));
    }
}
