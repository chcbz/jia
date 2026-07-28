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
            String tenantId, String clientId, String taskId, String agentId) {
        return resolveMemberAccess(tenantId, clientId, taskId, agentId, false);
    }

    @Override
    public AgentTaskAccessLevel resolveMemberAccessForUpdate(
            String tenantId, String clientId, String taskId, String agentId) {
        return resolveMemberAccess(tenantId, clientId, taskId, agentId, true);
    }

    private AgentTaskAccessLevel resolveMemberAccess(
            String tenantId, String clientId, String taskId, String agentId, boolean forUpdate) {
        requireScopeId(tenantId, "tenantId");
        requireScopeId(clientId, "clientId");
        requireScopeId(taskId, "taskId");
        requireScopeId(agentId, "agentId");

        AgentTaskMetaEntity task = forUpdate
                ? taskMetaDao.findByTaskIdForUpdate(tenantId, clientId, taskId)
                : taskMetaDao.findByTaskId(tenantId, clientId, taskId);
        if (!isCanonicalTask(task, tenantId, clientId, taskId)) {
            return AgentTaskAccessLevel.NONE;
        }
        AgentTaskMemberEntity member = forUpdate
                ? memberDao.findByTaskAndAgentForUpdate(tenantId, clientId, taskId, agentId)
                : memberDao.findByTaskAndAgent(tenantId, clientId, taskId, agentId);
        if (!isCanonicalMember(member, tenantId, clientId, taskId, agentId)) {
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
            AgentTaskMetaEntity task, String tenantId, String clientId, String taskId) {
        return task != null
                && tenantId.equals(task.getTenantId())
                && clientId.equals(task.getClientId())
                && taskId.equals(task.getTaskId())
                && isCanonicalTaskStatus(task.getRewardStatus());
    }

    private boolean isCanonicalMember(
            AgentTaskMemberEntity member, String tenantId, String clientId,
            String taskId, String agentId) {
        return member != null
                && tenantId.equals(member.getTenantId())
                && clientId.equals(member.getClientId())
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

    private void requireScopeId(String value, String field) {
        if (StringUtil.isBlank(value) || !value.equals(value.strip())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }
}
