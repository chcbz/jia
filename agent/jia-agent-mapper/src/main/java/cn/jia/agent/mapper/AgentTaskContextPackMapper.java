package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentTaskContextPackTaskSourceRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** F01 read-only safe-column lookup; nonnumeric legacy task IDs intentionally produce no row. */
public interface AgentTaskContextPackMapper {
    @Select("""
            SELECT #{taskId} AS task_id, plan.id AS plan_id,
                   plan.jiacn AS tenant_id, plan.client_id, plan.name AS title,
                   plan.description
              FROM task_plan plan
             WHERE #{taskId} REGEXP '^[0-9]+$'
               AND plan.id = CAST(#{taskId} AS UNSIGNED)
               AND plan.jiacn = #{tenantId}
               AND plan.client_id = #{clientId}
               AND CAST(plan.jiacn AS BINARY) = CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(plan.jiacn) = OCTET_LENGTH(#{tenantId})
               AND CAST(plan.client_id AS BINARY) = CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(plan.client_id) = OCTET_LENGTH(#{clientId})
             LIMIT 1
            """)
    AgentTaskContextPackTaskSourceRow findTaskDescription(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("taskId") String taskId);
}
