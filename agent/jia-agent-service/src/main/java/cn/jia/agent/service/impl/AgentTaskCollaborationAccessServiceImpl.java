package cn.jia.agent.service.impl;

import cn.jia.agent.access.AgentTaskAccessLevel;
import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.entity.AgentTaskMemberEntity;
import cn.jia.agent.service.AgentTaskCollaborationAccessService;
import cn.jia.agent.state.AgentTaskMemberStatus;
import cn.jia.core.util.StringUtil;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.util.Objects;

/**
 * B07 task-thread membership policy.
 *
 * <p>Active accepted/working/blocked members, including observer-role rows, may read and write.
 * Historical done/failed members may read but may not add new messages. Invited, rejected and
 * left members have no access. A coordinator id in task metadata is not an ACL grant by itself;
 * it must also have a member row. Unknown persisted states fail closed.</p>
 */
@Named
public class AgentTaskCollaborationAccessServiceImpl
        implements AgentTaskCollaborationAccessService {
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
        requireScopeId(tenantId, "tenantId");
        requireScopeId(clientId, "clientId");
        requireScopeId(taskId, "taskId");
        requireScopeId(agentId, "agentId");

        if (taskMetaDao.findByTaskId(tenantId, clientId, taskId) == null) {
            return AgentTaskAccessLevel.NONE;
        }
        AgentTaskMemberEntity member = memberDao.findByTaskAndAgent(
                tenantId, clientId, taskId, agentId);
        if (member == null) {
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

    private void requireScopeId(String value, String field) {
        if (StringUtil.isBlank(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
