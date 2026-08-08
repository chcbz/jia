package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentTaskEventEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * MyBatis mapper for {@code agent_task_event} (§7.5).
 *
 * <p>Key invariants:
 * <ul>
 *   <li>{@link #lockTaskMetaForEventVersion} uses SELECT ... FOR UPDATE to
 *       serialize event version allocation within a task scope.</li>
 *   <li>{@link #insertEvent} performs the event INSERT after version is allocated.</li>
 *   <li>{@link #incrementCurrentEventVersion} uses byte-exact CAS UPDATE
 *       (CAST/OCTET_LENGTH on tenant/client/task + current_event_version check).</li>
 *   <li>All scope lookups use byte-exact CAST(... AS BINARY) + OCTET_LENGTH
 *       to fail closed on padding/collation mismatches.</li>
 * </ul>
 */
public interface AgentTaskEventMapper extends BaseMapper<AgentTaskEventEntity> {

    @Select("""
            SELECT current_event_version
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
            FOR UPDATE
            """)
    Long lockTaskMetaForEventVersion(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("taskId") String taskId);

    @Insert("""
            INSERT INTO agent_task_event
                (task_id, event_version, event_id, event_type,
                 actor_type, actor_id, aggregate_type, aggregate_id,
                 event_json, occurred_at,
                 tenant_id, client_id, create_time, update_time)
            VALUES
                (#{taskId}, #{eventVersion}, #{eventId}, #{eventType},
                 #{actorType}, #{actorId}, #{aggregateType}, #{aggregateId},
                 #{eventJson}, #{occurredAt},
                 #{tenantId}, #{clientId}, #{createTime}, #{updateTime})
            """)
    int insertEvent(AgentTaskEventEntity event);

    /**
     * Byte-exact CAS update of current_event_version.
     *
     * <p>Conditions enforced:
     * <ul>
     *   <li>Byte-exact tenant/client/task match (CAST + OCTET_LENGTH)</li>
     *   <li>current_event_version = expectedCurrentVersion (CAS)</li>
     * </ul>
     *
     * @return 1 on success, 0 if scope or version mismatch
     */
    @Update("""
            UPDATE agent_task_meta
            SET current_event_version = #{newEventVersion},
                update_time = #{updateTime}
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
              AND current_event_version = #{expectedCurrentVersion}
            """)
    int incrementCurrentEventVersion(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("taskId") String taskId,
            @Param("expectedCurrentVersion") long expectedCurrentVersion,
            @Param("newEventVersion") long newEventVersion,
            @Param("updateTime") long updateTime);

    @Select("""
            SELECT id, task_id, event_version, event_id, event_type,
                   actor_type, actor_id, aggregate_type, aggregate_id,
                   event_json, occurred_at,
                   tenant_id, client_id, create_time, update_time
            FROM agent_task_event
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND task_id = #{taskId}
              AND CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(task_id AS BINARY) = CAST(#{taskId} AS BINARY)
              AND OCTET_LENGTH(task_id) = OCTET_LENGTH(#{taskId})
            ORDER BY event_version ASC
            """)
    List<AgentTaskEventEntity> findExactByTaskScope(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("taskId") String taskId);

    @Select("""
            SELECT id, task_id, event_version, event_id, event_type,
                   actor_type, actor_id, aggregate_type, aggregate_id,
                   event_json, occurred_at,
                   tenant_id, client_id, create_time, update_time
            FROM agent_task_event
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND task_id = #{taskId}
              AND event_version >= #{sinceVersion}
              AND CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(task_id AS BINARY) = CAST(#{taskId} AS BINARY)
              AND OCTET_LENGTH(task_id) = OCTET_LENGTH(#{taskId})
            ORDER BY event_version ASC
            """)
    List<AgentTaskEventEntity> findExactByTaskScopeSince(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("taskId") String taskId,
            @Param("sinceVersion") long sinceVersion);

    @Select("""
            SELECT id, task_id, event_version, event_id, event_type,
                   actor_type, actor_id, aggregate_type, aggregate_id,
                   event_json, occurred_at,
                   tenant_id, client_id, create_time, update_time
            FROM agent_task_event
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND event_id = #{eventId}
              AND CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(event_id AS BINARY) = CAST(#{eventId} AS BINARY)
              AND OCTET_LENGTH(event_id) = OCTET_LENGTH(#{eventId})
            LIMIT 1
            """)
    AgentTaskEventEntity findExactByEventId(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("eventId") String eventId);
}
