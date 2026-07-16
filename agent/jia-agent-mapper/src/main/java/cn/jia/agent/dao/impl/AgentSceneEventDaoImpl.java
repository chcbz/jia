package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentSceneEventDao;
import cn.jia.agent.entity.AgentSceneEventDTO;
import cn.jia.agent.entity.AgentSceneEventEntity;
import cn.jia.agent.entity.AgentSceneStateDTO;
import cn.jia.agent.mapper.AgentSceneEventMapper;
import cn.jia.core.util.DateUtil;
import cn.jia.core.util.JsonUtil;
import cn.jia.core.util.StringUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Named
public class AgentSceneEventDaoImpl implements AgentSceneEventDao {
    private final AgentSceneEventMapper baseMapper;

    @Inject
    public AgentSceneEventDaoImpl(AgentSceneEventMapper baseMapper) {
        this.baseMapper = baseMapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long nextSceneVersion(String tenantId, String clientId, String sceneId) {
        requireScope(tenantId, clientId, sceneId);
        int affected = baseMapper.allocateNextVersion(
                tenantId, clientId, sceneId, DateUtil.nowTime());
        if (affected <= 0) {
            throw new IllegalStateException("Unable to allocate scoped scene version");
        }
        Long allocated = baseMapper.selectLastAllocatedVersion();
        if (allocated == null || allocated <= 0) {
            throw new IllegalStateException("Unable to read allocated scoped scene version");
        }
        return allocated;
    }

    @Override
    public Long findCurrentSceneVersion(String tenantId, String clientId, String sceneId) {
        requireScope(tenantId, clientId, sceneId);
        return baseMapper.selectCurrentVersion(tenantId, clientId, sceneId);
    }

    @Override
    public Long findLatestSceneVersion(String tenantId, String clientId, String sceneId) {
        AgentSceneEventEntity event = findBoundary(tenantId, clientId, sceneId, false);
        return event == null ? null : event.getSceneVersion();
    }

    @Override
    public Long findEarliestSceneVersion(String tenantId, String clientId, String sceneId) {
        AgentSceneEventEntity event = findBoundary(tenantId, clientId, sceneId, true);
        return event == null ? null : event.getSceneVersion();
    }

    @Override
    public List<AgentSceneEventEntity> findAfterVersion(
            String tenantId, String clientId, String sceneId, long sinceVersion, int limit) {
        requireScope(tenantId, clientId, sceneId);
        int boundedLimit = Math.max(1, Math.min(limit, 1000));
        return baseMapper.selectList(scope(tenantId, clientId, sceneId)
                .gt(AgentSceneEventEntity::getSceneVersion, sinceVersion)
                .orderByAsc(AgentSceneEventEntity::getSceneVersion)
                .last("limit " + boundedLimit));
    }

    @Override
    public int insert(
            String tenantId, String clientId, String sceneId, AgentSceneEventDTO event) {
        requireScope(tenantId, clientId, sceneId);
        if (event == null || event.getSceneVersion() == null || StringUtil.isBlank(event.getEventType())) {
            throw new IllegalArgumentException("scene event version and type are required");
        }
        AgentSceneEventDTO safeEvent = safeCopy(event);
        AgentSceneEventEntity entity = new AgentSceneEventEntity();
        entity.setSceneVersion(safeEvent.getSceneVersion());
        entity.setEventType(safeEvent.getEventType());
        entity.setOccurredAt(safeEvent.getOccurredAt());
        entity.setEventJson(serializeSafeEvent(safeEvent));
        entity.setTenantId(tenantId);
        entity.setClientId(clientId);
        entity.setSceneId(sceneId);
        entity.init4Creation();
        return baseMapper.insert(entity);
    }

    private AgentSceneEventDTO safeCopy(AgentSceneEventDTO source) {
        AgentSceneEventDTO safe = new AgentSceneEventDTO();
        safe.setSceneVersion(source.getSceneVersion());
        safe.setEventType(source.getEventType());
        safe.setOccurredAt(source.getOccurredAt());
        safe.setState(AgentSceneStateDTO.copyOf(source.getState()));
        return safe;
    }

    private String serializeSafeEvent(AgentSceneEventDTO safeEvent) {
        String json = JsonUtil.toSafeJson(safeEvent);
        if (StringUtil.isBlank(json)) {
            throw new IllegalArgumentException("Unable to serialize safe scene event");
        }
        return json;
    }

    private AgentSceneEventEntity findBoundary(
            String tenantId, String clientId, String sceneId, boolean ascending) {
        requireScope(tenantId, clientId, sceneId);
        LambdaQueryWrapper<AgentSceneEventEntity> wrapper = scope(tenantId, clientId, sceneId);
        if (ascending) {
            wrapper.orderByAsc(AgentSceneEventEntity::getSceneVersion);
        } else {
            wrapper.orderByDesc(AgentSceneEventEntity::getSceneVersion);
        }
        return baseMapper.selectOne(wrapper.last("limit 1"));
    }

    private LambdaQueryWrapper<AgentSceneEventEntity> scope(
            String tenantId, String clientId, String sceneId) {
        return new LambdaQueryWrapper<AgentSceneEventEntity>()
                .eq(AgentSceneEventEntity::getTenantId, tenantId)
                .eq(AgentSceneEventEntity::getClientId, clientId)
                .eq(AgentSceneEventEntity::getSceneId, sceneId);
    }

    private void requireScope(String tenantId, String clientId, String sceneId) {
        if (StringUtil.isBlank(tenantId) || StringUtil.isBlank(clientId) || StringUtil.isBlank(sceneId)) {
            throw new IllegalArgumentException("tenantId, clientId and sceneId are required");
        }
    }
}
