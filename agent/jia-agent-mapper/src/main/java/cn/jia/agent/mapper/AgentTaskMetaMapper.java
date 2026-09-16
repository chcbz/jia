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

    @Select("""
            SELECT *
            FROM agent_task_meta
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND owner_jiacn = #{ownerJiacn}
              AND task_id = #{taskId}
              AND CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(owner_jiacn AS BINARY) = CAST(#{ownerJiacn} AS BINARY)
              AND OCTET_LENGTH(owner_jiacn) = OCTET_LENGTH(#{ownerJiacn})
              AND CAST(task_id AS BINARY) = CAST(#{taskId} AS BINARY)
              AND OCTET_LENGTH(task_id) = OCTET_LENGTH(#{taskId})
            LIMIT 1
            """)
    AgentTaskMetaEntity findExactByOwnerTaskScope(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn,
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

    @Insert("""
            INSERT IGNORE INTO agent_task_meta
                (task_id, owner_jiacn, reward_status, collaboration_mode, risk_level, max_agents,
                 review_required, task_version, current_event_version,
                 tenant_id, client_id, create_time, update_time)
            VALUES
                (#{taskId}, #{ownerJiacn}, 'open', 'single', 'low', 1,
                 0, 0, 0, #{tenantId}, #{clientId}, #{createTime}, #{createTime})
            """)
    int reserveOpenTaskRootInOwnerScope(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn,
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

    @Update("""
            UPDATE agent_task_meta
            SET task_id = #{finalTaskId},
                update_time = #{updateTime}
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND owner_jiacn = #{ownerJiacn}
              AND task_id = #{reservedTaskId}
              AND CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(owner_jiacn AS BINARY) = CAST(#{ownerJiacn} AS BINARY)
              AND OCTET_LENGTH(owner_jiacn) = OCTET_LENGTH(#{ownerJiacn})
              AND CAST(task_id AS BINARY) = CAST(#{reservedTaskId} AS BINARY)
              AND OCTET_LENGTH(task_id) = OCTET_LENGTH(#{reservedTaskId})
              AND reward_status = 'open'
              AND task_version = 0
              AND current_event_version = 0
            """)
    int rekeyReservedTaskRootInOwnerScope(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn,
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
              AND owner_jiacn = #{ownerJiacn}
              AND task_id = #{taskId}
              AND CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(owner_jiacn AS BINARY) = CAST(#{ownerJiacn} AS BINARY)
              AND OCTET_LENGTH(owner_jiacn) = OCTET_LENGTH(#{ownerJiacn})
              AND CAST(task_id AS BINARY) = CAST(#{taskId} AS BINARY)
              AND OCTET_LENGTH(task_id) = OCTET_LENGTH(#{taskId})
            LIMIT 1
            FOR UPDATE
            """)
    AgentTaskMetaEntity findExactByOwnerTaskScopeForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn,
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
            <script>
            SELECT task.tenant_id AS tenantId,
                   task.client_id AS clientId,
                   task.assigned_agent_id AS agentId,
                   COUNT(*) AS taskCount,
                   SUM(CASE
                         WHEN CAST(task.reward_status AS BINARY) = CAST('completed' AS BINARY)
                          AND OCTET_LENGTH(task.reward_status) = OCTET_LENGTH('completed')
                         THEN 1 ELSE 0 END) AS completedTaskCount,
                   SUM(CASE
                         WHEN CAST(task.reward_status AS BINARY) = CAST('failed' AS BINARY)
                          AND OCTET_LENGTH(task.reward_status) = OCTET_LENGTH('failed')
                         THEN 1 ELSE 0 END) AS failedTaskCount,
                   SUM(CASE
                         WHEN CAST(task.reward_status AS BINARY) = CAST('completed' AS BINARY)
                          AND OCTET_LENGTH(task.reward_status) = OCTET_LENGTH('completed')
                          AND task.started_at IS NOT NULL
                          AND task.completed_at IS NOT NULL
                          AND task.completed_at &gt;= task.started_at
                         THEN 1 ELSE 0 END) AS completedDurationCount,
                   COALESCE(SUM(CASE
                         WHEN CAST(task.reward_status AS BINARY) = CAST('completed' AS BINARY)
                          AND OCTET_LENGTH(task.reward_status) = OCTET_LENGTH('completed')
                          AND task.started_at IS NOT NULL
                          AND task.completed_at IS NOT NULL
                          AND task.completed_at &gt;= task.started_at
                         THEN FLOOR((task.completed_at - task.started_at) / 1000)
                         ELSE 0 END), 0) AS completedDurationSeconds
            FROM agent_task_meta task
            WHERE
            <foreach collection="scopes" item="scope" open="(" separator=" OR " close=")">
              (task.tenant_id = #{scope.tenantId}
               AND task.client_id = #{scope.clientId}
               AND task.assigned_agent_id = #{scope.agentId}
               AND CAST(task.tenant_id AS BINARY) = CAST(#{scope.tenantId} AS BINARY)
               AND OCTET_LENGTH(task.tenant_id) = OCTET_LENGTH(#{scope.tenantId})
               AND CAST(task.client_id AS BINARY) = CAST(#{scope.clientId} AS BINARY)
               AND OCTET_LENGTH(task.client_id) = OCTET_LENGTH(#{scope.clientId})
               AND CAST(task.assigned_agent_id AS BINARY) = CAST(#{scope.agentId} AS BINARY)
               AND OCTET_LENGTH(task.assigned_agent_id) = OCTET_LENGTH(#{scope.agentId}))
            </foreach>
            GROUP BY task.tenant_id, CAST(task.tenant_id AS BINARY), OCTET_LENGTH(task.tenant_id),
                     task.client_id, CAST(task.client_id AS BINARY), OCTET_LENGTH(task.client_id),
                     task.assigned_agent_id, CAST(task.assigned_agent_id AS BINARY),
                     OCTET_LENGTH(task.assigned_agent_id)
            </script>
            """)
    List<AgentTaskStatsRow> selectStatsByAgentScopes(
            @Param("scopes") List<AgentTaskStatsScope> scopes);

    @Select("""
            SELECT task.*
            FROM agent_task_meta task
            WHERE task.tenant_id = #{tenantId}
              AND task.client_id = #{clientId}
              AND CAST(task.tenant_id AS BINARY(200)) = CAST(#{tenantId} AS BINARY(200))
              AND OCTET_LENGTH(task.tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(task.client_id AS BINARY(200)) = CAST(#{clientId} AS BINARY(200))
              AND OCTET_LENGTH(task.client_id) = OCTET_LENGTH(#{clientId})
              AND (
                  (
                      task.assigned_agent_id = #{agentId}
                      AND CAST(task.assigned_agent_id AS BINARY(400)) = CAST(#{agentId} AS BINARY(400))
                      AND OCTET_LENGTH(task.assigned_agent_id) = OCTET_LENGTH(#{agentId})
                      AND CAST(task.reward_status AS BINARY) IN (
                          CAST('open' AS BINARY), CAST('planning' AS BINARY),
                          CAST('assigned' AS BINARY), CAST('running' AS BINARY),
                          CAST('reviewing' AS BINARY), CAST('blocked' AS BINARY)
                      )
                  )
                  OR EXISTS (
                      SELECT 1
                      FROM agent_task_member member
                      WHERE member.tenant_id = #{tenantId}
                        AND member.client_id = #{clientId}
                        AND member.agent_id = #{agentId}
                        AND member.task_id = task.task_id
                        AND CAST(member.tenant_id AS BINARY(200)) = CAST(#{tenantId} AS BINARY(200))
                        AND OCTET_LENGTH(member.tenant_id) = OCTET_LENGTH(#{tenantId})
                        AND CAST(member.client_id AS BINARY(200)) = CAST(#{clientId} AS BINARY(200))
                        AND OCTET_LENGTH(member.client_id) = OCTET_LENGTH(#{clientId})
                        AND CAST(member.agent_id AS BINARY(400)) = CAST(#{agentId} AS BINARY(400))
                        AND OCTET_LENGTH(member.agent_id) = OCTET_LENGTH(#{agentId})
                        AND CAST(member.task_id AS BINARY(400)) = CAST(task.task_id AS BINARY(400))
                        AND OCTET_LENGTH(member.task_id) = OCTET_LENGTH(task.task_id)
                        AND CAST(member.member_status AS BINARY) IN (
                            CAST('accepted' AS BINARY), CAST('working' AS BINARY),
                            CAST('blocked' AS BINARY)
                        )
                  )
                  OR EXISTS (
                      SELECT 1
                      FROM agent_task_work_item work_item
                      WHERE work_item.tenant_id = #{tenantId}
                        AND work_item.client_id = #{clientId}
                        AND work_item.assignee_agent_id = #{agentId}
                        AND work_item.task_id = task.task_id
                        AND CAST(work_item.tenant_id AS BINARY(200)) = CAST(#{tenantId} AS BINARY(200))
                        AND OCTET_LENGTH(work_item.tenant_id) = OCTET_LENGTH(#{tenantId})
                        AND CAST(work_item.client_id AS BINARY(200)) = CAST(#{clientId} AS BINARY(200))
                        AND OCTET_LENGTH(work_item.client_id) = OCTET_LENGTH(#{clientId})
                        AND CAST(work_item.assignee_agent_id AS BINARY(400)) = CAST(#{agentId} AS BINARY(400))
                        AND OCTET_LENGTH(work_item.assignee_agent_id) = OCTET_LENGTH(#{agentId})
                        AND CAST(work_item.task_id AS BINARY(400)) = CAST(task.task_id AS BINARY(400))
                        AND OCTET_LENGTH(work_item.task_id) = OCTET_LENGTH(task.task_id)
                        AND CAST(work_item.status AS BINARY) IN (
                            CAST('pending' AS BINARY), CAST('ready' AS BINARY),
                            CAST('claimed' AS BINARY), CAST('running' AS BINARY),
                            CAST('blocked' AS BINARY), CAST('submitted' AS BINARY)
                        )
                  )
              )
            ORDER BY task.task_id ASC, task.id ASC
            LIMIT 1
            FOR UPDATE
            """)
    AgentTaskMetaEntity findDurableActiveAssignmentByAgentForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("agentId") String agentId);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM agent_task_meta task
            LEFT JOIN task_plan plan
              ON task.task_id REGEXP '^[0-9]+$'
             AND plan.id = CAST(task.task_id AS UNSIGNED)
             AND plan.jiacn = #{ownerJiacn}
             AND plan.client_id = #{clientId}
             AND CAST(plan.jiacn AS BINARY(200)) = CAST(#{ownerJiacn} AS BINARY(200))
             AND OCTET_LENGTH(plan.jiacn) = OCTET_LENGTH(#{ownerJiacn})
             AND CAST(plan.client_id AS BINARY(200)) = CAST(#{clientId} AS BINARY(200))
             AND OCTET_LENGTH(plan.client_id) = OCTET_LENGTH(#{clientId})
            WHERE task.tenant_id = #{tenantId}
              AND task.client_id = #{clientId}
              AND task.owner_jiacn = #{ownerJiacn}
              AND CAST(task.tenant_id AS BINARY(200)) = CAST(#{tenantId} AS BINARY(200))
              AND OCTET_LENGTH(task.tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(task.client_id AS BINARY(200)) = CAST(#{clientId} AS BINARY(200))
              AND CAST(task.owner_jiacn AS BINARY(200)) = CAST(#{ownerJiacn} AS BINARY(200))
              AND OCTET_LENGTH(task.client_id) = OCTET_LENGTH(#{clientId})
              AND OCTET_LENGTH(task.owner_jiacn) = OCTET_LENGTH(#{ownerJiacn})
            <if test="status != null and status.trim() != ''">
              AND task.reward_status = #{status}
            </if>
            <if test="ability != null and ability.trim() != ''">
              AND task.required_abilities LIKE CONCAT('%', '"', #{ability}, '"', '%')
            </if>
            <if test="keyword != null and keyword.trim() != ''">
              AND (
                    LOWER(task.task_id) LIKE CONCAT('%', LOWER(#{keyword}), '%')
                 OR LOWER(COALESCE(plan.name, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%')
                 OR LOWER(COALESCE(plan.description, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%')
                 OR LOWER(COALESCE(task.required_abilities, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%')
                 OR LOWER(COALESCE(
                      (
                          SELECT runtime.name
                          FROM agent_task_member member
                          LEFT JOIN agent_runtime runtime
                            ON runtime.agent_id = member.agent_id
                           AND runtime.tenant_id = #{tenantId}
                           AND runtime.client_id = #{clientId}
                           AND runtime.owner_jiacn = #{ownerJiacn}
                           AND CAST(runtime.tenant_id AS BINARY(200)) = CAST(#{tenantId} AS BINARY(200))
                           AND OCTET_LENGTH(runtime.tenant_id) = OCTET_LENGTH(#{tenantId})
                           AND CAST(runtime.client_id AS BINARY(200)) = CAST(#{clientId} AS BINARY(200))
                           AND OCTET_LENGTH(runtime.client_id) = OCTET_LENGTH(#{clientId})
                           AND CAST(runtime.owner_jiacn AS BINARY(200)) = CAST(#{ownerJiacn} AS BINARY(200))
                           AND OCTET_LENGTH(runtime.owner_jiacn) = OCTET_LENGTH(#{ownerJiacn})
                           AND CAST(runtime.agent_id AS BINARY(400)) = CAST(member.agent_id AS BINARY(400))
                           AND OCTET_LENGTH(runtime.agent_id) = OCTET_LENGTH(member.agent_id)
                          WHERE member.tenant_id = #{tenantId}
                            AND member.client_id = #{clientId}
                            AND member.owner_jiacn = #{ownerJiacn}
                            AND member.task_id = task.task_id
                            AND CAST(member.tenant_id AS BINARY(200)) = CAST(#{tenantId} AS BINARY(200))
                            AND OCTET_LENGTH(member.tenant_id) = OCTET_LENGTH(#{tenantId})
                            AND CAST(member.client_id AS BINARY(200)) = CAST(#{clientId} AS BINARY(200))
                            AND CAST(member.owner_jiacn AS BINARY(200)) = CAST(#{ownerJiacn} AS BINARY(200))
                            AND OCTET_LENGTH(member.client_id) = OCTET_LENGTH(#{clientId})
                            AND OCTET_LENGTH(member.owner_jiacn) = OCTET_LENGTH(#{ownerJiacn})
                            AND CAST(member.task_id AS BINARY(400)) = CAST(task.task_id AS BINARY(400))
                            AND OCTET_LENGTH(member.task_id) = OCTET_LENGTH(task.task_id)
                            AND member.member_status NOT IN ('rejected', 'left')
                          ORDER BY member.member_role ASC, member.agent_id ASC, member.id ASC
                          LIMIT 1
                      ),
                      CASE WHEN NOT EXISTS (
                          SELECT 1
                          FROM agent_task_member any_member
                          WHERE any_member.tenant_id = #{tenantId}
                            AND any_member.client_id = #{clientId}
                            AND any_member.owner_jiacn = #{ownerJiacn}
                            AND any_member.task_id = task.task_id
                            AND CAST(any_member.tenant_id AS BINARY(200)) = CAST(#{tenantId} AS BINARY(200))
                            AND OCTET_LENGTH(any_member.tenant_id) = OCTET_LENGTH(#{tenantId})
                            AND CAST(any_member.client_id AS BINARY(200)) = CAST(#{clientId} AS BINARY(200))
                            AND CAST(any_member.owner_jiacn AS BINARY(200)) = CAST(#{ownerJiacn} AS BINARY(200))
                            AND OCTET_LENGTH(any_member.client_id) = OCTET_LENGTH(#{clientId})
                            AND OCTET_LENGTH(any_member.owner_jiacn) = OCTET_LENGTH(#{ownerJiacn})
                            AND CAST(any_member.task_id AS BINARY(400)) = CAST(task.task_id AS BINARY(400))
                            AND OCTET_LENGTH(any_member.task_id) = OCTET_LENGTH(task.task_id)
                      ) THEN (
                          SELECT runtime.name
                          FROM agent_runtime runtime
                          WHERE runtime.agent_id = task.assigned_agent_id
                            AND runtime.tenant_id = #{tenantId}
                            AND runtime.client_id = #{clientId}
                            AND runtime.owner_jiacn = #{ownerJiacn}
                            AND CAST(runtime.tenant_id AS BINARY(200)) = CAST(#{tenantId} AS BINARY(200))
                            AND OCTET_LENGTH(runtime.tenant_id) = OCTET_LENGTH(#{tenantId})
                            AND CAST(runtime.client_id AS BINARY(200)) = CAST(#{clientId} AS BINARY(200))
                            AND OCTET_LENGTH(runtime.client_id) = OCTET_LENGTH(#{clientId})
                            AND CAST(runtime.owner_jiacn AS BINARY(200)) = CAST(#{ownerJiacn} AS BINARY(200))
                            AND OCTET_LENGTH(runtime.owner_jiacn) = OCTET_LENGTH(#{ownerJiacn})
                            AND CAST(runtime.agent_id AS BINARY(400)) = CAST(task.assigned_agent_id AS BINARY(400))
                            AND OCTET_LENGTH(runtime.agent_id) = OCTET_LENGTH(task.assigned_agent_id)
                          LIMIT 1
                      ) END,
                      '')) LIKE CONCAT('%', LOWER(#{keyword}), '%')
              )
            </if>
            </script>
            """)
    long countSearchExactInScope(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn,
            @Param("status") String status,
            @Param("ability") String ability,
            @Param("keyword") String keyword);

    @Select("""
            <script>
            SELECT task.task_id, task.reward_status, task.assigned_agent_id,
                   task.required_abilities, task.reward, task.assigned_at, task.started_at,
                   task.completed_at, task.failure_reason, task.task_version,
                   task.create_time, task.update_time, task.tenant_id, task.client_id, task.owner_jiacn,
                   plan.name AS planTitle, plan.description AS planDescription,
                   CAST(plan.amount AS SIGNED) AS planReward,
                   plan.create_time AS planCreateTime, plan.update_time AS planUpdateTime,
                   0 AS fundingPresent,
                   NULL AS fundingMode, NULL AS fundingStatus, NULL AS escrowId,
                   NULL AS grossBountyAmountMicro, NULL AS remainingMicro,
                   NULL AS requiredSkillRequirements, NULL AS fundingProjectionValid
            FROM agent_task_meta task
            LEFT JOIN task_plan plan
              ON task.task_id REGEXP '^[0-9]+$'
             AND plan.id = CAST(task.task_id AS UNSIGNED)
             AND plan.jiacn = #{ownerJiacn}
             AND plan.client_id = #{clientId}
             AND CAST(plan.jiacn AS BINARY(200)) = CAST(#{ownerJiacn} AS BINARY(200))
             AND OCTET_LENGTH(plan.jiacn) = OCTET_LENGTH(#{ownerJiacn})
             AND CAST(plan.client_id AS BINARY(200)) = CAST(#{clientId} AS BINARY(200))
             AND OCTET_LENGTH(plan.client_id) = OCTET_LENGTH(#{clientId})
            WHERE task.tenant_id = #{tenantId}
              AND task.client_id = #{clientId}
              AND task.owner_jiacn = #{ownerJiacn}
              AND CAST(task.tenant_id AS BINARY(200)) = CAST(#{tenantId} AS BINARY(200))
              AND OCTET_LENGTH(task.tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(task.client_id AS BINARY(200)) = CAST(#{clientId} AS BINARY(200))
              AND CAST(task.owner_jiacn AS BINARY(200)) = CAST(#{ownerJiacn} AS BINARY(200))
              AND OCTET_LENGTH(task.client_id) = OCTET_LENGTH(#{clientId})
              AND OCTET_LENGTH(task.owner_jiacn) = OCTET_LENGTH(#{ownerJiacn})
            <if test="status != null and status.trim() != ''">
              AND task.reward_status = #{status}
            </if>
            <if test="ability != null and ability.trim() != ''">
              AND task.required_abilities LIKE CONCAT('%', '"', #{ability}, '"', '%')
            </if>
            <if test="keyword != null and keyword.trim() != ''">
              AND (
                    LOWER(task.task_id) LIKE CONCAT('%', LOWER(#{keyword}), '%')
                 OR LOWER(COALESCE(plan.name, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%')
                 OR LOWER(COALESCE(plan.description, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%')
                 OR LOWER(COALESCE(task.required_abilities, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%')
                 OR LOWER(COALESCE(
                      (
                          SELECT runtime.name
                          FROM agent_task_member member
                          LEFT JOIN agent_runtime runtime
                            ON runtime.agent_id = member.agent_id
                           AND runtime.tenant_id = #{tenantId}
                           AND runtime.client_id = #{clientId}
                           AND runtime.owner_jiacn = #{ownerJiacn}
                           AND CAST(runtime.tenant_id AS BINARY(200)) = CAST(#{tenantId} AS BINARY(200))
                           AND OCTET_LENGTH(runtime.tenant_id) = OCTET_LENGTH(#{tenantId})
                           AND CAST(runtime.client_id AS BINARY(200)) = CAST(#{clientId} AS BINARY(200))
                           AND OCTET_LENGTH(runtime.client_id) = OCTET_LENGTH(#{clientId})
                           AND CAST(runtime.owner_jiacn AS BINARY(200)) = CAST(#{ownerJiacn} AS BINARY(200))
                           AND OCTET_LENGTH(runtime.owner_jiacn) = OCTET_LENGTH(#{ownerJiacn})
                           AND CAST(runtime.agent_id AS BINARY(400)) = CAST(member.agent_id AS BINARY(400))
                           AND OCTET_LENGTH(runtime.agent_id) = OCTET_LENGTH(member.agent_id)
                          WHERE member.tenant_id = #{tenantId}
                            AND member.client_id = #{clientId}
                            AND member.owner_jiacn = #{ownerJiacn}
                            AND member.task_id = task.task_id
                            AND CAST(member.tenant_id AS BINARY(200)) = CAST(#{tenantId} AS BINARY(200))
                            AND OCTET_LENGTH(member.tenant_id) = OCTET_LENGTH(#{tenantId})
                            AND CAST(member.client_id AS BINARY(200)) = CAST(#{clientId} AS BINARY(200))
                            AND CAST(member.owner_jiacn AS BINARY(200)) = CAST(#{ownerJiacn} AS BINARY(200))
                            AND OCTET_LENGTH(member.client_id) = OCTET_LENGTH(#{clientId})
                            AND OCTET_LENGTH(member.owner_jiacn) = OCTET_LENGTH(#{ownerJiacn})
                            AND CAST(member.task_id AS BINARY(400)) = CAST(task.task_id AS BINARY(400))
                            AND OCTET_LENGTH(member.task_id) = OCTET_LENGTH(task.task_id)
                            AND member.member_status NOT IN ('rejected', 'left')
                          ORDER BY member.member_role ASC, member.agent_id ASC, member.id ASC
                          LIMIT 1
                      ),
                      CASE WHEN NOT EXISTS (
                          SELECT 1
                          FROM agent_task_member any_member
                          WHERE any_member.tenant_id = #{tenantId}
                            AND any_member.client_id = #{clientId}
                            AND any_member.owner_jiacn = #{ownerJiacn}
                            AND any_member.task_id = task.task_id
                            AND CAST(any_member.tenant_id AS BINARY(200)) = CAST(#{tenantId} AS BINARY(200))
                            AND OCTET_LENGTH(any_member.tenant_id) = OCTET_LENGTH(#{tenantId})
                            AND CAST(any_member.client_id AS BINARY(200)) = CAST(#{clientId} AS BINARY(200))
                            AND CAST(any_member.owner_jiacn AS BINARY(200)) = CAST(#{ownerJiacn} AS BINARY(200))
                            AND OCTET_LENGTH(any_member.client_id) = OCTET_LENGTH(#{clientId})
                            AND OCTET_LENGTH(any_member.owner_jiacn) = OCTET_LENGTH(#{ownerJiacn})
                            AND CAST(any_member.task_id AS BINARY(400)) = CAST(task.task_id AS BINARY(400))
                            AND OCTET_LENGTH(any_member.task_id) = OCTET_LENGTH(task.task_id)
                      ) THEN (
                          SELECT runtime.name
                          FROM agent_runtime runtime
                          WHERE runtime.agent_id = task.assigned_agent_id
                            AND runtime.tenant_id = #{tenantId}
                            AND runtime.client_id = #{clientId}
                            AND runtime.owner_jiacn = #{ownerJiacn}
                            AND CAST(runtime.tenant_id AS BINARY(200)) = CAST(#{tenantId} AS BINARY(200))
                            AND OCTET_LENGTH(runtime.tenant_id) = OCTET_LENGTH(#{tenantId})
                            AND CAST(runtime.client_id AS BINARY(200)) = CAST(#{clientId} AS BINARY(200))
                            AND OCTET_LENGTH(runtime.client_id) = OCTET_LENGTH(#{clientId})
                            AND CAST(runtime.owner_jiacn AS BINARY(200)) = CAST(#{ownerJiacn} AS BINARY(200))
                            AND OCTET_LENGTH(runtime.owner_jiacn) = OCTET_LENGTH(#{ownerJiacn})
                            AND CAST(runtime.agent_id AS BINARY(400)) = CAST(task.assigned_agent_id AS BINARY(400))
                            AND OCTET_LENGTH(runtime.agent_id) = OCTET_LENGTH(task.assigned_agent_id)
                          LIMIT 1
                      ) END,
                      '')) LIKE CONCAT('%', LOWER(#{keyword}), '%')
              )
            </if>
            ORDER BY task.update_time DESC, task.task_id ASC, task.id ASC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<AgentTaskSearchRow> searchPageExactInScope(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn,
            @Param("status") String status,
            @Param("ability") String ability,
            @Param("keyword") String keyword,
            @Param("offset") long offset,
            @Param("limit") int limit);


    @Select("""
            <script>
            SELECT task.task_id, task.reward_status, task.assigned_agent_id,
                   task.required_abilities, task.reward, task.assigned_at, task.started_at,
                   task.completed_at, task.failure_reason, task.task_version,
                   task.create_time, task.update_time, task.tenant_id, task.client_id, task.owner_jiacn,
                   plan.name AS planTitle, plan.description AS planDescription,
                   CAST(plan.amount AS SIGNED) AS planReward,
                   plan.create_time AS planCreateTime, plan.update_time AS planUpdateTime,
                   CASE WHEN funding.task_id IS NULL THEN 0 ELSE 1 END AS fundingPresent,
                   funding.funding_mode AS fundingMode, funding.funding_status AS fundingStatus,
                   funding.escrow_id AS escrowId,
                   funding.gross_bounty_amount_micro AS grossBountyAmountMicro,
                   funding.remaining_micro AS remainingMicro,
                   funding.required_skill_requirements AS requiredSkillRequirements,
                   CASE
                     WHEN funding.task_id IS NULL THEN NULL
                     WHEN funding.funding_mode = 'FUNDED_SINGLE_AGENT'
                      AND funding.funding_status IN ('FUNDS_HELD', 'REFUNDED', 'SETTLED')
                      AND funding.gross_bounty_amount_micro &gt; 0
                      AND funding.remaining_micro &gt;= 0
                      AND funding.remaining_micro &lt;= funding.gross_bounty_amount_micro
                      AND funding.escrow_id IS NOT NULL
                     THEN 1 ELSE 0 END AS fundingProjectionValid
            FROM agent_task_meta task
            LEFT JOIN task_plan plan
              ON task.task_id REGEXP '^[0-9]+$'
             AND plan.id = CAST(task.task_id AS UNSIGNED)
             AND plan.jiacn = #{ownerJiacn}
             AND plan.client_id = #{clientId}
             AND CAST(plan.jiacn AS BINARY(200)) = CAST(#{ownerJiacn} AS BINARY(200))
             AND OCTET_LENGTH(plan.jiacn) = OCTET_LENGTH(#{ownerJiacn})
             AND CAST(plan.client_id AS BINARY(200)) = CAST(#{clientId} AS BINARY(200))
             AND OCTET_LENGTH(plan.client_id) = OCTET_LENGTH(#{clientId})
            LEFT JOIN agent_task_funding funding
              ON funding.tenant_id = #{tenantId}
             AND funding.client_id = #{clientId}
              AND funding.owner_jiacn = #{ownerJiacn}
             AND funding.task_id = task.task_id
             AND CAST(funding.tenant_id AS BINARY(200)) = CAST(#{tenantId} AS BINARY(200))
             AND OCTET_LENGTH(funding.tenant_id) = OCTET_LENGTH(#{tenantId})
             AND CAST(funding.client_id AS BINARY(200)) = CAST(#{clientId} AS BINARY(200))
              AND CAST(funding.owner_jiacn AS BINARY(200)) = CAST(#{ownerJiacn} AS BINARY(200))
             AND OCTET_LENGTH(funding.client_id) = OCTET_LENGTH(#{clientId})
              AND OCTET_LENGTH(funding.owner_jiacn) = OCTET_LENGTH(#{ownerJiacn})
             AND CAST(funding.task_id AS BINARY(400)) = CAST(task.task_id AS BINARY(400))
             AND OCTET_LENGTH(funding.task_id) = OCTET_LENGTH(task.task_id)
            WHERE task.tenant_id = #{tenantId}
              AND task.client_id = #{clientId}
              AND task.owner_jiacn = #{ownerJiacn}
              AND CAST(task.tenant_id AS BINARY(200)) = CAST(#{tenantId} AS BINARY(200))
              AND OCTET_LENGTH(task.tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(task.client_id AS BINARY(200)) = CAST(#{clientId} AS BINARY(200))
              AND CAST(task.owner_jiacn AS BINARY(200)) = CAST(#{ownerJiacn} AS BINARY(200))
              AND OCTET_LENGTH(task.client_id) = OCTET_LENGTH(#{clientId})
              AND OCTET_LENGTH(task.owner_jiacn) = OCTET_LENGTH(#{ownerJiacn})
            <if test="status != null and status.trim() != ''">
              AND task.reward_status = #{status}
            </if>
            <if test="ability != null and ability.trim() != ''">
              AND task.required_abilities LIKE CONCAT('%', '"', #{ability}, '"', '%')
            </if>
            <if test="keyword != null and keyword.trim() != ''">
              AND (
                    LOWER(task.task_id) LIKE CONCAT('%', LOWER(#{keyword}), '%')
                 OR LOWER(COALESCE(plan.name, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%')
                 OR LOWER(COALESCE(plan.description, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%')
                 OR LOWER(COALESCE(task.required_abilities, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%')
                 OR LOWER(COALESCE(
                      (
                          SELECT runtime.name
                          FROM agent_task_member member
                          LEFT JOIN agent_runtime runtime
                            ON runtime.agent_id = member.agent_id
                           AND runtime.tenant_id = #{tenantId}
                           AND runtime.client_id = #{clientId}
                           AND runtime.owner_jiacn = #{ownerJiacn}
                           AND CAST(runtime.tenant_id AS BINARY(200)) = CAST(#{tenantId} AS BINARY(200))
                           AND OCTET_LENGTH(runtime.tenant_id) = OCTET_LENGTH(#{tenantId})
                           AND CAST(runtime.client_id AS BINARY(200)) = CAST(#{clientId} AS BINARY(200))
                           AND OCTET_LENGTH(runtime.client_id) = OCTET_LENGTH(#{clientId})
                           AND CAST(runtime.owner_jiacn AS BINARY(200)) = CAST(#{ownerJiacn} AS BINARY(200))
                           AND OCTET_LENGTH(runtime.owner_jiacn) = OCTET_LENGTH(#{ownerJiacn})
                           AND CAST(runtime.agent_id AS BINARY(400)) = CAST(member.agent_id AS BINARY(400))
                           AND OCTET_LENGTH(runtime.agent_id) = OCTET_LENGTH(member.agent_id)
                          WHERE member.tenant_id = #{tenantId}
                            AND member.client_id = #{clientId}
                            AND member.owner_jiacn = #{ownerJiacn}
                            AND member.task_id = task.task_id
                            AND CAST(member.tenant_id AS BINARY(200)) = CAST(#{tenantId} AS BINARY(200))
                            AND OCTET_LENGTH(member.tenant_id) = OCTET_LENGTH(#{tenantId})
                            AND CAST(member.client_id AS BINARY(200)) = CAST(#{clientId} AS BINARY(200))
                            AND CAST(member.owner_jiacn AS BINARY(200)) = CAST(#{ownerJiacn} AS BINARY(200))
                            AND OCTET_LENGTH(member.client_id) = OCTET_LENGTH(#{clientId})
                            AND OCTET_LENGTH(member.owner_jiacn) = OCTET_LENGTH(#{ownerJiacn})
                            AND CAST(member.task_id AS BINARY(400)) = CAST(task.task_id AS BINARY(400))
                            AND OCTET_LENGTH(member.task_id) = OCTET_LENGTH(task.task_id)
                            AND member.member_status NOT IN ('rejected', 'left')
                          ORDER BY member.member_role ASC, member.agent_id ASC, member.id ASC
                          LIMIT 1
                      ),
                      CASE WHEN NOT EXISTS (
                          SELECT 1
                          FROM agent_task_member any_member
                          WHERE any_member.tenant_id = #{tenantId}
                            AND any_member.client_id = #{clientId}
                            AND any_member.owner_jiacn = #{ownerJiacn}
                            AND any_member.task_id = task.task_id
                            AND CAST(any_member.tenant_id AS BINARY(200)) = CAST(#{tenantId} AS BINARY(200))
                            AND OCTET_LENGTH(any_member.tenant_id) = OCTET_LENGTH(#{tenantId})
                            AND CAST(any_member.client_id AS BINARY(200)) = CAST(#{clientId} AS BINARY(200))
                            AND CAST(any_member.owner_jiacn AS BINARY(200)) = CAST(#{ownerJiacn} AS BINARY(200))
                            AND OCTET_LENGTH(any_member.client_id) = OCTET_LENGTH(#{clientId})
                            AND OCTET_LENGTH(any_member.owner_jiacn) = OCTET_LENGTH(#{ownerJiacn})
                            AND CAST(any_member.task_id AS BINARY(400)) = CAST(task.task_id AS BINARY(400))
                            AND OCTET_LENGTH(any_member.task_id) = OCTET_LENGTH(task.task_id)
                      ) THEN (
                          SELECT runtime.name
                          FROM agent_runtime runtime
                          WHERE runtime.agent_id = task.assigned_agent_id
                            AND runtime.tenant_id = #{tenantId}
                            AND runtime.client_id = #{clientId}
                            AND runtime.owner_jiacn = #{ownerJiacn}
                            AND CAST(runtime.tenant_id AS BINARY(200)) = CAST(#{tenantId} AS BINARY(200))
                            AND OCTET_LENGTH(runtime.tenant_id) = OCTET_LENGTH(#{tenantId})
                            AND CAST(runtime.client_id AS BINARY(200)) = CAST(#{clientId} AS BINARY(200))
                            AND OCTET_LENGTH(runtime.client_id) = OCTET_LENGTH(#{clientId})
                            AND CAST(runtime.owner_jiacn AS BINARY(200)) = CAST(#{ownerJiacn} AS BINARY(200))
                            AND OCTET_LENGTH(runtime.owner_jiacn) = OCTET_LENGTH(#{ownerJiacn})
                            AND CAST(runtime.agent_id AS BINARY(400)) = CAST(task.assigned_agent_id AS BINARY(400))
                            AND OCTET_LENGTH(runtime.agent_id) = OCTET_LENGTH(task.assigned_agent_id)
                          LIMIT 1
                      ) END,
                      '')) LIKE CONCAT('%', LOWER(#{keyword}), '%')
              )
            </if>
            ORDER BY task.update_time DESC, task.task_id ASC, task.id ASC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<AgentTaskSearchRow> searchPageWithFundingExactInScope(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn,
            @Param("status") String status,
            @Param("ability") String ability,
            @Param("keyword") String keyword,
            @Param("offset") long offset,
            @Param("limit") int limit);

    @Select("""
            <script>
            SELECT member.tenant_id, member.client_id, member.owner_jiacn, member.task_id,
                   member.agent_id, member.member_status
            FROM agent_task_member member
            WHERE member.tenant_id = #{tenantId}
              AND member.client_id = #{clientId}
                            AND member.owner_jiacn = #{ownerJiacn}
              AND CAST(member.tenant_id AS BINARY(200)) = CAST(#{tenantId} AS BINARY(200))
              AND OCTET_LENGTH(member.tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(member.client_id AS BINARY(200)) = CAST(#{clientId} AS BINARY(200))
                            AND CAST(member.owner_jiacn AS BINARY(200)) = CAST(#{ownerJiacn} AS BINARY(200))
              AND OCTET_LENGTH(member.client_id) = OCTET_LENGTH(#{clientId})
                            AND OCTET_LENGTH(member.owner_jiacn) = OCTET_LENGTH(#{ownerJiacn})
              AND (
              <foreach collection="taskIds" item="taskId" separator=" OR ">
                (member.task_id = #{taskId}
                 AND CAST(member.task_id AS BINARY(400)) = CAST(#{taskId} AS BINARY(400))
                 AND OCTET_LENGTH(member.task_id) = OCTET_LENGTH(#{taskId}))
              </foreach>
              )
            ORDER BY member.task_id ASC, member.member_role ASC, member.agent_id ASC, member.id ASC
            </script>
            """)
    List<cn.jia.agent.entity.AgentTaskMemberEntity> selectSearchMembersExactInScope(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn,
            @Param("taskIds") List<String> taskIds);

    @Select("""
            <script>
            SELECT runtime.agent_id, runtime.name, runtime.status, runtime.tenant_id, runtime.client_id,
                   runtime.owner_jiacn
            FROM agent_runtime runtime
            WHERE runtime.tenant_id = #{tenantId}
              AND runtime.client_id = #{clientId}
              AND runtime.owner_jiacn = #{ownerJiacn}
              AND CAST(runtime.tenant_id AS BINARY(200)) = CAST(#{tenantId} AS BINARY(200))
              AND OCTET_LENGTH(runtime.tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(runtime.client_id AS BINARY(200)) = CAST(#{clientId} AS BINARY(200))
              AND OCTET_LENGTH(runtime.client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(runtime.owner_jiacn AS BINARY(200)) = CAST(#{ownerJiacn} AS BINARY(200))
              AND OCTET_LENGTH(runtime.owner_jiacn) = OCTET_LENGTH(#{ownerJiacn})
              AND (
              <foreach collection="agentIds" item="agentId" separator=" OR ">
                (runtime.agent_id = #{agentId}
                 AND CAST(runtime.agent_id AS BINARY(400)) = CAST(#{agentId} AS BINARY(400))
                 AND OCTET_LENGTH(runtime.agent_id) = OCTET_LENGTH(#{agentId}))
              </foreach>
              )
            ORDER BY runtime.agent_id ASC
            </script>
            """)
    List<cn.jia.agent.entity.AgentRuntimeEntity> selectSearchRuntimesExactInOwnerScope(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn,
            @Param("agentIds") List<String> agentIds);

    @Select("""
            <script>
            SELECT task.tenant_id AS tenantId, task.client_id AS clientId,
                   task.owner_jiacn AS ownerJiacn,
                   COALESCE(task.reward_status, 'open') AS status, COUNT(*) AS taskCount
            FROM agent_task_meta task
            WHERE task.tenant_id = #{tenantId}
              AND task.client_id = #{clientId}
              AND task.owner_jiacn = #{ownerJiacn}
              AND CAST(task.tenant_id AS BINARY(200)) = CAST(#{tenantId} AS BINARY(200))
              AND OCTET_LENGTH(task.tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(task.client_id AS BINARY(200)) = CAST(#{clientId} AS BINARY(200))
              AND CAST(task.owner_jiacn AS BINARY(200)) = CAST(#{ownerJiacn} AS BINARY(200))
              AND OCTET_LENGTH(task.client_id) = OCTET_LENGTH(#{clientId})
              AND OCTET_LENGTH(task.owner_jiacn) = OCTET_LENGTH(#{ownerJiacn})
            <if test="ability != null and ability.trim() != ''">
              AND task.required_abilities LIKE CONCAT('%', '"', #{ability}, '"', '%')
            </if>
            <if test="keyword != null and keyword.trim() != ''">
              AND LOCATE(CAST(#{keyword} AS BINARY), CAST(task.task_id AS BINARY)) &gt; 0
            </if>
            GROUP BY task.tenant_id, CAST(task.tenant_id AS BINARY), OCTET_LENGTH(task.tenant_id),
                     task.client_id, CAST(task.client_id AS BINARY), OCTET_LENGTH(task.client_id),
                     task.owner_jiacn, CAST(task.owner_jiacn AS BINARY), OCTET_LENGTH(task.owner_jiacn),
                     COALESCE(task.reward_status, 'open')
            ORDER BY status ASC
            </script>
            """)
    List<AgentTaskStatusCountRow> countSearchByStatusExactInScope(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn,
            @Param("ability") String ability,
            @Param("keyword") String keyword);

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
            SELECT parent.*
            FROM agent_task_meta parent
            WHERE parent.tenant_id = #{tenantId}
              AND parent.client_id = #{clientId}
              AND parent.owner_jiacn = #{ownerJiacn}
              AND CAST(parent.tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(parent.tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(parent.client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(parent.client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(parent.owner_jiacn AS BINARY) = CAST(#{ownerJiacn} AS BINARY)
              AND OCTET_LENGTH(parent.owner_jiacn) = OCTET_LENGTH(#{ownerJiacn})
              AND EXISTS (
                  SELECT 1
                  FROM agent_task_work_item child
                  WHERE child.tenant_id = #{tenantId}
                    AND child.client_id = #{clientId}
                    AND child.owner_jiacn = #{ownerJiacn}
                    AND child.work_item_id = #{workItemId}
                    AND CAST(child.tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
                    AND OCTET_LENGTH(child.tenant_id) = OCTET_LENGTH(#{tenantId})
                    AND CAST(child.client_id AS BINARY) = CAST(#{clientId} AS BINARY)
                    AND OCTET_LENGTH(child.client_id) = OCTET_LENGTH(#{clientId})
                    AND CAST(child.owner_jiacn AS BINARY) = CAST(#{ownerJiacn} AS BINARY)
                    AND OCTET_LENGTH(child.owner_jiacn) = OCTET_LENGTH(#{ownerJiacn})
                    AND child.tenant_id = parent.tenant_id
                    AND CAST(child.tenant_id AS BINARY) = CAST(parent.tenant_id AS BINARY)
                    AND OCTET_LENGTH(child.tenant_id) = OCTET_LENGTH(parent.tenant_id)
                    AND child.client_id = parent.client_id
                    AND CAST(child.client_id AS BINARY) = CAST(parent.client_id AS BINARY)
                    AND OCTET_LENGTH(child.client_id) = OCTET_LENGTH(parent.client_id)
                    AND child.owner_jiacn = parent.owner_jiacn
                    AND CAST(child.owner_jiacn AS BINARY) = CAST(parent.owner_jiacn AS BINARY)
                    AND OCTET_LENGTH(child.owner_jiacn) = OCTET_LENGTH(parent.owner_jiacn)
                    AND CAST(child.work_item_id AS BINARY) = CAST(#{workItemId} AS BINARY)
                    AND OCTET_LENGTH(child.work_item_id) = OCTET_LENGTH(#{workItemId})
                    AND child.task_id = parent.task_id
                    AND CAST(child.task_id AS BINARY) = CAST(parent.task_id AS BINARY)
                    AND OCTET_LENGTH(child.task_id) = OCTET_LENGTH(parent.task_id)
              )
            ORDER BY parent.task_id ASC, parent.id ASC
            LIMIT 1
            FOR UPDATE
            """)
    AgentTaskMetaEntity findTaskRootByWorkItemForUpdateInOwnerScope(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn,
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
              AND owner_jiacn = #{ownerJiacn}
              AND task_id = #{taskId}
              AND CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(owner_jiacn AS BINARY) = CAST(#{ownerJiacn} AS BINARY)
              AND OCTET_LENGTH(owner_jiacn) = OCTET_LENGTH(#{ownerJiacn})
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
              AND owner_jiacn = #{ownerJiacn}
              AND task_id = #{taskId}
              AND CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(owner_jiacn AS BINARY) = CAST(#{ownerJiacn} AS BINARY)
              AND OCTET_LENGTH(owner_jiacn) = OCTET_LENGTH(#{ownerJiacn})
              AND CAST(task_id AS BINARY) = CAST(#{taskId} AS BINARY)
              AND OCTET_LENGTH(task_id) = OCTET_LENGTH(#{taskId})
            ORDER BY row_type, entity_id
            """)
    List<AgentTaskAggregationSnapshotRow> selectAggregationSnapshot(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn,
            @Param("taskId") String taskId);

    @Update("""
            UPDATE agent_task_meta
            SET assigned_agent_id=#{assignedAgentId}, reward_status=#{rewardStatus},
                assigned_at=#{assignedAt}, collaboration_mode=#{collaborationMode},
                max_agents=#{maxAgents}, coordinator_agent_id=#{coordinatorAgentId},
                task_version=#{resultVersion}, update_time=#{updateTime}
            WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND task_id=#{taskId}
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(task_id AS BINARY)=CAST(#{taskId} AS BINARY)
              AND OCTET_LENGTH(task_id)=OCTET_LENGTH(#{taskId})
              AND task_version=#{expectedVersion}
            """)
    int updateAssignmentByVersion(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("taskId") String taskId,
            @Param("expectedVersion") long expectedVersion,
            @Param("resultVersion") long resultVersion,
            @Param("assignedAgentId") String assignedAgentId,
            @Param("rewardStatus") String rewardStatus,
            @Param("assignedAt") Long assignedAt,
            @Param("collaborationMode") String collaborationMode,
            @Param("maxAgents") Integer maxAgents,
            @Param("coordinatorAgentId") String coordinatorAgentId,
            @Param("updateTime") long updateTime);

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
              AND owner_jiacn = #{ownerJiacn}
              AND task_id = #{taskId}
              AND CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(owner_jiacn AS BINARY) = CAST(#{ownerJiacn} AS BINARY)
              AND OCTET_LENGTH(owner_jiacn) = OCTET_LENGTH(#{ownerJiacn})
              AND CAST(task_id AS BINARY) = CAST(#{taskId} AS BINARY)
              AND OCTET_LENGTH(task_id) = OCTET_LENGTH(#{taskId})
              AND task_version = #{expectedVersion}
            """)
    int updateStatusByVersionInOwnerScope(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn,
            @Param("taskId") String taskId,
            @Param("expectedVersion") long expectedVersion,
            @Param("rewardStatus") String rewardStatus,
            @Param("startedAt") Long startedAt,
            @Param("completedAt") Long completedAt,
            @Param("failureReason") String failureReason,
            @Param("updateTime") long updateTime);
}
