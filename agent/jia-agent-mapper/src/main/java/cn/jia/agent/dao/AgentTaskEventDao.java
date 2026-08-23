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
    int MAX_REPLAY_PAGE_SIZE = 1000;

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

    /**
     * Return events strictly after the supplied replay cursor.
     *
     * <p>The cursor is exclusive: only {@code event_version > afterVersion}
     * is returned, ordered by event version ascending and bounded by {@code limit}.
     */
    List<AgentTaskEventEntity> findAfterVersion(
            String tenantId, String clientId, String taskId, long afterVersion, int limit);

    /** Return the byte-exact task scope's durable high-water, or null when absent. */
    Long findCurrentVersion(String tenantId, String clientId, String taskId);

    /** Return the earliest retained byte-exact event version, or null when no event is retained. */
    Long findEarliestVersion(String tenantId, String clientId, String taskId);

    AgentTaskEventEntity findByEventId(
            String tenantId, String clientId, String eventId);
}
