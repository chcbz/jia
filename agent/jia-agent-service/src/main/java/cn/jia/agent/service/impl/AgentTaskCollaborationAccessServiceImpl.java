package cn.jia.agent.service.impl;

import cn.jia.agent.access.AgentTaskAccessLevel;
import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.entity.AgentTaskMemberEntity;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.service.AgentTaskCollaborationAccessService;
import cn.jia.agent.state.AgentTaskMemberStatus;
import cn.jia.agent.state.AgentTaskStatus;
import cn.jia.core.util.StringUtil;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.util.Objects;
import java.util.Set;

/**
 * B07 task-thread membership policy.
 *
 * <p>Active accepted/working/blocked members, including observer-role rows, may read and write.
 * Historical done/failed members may read but may not add new messages. Invited, rejected and
 * left members have no access. A coordinator id in task metadata is not an ACL grant by itself;
 * it must also have a member row. Unknown or non-canonical persisted values fail closed.</p>
 */
@Named
public class AgentTaskCollaborationAccessServiceImpl
        implements AgentTaskCollaborationAccessService {
    private static final Set<String> MEMBER_ROLES = Set.of(
            "coordinator", "worker", "reviewer", "observer");
    private static final Set<String> ASSIGNMENT_SOURCES = Set.of(
            "manual", "auto", "migration", "legacy");

    private final AgentTaskMetaDao taskMetaDao;
    private final AgentTaskMemberDao memberDao;

    @Inject
    public AgentTaskCollaborationAccessServiceImpl(
            AgentTaskMetaDao taskMetaDao, AgentTaskMemberDao memberDao) {
        this.taskMetaDao = Objects.requireNonNull(taskMetaDao, "taskMetaDao");
        this.memberDao = Objects.requireNonNull(memberDao, "memberDao");
    }

    @Override
    public AgentTaskAccessLevel resolveMemberAccess(
            String tenantId, String clientId, String ownerJiacn, String taskId, String agentId) {
        return resolveMemberAccess(tenantId, clientId, ownerJiacn, taskId, agentId, false);
    }

    @Override
    public AgentTaskAccessLevel resolveMemberAccessForUpdate(
            String tenantId, String clientId, String ownerJiacn, String taskId, String agentId) {
        return resolveMemberAccess(tenantId, clientId, ownerJiacn, taskId, agentId, true);
    }

    private AgentTaskAccessLevel resolveMemberAccess(
            String tenantId, String clientId, String ownerJiacn, String taskId, String agentId, boolean forUpdate) {
        requireStrictOwnerScope(tenantId, clientId, ownerJiacn);
        requireScopeId(taskId, "taskId");
        requireScopeId(agentId, "agentId");

        AgentTaskMetaEntity task = forUpdate
                ? taskMetaDao.findByTaskIdForUpdateInOwnerScope(tenantId, clientId, ownerJiacn, taskId)
                : taskMetaDao.findByTaskIdInOwnerScope(tenantId, clientId, ownerJiacn, taskId);
        if (!isCanonicalTask(task, tenantId, clientId, ownerJiacn, taskId)) {
            return AgentTaskAccessLevel.NONE;
        }
        AgentTaskMemberEntity member = forUpdate
                ? memberDao.findByTaskAndAgentForUpdate(tenantId, clientId, ownerJiacn, taskId, agentId)
                : memberDao.findByTaskAndAgent(tenantId, clientId, ownerJiacn, taskId, agentId);
        if (!isCanonicalMember(member, tenantId, clientId, ownerJiacn, taskId, agentId)) {
            return AgentTaskAccessLevel.NONE;
        }

        AgentTaskMemberStatus status;
        try {
            status = AgentTaskMemberStatus.fromPersistedValue(member.getMemberStatus());
        } catch (IllegalArgumentException exception) {
            return AgentTaskAccessLevel.NONE;
        }
        return switch (status) {
            case ACCEPTED, WORKING, BLOCKED -> AgentTaskAccessLevel.READ_WRITE;
            case DONE, FAILED -> AgentTaskAccessLevel.READ_ONLY;
            case INVITED, REJECTED, LEFT -> AgentTaskAccessLevel.NONE;
        };
    }

    private boolean isCanonicalTask(
            AgentTaskMetaEntity task, String tenantId, String clientId, String ownerJiacn, String taskId) {
        return task != null
                && tenantId.equals(task.getTenantId())
                && clientId.equals(task.getClientId())
                && ownerJiacn.equals(task.getOwnerJiacn())
                && taskId.equals(task.getTaskId())
                && isCanonicalTaskStatus(task.getRewardStatus());
    }

    private boolean isCanonicalMember(
            AgentTaskMemberEntity member, String tenantId, String clientId,
            String ownerJiacn, String taskId, String agentId) {
        return member != null
                && tenantId.equals(member.getTenantId())
                && clientId.equals(member.getClientId())
                && ownerJiacn.equals(member.getOwnerJiacn())
                && taskId.equals(member.getTaskId())
                && agentId.equals(member.getAgentId())
                && MEMBER_ROLES.contains(member.getMemberRole())
                && ASSIGNMENT_SOURCES.contains(member.getAssignmentSource());
    }

    private boolean isCanonicalTaskStatus(String value) {
        try {
            AgentTaskStatus.fromPersistedValue(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void requireStrictOwnerScope(
            String tenantId, String clientId, String ownerJiacn) {
        if (!"0".equals(tenantId)) {
            throw new IllegalArgumentException("tenantId must be 0");
        }
        requireScopeId(clientId, "clientId");
        requireScopeId(ownerJiacn, "ownerJiacn");
        if ("0".equals(ownerJiacn)) {
            throw new IllegalArgumentException("ownerJiacn must not be 0");
        }
    }

    private void requireScopeId(String value, String field) {
        if (StringUtil.isBlank(value) || !value.equals(value.strip())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }
}
