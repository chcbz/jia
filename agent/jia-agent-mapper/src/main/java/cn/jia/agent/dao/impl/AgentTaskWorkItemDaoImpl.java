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
    private static final List<String> LEASED_STATUSES = List.of("claimed", "running");

    private final AgentTaskWorkItemMapper baseMapper;

    @Inject
    public AgentTaskWorkItemDaoImpl(AgentTaskWorkItemMapper baseMapper) {
        this.baseMapper = baseMapper;
    }

    @Override
    public int insert(String tenantId, String clientId, String ownerJiacn, AgentTaskWorkItemDTO item) {
        TaskCollaborationDaoSupport.requireStrictOwnerScope(tenantId, clientId, ownerJiacn);
        requireItem(item, true);
        AgentTaskWorkItemEntity entity = toEntity(item);
        TaskCollaborationDaoSupport.applyScope(entity, tenantId, clientId);
        entity.setOwnerJiacn(ownerJiacn);
        entity.setPriority(item.getPriority() == null ? 0 : item.getPriority());
        entity.setRequiredItem(item.getRequiredItem() == null || item.getRequiredItem());
        entity.setAttemptCount(item.getAttemptCount() == null ? 0 : item.getAttemptCount());
        entity.setMaxAttempts(item.getMaxAttempts() == null ? 3 : item.getMaxAttempts());
        entity.setVersion(0L);
        entity.init4Creation();
        return baseMapper.insertIfParentNonTerminal(tenantId, clientId, ownerJiacn, entity);
    }

    @Override
    public AgentTaskWorkItemEntity findByWorkItemId(
            String tenantId, String clientId, String ownerJiacn, String workItemId) {
        TaskCollaborationDaoSupport.requireStrictOwnerScope(tenantId, clientId, ownerJiacn);
        TaskCollaborationDaoSupport.requireId(workItemId, "workItemId");
        LambdaQueryWrapper<AgentTaskWorkItemEntity> wrapper = scope(tenantId, clientId, ownerJiacn)
                .eq(AgentTaskWorkItemEntity::getWorkItemId, workItemId);
        TaskCollaborationDaoSupport.exact(wrapper, "work_item_id", workItemId);
        return baseMapper.selectOne(wrapper.last("limit 1"));
    }

    @Override
    public AgentTaskWorkItemEntity findByTaskAndWorkItemId(
            String tenantId, String clientId, String ownerJiacn, String taskId, String workItemId) {
        TaskCollaborationDaoSupport.requireStrictOwnerScope(tenantId, clientId, ownerJiacn);
        TaskCollaborationDaoSupport.requireId(taskId, "taskId");
        TaskCollaborationDaoSupport.requireId(workItemId, "workItemId");
        LambdaQueryWrapper<AgentTaskWorkItemEntity> wrapper = scope(tenantId, clientId, ownerJiacn)
                .eq(AgentTaskWorkItemEntity::getTaskId, taskId)
                .eq(AgentTaskWorkItemEntity::getWorkItemId, workItemId);
        TaskCollaborationDaoSupport.exact(wrapper, "task_id", taskId);
        TaskCollaborationDaoSupport.exact(wrapper, "work_item_id", workItemId);
        return baseMapper.selectOne(wrapper.last("limit 1"));
    }

    @Override
    public List<AgentTaskWorkItemEntity> listByTask(
            String tenantId, String clientId, String ownerJiacn, String taskId, String status, int limit) {
        TaskCollaborationDaoSupport.requireStrictOwnerScope(tenantId, clientId, ownerJiacn);
        TaskCollaborationDaoSupport.requireId(taskId, "taskId");
        LambdaQueryWrapper<AgentTaskWorkItemEntity> wrapper = scope(tenantId, clientId, ownerJiacn)
                .eq(AgentTaskWorkItemEntity::getTaskId, taskId);
        TaskCollaborationDaoSupport.exact(wrapper, "task_id", taskId);
        appendStatus(wrapper, status);
        return ordered(wrapper, limit);
    }

    @Override
    public List<AgentTaskWorkItemEntity> listByTaskForUpdate(
            String tenantId, String clientId, String ownerJiacn, String taskId, int limit) {
        TaskCollaborationDaoSupport.requireStrictOwnerScope(tenantId, clientId, ownerJiacn);
        TaskCollaborationDaoSupport.requireId(taskId, "taskId");
        return baseMapper.selectTaskGraphForUpdate(
                tenantId, clientId, ownerJiacn, taskId, boundedDependencyGraphLimit(limit));
    }

    @Override
    public List<AgentTaskWorkItemEntity> listByAssignee(
            String tenantId, String clientId, String ownerJiacn, String assigneeAgentId, String status, int limit) {
        TaskCollaborationDaoSupport.requireStrictOwnerScope(tenantId, clientId, ownerJiacn);
        TaskCollaborationDaoSupport.requireId(assigneeAgentId, "assigneeAgentId");
        LambdaQueryWrapper<AgentTaskWorkItemEntity> wrapper = scope(tenantId, clientId, ownerJiacn)
                .eq(AgentTaskWorkItemEntity::getAssigneeAgentId, assigneeAgentId);
        TaskCollaborationDaoSupport.exact(wrapper, "assignee_agent_id", assigneeAgentId);
        appendStatus(wrapper, status);
        return ordered(wrapper, limit);
    }

    @Override
    public List<AgentTaskWorkItemEntity> listByTaskAndAssignee(
            String tenantId, String clientId, String ownerJiacn, String taskId, String assigneeAgentId, int limit) {
        TaskCollaborationDaoSupport.requireStrictOwnerScope(tenantId, clientId, ownerJiacn);
        TaskCollaborationDaoSupport.requireId(taskId, "taskId");
        TaskCollaborationDaoSupport.requireId(assigneeAgentId, "assigneeAgentId");
        LambdaQueryWrapper<AgentTaskWorkItemEntity> wrapper = scope(tenantId, clientId, ownerJiacn)
                .eq(AgentTaskWorkItemEntity::getTaskId, taskId)
                .eq(AgentTaskWorkItemEntity::getAssigneeAgentId, assigneeAgentId);
        TaskCollaborationDaoSupport.exact(wrapper, "task_id", taskId);
        TaskCollaborationDaoSupport.exact(wrapper, "assignee_agent_id", assigneeAgentId);
        return ordered(wrapper, limit);
    }

    @Override
    public List<AgentTaskWorkItemEntity> listByTaskAssigneeAndType(
            String tenantId, String clientId, String ownerJiacn, String taskId, String assigneeAgentId,
            String workType, int limit) {
        TaskCollaborationDaoSupport.requireStrictOwnerScope(tenantId, clientId, ownerJiacn);
        TaskCollaborationDaoSupport.requireId(taskId, "taskId");
        TaskCollaborationDaoSupport.requireId(assigneeAgentId, "assigneeAgentId");
        TaskCollaborationDaoSupport.requireId(workType, "workType");
        LambdaQueryWrapper<AgentTaskWorkItemEntity> wrapper = scope(tenantId, clientId, ownerJiacn)
                .eq(AgentTaskWorkItemEntity::getTaskId, taskId)
                .eq(AgentTaskWorkItemEntity::getAssigneeAgentId, assigneeAgentId)
                .eq(AgentTaskWorkItemEntity::getWorkType, workType);
        TaskCollaborationDaoSupport.exact(wrapper, "task_id", taskId);
        TaskCollaborationDaoSupport.exact(wrapper, "assignee_agent_id", assigneeAgentId);
        TaskCollaborationDaoSupport.exact(wrapper, "work_type", workType);
        return ordered(wrapper, limit);
    }

    @Override
    public List<AgentTaskWorkItemEntity> listExpiredLeases(
            String tenantId, String clientId, String ownerJiacn, long expiredAtOrBefore, int limit) {
        TaskCollaborationDaoSupport.requireStrictOwnerScope(tenantId, clientId, ownerJiacn);
        requireNonnegativeTime(expiredAtOrBefore, "expiredAtOrBefore");
        LambdaQueryWrapper<AgentTaskWorkItemEntity> wrapper = scope(tenantId, clientId, ownerJiacn)
                .in(AgentTaskWorkItemEntity::getStatus, LEASED_STATUSES);
        TaskCollaborationDaoSupport.exactAny(wrapper, "status", LEASED_STATUSES);
        return baseMapper.selectList(wrapper
                .isNotNull(AgentTaskWorkItemEntity::getLeaseUntil)
                .le(AgentTaskWorkItemEntity::getLeaseUntil, expiredAtOrBefore)
                .orderByAsc(AgentTaskWorkItemEntity::getLeaseUntil)
                .orderByAsc(AgentTaskWorkItemEntity::getWorkItemId)
                .orderByAsc(AgentTaskWorkItemEntity::getId)
                .last("limit " + TaskCollaborationDaoSupport.boundedLimit(limit)));
    }

    @Override
    public int updateByVersion(String tenantId, String clientId, String ownerJiacn, String workItemId,
            long expectedVersion, AgentTaskWorkItemDTO item) {
        TaskCollaborationDaoSupport.requireStrictOwnerScope(tenantId, clientId, ownerJiacn);
        TaskCollaborationDaoSupport.requireId(workItemId, "workItemId");
        requireItem(item, false);
        TaskCollaborationDaoSupport.requireExpectedVersion(expectedVersion);
        return baseMapper.updateByVersion(
                tenantId, clientId, ownerJiacn, workItemId, expectedVersion, item, DateUtil.nowTime());
    }

    @Override
    public int claimReadyByVersion(
            String tenantId, String clientId, String ownerJiacn, String taskId, String workItemId,
            String expectedAssigneeAgentId, long expectedVersion, AgentTaskWorkItemDTO item) {
        requireLeaseCasCommon(tenantId, clientId, ownerJiacn, taskId, workItemId, expectedVersion, item);
        long updateTime = DateUtil.nowTime();
        if (StringUtil.isBlank(expectedAssigneeAgentId)) {
            return baseMapper.claimReadyUnassignedByVersion(
                    tenantId, clientId, ownerJiacn, taskId, workItemId, expectedVersion, item, updateTime);
        }
        return baseMapper.claimReadyAssignedByVersion(
                tenantId, clientId, ownerJiacn, taskId, workItemId, expectedAssigneeAgentId,
                expectedVersion, item, updateTime);
    }

    @Override
    public int readyPendingByVersion(
            String tenantId, String clientId, String ownerJiacn, String taskId, String workItemId,
            long expectedVersion, long changedAt, AgentTaskWorkItemDTO item) {
        requireLeaseCasCommon(tenantId, clientId, ownerJiacn, taskId, workItemId, expectedVersion, item);
        if (!"ready".equals(item.getStatus())
                || !taskId.equals(item.getTaskId())
                || !workItemId.equals(item.getWorkItemId())
                || item.getVersion() == null || item.getVersion() != expectedVersion
                || item.getLeaseToken() != null || item.getLeaseUntil() != null
                || item.getResultArtifactId() != null || item.getSubmittedAt() != null
                || item.getCompletedAt() != null
                || item.getAttemptCount() >= item.getMaxAttempts()
                || changedAt <= 0) {
            throw new IllegalArgumentException(
                    "dependency ready CAS requires an exact ready snapshot and positive changedAt");
        }
        return baseMapper.readyPendingByVersion(
                tenantId, clientId, ownerJiacn, taskId, workItemId,
                expectedVersion, item, changedAt);
    }

    @Override
    public int updateActiveLeaseByVersion(
            String tenantId, String clientId, String ownerJiacn, String taskId, String workItemId,
            String assigneeAgentId, String leaseToken, String expectedStatus,
            long expectedLeaseUntil, long expectedVersion, long operationTime,
            AgentTaskWorkItemDTO item) {
        requireLeaseCasCommon(tenantId, clientId, ownerJiacn, taskId, workItemId, expectedVersion, item);
        requireLeaseIdentity(assigneeAgentId, leaseToken, expectedStatus, expectedLeaseUntil);
        requireNonnegativeTime(operationTime, "operationTime");
        return baseMapper.updateActiveLeaseByVersion(
                tenantId, clientId, ownerJiacn, taskId, workItemId, assigneeAgentId, leaseToken,
                expectedStatus, expectedLeaseUntil, expectedVersion, operationTime,
                item, DateUtil.nowTime());
    }

    @Override
    public int reassignExpiredLeaseByVersion(
            String tenantId, String clientId, String ownerJiacn, String taskId, String workItemId,
            String previousAgentId, String previousLeaseToken, String expectedStatus,
            long expectedLeaseUntil, long expectedVersion, long expiredAtOrBefore,
            AgentTaskWorkItemDTO item) {
        requireLeaseCasCommon(tenantId, clientId, ownerJiacn, taskId, workItemId, expectedVersion, item);
        requireLeaseIdentity(previousAgentId, previousLeaseToken, expectedStatus, expectedLeaseUntil);
        requireNonnegativeTime(expiredAtOrBefore, "expiredAtOrBefore");
        if (!LEASED_STATUSES.contains(expectedStatus)
                || !"claimed".equals(item.getStatus())
                || StringUtil.isBlank(item.getAssigneeAgentId())
                || previousAgentId.equals(item.getAssigneeAgentId())
                || StringUtil.isBlank(item.getLeaseToken()) || item.getLeaseUntil() == null
                || item.getLeaseUntil() <= expiredAtOrBefore
                || item.getAttemptCount() == null || item.getMaxAttempts() == null
                || item.getAttemptCount() <= 0 || item.getAttemptCount() >= item.getMaxAttempts()) {
            throw new IllegalArgumentException(
                    "reassignment CAS requires a complete fresh claimed lease with remaining attempts");
        }
        return baseMapper.reassignExpiredLeaseByVersion(
                tenantId, clientId, ownerJiacn, taskId, workItemId, previousAgentId, previousLeaseToken,
                expectedStatus, expectedLeaseUntil, expectedVersion, expiredAtOrBefore,
                item, DateUtil.nowTime());
    }

    @Override
    public int expireLeaseByVersion(
            String tenantId, String clientId, String ownerJiacn, String taskId, String workItemId,
            String assigneeAgentId, String leaseToken, String expectedStatus,
            long expectedLeaseUntil, long expectedVersion, long expiredAtOrBefore,
            AgentTaskWorkItemDTO item) {
        requireLeaseCasCommon(tenantId, clientId, ownerJiacn, taskId, workItemId, expectedVersion, item);
        requireLeaseIdentity(assigneeAgentId, leaseToken, expectedStatus, expectedLeaseUntil);
        requireNonnegativeTime(expiredAtOrBefore, "expiredAtOrBefore");
        return baseMapper.expireLeaseByVersion(
                tenantId, clientId, ownerJiacn, taskId, workItemId, assigneeAgentId, leaseToken,
                expectedStatus, expectedLeaseUntil, expectedVersion, expiredAtOrBefore,
                item, DateUtil.nowTime());
    }

    private void requireLeaseCasCommon(
            String tenantId, String clientId, String ownerJiacn, String taskId, String workItemId,
            long expectedVersion, AgentTaskWorkItemDTO item) {
        TaskCollaborationDaoSupport.requireStrictOwnerScope(tenantId, clientId, ownerJiacn);
        TaskCollaborationDaoSupport.requireId(taskId, "taskId");
        TaskCollaborationDaoSupport.requireId(workItemId, "workItemId");
        TaskCollaborationDaoSupport.requireExpectedVersion(expectedVersion);
        requireItem(item, false);
    }

    private void requireLeaseIdentity(
            String assigneeAgentId, String leaseToken, String expectedStatus,
            long expectedLeaseUntil) {
        TaskCollaborationDaoSupport.requireId(assigneeAgentId, "assigneeAgentId");
        TaskCollaborationDaoSupport.requireId(leaseToken, "leaseToken");
        TaskCollaborationDaoSupport.requireId(expectedStatus, "expectedStatus");
        if (expectedLeaseUntil <= 0) {
            throw new IllegalArgumentException("expectedLeaseUntil must be positive");
        }
    }

    private void requireNonnegativeTime(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be nonnegative");
        }
    }

    private int boundedDependencyGraphLimit(int limit) {
        if (limit <= 0 || limit > 501) {
            throw new IllegalArgumentException("dependency graph limit must be between 1 and 501");
        }
        return limit;
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
            TaskCollaborationDaoSupport.requireId(status, "status");
            wrapper.eq(AgentTaskWorkItemEntity::getStatus, status);
            TaskCollaborationDaoSupport.exact(wrapper, "status", status);
        }
    }

    private LambdaQueryWrapper<AgentTaskWorkItemEntity> scope(
            String tenantId, String clientId, String ownerJiacn) {
        LambdaQueryWrapper<AgentTaskWorkItemEntity> wrapper =
                new LambdaQueryWrapper<AgentTaskWorkItemEntity>()
                        .eq(AgentTaskWorkItemEntity::getTenantId, tenantId)
                        .eq(AgentTaskWorkItemEntity::getClientId, clientId)
                        .eq(AgentTaskWorkItemEntity::getOwnerJiacn, ownerJiacn);
        TaskCollaborationDaoSupport.exact(wrapper, "tenant_id", tenantId);
        TaskCollaborationDaoSupport.exact(wrapper, "client_id", clientId);
        return TaskCollaborationDaoSupport.exact(wrapper, "owner_jiacn", ownerJiacn);
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
