package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentTaskAggregationSnapshotRow;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface AgentTaskMetaMapper extends BaseMapper<AgentTaskMetaEntity> {
    @Select("""
            SELECT *
            FROM agent_task_meta
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND task_id = #{taskId}
              AND CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(task_id AS BINARY) = CAST(#{taskId} AS BINARY)
              AND OCTET_LENGTH(task_id) = OCTET_LENGTH(#{taskId})
            LIMIT 1
            """)
    AgentTaskMetaEntity findExactByTaskScope(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("taskId") String taskId);

    @Insert("""
            INSERT IGNORE INTO agent_task_meta
                (task_id, reward_status, collaboration_mode, risk_level, max_agents,
                 review_required, task_version, current_event_version,
                 tenant_id, client_id, create_time, update_time)
            VALUES
                (#{taskId}, 'open', 'single', 'low', 1,
                 0, 0, 0, #{tenantId}, #{clientId}, #{createTime}, #{createTime})
            """)
    int reserveOpenTaskRoot(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("taskId") String taskId,
            @Param("createTime") long createTime);

    @Update("""
            UPDATE agent_task_meta
            SET task_id = #{finalTaskId},
                update_time = #{updateTime}
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND task_id = #{reservedTaskId}
              AND CAST(tenant_id AS BINARY(200)) = CAST(#{tenantId} AS BINARY(200))
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY(200)) = CAST(#{clientId} AS BINARY(200))
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(SUBSTRING(task_id, 1, 50) AS BINARY(200))
                  = CAST(SUBSTRING(#{reservedTaskId}, 1, 50) AS BINARY(200))
              AND CAST(SUBSTRING(task_id, 51, 50) AS BINARY(200))
                  = CAST(SUBSTRING(#{reservedTaskId}, 51, 50) AS BINARY(200))
              AND OCTET_LENGTH(task_id) = OCTET_LENGTH(#{reservedTaskId})
              AND reward_status = 'open'
              AND CAST(reward_status AS BINARY) = CAST('open' AS BINARY)
              AND OCTET_LENGTH(reward_status) = OCTET_LENGTH('open')
              AND task_version = 0
              AND current_event_version = 0
            """)
    int rekeyReservedTaskRoot(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("reservedTaskId") String reservedTaskId,
            @Param("finalTaskId") String finalTaskId,
            @Param("updateTime") long updateTime);

    @Delete("""
            DELETE FROM agent_task_meta
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND task_id = #{reservedTaskId}
              AND CAST(tenant_id AS BINARY(200)) = CAST(#{tenantId} AS BINARY(200))
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY(200)) = CAST(#{clientId} AS BINARY(200))
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(SUBSTRING(task_id, 1, 50) AS BINARY(200))
                  = CAST(SUBSTRING(#{reservedTaskId}, 1, 50) AS BINARY(200))
              AND CAST(SUBSTRING(task_id, 51, 50) AS BINARY(200))
                  = CAST(SUBSTRING(#{reservedTaskId}, 51, 50) AS BINARY(200))
              AND OCTET_LENGTH(task_id) = OCTET_LENGTH(#{reservedTaskId})
              AND reward_status = 'open'
              AND CAST(reward_status AS BINARY) = CAST('open' AS BINARY)
              AND OCTET_LENGTH(reward_status) = OCTET_LENGTH('open')
              AND task_version = 0
              AND current_event_version = 0
            """)
    int deleteReservedTaskRoot(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("reservedTaskId") String reservedTaskId);

    @Select("""
            SELECT *
            FROM agent_task_meta
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND task_id = #{taskId}
              AND CAST(tenant_id AS BINARY(200)) = CAST(#{tenantId} AS BINARY(200))
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY(200)) = CAST(#{clientId} AS BINARY(200))
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(SUBSTRING(task_id, 1, 50) AS BINARY(200))
                  = CAST(SUBSTRING(#{taskId}, 1, 50) AS BINARY(200))
              AND CAST(SUBSTRING(task_id, 51, 50) AS BINARY(200))
                  = CAST(SUBSTRING(#{taskId}, 51, 50) AS BINARY(200))
              AND OCTET_LENGTH(task_id) = OCTET_LENGTH(#{taskId})
            LIMIT 1
            FOR UPDATE
            """)
    AgentTaskMetaEntity findExactByTaskScopeForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("taskId") String taskId);

    @Select("""
            SELECT *
            FROM agent_task_meta
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND assigned_agent_id = #{agentId}
              AND CAST(tenant_id AS BINARY(200)) = CAST(#{tenantId} AS BINARY(200))
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY(200)) = CAST(#{clientId} AS BINARY(200))
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(assigned_agent_id AS BINARY(400)) = CAST(#{agentId} AS BINARY(400))
              AND OCTET_LENGTH(assigned_agent_id) = OCTET_LENGTH(#{agentId})
            ORDER BY update_time DESC, task_id ASC, id ASC
            LIMIT #{limit}
            """)
    List<AgentTaskMetaEntity> selectByAgentInScope(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("agentId") String agentId,
            @Param("limit") int limit);

    @Select("""
            SELECT parent.*
            FROM agent_task_meta parent
            WHERE parent.tenant_id = #{tenantId}
              AND parent.client_id = #{clientId}
              AND CAST(parent.tenant_id AS BINARY(200)) = CAST(#{tenantId} AS BINARY(200))
              AND OCTET_LENGTH(parent.tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(parent.client_id AS BINARY(200)) = CAST(#{clientId} AS BINARY(200))
              AND OCTET_LENGTH(parent.client_id) = OCTET_LENGTH(#{clientId})
              AND EXISTS (
                  SELECT 1
                  FROM agent_task_work_item child
                  WHERE child.tenant_id = #{tenantId}
                    AND child.client_id = #{clientId}
                    AND child.work_item_id = #{workItemId}
                    AND CAST(child.tenant_id AS BINARY(200)) = CAST(#{tenantId} AS BINARY(200))
                    AND OCTET_LENGTH(child.tenant_id) = OCTET_LENGTH(#{tenantId})
                    AND CAST(child.client_id AS BINARY(200)) = CAST(#{clientId} AS BINARY(200))
                    AND OCTET_LENGTH(child.client_id) = OCTET_LENGTH(#{clientId})
                    AND child.tenant_id = parent.tenant_id
                    AND CAST(child.tenant_id AS BINARY(200))
                        = CAST(parent.tenant_id AS BINARY(200))
                    AND OCTET_LENGTH(child.tenant_id) = OCTET_LENGTH(parent.tenant_id)
                    AND child.client_id = parent.client_id
                    AND CAST(child.client_id AS BINARY(200))
                        = CAST(parent.client_id AS BINARY(200))
                    AND OCTET_LENGTH(child.client_id) = OCTET_LENGTH(parent.client_id)
                    AND CAST(SUBSTRING(child.work_item_id, 1, 50) AS BINARY(200))
                        = CAST(SUBSTRING(#{workItemId}, 1, 50) AS BINARY(200))
                    AND CAST(SUBSTRING(child.work_item_id, 51, 50) AS BINARY(200))
                        = CAST(SUBSTRING(#{workItemId}, 51, 50) AS BINARY(200))
                    AND OCTET_LENGTH(child.work_item_id) = OCTET_LENGTH(#{workItemId})
                    AND child.task_id = parent.task_id
                    AND CAST(SUBSTRING(child.task_id, 1, 50) AS BINARY(200))
                        = CAST(SUBSTRING(parent.task_id, 1, 50) AS BINARY(200))
                    AND CAST(SUBSTRING(child.task_id, 51, 50) AS BINARY(200))
                        = CAST(SUBSTRING(parent.task_id, 51, 50) AS BINARY(200))
                    AND OCTET_LENGTH(child.task_id) = OCTET_LENGTH(parent.task_id)
              )
            ORDER BY parent.task_id ASC, parent.id ASC
            LIMIT 1
            FOR UPDATE
            """)
    AgentTaskMetaEntity findTaskRootByWorkItemForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("workItemId") String workItemId);

    @Select("""
            SELECT 'member' AS row_type, tenant_id, client_id, task_id,
                   agent_id AS entity_id, member_role AS role, member_status AS status,
                   NULL AS required_item, NULL AS attempt_count, NULL AS max_attempts,
                   NULL AS result_artifact_id, completed_at, version,
                   NULL AS title, NULL AS work_type, NULL AS lease_token,
                   NULL AS lease_until, NULL AS assignee_agent_id
            FROM agent_task_member
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND task_id = #{taskId}
              AND CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(task_id AS BINARY) = CAST(#{taskId} AS BINARY)
              AND OCTET_LENGTH(task_id) = OCTET_LENGTH(#{taskId})
            UNION ALL
            SELECT 'work_item' AS row_type, tenant_id, client_id, task_id,
                   work_item_id AS entity_id, NULL AS role, status, required_item,
                   attempt_count, max_attempts, result_artifact_id, completed_at, version,
                   title, work_type, lease_token, lease_until, assignee_agent_id
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
            ORDER BY row_type, entity_id
            """)
    List<AgentTaskAggregationSnapshotRow> selectAggregationSnapshot(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("taskId") String taskId);

    @Update("""
            UPDATE agent_task_meta
            SET reward_status = #{rewardStatus},
                started_at = #{startedAt},
                completed_at = #{completedAt},
                failure_reason = #{failureReason},
                update_time = #{updateTime},
                task_version = task_version + 1
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND task_id = #{taskId}
              AND CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(task_id AS BINARY) = CAST(#{taskId} AS BINARY)
              AND OCTET_LENGTH(task_id) = OCTET_LENGTH(#{taskId})
              AND task_version = #{expectedVersion}
            """)
    int updateStatusByVersion(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("taskId") String taskId,
            @Param("expectedVersion") long expectedVersion,
            @Param("rewardStatus") String rewardStatus,
            @Param("startedAt") Long startedAt,
            @Param("completedAt") Long completedAt,
            @Param("failureReason") String failureReason,
            @Param("updateTime") long updateTime);
}
