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
        requireExactId(agentId, "agentId");
        return baseMapper.findExactByAgentId(agentId);
    }

    @Override
    public AgentRuntimeEntity findByAgentIdForUpdate(String agentId) {
        requireExactId(agentId, "agentId");
        return baseMapper.findExactByAgentIdForUpdate(agentId);
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
        requireExactId(clientId, "clientId");
        requireExactId(jiacn, "jiacn");
        return baseMapper.findActiveRosterByOwner(clientId, jiacn, status, ability);
    }

    @Override
    public int clearBindingAfterUnbind(long runtimeId, String agentId, long bindingId,
            String clientId, String ownerJiacn, long detachedAt) {
        if (runtimeId <= 0 || bindingId <= 0 || detachedAt <= 0) {
            throw new IllegalArgumentException("runtime detach coordinates are invalid");
        }
        requireExactId(agentId, "agentId");
        requireExactId(clientId, "clientId");
        requireExactId(ownerJiacn, "ownerJiacn");
        return baseMapper.clearBindingAfterUnbind(
                runtimeId, agentId, bindingId, clientId, ownerJiacn, detachedAt);
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
    private void requireExactId(String value, String field) {
        if (StringUtil.isBlank(value) || !value.equals(value.strip())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

}
