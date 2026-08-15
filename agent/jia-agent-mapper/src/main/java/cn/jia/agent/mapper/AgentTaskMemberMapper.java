package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentTaskMemberDTO;
import cn.jia.agent.entity.AgentTaskMemberEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AgentTaskMemberMapper extends BaseMapper<AgentTaskMemberEntity> {
    @Select("""
            SELECT *
            FROM agent_task_member
            WHERE (tenant_id = #{tenantId} OR tenant_id = '0')
              AND client_id = #{clientId}
              AND task_id = #{taskId}
              AND agent_id = #{agentId}
              AND (CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY) OR tenant_id = '0')
              AND (OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId}) OR tenant_id = '0')
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(task_id AS BINARY) = CAST(#{taskId} AS BINARY)
              AND OCTET_LENGTH(task_id) = OCTET_LENGTH(#{taskId})
              AND CAST(agent_id AS BINARY) = CAST(#{agentId} AS BINARY)
              AND OCTET_LENGTH(agent_id) = OCTET_LENGTH(#{agentId})
            LIMIT 1
            """)
    AgentTaskMemberEntity findExactByTaskAndAgent(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("taskId") String taskId,
            @Param("agentId") String agentId);

    @Select("""
            SELECT *
            FROM agent_task_member
            WHERE (tenant_id = #{tenantId} OR tenant_id = '0')
              AND client_id = #{clientId}
              AND task_id = #{taskId}
              AND agent_id = #{agentId}
              AND (CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY) OR tenant_id = '0')
              AND (OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId}) OR tenant_id = '0')
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(task_id AS BINARY) = CAST(#{taskId} AS BINARY)
              AND OCTET_LENGTH(task_id) = OCTET_LENGTH(#{taskId})
              AND CAST(agent_id AS BINARY) = CAST(#{agentId} AS BINARY)
              AND OCTET_LENGTH(agent_id) = OCTET_LENGTH(#{agentId})
            LIMIT 1
            FOR UPDATE
            """)
    AgentTaskMemberEntity findExactByTaskAndAgentForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("taskId") String taskId,
            @Param("agentId") String agentId);

    @Update("""
            UPDATE agent_task_member
            SET member_role = #{member.memberRole},
                member_status = #{member.memberStatus},
                assignment_source = #{member.assignmentSource},
                joined_at = #{member.joinedAt},
                accepted_at = #{member.acceptedAt},
                started_at = #{member.startedAt},
                completed_at = #{member.completedAt},
                last_heartbeat_at = #{member.lastHeartbeatAt},
                failure_reason = #{member.failureReason},
                update_time = #{updateTime},
                version = version + 1
            WHERE (tenant_id = #{tenantId} OR tenant_id = '0')
              AND client_id = #{clientId}
              AND task_id = #{taskId}
              AND agent_id = #{agentId}
              AND version = #{expectedVersion}
            """)
    int updateByVersion(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("taskId") String taskId,
            @Param("agentId") String agentId,
            @Param("expectedVersion") long expectedVersion,
            @Param("member") AgentTaskMemberDTO member,
            @Param("updateTime") long updateTime);
}
