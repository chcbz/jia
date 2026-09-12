package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentSceneStateDao;
import cn.jia.agent.entity.AgentSceneStateEntity;
import cn.jia.agent.mapper.AgentSceneSnapshotRow;
import cn.jia.agent.mapper.AgentSceneStateMapper;
import cn.jia.core.util.StringUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.util.List;

@Named
public class AgentSceneStateDaoImpl implements AgentSceneStateDao {
    private final AgentSceneStateMapper baseMapper;

    @Inject
    public AgentSceneStateDaoImpl(AgentSceneStateMapper baseMapper) {
        this.baseMapper = baseMapper;
    }

    @Override
    public AgentSceneStateEntity findByAgent(
            String tenantId, String clientId, String sceneId, String agentId) {
        requireScope(tenantId, clientId, sceneId);
        if (StringUtil.isBlank(agentId)) {
            throw new IllegalArgumentException("agentId is required");
        }
        return baseMapper.selectOne(scope(tenantId, clientId, sceneId)
                .eq(AgentSceneStateEntity::getAgentId, agentId)
                .last("limit 1"));
    }

    @Override
    public List<AgentSceneStateEntity> findActiveByScene(
            String tenantId, String clientId, String sceneId, long activeAt) {
        requireScope(tenantId, clientId, sceneId);
        return baseMapper.selectList(scope(tenantId, clientId, sceneId)
                .and(wrapper -> wrapper.isNull(AgentSceneStateEntity::getExpiresAt)
                        .or().gt(AgentSceneStateEntity::getExpiresAt, activeAt))
                .orderByAsc(AgentSceneStateEntity::getAgentId));
    }

    @Override
    public List<AgentSceneSnapshotRow> findSnapshotRows(
            String tenantId, String clientId, String sceneId, long activeAt) {
        requireScope(tenantId, clientId, sceneId);
        if (activeAt < 0) {
            throw new IllegalArgumentException("activeAt must be nonnegative");
        }
        return baseMapper.selectSnapshotRows(tenantId, clientId, sceneId, activeAt);
    }

    @Override
    public int upsert(
            String tenantId, String clientId, String sceneId, AgentSceneStateEntity entity) {
        requireScope(tenantId, clientId, sceneId);
        if (entity == null || StringUtil.isBlank(entity.getAgentId()) || entity.getStateVersion() == null) {
            throw new IllegalArgumentException("scene state, agentId and stateVersion are required");
        }
        applyScope(entity, tenantId, clientId, sceneId);
        entity.init4Creation();
        return baseMapper.upsertMonotonic(tenantId, clientId, sceneId, entity);
    }

    @Override
    public int updatePhase(String tenantId, String clientId, String sceneId, String agentId,
            long stateVersion, String phase, long updateTime) {
        requireScope(tenantId, clientId, sceneId);
        if (StringUtil.isBlank(agentId) || StringUtil.isBlank(phase)) {
            throw new IllegalArgumentException("agentId and phase are required");
        }
        AgentSceneStateEntity update = new AgentSceneStateEntity();
        update.setPhase(phase);
        update.setUpdateTime(updateTime);
        return baseMapper.update(update, scopeUpdate(tenantId, clientId, sceneId)
                .eq(AgentSceneStateEntity::getAgentId, agentId)
                .eq(AgentSceneStateEntity::getStateVersion, stateVersion));
    }

    private LambdaQueryWrapper<AgentSceneStateEntity> scope(
            String tenantId, String clientId, String sceneId) {
        return new LambdaQueryWrapper<AgentSceneStateEntity>()
                .eq(AgentSceneStateEntity::getTenantId, tenantId)
                .eq(AgentSceneStateEntity::getClientId, clientId)
                .eq(AgentSceneStateEntity::getSceneId, sceneId);
    }

    private LambdaUpdateWrapper<AgentSceneStateEntity> scopeUpdate(
            String tenantId, String clientId, String sceneId) {
        return new LambdaUpdateWrapper<AgentSceneStateEntity>()
                .eq(AgentSceneStateEntity::getTenantId, tenantId)
                .eq(AgentSceneStateEntity::getClientId, clientId)
                .eq(AgentSceneStateEntity::getSceneId, sceneId);
    }

    private void applyScope(AgentSceneStateEntity entity, String tenantId, String clientId, String sceneId) {
        entity.setTenantId(tenantId);
        entity.setClientId(clientId);
        entity.setSceneId(sceneId);
    }

    private void requireScope(String tenantId, String clientId, String sceneId) {
        if (StringUtil.isBlank(tenantId) || StringUtil.isBlank(clientId) || StringUtil.isBlank(sceneId)) {
            throw new IllegalArgumentException("tenantId, clientId and sceneId are required");
        }
    }
}
