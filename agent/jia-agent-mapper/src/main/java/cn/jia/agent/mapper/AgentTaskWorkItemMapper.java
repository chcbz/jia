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

    @Update("""
            UPDATE agent_task_work_item
            SET title = #{item.title}, description = #{item.description},
                work_type = #{item.workType}, required_abilities = #{item.requiredAbilities},
                assignee_agent_id = #{item.assigneeAgentId}, status = #{item.status},
                priority = #{item.priority}, required_item = #{item.requiredItem},
                dependency_json = #{item.dependencyJson}, lease_token = #{item.leaseToken},
                lease_until = #{item.leaseUntil}, attempt_count = #{item.attemptCount},
                max_attempts = #{item.maxAttempts}, result_artifact_id = #{item.resultArtifactId},
                submitted_at = #{item.submittedAt}, completed_at = #{item.completedAt},
                update_time = #{updateTime}, version = version + 1
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND task_id = #{taskId}
              AND work_item_id = #{workItemId}
              AND status = 'ready'
              AND assignee_agent_id IS NULL
              AND lease_token IS NULL
              AND lease_until IS NULL
              AND version = #{expectedVersion}
            """)
    int claimReadyUnassignedByVersion(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("taskId") String taskId,
            @Param("workItemId") String workItemId,
            @Param("expectedVersion") long expectedVersion,
            @Param("item") AgentTaskWorkItemDTO item,
            @Param("updateTime") long updateTime);

    @Update("""
            UPDATE agent_task_work_item
            SET title = #{item.title}, description = #{item.description},
                work_type = #{item.workType}, required_abilities = #{item.requiredAbilities},
                assignee_agent_id = #{item.assigneeAgentId}, status = #{item.status},
                priority = #{item.priority}, required_item = #{item.requiredItem},
                dependency_json = #{item.dependencyJson}, lease_token = #{item.leaseToken},
                lease_until = #{item.leaseUntil}, attempt_count = #{item.attemptCount},
                max_attempts = #{item.maxAttempts}, result_artifact_id = #{item.resultArtifactId},
                submitted_at = #{item.submittedAt}, completed_at = #{item.completedAt},
                update_time = #{updateTime}, version = version + 1
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND task_id = #{taskId}
              AND work_item_id = #{workItemId}
              AND status = 'ready'
              AND assignee_agent_id = #{expectedAssigneeAgentId}
              AND lease_token IS NULL
              AND lease_until IS NULL
              AND version = #{expectedVersion}
            """)
    int claimReadyAssignedByVersion(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("taskId") String taskId,
            @Param("workItemId") String workItemId,
            @Param("expectedAssigneeAgentId") String expectedAssigneeAgentId,
            @Param("expectedVersion") long expectedVersion,
            @Param("item") AgentTaskWorkItemDTO item,
            @Param("updateTime") long updateTime);

    @Update("""
            UPDATE agent_task_work_item
            SET title = #{item.title}, description = #{item.description},
                work_type = #{item.workType}, required_abilities = #{item.requiredAbilities},
                assignee_agent_id = #{item.assigneeAgentId}, status = #{item.status},
                priority = #{item.priority}, required_item = #{item.requiredItem},
                dependency_json = #{item.dependencyJson}, lease_token = #{item.leaseToken},
                lease_until = #{item.leaseUntil}, attempt_count = #{item.attemptCount},
                max_attempts = #{item.maxAttempts}, result_artifact_id = #{item.resultArtifactId},
                submitted_at = #{item.submittedAt}, completed_at = #{item.completedAt},
                update_time = #{updateTime}, version = version + 1
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND task_id = #{taskId}
              AND work_item_id = #{workItemId}
              AND assignee_agent_id = #{assigneeAgentId}
              AND lease_token = #{leaseToken}
              AND status = #{expectedStatus}
              AND lease_until = #{expectedLeaseUntil}
              AND lease_until > #{operationTime}
              AND version = #{expectedVersion}
            """)
    int updateActiveLeaseByVersion(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("taskId") String taskId,
            @Param("workItemId") String workItemId,
            @Param("assigneeAgentId") String assigneeAgentId,
            @Param("leaseToken") String leaseToken,
            @Param("expectedStatus") String expectedStatus,
            @Param("expectedLeaseUntil") long expectedLeaseUntil,
            @Param("expectedVersion") long expectedVersion,
            @Param("operationTime") long operationTime,
            @Param("item") AgentTaskWorkItemDTO item,
            @Param("updateTime") long updateTime);

    @Update("""
            UPDATE agent_task_work_item
            SET title = #{item.title}, description = #{item.description},
                work_type = #{item.workType}, required_abilities = #{item.requiredAbilities},
                assignee_agent_id = #{item.assigneeAgentId}, status = #{item.status},
                priority = #{item.priority}, required_item = #{item.requiredItem},
                dependency_json = #{item.dependencyJson}, lease_token = #{item.leaseToken},
                lease_until = #{item.leaseUntil}, attempt_count = #{item.attemptCount},
                max_attempts = #{item.maxAttempts}, result_artifact_id = #{item.resultArtifactId},
                submitted_at = #{item.submittedAt}, completed_at = #{item.completedAt},
                update_time = #{updateTime}, version = version + 1
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND task_id = #{taskId}
              AND work_item_id = #{workItemId}
              AND assignee_agent_id = #{assigneeAgentId}
              AND lease_token = #{leaseToken}
              AND status = #{expectedStatus}
              AND lease_until = #{expectedLeaseUntil}
              AND lease_until <= #{expiredAtOrBefore}
              AND version = #{expectedVersion}
            """)
    int expireLeaseByVersion(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("taskId") String taskId,
            @Param("workItemId") String workItemId,
            @Param("assigneeAgentId") String assigneeAgentId,
            @Param("leaseToken") String leaseToken,
            @Param("expectedStatus") String expectedStatus,
            @Param("expectedLeaseUntil") long expectedLeaseUntil,
            @Param("expectedVersion") long expectedVersion,
            @Param("expiredAtOrBefore") long expiredAtOrBefore,
            @Param("item") AgentTaskWorkItemDTO item,
            @Param("updateTime") long updateTime);
}
