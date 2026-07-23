package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentTaskRequestDao;
import cn.jia.agent.entity.AgentTaskRequestDTO;
import cn.jia.agent.entity.AgentTaskRequestEntity;
import cn.jia.agent.mapper.AgentTaskRequestMapper;
import cn.jia.core.util.DateUtil;
import cn.jia.core.util.StringUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.util.List;

@Named
public class AgentTaskRequestDaoImpl implements AgentTaskRequestDao {
    private final AgentTaskRequestMapper baseMapper;

    @Inject
    public AgentTaskRequestDaoImpl(AgentTaskRequestMapper baseMapper) {
        this.baseMapper = baseMapper;
    }

    @Override
    public int insert(String tenantId, String clientId, AgentTaskRequestDTO request) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        requireRequest(request, true);
        AgentTaskRequestEntity entity = toEntity(request);
        TaskCollaborationDaoSupport.applyScope(entity, tenantId, clientId);
        entity.setPriority(request.getPriority() == null ? 0 : request.getPriority());
        entity.setVersion(0L);
        entity.init4Creation();
        return baseMapper.insert(entity);
    }

    @Override
    public AgentTaskRequestEntity findByRequestId(
            String tenantId, String clientId, String taskId, String requestId) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        TaskCollaborationDaoSupport.requireId(taskId, "taskId");
        TaskCollaborationDaoSupport.requireId(requestId, "requestId");
        return baseMapper.selectOne(scope(tenantId, clientId)
                .eq(AgentTaskRequestEntity::getTaskId, taskId)
                .eq(AgentTaskRequestEntity::getRequestId, requestId)
                .last("limit 1"));
    }

    @Override
    public List<AgentTaskRequestEntity> listByTask(
            String tenantId, String clientId, String taskId, String status,
            String workItemId, int limit) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        TaskCollaborationDaoSupport.requireId(taskId, "taskId");
        LambdaQueryWrapper<AgentTaskRequestEntity> wrapper = scope(tenantId, clientId)
                .eq(AgentTaskRequestEntity::getTaskId, taskId);
        appendStatus(wrapper, status);
        if (!StringUtil.isBlank(workItemId)) {
            TaskCollaborationDaoSupport.requireId(workItemId, "workItemId");
            wrapper.eq(AgentTaskRequestEntity::getWorkItemId, workItemId);
        }
        return baseMapper.selectList(orderByTask(wrapper, limit));
    }

    @Override
    public List<AgentTaskRequestEntity> listByTarget(String tenantId, String clientId, String taskId,
            String targetType, String targetId, String status, int limit) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        TaskCollaborationDaoSupport.requireId(taskId, "taskId");
        TaskCollaborationDaoSupport.requireId(targetType, "targetType");
        TaskCollaborationDaoSupport.requireId(targetId, "targetId");
        LambdaQueryWrapper<AgentTaskRequestEntity> wrapper = scope(tenantId, clientId)
                .eq(AgentTaskRequestEntity::getTaskId, taskId)
                .eq(AgentTaskRequestEntity::getTargetType, targetType)
                .eq(AgentTaskRequestEntity::getTargetId, targetId);
        appendStatus(wrapper, status);
        return baseMapper.selectList(wrapper
                .orderByDesc(AgentTaskRequestEntity::getPriority)
                .orderByAsc(AgentTaskRequestEntity::getDueAt)
                .orderByAsc(AgentTaskRequestEntity::getCreateTime)
                .orderByAsc(AgentTaskRequestEntity::getRequestId)
                .orderByAsc(AgentTaskRequestEntity::getId)
                .last("limit " + TaskCollaborationDaoSupport.boundedLimit(limit)));
    }

    @Override
    public int updateByVersion(String tenantId, String clientId, String taskId, String requestId,
            long expectedVersion, AgentTaskRequestDTO request) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        TaskCollaborationDaoSupport.requireId(taskId, "taskId");
        TaskCollaborationDaoSupport.requireId(requestId, "requestId");
        requireRequest(request, false);
        TaskCollaborationDaoSupport.requireExpectedVersion(expectedVersion);
        return baseMapper.updateByVersion(
                tenantId, clientId, taskId, requestId, expectedVersion, request, DateUtil.nowTime());
    }

    private LambdaQueryWrapper<AgentTaskRequestEntity> orderByTask(
            LambdaQueryWrapper<AgentTaskRequestEntity> wrapper, int limit) {
        return wrapper.orderByDesc(AgentTaskRequestEntity::getPriority)
                .orderByAsc(AgentTaskRequestEntity::getCreateTime)
                .orderByAsc(AgentTaskRequestEntity::getRequestId)
                .orderByAsc(AgentTaskRequestEntity::getId)
                .last("limit " + TaskCollaborationDaoSupport.boundedLimit(limit));
    }

    private void appendStatus(LambdaQueryWrapper<AgentTaskRequestEntity> wrapper, String status) {
        if (!StringUtil.isBlank(status)) {
            wrapper.eq(AgentTaskRequestEntity::getStatus, status);
        }
    }

    private LambdaQueryWrapper<AgentTaskRequestEntity> scope(String tenantId, String clientId) {
        return new LambdaQueryWrapper<AgentTaskRequestEntity>()
                .eq(AgentTaskRequestEntity::getTenantId, tenantId)
                .eq(AgentTaskRequestEntity::getClientId, clientId);
    }

    private void requireRequest(AgentTaskRequestDTO request, boolean requireIdentity) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        if (requireIdentity) {
            TaskCollaborationDaoSupport.requireId(request.getRequestId(), "requestId");
            TaskCollaborationDaoSupport.requireId(request.getTaskId(), "taskId");
        }
        if (!requireIdentity && request.getPriority() == null) {
            throw new IllegalArgumentException("versioned request updates require a non-null priority");
        }
        if (StringUtil.isBlank(request.getRequesterAgentId())
                || StringUtil.isBlank(request.getTargetType())
                || StringUtil.isBlank(request.getTargetId())
                || StringUtil.isBlank(request.getRequestType())
                || StringUtil.isBlank(request.getStatus())
                || StringUtil.isBlank(request.getTitle())
                || StringUtil.isBlank(request.getDescription())) {
            throw new IllegalArgumentException("request actor, target, type, status, title and description are required");
        }
    }

    private AgentTaskRequestEntity toEntity(AgentTaskRequestDTO request) {
        return new AgentTaskRequestEntity()
                .setRequestId(request.getRequestId())
                .setTaskId(request.getTaskId())
                .setWorkItemId(request.getWorkItemId())
                .setRequesterAgentId(request.getRequesterAgentId())
                .setTargetType(request.getTargetType())
                .setTargetId(request.getTargetId())
                .setRequestType(request.getRequestType())
                .setStatus(request.getStatus())
                .setTitle(request.getTitle())
                .setDescription(request.getDescription())
                .setResponseJson(request.getResponseJson())
                .setDueAt(request.getDueAt())
                .setAcknowledgedAt(request.getAcknowledgedAt())
                .setResolvedAt(request.getResolvedAt());
    }
}
