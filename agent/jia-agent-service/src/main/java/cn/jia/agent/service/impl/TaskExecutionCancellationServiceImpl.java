package cn.jia.agent.service.impl;

import cn.jia.agent.dao.PersonalWorkspaceExecutionDao;
import cn.jia.agent.dao.SkillExecutionDao;
import cn.jia.agent.entity.WorkspaceExecutionProjectionHint;
import cn.jia.agent.service.TaskExecutionCancellationService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Execution state is authoritative. Chat consumes the existing post-commit projection hint. */
@Service
public class TaskExecutionCancellationServiceImpl implements TaskExecutionCancellationService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TaskExecutionCancellationServiceImpl.class);
    private final PersonalWorkspaceExecutionDao executions;
    private final SkillExecutionDao skills;
    private final ApplicationEventPublisher events;
    public TaskExecutionCancellationServiceImpl(PersonalWorkspaceExecutionDao executions,
            SkillExecutionDao skills, ApplicationEventPublisher events) {
        this.executions = executions; this.skills = skills; this.events = events;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void cancelTaskExecutions(String tenant, String client, String owner, String task) {
        // Authorization/root locking belongs to the calling task service. Current execution
        // locks fence claims/results; scoped state CAS never rewrites a terminal result.
        List<WorkspaceExecutionProjectionHint> hints = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (var execution : executions.lockUnfinishedTaskExecutions(tenant, client, owner, task)) {
            if (!Set.of("QUEUED", "OUTPUT_STAGED").contains(execution.getExecutionState())) {
                throw new IllegalStateException("Unsupported unfinished execution state");
            }
            if (Integer.valueOf(4).equals(execution.getExecutionProtocolVersion())
                    || Integer.valueOf(5).equals(execution.getExecutionProtocolVersion())) {
                var skill = skills.lock(tenant, client, owner, execution.getExecutionId());
                if (skill == null || !Set.of("READY", "RUNNING", "WAITING_PROVIDER", "RESULT_UNKNOWN", "RESULT_READY")
                        .contains(skill.getPhase()) || skill.getVersion() == null || skill.getVersion() < 0
                        || skill.getVersion() == Long.MAX_VALUE) {
                    throw new IllegalStateException("Skill execution cancellation state is inconsistent");
                }
                String phase = skill.getPhase(); long version = skill.getVersion();
                skill.setPhase("CANCELLED").setUpdatedAt(now);
                // Preserve unresolved external effects and original failure context. CANCELLED
                // describes local admission, never a successful rollback of Provider effects.
                if (skill.getLastErrorCode() == null) skill.setLastErrorCode("TASK_CANCELLED");
                if (!skills.compareAndSet(skill, version, phase)) {
                    throw new IllegalStateException("Skill execution changed during cancellation");
                }
            }
            if (!executions.cancelLockedTaskExecution(execution, now)) {
                throw new IllegalStateException("Execution changed during cancellation");
            }
            // Keep all input/output rows, token/lease/provider-start history and result manifests.
            if ("CONVERSATION".equals(execution.getExecutionMode())) {
                hints.add(new WorkspaceExecutionProjectionHint(tenant, client, owner, execution.getExecutionId()));
            }
        }
        if (!hints.isEmpty()) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                for (var hint : hints) try { events.publishEvent(hint); }
                catch (RuntimeException unavailable) {
                    // Existing durable projection scanning recovers a lost in-process wakeup.
                    log.warn("Task execution cancellation projection wakeup failed: {}", hint.executionId());
                }
            }
        });
    }
}
