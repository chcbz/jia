package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentTaskWorkItemDTO;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface AgentTaskWorkItemMapper extends BaseMapper<AgentTaskWorkItemEntity> {
    @Update("""
            UPDATE agent_task_work_item
            SET title = #{item.title},
                description = #{item.description},
                work_type = #{item.workType},
                required_abilities = #{item.requiredAbilities},
                assignee_agent_id = #{item.assigneeAgentId},
                status = #{item.status},
                priority = #{item.priority},
                required_item = #{item.requiredItem},
                dependency_json = #{item.dependencyJson},
                lease_token = #{item.leaseToken},
                lease_until = #{item.leaseUntil},
                attempt_count = #{item.attemptCount},
                max_attempts = #{item.maxAttempts},
                result_artifact_id = #{item.resultArtifactId},
                submitted_at = #{item.submittedAt},
                completed_at = #{item.completedAt},
                update_time = #{updateTime},
                version = version + 1
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND work_item_id = #{workItemId}
              AND version = #{expectedVersion}
            """)
    int updateByVersion(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("workItemId") String workItemId,
            @Param("expectedVersion") long expectedVersion,
            @Param("item") AgentTaskWorkItemDTO item,
            @Param("updateTime") long updateTime);
}
