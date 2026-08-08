package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentTaskEventDao;
import cn.jia.agent.entity.AgentTaskEventEntity;
import cn.jia.agent.mapper.AgentTaskEventMapper;
import cn.jia.common.dao.BaseDaoImpl;
import jakarta.inject.Named;

import java.util.List;

@Named
public class AgentTaskEventDaoImpl
        extends BaseDaoImpl<AgentTaskEventMapper, AgentTaskEventEntity>
        implements AgentTaskEventDao {

    @Override
    public Long lockAndAllocateVersion(String tenantId, String clientId, String taskId) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        requireExactId(taskId, "taskId");
        return baseMapper.lockTaskMetaForEventVersion(tenantId, clientId, taskId);
    }

    @Override
    public int insertEvent(AgentTaskEventEntity event) {
        requireEventEntity(event);
        return baseMapper.insertEvent(event);
    }

    @Override
    public int commitEventVersion(
            String tenantId, String clientId, String taskId,
            long expectedCurrentVersion, long newEventVersion,
            long updateTime) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        requireExactId(taskId, "taskId");
        if (expectedCurrentVersion < 0) {
            throw new IllegalArgumentException("expectedCurrentVersion must not be negative");
        }
        if (newEventVersion <= expectedCurrentVersion) {
            throw new IllegalArgumentException(
                    "newEventVersion must be greater than expectedCurrentVersion: "
                    + newEventVersion + " <= " + expectedCurrentVersion);
        }
        if (updateTime <= 0) {
            throw new IllegalArgumentException("updateTime must be positive");
        }
        return baseMapper.incrementCurrentEventVersion(
                tenantId, clientId, taskId,
                expectedCurrentVersion, newEventVersion, updateTime);
    }

    @Override
    public List<AgentTaskEventEntity> findByTaskScope(
            String tenantId, String clientId, String taskId) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        requireExactId(taskId, "taskId");
        return baseMapper.findExactByTaskScope(tenantId, clientId, taskId);
    }

    @Override
    public List<AgentTaskEventEntity> findByTaskScopeSince(
            String tenantId, String clientId, String taskId, long sinceVersion) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        requireExactId(taskId, "taskId");
        if (sinceVersion < 0) {
            throw new IllegalArgumentException("sinceVersion must not be negative");
        }
        return baseMapper.findExactByTaskScopeSince(
                tenantId, clientId, taskId, sinceVersion);
    }

    @Override
    public AgentTaskEventEntity findByEventId(
            String tenantId, String clientId, String eventId) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        requireExactId(eventId, "eventId");
        return baseMapper.findExactByEventId(tenantId, clientId, eventId);
    }

    private void requireExactId(String value, String name) {
        TaskCollaborationDaoSupport.requireId(value, name);
        if (value.length() > 100 || !value.equals(value.strip())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    name + " must be byte-exact, unpadded, and free of control characters");
        }
    }

    private void requireEventEntity(AgentTaskEventEntity event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        requireExactId(event.getTenantId(), "tenantId");
        requireExactId(event.getClientId(), "clientId");
        requireExactId(event.getTaskId(), "taskId");
        requireExactId(event.getEventId(), "eventId");
        if (event.getEventVersion() == null || event.getEventVersion() <= 0) {
            throw new IllegalArgumentException("eventVersion must be positive");
        }
        if (event.getEventType() == null || event.getEventType().isBlank()) {
            throw new IllegalArgumentException("eventType is required");
        }
        if (event.getActor() == null || event.getActor().isBlank()) {
            throw new IllegalArgumentException("actor is required");
        }
        if (event.getAggregateType() == null || event.getAggregateType().isBlank()) {
            throw new IllegalArgumentException("aggregateType is required");
        }
        if (event.getAggregateId() == null || event.getAggregateId().isBlank()) {
            throw new IllegalArgumentException("aggregateId is required");
        }
        if (event.getCreatedAt() == null || event.getCreatedAt() <= 0) {
            throw new IllegalArgumentException("createdAt must be positive");
        }
    }
}
