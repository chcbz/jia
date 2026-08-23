package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.entity.AgentTaskMemberDTO;
import cn.jia.agent.entity.AgentTaskMemberEntity;
import cn.jia.agent.mapper.AgentTaskMemberMapper;
import cn.jia.core.util.DateUtil;
import cn.jia.core.util.StringUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.util.List;

@Named
public class AgentTaskMemberDaoImpl implements AgentTaskMemberDao {
    private final AgentTaskMemberMapper baseMapper;

    @Inject
    public AgentTaskMemberDaoImpl(AgentTaskMemberMapper baseMapper) {
        this.baseMapper = baseMapper;
    }

    @Override
    public int insert(String tenantId, String clientId, AgentTaskMemberDTO member) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        requireMember(member);
        AgentTaskMemberEntity entity = toEntity(member);
        TaskCollaborationDaoSupport.applyScope(entity, tenantId, clientId);
        entity.setVersion(0L);
        entity.init4Creation();
        return baseMapper.insert(entity);
    }

    @Override
    public AgentTaskMemberEntity findByTaskAndAgent(
            String tenantId, String clientId, String taskId, String agentId) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        TaskCollaborationDaoSupport.requireId(taskId, "taskId");
        TaskCollaborationDaoSupport.requireId(agentId, "agentId");
        return baseMapper.findExactByTaskAndAgent(tenantId, clientId, taskId, agentId);
    }

    @Override
    public AgentTaskMemberEntity findByTaskAndAgentForUpdate(
            String tenantId, String clientId, String taskId, String agentId) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        TaskCollaborationDaoSupport.requireId(taskId, "taskId");
        TaskCollaborationDaoSupport.requireId(agentId, "agentId");
        return baseMapper.findExactByTaskAndAgentForUpdate(
                tenantId, clientId, taskId, agentId);
    }

    @Override
    public List<AgentTaskMemberEntity> listByTask(String tenantId, String clientId, String taskId) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        TaskCollaborationDaoSupport.requireId(taskId, "taskId");
        LambdaQueryWrapper<AgentTaskMemberEntity> wrapper = scope(tenantId, clientId)
                .eq(AgentTaskMemberEntity::getTaskId, taskId);
        TaskCollaborationDaoSupport.exact(wrapper, "task_id", taskId);
        return baseMapper.selectList(wrapper
                .orderByAsc(AgentTaskMemberEntity::getMemberRole)
                .orderByAsc(AgentTaskMemberEntity::getAgentId)
                .orderByAsc(AgentTaskMemberEntity::getId));
    }

    @Override
    public List<AgentTaskMemberEntity> listByAgent(
            String tenantId, String clientId, String agentId, String memberStatus, int limit) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        TaskCollaborationDaoSupport.requireId(agentId, "agentId");
        LambdaQueryWrapper<AgentTaskMemberEntity> wrapper = scope(tenantId, clientId)
                .eq(AgentTaskMemberEntity::getAgentId, agentId);
        TaskCollaborationDaoSupport.exact(wrapper, "agent_id", agentId);
        if (!StringUtil.isBlank(memberStatus)) {
            TaskCollaborationDaoSupport.requireId(memberStatus, "memberStatus");
            wrapper.eq(AgentTaskMemberEntity::getMemberStatus, memberStatus);
            TaskCollaborationDaoSupport.exact(wrapper, "member_status", memberStatus);
        }
        return baseMapper.selectList(wrapper
                .orderByDesc(AgentTaskMemberEntity::getUpdateTime)
                .orderByAsc(AgentTaskMemberEntity::getTaskId)
                .orderByAsc(AgentTaskMemberEntity::getId)
                .last("limit " + TaskCollaborationDaoSupport.boundedLimit(limit)));
    }

    @Override
    public int updateByVersion(String tenantId, String clientId, String taskId, String agentId,
            long expectedVersion, AgentTaskMemberDTO member) {
        TaskCollaborationDaoSupport.requireScope(tenantId, clientId);
        TaskCollaborationDaoSupport.requireId(taskId, "taskId");
        TaskCollaborationDaoSupport.requireId(agentId, "agentId");
        requireMemberUpdate(member);
        TaskCollaborationDaoSupport.requireExpectedVersion(expectedVersion);
        return baseMapper.updateByVersion(
                tenantId, clientId, taskId, agentId, expectedVersion, member, DateUtil.nowTime());
    }

    private LambdaQueryWrapper<AgentTaskMemberEntity> scope(String tenantId, String clientId) {
        LambdaQueryWrapper<AgentTaskMemberEntity> wrapper =
                new LambdaQueryWrapper<AgentTaskMemberEntity>()
                        .eq(AgentTaskMemberEntity::getTenantId, tenantId)
                        .eq(AgentTaskMemberEntity::getClientId, clientId);
        TaskCollaborationDaoSupport.exact(wrapper, "tenant_id", tenantId);
        return TaskCollaborationDaoSupport.exact(wrapper, "client_id", clientId);
    }

    private void requireMember(AgentTaskMemberDTO member) {
        if (member == null) {
            throw new IllegalArgumentException("member is required");
        }
        TaskCollaborationDaoSupport.requireId(member.getTaskId(), "taskId");
        TaskCollaborationDaoSupport.requireId(member.getAgentId(), "agentId");
        requireMemberUpdate(member);
    }

    private void requireMemberUpdate(AgentTaskMemberDTO member) {
        if (member == null || StringUtil.isBlank(member.getMemberRole())
                || StringUtil.isBlank(member.getMemberStatus())
                || StringUtil.isBlank(member.getAssignmentSource())) {
            throw new IllegalArgumentException("member role, status and assignmentSource are required");
        }
    }

    private AgentTaskMemberEntity toEntity(AgentTaskMemberDTO member) {
        return new AgentTaskMemberEntity()
                .setTaskId(member.getTaskId())
                .setAgentId(member.getAgentId())
                .setMemberRole(member.getMemberRole())
                .setMemberStatus(member.getMemberStatus())
                .setAssignmentSource(member.getAssignmentSource())
                .setJoinedAt(member.getJoinedAt())
                .setAcceptedAt(member.getAcceptedAt())
                .setStartedAt(member.getStartedAt())
                .setCompletedAt(member.getCompletedAt())
                .setLastHeartbeatAt(member.getLastHeartbeatAt())
                .setFailureReason(member.getFailureReason());
    }
}
