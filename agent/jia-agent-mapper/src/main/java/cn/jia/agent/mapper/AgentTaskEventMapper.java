package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentTaskEventEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * MyBatis mapper for {@code agent_task_event}.
 *
 * <p>Key design invariants:
 * <ul>
 *   <li>{@link #lockTaskMetaForEventVersion} uses SELECT ... FOR UPDATE to
 *       serialize event version allocation within a task scope.</li>
 *   <li>{@link #insertEvent} performs the event INSERT after version is allocated.</li>
 *   <li>{@link #incrementCurrentEventVersion} atomically updates
 *       {@code current_event_version} to the new value.
 *       This MUST be called AFTER the event insert succeeds, all in the same transaction.</li>
 *   <li>All scope lookups use byte-exact CAST(... AS BINARY) + OCTET_LENGTH
 *       to fail closed on padding/collation mismatches.</li>
 * </ul>
 */
public interface AgentTaskEventMapper extends BaseMapper<AgentTaskEventEntity> {

    /**
     * Lock the task meta row for event version allocation.
     *
     * <p>Returns the current event version (before increment). Caller
     * computes {@code newEventVersion = current + 1}, inserts the event,
     * then calls {@link #incrementCurrentEventVersion}.
     *
     * @return the current event version, or null if the task does not exist in scope
     */
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

    /**
     * Insert a scoped event. Caller must have already allocated
     * {@code eventVersion} via {@link #lockTaskMetaForEventVersion}.
     */
    @Insert("""
            INSERT INTO agent_task_event
                (event_id, task_id, event_version, event_type, actor,
                 aggregate_type, aggregate_id, payload, created_at,
                 tenant_id, client_id, create_time, update_time)
            VALUES
                (#{eventId}, #{taskId}, #{eventVersion}, #{eventType}, #{actor},
                 #{aggregateType}, #{aggregateId}, #{payload}, #{createdAt},
                 #{tenantId}, #{clientId}, #{createTime}, #{updateTime})
            """)
    int insertEvent(AgentTaskEventEntity event);

    /**
     * Atomically increment current_event_version.
     * Must be called AFTER the event INSERT, in the same transaction.
     *
     * @return number of rows updated (1 on success, 0 if scope/version mismatch)
     */
    @Update("""
            UPDATE agent_task_meta
            SET current_event_version = #{newEventVersion},
                update_time = #{updateTime}
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND task_id = #{taskId}
              AND current_event_version = #{expectedCurrentVersion}
            """)
    int incrementCurrentEventVersion(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("taskId") String taskId,
            @Param("expectedCurrentVersion") long expectedCurrentVersion,
            @Param("newEventVersion") long newEventVersion,
            @Param("updateTime") long updateTime);

    /**
     * Find events for a task scope, ordered by event_version ascending.
     */
    @Select("""
            SELECT *
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

    /**
     * Find events for a task scope starting from a specific version (inclusive).
     */
    @Select("""
            SELECT *
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

    /**
     * Find a single event by exact event_id in scope.
     */
    @Select("""
            SELECT *
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
