package cn.jia.agent.service;

import cn.jia.agent.entity.AgentTaskMetaEntity;

/**
 * C01B reusable REQUIRED transaction and task-root lock boundary.
 *
 * <p>For existing tasks, the scoped {@code agent_task_meta} row is locked before
 * the mutation callback. For create/reserve flows, root reservation runs first,
 * then the same scoped root is locked before any mutable child or identity work.
 * Event append remains inside the callback so the C01 writer participates in the
 * same transaction and only re-enters the already-held root lock.
 */
public interface AgentTaskMutationTransaction {

    <T> T executeWithLockedTaskRoot(
            String tenantId,
            String clientId,
            String taskId,
            LockedTaskMutation<T> mutation);

    <T> T executeWithLockedTaskRootForWorkItem(
            String tenantId,
            String clientId,
            String workItemId,
            LockedTaskMutation<T> mutation);

    <T> T executeAfterTaskRootReservation(
            String tenantId,
            String clientId,
            String taskId,
            TaskRootReservation reservation,
            ReservedTaskMutation<T> mutation);

    @FunctionalInterface
    interface LockedTaskMutation<T> {
        T apply(AgentTaskMetaEntity taskRoot);
    }

    @FunctionalInterface
    interface TaskRootReservation {
        /** @return exactly 1 when created/reserved, or 0 when already present */
        int reserve();
    }

    @FunctionalInterface
    interface ReservedTaskMutation<T> {
        T apply(AgentTaskMetaEntity taskRoot, boolean rootCreated);
    }
}
