package cn.jia.agent.mapper;

/** One read-model expression and keyword/owner predicate for page, total, and grouped counts. */
final class AgentTaskPhaseSql {
    private AgentTaskPhaseSql() { }
    static final String PHASE = """
CASE
 WHEN BINARY task.reward_status = BINARY 'completed' THEN 'COMPLETED'
 WHEN BINARY task.reward_status IN (BINARY 'failed', BINARY 'cancelled', BINARY 'archived') THEN 'HISTORY'
 WHEN BINARY task.reward_status = BINARY 'reviewing' AND
   (SELECT COUNT(*) FROM agent_task_formal_delivery d
     WHERE BINARY d.tenant_id = BINARY task.tenant_id
       AND BINARY d.client_id = BINARY task.client_id AND BINARY d.task_id = BINARY task.task_id
       AND BINARY d.state = BINARY 'submitted') = 1 AND EXISTS (
    SELECT 1 FROM agent_task_formal_delivery d
     WHERE BINARY d.tenant_id = BINARY task.tenant_id
       AND BINARY d.client_id = BINARY task.client_id AND BINARY d.task_id = BINARY task.task_id
       AND BINARY d.state = BINARY 'submitted' AND NOT EXISTS (
        SELECT 1 FROM agent_task_formal_delivery newer
         WHERE BINARY newer.tenant_id = BINARY d.tenant_id
           AND BINARY newer.client_id = BINARY d.client_id AND BINARY newer.task_id = BINARY d.task_id
           AND newer.revision &gt; d.revision)) THEN 'PENDING_ACCEPTANCE'
 WHEN BINARY task.reward_status IN (BINARY 'open', BINARY 'planning', BINARY 'assigned', BINARY 'running', BINARY 'blocked')
   AND NOT EXISTS (SELECT 1 FROM agent_task_formal_delivery d
     WHERE BINARY d.tenant_id = BINARY task.tenant_id
       AND BINARY d.client_id = BINARY task.client_id AND BINARY d.task_id = BINARY task.task_id
       AND BINARY d.state = BINARY 'submitted')
 THEN CASE WHEN BINARY task.reward_status = BINARY 'open' THEN 'PENDING_ASSIGNMENT' ELSE 'IN_PROGRESS' END
 ELSE 'UNKNOWN' END
""";
    static final String FROM_PLAN = """
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

""";
    static final String FILTERS = """
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
              AND CAST(task.reward_status AS BINARY) = CAST(#{status} AS BINARY)
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

""";
    static final String PHASE_FILTER = "<if test=\"phase != null\"> AND (" + PHASE + ") = #{phase} </if>";
}
