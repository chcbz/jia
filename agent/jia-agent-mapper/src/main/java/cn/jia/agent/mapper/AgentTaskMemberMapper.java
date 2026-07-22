package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentTaskMemberDTO;
import cn.jia.agent.entity.AgentTaskMemberEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface AgentTaskMemberMapper extends BaseMapper<AgentTaskMemberEntity> {
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
            WHERE tenant_id = #{tenantId}
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
