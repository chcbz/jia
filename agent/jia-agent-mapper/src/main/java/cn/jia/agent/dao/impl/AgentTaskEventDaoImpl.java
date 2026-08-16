package cn.jia.agent.dao.impl;

import cn.jia.agent.common.TaskEventPayload;
import cn.jia.agent.common.TaskEventType;
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
        requireExactId(taskId, "taskId", 100);
        return baseMapper.lockTaskMetaForEventVersion(tenantId, clientId, taskId);
    }

    @Override
    public int insertEvent(AgentTaskEventEntity event) {
        requireEventEntity(event);
        event.setEventJson(TaskEventPayload.normalizeAllowedJson(event.getEventJson()));
        return baseMapper.insertEvent(event);
    }

    @Override
    public int commitEventVersion(
            String tenantId, String clientId, String taskId,
            long expectedCurrentVersion, long newEventVersion,
            long updateTime) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        requireExactId(taskId, "taskId", 100);
        if (expectedCurrentVersion < 0) {
            throw new IllegalArgumentException("expectedCurrentVersion must not be negative");
        }
        if (expectedCurrentVersion == Long.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "current_event_version has reached Long.MAX_VALUE; cannot allocate further versions");
        }
        if (newEventVersion != expectedCurrentVersion + 1) {
            throw new IllegalArgumentException(
                    "newEventVersion must be exactly expectedCurrentVersion + 1: "
                    + newEventVersion + " != " + expectedCurrentVersion + " + 1");
        }
        if (updateTime <= 0) {
            throw new IllegalArgumentException("updateTime must be positive");
        }
        return baseMapper.incrementCurrentEventVersion(
                tenantId, clientId, taskId,
                expectedCurrentVersion, newEventVersion, updateTime);
    }

    @Override
    public List<AgentTaskEventEntity> findAfterVersion(
            String tenantId, String clientId, String taskId, long afterVersion, int limit) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        requireExactId(taskId, "taskId", 100);
        if (afterVersion < 0) {
            throw new IllegalArgumentException("afterVersion must not be negative");
        }
        if (limit <= 0 || limit > MAX_REPLAY_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and " + MAX_REPLAY_PAGE_SIZE);
        }
        return baseMapper.findExactByTaskScopeAfterVersion(
                tenantId, clientId, taskId, afterVersion, limit);
    }

    @Override
    public Long findCurrentVersion(String tenantId, String clientId, String taskId) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        requireExactId(taskId, "taskId", 100);
        return baseMapper.findExactCurrentEventVersion(tenantId, clientId, taskId);
    }

    @Override
    public Long findEarliestVersion(String tenantId, String clientId, String taskId) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        requireExactId(taskId, "taskId", 100);
        return baseMapper.findExactEarliestEventVersion(tenantId, clientId, taskId);
    }

    @Override
    public AgentTaskEventEntity findByEventId(
            String tenantId, String clientId, String eventId) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        requireExactId(eventId, "eventId", 100);
        return baseMapper.findExactByEventId(tenantId, clientId, eventId);
    }

    private void requireExactId(String value, String name, int maxLength) {
        TaskCollaborationDaoSupport.requireId(value, name);
        if (value.length() > maxLength || !value.equals(value.strip())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    name + " must be byte-exact, unpadded, and free of control characters"
                    + " (max " + maxLength + " chars)");
        }
    }

    private void requireEventEntity(AgentTaskEventEntity event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        requireExactId(event.getTenantId(), "tenantId", 50);
        requireExactId(event.getClientId(), "clientId", 50);
        requireExactId(event.getTaskId(), "taskId", 100);
        requireExactId(event.getEventId(), "eventId", 100);
        if (event.getEventVersion() == null || event.getEventVersion() <= 0) {
            throw new IllegalArgumentException("eventVersion must be positive");
        }
        if (event.getEventType() == null || event.getEventType().isBlank()
                || event.getEventType().length() > 64) {
            throw new IllegalArgumentException("eventType is required and ≤ 64 chars");
        }
        if (event.getActorType() == null || event.getActorType().isBlank()
                || event.getActorType().length() > 20) {
            throw new IllegalArgumentException("actorType is required and ≤ 20 chars");
        }
        if (event.getActorId() != null && (event.getActorId().isBlank()
                || event.getActorId().length() > 100
                || !event.getActorId().equals(event.getActorId().strip())
                || event.getActorId().chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("actorId must be null or byte-exact ≤ 100 chars");
        }
        if (event.getAggregateType() == null || event.getAggregateType().isBlank()
                || event.getAggregateType().length() > 30) {
            throw new IllegalArgumentException("aggregateType is required and ≤ 30 chars");
        }
        requireExactId(event.getAggregateId(), "aggregateId", 100);
        if (event.getEventJson() == null || event.getEventJson().isBlank()) {
            throw new IllegalArgumentException("eventJson is required (NOT NULL)");
        }
        if (event.getOccurredAt() == null || event.getOccurredAt() <= 0) {
            throw new IllegalArgumentException("occurredAt must be positive");
        }
        TaskEventType.requireKnown(event.getEventType());
        TaskEventType.ActorType.requireKnown(event.getActorType());
        TaskEventType.Aggregate.requireKnown(event.getAggregateType());
    }
}
