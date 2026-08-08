package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentTaskEventEntity;
import cn.jia.core.dao.IBaseDao;

import java.util.List;

/**
 * DAO for {@code agent_task_event}.
 *
 * <p>C01 provides the reliable foundation:
 * <ul>
 *   <li>{@link #lockAndAllocateVersion} — atomic event version allocation (SELECT FOR UPDATE)</li>
 *   <li>{@link #insertEvent} — insert event with pre-allocated version</li>
 *   <li>{@link #commitEventVersion} — CAS update of current_event_version after event insert</li>
 *   <li>Read paths for replay/C02+ consumption</li>
 * </ul>
 *
 * <p>C01B will compose these three calls within a single Spring transaction
 * for each business write path.
 */
public interface AgentTaskEventDao extends IBaseDao<AgentTaskEventEntity> {

    /**
     * Lock the task meta row and return the current event version.
     *
     * <p>This serializes event version allocation. The caller must:
     * <ol>
     *   <li>Call this to obtain {@code currentVersion} under lock</li>
     *   <li>Compute {@code newVersion = currentVersion + 1}</li>
     *   <li>Set {@code newVersion} on the event entity</li>
     *   <li>Call {@link #insertEvent}</li>
     *   <li>Call {@link #commitEventVersion}</li>
     * </ol>
     * All in the same transaction.
     *
     * @return current event version (0 for new tasks), or null if task not found
     * @throws IllegalArgumentException if scope is invalid
     */
    Long lockAndAllocateVersion(String tenantId, String clientId, String taskId);

    /**
     * Insert an event entity with its pre-allocated event version.
     *
     * @return 1 on success
     */
    int insertEvent(AgentTaskEventEntity event);

    /**
     * CAS-update current_event_version to the new value.
     * Uses expectedCurrentVersion to prevent lost updates.
     *
     * @return 1 on success, 0 if version mismatch
     */
    int commitEventVersion(
            String tenantId, String clientId, String taskId,
            long expectedCurrentVersion, long newEventVersion,
            long updateTime);

    /**
     * Find all events for a task scope, ordered by event_version ascending.
     */
    List<AgentTaskEventEntity> findByTaskScope(
            String tenantId, String clientId, String taskId);

    /**
     * Find events since a version (inclusive), ordered by event_version ascending.
     */
    List<AgentTaskEventEntity> findByTaskScopeSince(
            String tenantId, String clientId, String taskId, long sinceVersion);

    /**
     * Find a single event by exact event_id in scope.
     */
    AgentTaskEventEntity findByEventId(
            String tenantId, String clientId, String eventId);
}
