package cn.jia.agent.service;

import cn.jia.agent.entity.AgentTaskEventWriteCommand;
import cn.jia.agent.entity.AgentTaskEventWriteResult;

/**
 * C01 production entry point for atomic task event append.
 *
 * <p>Semantics:
 * <ol>
 *   <li>Lock task meta row (SELECT FOR UPDATE) to serialize version allocation</li>
 *   <li>Detect task-not-found / Long.MAX_VALUE overflow → fail closed</li>
 *   <li>Compute next event version = current_event_version + 1</li>
 *   <li>Insert agent_task_event row</li>
 *   <li>CAS-update current_event_version (byte-exact scope + version check)</li>
 *   <li>All within a single Spring transaction (@Transactional REQUIRED)</li>
 * </ol>
 *
 * <p>This method can participate in C01B's outer business transactions
 * (propagation REQUIRED). If the outer transaction rolls back, the event
 * and version update are also rolled back.
 *
 * <p>Validation:
 * <ul>
 *   <li>{@code eventType} is validated via {@code TaskEventType.requireKnown}</li>
 *   <li>{@code aggregateType} is validated via {@code TaskEventType.Aggregate.requireKnown}</li>
 *   <li>All field lengths and byte-exactness are validated</li>
 *   <li>{@code eventJson} is validated and canonicalized through the C01B bounded
 *       payload allowlist before any task lock or version allocation</li>
 * </ul>
 */
public interface AgentTaskEventWriter {

    /**
     * Atomically append a task event and advance current_event_version.
     *
     * @param command the event write command (must pass validation)
     * @return result containing allocated event version and persisted entity
     * @throws IllegalArgumentException if validation fails
     * @throws cn.jia.agent.exception.AgentTaskCollaborationException if task not found in scope
     * @throws IllegalStateException if current_event_version has overflowed
     * @throws RuntimeException on insert or CAS failure (triggers rollback)
     */
    AgentTaskEventWriteResult append(AgentTaskEventWriteCommand command);
}
