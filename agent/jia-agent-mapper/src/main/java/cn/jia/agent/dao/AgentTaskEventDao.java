package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentTaskEventEntity;
import cn.jia.core.dao.IBaseDao;

import java.util.List;

/**
 * DAO for {@code agent_task_event} (§7.5).
 *
 * <p>C01 provides the reliable foundation. C01B will compose
 * these calls within a single Spring transaction per business write path.
 *
 * <p>Preferred production entry point: {@code AgentTaskEventWriter.append()}
 * which wraps lock/insert/commit atomically.
 */
public interface AgentTaskEventDao extends IBaseDao<AgentTaskEventEntity> {

    /**
     * Lock the task meta row and return the current event version.
     *
     * @return current event version, or null if task not found in scope
     * @throws IllegalArgumentException if scope is invalid
     */
    Long lockAndAllocateVersion(String tenantId, String clientId, String taskId);

    /**
     * Insert an event entity with its pre-allocated event version.
     *
     * @return 1 on success
     * @throws IllegalArgumentException if required fields are missing
     */
    int insertEvent(AgentTaskEventEntity event);

    /**
     * Byte-exact CAS update of current_event_version.
     *
     * <p>Enforces:
     * <ul>
     *   <li>{@code newEventVersion == expectedCurrentVersion + 1}</li>
     *   <li>{@code expectedCurrentVersion < Long.MAX_VALUE} (overflow rejection)</li>
     *   <li>Byte-exact scope match (CAST/OCTET_LENGTH in SQL)</li>
     * </ul>
     *
     * @return 1 on success, 0 if version/scope mismatch
     */
    int commitEventVersion(
            String tenantId, String clientId, String taskId,
            long expectedCurrentVersion, long newEventVersion,
            long updateTime);

    List<AgentTaskEventEntity> findByTaskScope(
            String tenantId, String clientId, String taskId);

    List<AgentTaskEventEntity> findByTaskScopeSince(
            String tenantId, String clientId, String taskId, long sinceVersion);

    AgentTaskEventEntity findByEventId(
            String tenantId, String clientId, String eventId);
}
