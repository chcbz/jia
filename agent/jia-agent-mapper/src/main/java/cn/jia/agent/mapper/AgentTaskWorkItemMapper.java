package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentTaskWorkItemDTO;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AgentTaskWorkItemMapper extends BaseMapper<AgentTaskWorkItemEntity> {
    @Insert("""
            INSERT INTO agent_task_work_item
                (work_item_id, task_id, title, description, work_type, required_abilities,
                 assignee_agent_id, status, priority, required_item, dependency_json,
                 lease_token, lease_until, attempt_count, max_attempts,
                 result_artifact_id, submitted_at, completed_at, version,
                 tenant_id, client_id, create_time, update_time)
            SELECT #{item.workItemId}, #{item.taskId}, #{item.title}, #{item.description},
                   #{item.workType}, #{item.requiredAbilities}, #{item.assigneeAgentId},
                   #{item.status}, #{item.priority}, #{item.requiredItem},
                   #{item.dependencyJson}, #{item.leaseToken}, #{item.leaseUntil},
                   #{item.attemptCount}, #{item.maxAttempts}, #{item.resultArtifactId},
                   #{item.submittedAt}, #{item.completedAt}, #{item.version},
                   #{tenantId}, #{clientId}, #{item.createTime}, #{item.updateTime}
            FROM agent_task_meta parent
            WHERE parent.tenant_id = #{tenantId}
              AND parent.client_id = #{clientId}
              AND parent.task_id = #{item.taskId}
              AND CAST(parent.tenant_id AS BINARY(200)) = CAST(#{tenantId} AS BINARY(200))
              AND OCTET_LENGTH(parent.tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(parent.client_id AS BINARY(200)) = CAST(#{clientId} AS BINARY(200))
              AND OCTET_LENGTH(parent.client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(SUBSTRING(parent.task_id, 1, 50) AS BINARY(200))
                  = CAST(SUBSTRING(#{item.taskId}, 1, 50) AS BINARY(200))
              AND CAST(SUBSTRING(parent.task_id, 51, 50) AS BINARY(200))
                  = CAST(SUBSTRING(#{item.taskId}, 51, 50) AS BINARY(200))
              AND OCTET_LENGTH(parent.task_id) = OCTET_LENGTH(#{item.taskId})
              AND (
                  (CAST(parent.reward_status AS BINARY(80)) = CAST('open' AS BINARY(80))
                   AND OCTET_LENGTH(parent.reward_status) = OCTET_LENGTH('open'))
                  OR (CAST(parent.reward_status AS BINARY(80)) = CAST('planning' AS BINARY(80))
                      AND OCTET_LENGTH(parent.reward_status) = OCTET_LENGTH('planning'))
                  OR (CAST(parent.reward_status AS BINARY(80)) = CAST('assigned' AS BINARY(80))
                      AND OCTET_LENGTH(parent.reward_status) = OCTET_LENGTH('assigned'))
                  OR (CAST(parent.reward_status AS BINARY(80)) = CAST('running' AS BINARY(80))
                      AND OCTET_LENGTH(parent.reward_status) = OCTET_LENGTH('running'))
                  OR (CAST(parent.reward_status AS BINARY(80)) = CAST('reviewing' AS BINARY(80))
                      AND OCTET_LENGTH(parent.reward_status) = OCTET_LENGTH('reviewing'))
                  OR (CAST(parent.reward_status AS BINARY(80)) = CAST('blocked' AS BINARY(80))
                      AND OCTET_LENGTH(parent.reward_status) = OCTET_LENGTH('blocked'))
              )
            FOR UPDATE
            """)
    int insertIfParentNonTerminal(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("item") AgentTaskWorkItemEntity item);

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
              AND CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(work_item_id AS BINARY) = CAST(#{workItemId} AS BINARY)
              AND OCTET_LENGTH(work_item_id) = OCTET_LENGTH(#{workItemId})
              AND version = #{expectedVersion}
            """)
    int updateByVersion(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("workItemId") String workItemId,
            @Param("expectedVersion") long expectedVersion,
            @Param("item") AgentTaskWorkItemDTO item,
            @Param("updateTime") long updateTime);

    @Select("""
            SELECT *
            FROM agent_task_work_item
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND task_id = #{taskId}
              AND CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(task_id AS BINARY) = CAST(#{taskId} AS BINARY)
              AND OCTET_LENGTH(task_id) = OCTET_LENGTH(#{taskId})
            ORDER BY CAST(work_item_id AS BINARY), id
            LIMIT #{limit}
            FOR UPDATE
            """)
    java.util.List<AgentTaskWorkItemEntity> selectTaskGraphForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("taskId") String taskId,
            @Param("limit") int limit);

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
                update_time = #{changedAt}, version = version + 1
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND task_id = #{taskId}
              AND work_item_id = #{workItemId}
              AND CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(task_id AS BINARY) = CAST(#{taskId} AS BINARY)
              AND OCTET_LENGTH(task_id) = OCTET_LENGTH(#{taskId})
              AND CAST(work_item_id AS BINARY) = CAST(#{workItemId} AS BINARY)
              AND OCTET_LENGTH(work_item_id) = OCTET_LENGTH(#{workItemId})
              AND status = 'pending'
              AND CAST(status AS BINARY) = CAST('pending' AS BINARY)
              AND OCTET_LENGTH(status) = OCTET_LENGTH('pending')
              AND lease_token IS NULL
              AND lease_until IS NULL
              AND version = #{expectedVersion}
            """)
    int readyPendingByVersion(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("taskId") String taskId,
            @Param("workItemId") String workItemId,
            @Param("expectedVersion") long expectedVersion,
            @Param("item") AgentTaskWorkItemDTO item,
            @Param("changedAt") long changedAt);

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
              AND CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(task_id AS BINARY) = CAST(#{taskId} AS BINARY)
              AND OCTET_LENGTH(task_id) = OCTET_LENGTH(#{taskId})
              AND CAST(work_item_id AS BINARY) = CAST(#{workItemId} AS BINARY)
              AND OCTET_LENGTH(work_item_id) = OCTET_LENGTH(#{workItemId})
              AND status = 'ready'
              AND CAST(status AS BINARY) = CAST('ready' AS BINARY)
              AND OCTET_LENGTH(status) = OCTET_LENGTH('ready')
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
              AND CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(task_id AS BINARY) = CAST(#{taskId} AS BINARY)
              AND OCTET_LENGTH(task_id) = OCTET_LENGTH(#{taskId})
              AND CAST(work_item_id AS BINARY) = CAST(#{workItemId} AS BINARY)
              AND OCTET_LENGTH(work_item_id) = OCTET_LENGTH(#{workItemId})
              AND status = 'ready'
              AND CAST(status AS BINARY) = CAST('ready' AS BINARY)
              AND OCTET_LENGTH(status) = OCTET_LENGTH('ready')
              AND assignee_agent_id = #{expectedAssigneeAgentId}
              AND CAST(assignee_agent_id AS BINARY) = CAST(#{expectedAssigneeAgentId} AS BINARY)
              AND OCTET_LENGTH(assignee_agent_id) = OCTET_LENGTH(#{expectedAssigneeAgentId})
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
              AND CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(task_id AS BINARY) = CAST(#{taskId} AS BINARY)
              AND OCTET_LENGTH(task_id) = OCTET_LENGTH(#{taskId})
              AND CAST(work_item_id AS BINARY) = CAST(#{workItemId} AS BINARY)
              AND OCTET_LENGTH(work_item_id) = OCTET_LENGTH(#{workItemId})
              AND assignee_agent_id = #{assigneeAgentId}
              AND CAST(assignee_agent_id AS BINARY) = CAST(#{assigneeAgentId} AS BINARY)
              AND OCTET_LENGTH(assignee_agent_id) = OCTET_LENGTH(#{assigneeAgentId})
              AND lease_token = #{leaseToken}
              AND CAST(lease_token AS BINARY) = CAST(#{leaseToken} AS BINARY)
              AND OCTET_LENGTH(lease_token) = OCTET_LENGTH(#{leaseToken})
              AND status = #{expectedStatus}
              AND CAST(status AS BINARY) = CAST(#{expectedStatus} AS BINARY)
              AND OCTET_LENGTH(status) = OCTET_LENGTH(#{expectedStatus})
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
              AND CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(task_id AS BINARY) = CAST(#{taskId} AS BINARY)
              AND OCTET_LENGTH(task_id) = OCTET_LENGTH(#{taskId})
              AND CAST(work_item_id AS BINARY) = CAST(#{workItemId} AS BINARY)
              AND OCTET_LENGTH(work_item_id) = OCTET_LENGTH(#{workItemId})
              AND assignee_agent_id = #{assigneeAgentId}
              AND CAST(assignee_agent_id AS BINARY) = CAST(#{assigneeAgentId} AS BINARY)
              AND OCTET_LENGTH(assignee_agent_id) = OCTET_LENGTH(#{assigneeAgentId})
              AND lease_token = #{leaseToken}
              AND CAST(lease_token AS BINARY) = CAST(#{leaseToken} AS BINARY)
              AND OCTET_LENGTH(lease_token) = OCTET_LENGTH(#{leaseToken})
              AND status = #{expectedStatus}
              AND CAST(status AS BINARY) = CAST(#{expectedStatus} AS BINARY)
              AND OCTET_LENGTH(status) = OCTET_LENGTH(#{expectedStatus})
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
