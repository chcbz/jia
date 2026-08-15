package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.entity.AgentTaskAggregationSnapshotRow;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.mapper.AgentTaskMetaMapper;
import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.core.util.DateUtil;
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
    public AgentTaskMetaEntity findByTaskId(String tenantId, String clientId, String taskId) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        TaskCollaborationDaoSupport.requireId(taskId, "taskId");
        return baseMapper.findExactByTaskScope(tenantId, clientId, taskId);
    }

    @Override
    public int reserveOpenTaskRoot(
            String tenantId, String clientId, String taskId, long createTime) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        requireExactId(taskId, "taskId", 100);
        if (createTime <= 0) {
            throw new IllegalArgumentException("createTime must be positive");
        }
        return baseMapper.reserveOpenTaskRoot(tenantId, clientId, taskId, createTime);
    }

    @Override
    public int rekeyReservedTaskRoot(
            String tenantId, String clientId, String reservedTaskId,
            String finalTaskId, long updateTime) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        requireExactId(reservedTaskId, "reservedTaskId", 100);
        requireExactId(finalTaskId, "finalTaskId", 100);
        if (updateTime <= 0) {
            throw new IllegalArgumentException("updateTime must be positive");
        }
        return baseMapper.rekeyReservedTaskRoot(
                tenantId, clientId, reservedTaskId, finalTaskId, updateTime);
    }

    @Override
    public int deleteReservedTaskRoot(
            String tenantId, String clientId, String reservedTaskId) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        requireExactId(reservedTaskId, "reservedTaskId", 100);
        return baseMapper.deleteReservedTaskRoot(tenantId, clientId, reservedTaskId);
    }

    @Override
    public AgentTaskMetaEntity findByTaskIdForUpdate(
            String tenantId, String clientId, String taskId) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        requireExactId(taskId, "taskId", 100);
        return baseMapper.findExactByTaskScopeForUpdate(tenantId, clientId, taskId);
    }

    @Override
    public AgentTaskMetaEntity findByWorkItemIdForUpdate(
            String tenantId, String clientId, String workItemId) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        requireExactId(workItemId, "workItemId", 100);
        return baseMapper.findTaskRootByWorkItemForUpdate(
                tenantId, clientId, workItemId);
    }

    @Override
    public List<AgentTaskAggregationSnapshotRow> findAggregationSnapshot(
            String tenantId, String clientId, String taskId) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        TaskCollaborationDaoSupport.requireId(taskId, "taskId");
        return baseMapper.selectAggregationSnapshot(tenantId, clientId, taskId);
    }

    @Override
    public int updateStatusByVersion(String tenantId, String clientId, String taskId,
            long expectedVersion, String rewardStatus, Long startedAt, Long completedAt,
            String failureReason) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        TaskCollaborationDaoSupport.requireId(taskId, "taskId");
        TaskCollaborationDaoSupport.requireId(rewardStatus, "rewardStatus");
        TaskCollaborationDaoSupport.requireExpectedVersion(expectedVersion);
        return baseMapper.updateStatusByVersion(tenantId, clientId, taskId, expectedVersion,
                rewardStatus, startedAt, completedAt, failureReason, DateUtil.nowTime());
    }

    @Override
    public List<AgentTaskMetaEntity> findByAgentId(
            String tenantId, String clientId, String agentId, int limit) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        requireExactId(agentId, "agentId", 100);
        return baseMapper.selectByAgentInScope(
                tenantId, clientId, agentId, TaskCollaborationDaoSupport.boundedLimit(limit));
    }

    private void requireExactId(String value, String name, int maxLength) {
        TaskCollaborationDaoSupport.requireId(value, name);
        if (value.length() > maxLength || !value.equals(value.strip())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    name + " must be byte-exact, unpadded, and free of control characters");
        }
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
