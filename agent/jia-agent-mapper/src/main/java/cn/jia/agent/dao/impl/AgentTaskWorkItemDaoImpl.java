package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.entity.AgentTaskWorkItemDTO;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.mapper.AgentTaskWorkItemMapper;
import cn.jia.core.util.DateUtil;
import cn.jia.core.util.StringUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.util.List;

@Named
public class AgentTaskWorkItemDaoImpl implements AgentTaskWorkItemDao {
    private final AgentTaskWorkItemMapper baseMapper;

    @Inject
    public AgentTaskWorkItemDaoImpl(AgentTaskWorkItemMapper baseMapper) {
        this.baseMapper = baseMapper;
    }

    @Override
    public int insert(String tenantId, String clientId, AgentTaskWorkItemDTO item) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        requireItem(item, true);
        AgentTaskWorkItemEntity entity = toEntity(item);
        TaskCollaborationDaoSupport.applyScope(entity, tenantId, clientId);
        entity.setPriority(item.getPriority() == null ? 0 : item.getPriority());
        entity.setRequiredItem(item.getRequiredItem() == null || item.getRequiredItem());
        entity.setAttemptCount(item.getAttemptCount() == null ? 0 : item.getAttemptCount());
        entity.setMaxAttempts(item.getMaxAttempts() == null ? 3 : item.getMaxAttempts());
        entity.setVersion(0L);
        entity.init4Creation();
        return baseMapper.insert(entity);
    }

    @Override
    public AgentTaskWorkItemEntity findByWorkItemId(
            String tenantId, String clientId, String workItemId) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        TaskCollaborationDaoSupport.requireId(workItemId, "workItemId");
        return baseMapper.selectOne(scope(tenantId, clientId)
                .eq(AgentTaskWorkItemEntity::getWorkItemId, workItemId)
                .last("limit 1"));
    }

    @Override
    public List<AgentTaskWorkItemEntity> listByTask(
            String tenantId, String clientId, String taskId, String status, int limit) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        TaskCollaborationDaoSupport.requireId(taskId, "taskId");
        LambdaQueryWrapper<AgentTaskWorkItemEntity> wrapper = scope(tenantId, clientId)
                .eq(AgentTaskWorkItemEntity::getTaskId, taskId);
        appendStatus(wrapper, status);
        return ordered(wrapper, limit);
    }

    @Override
    public List<AgentTaskWorkItemEntity> listByAssignee(
            String tenantId, String clientId, String assigneeAgentId, String status, int limit) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        TaskCollaborationDaoSupport.requireId(assigneeAgentId, "assigneeAgentId");
        LambdaQueryWrapper<AgentTaskWorkItemEntity> wrapper = scope(tenantId, clientId)
                .eq(AgentTaskWorkItemEntity::getAssigneeAgentId, assigneeAgentId);
        appendStatus(wrapper, status);
        return ordered(wrapper, limit);
    }

    @Override
    public int updateByVersion(String tenantId, String clientId, String workItemId,
            long expectedVersion, AgentTaskWorkItemDTO item) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        TaskCollaborationDaoSupport.requireId(workItemId, "workItemId");
        requireItem(item, false);
        TaskCollaborationDaoSupport.requireExpectedVersion(expectedVersion);
        return baseMapper.updateByVersion(
                tenantId, clientId, workItemId, expectedVersion, item, DateUtil.nowTime());
    }

    private List<AgentTaskWorkItemEntity> ordered(
            LambdaQueryWrapper<AgentTaskWorkItemEntity> wrapper, int limit) {
        return baseMapper.selectList(wrapper
                .orderByDesc(AgentTaskWorkItemEntity::getPriority)
                .orderByAsc(AgentTaskWorkItemEntity::getCreateTime)
                .orderByAsc(AgentTaskWorkItemEntity::getWorkItemId)
                .orderByAsc(AgentTaskWorkItemEntity::getId)
                .last("limit " + TaskCollaborationDaoSupport.boundedLimit(limit)));
    }

    private void appendStatus(LambdaQueryWrapper<AgentTaskWorkItemEntity> wrapper, String status) {
        if (!StringUtil.isBlank(status)) {
            wrapper.eq(AgentTaskWorkItemEntity::getStatus, status);
        }
    }

    private LambdaQueryWrapper<AgentTaskWorkItemEntity> scope(String tenantId, String clientId) {
        return new LambdaQueryWrapper<AgentTaskWorkItemEntity>()
                .eq(AgentTaskWorkItemEntity::getTenantId, tenantId)
                .eq(AgentTaskWorkItemEntity::getClientId, clientId);
    }

    private void requireItem(AgentTaskWorkItemDTO item, boolean requireIdentity) {
        if (item == null) {
            throw new IllegalArgumentException("work item is required");
        }
        if (requireIdentity) {
            TaskCollaborationDaoSupport.requireId(item.getWorkItemId(), "workItemId");
            TaskCollaborationDaoSupport.requireId(item.getTaskId(), "taskId");
        }
        if (StringUtil.isBlank(item.getTitle()) || StringUtil.isBlank(item.getWorkType())
                || StringUtil.isBlank(item.getStatus())) {
            throw new IllegalArgumentException("work item title, type and status are required");
        }
        if (!requireIdentity && (item.getPriority() == null || item.getRequiredItem() == null
                || item.getAttemptCount() == null || item.getMaxAttempts() == null)) {
            throw new IllegalArgumentException("versioned work item updates require a complete non-null snapshot");
        }
    }

    private AgentTaskWorkItemEntity toEntity(AgentTaskWorkItemDTO item) {
        return new AgentTaskWorkItemEntity()
                .setWorkItemId(item.getWorkItemId())
                .setTaskId(item.getTaskId())
                .setTitle(item.getTitle())
                .setDescription(item.getDescription())
                .setWorkType(item.getWorkType())
                .setRequiredAbilities(item.getRequiredAbilities())
                .setAssigneeAgentId(item.getAssigneeAgentId())
                .setStatus(item.getStatus())
                .setDependencyJson(item.getDependencyJson())
                .setLeaseToken(item.getLeaseToken())
                .setLeaseUntil(item.getLeaseUntil())
                .setResultArtifactId(item.getResultArtifactId())
                .setSubmittedAt(item.getSubmittedAt())
                .setCompletedAt(item.getCompletedAt());
    }
}
