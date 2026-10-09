package cn.jia.agent.service.impl;

import cn.jia.agent.state.AgentTaskStatus;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskPointAndStartPolicyService;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.dao.DataAccessException;

import java.util.Objects;

/** Read-only exact-scope policy; no grant, assignment, lease or runtime mutation. */
@Named
public final class AgentTaskPointAndStartPolicyServiceImpl
        implements AgentTaskPointAndStartPolicyService {
    private final AgentTaskMetaDao tasks;
    private final AgentIdentityService identities;

    @Inject
    public AgentTaskPointAndStartPolicyServiceImpl(AgentTaskMetaDao tasks,
            AgentIdentityService identities) {
        this.tasks = Objects.requireNonNull(tasks);
        this.identities = Objects.requireNonNull(identities);
    }

    @Override
    public Snapshot read(AgentTaskExecutionGrantService.Scope scope, String taskId,
            String targetAgentId) {
        exactScope(scope); exact(taskId, 100); exact(targetAgentId, 100);
        try {
            String canonical = identities.requireCanonicalAgentIdInScope(scope.tenantId(),
                    scope.clientId(), scope.ownerJiacn(), targetAgentId);
            if (!targetAgentId.equals(canonical)) throw notFound();
            AgentTaskMetaEntity task = tasks.findByTaskIdInOwnerScope(scope.tenantId(),
                    scope.clientId(), scope.ownerJiacn(), taskId);
            if (task == null) throw notFound();
            if (!Objects.equals(scope.tenantId(), task.getTenantId())
                    || !Objects.equals(scope.clientId(), task.getClientId())
                    || !Objects.equals(scope.ownerJiacn(), task.getOwnerJiacn())
                    || !Objects.equals(taskId, task.getTaskId())
                    || task.getTaskVersion() == null || task.getTaskVersion() < 0) throw integrity();
            AgentTaskStatus status = AgentTaskStatus.fromPersistedValue(task.getRewardStatus());
            boolean unassigned = task.getAssignedAgentId() == null
                    || task.getAssignedAgentId().isBlank();
            boolean assignableState = status == AgentTaskStatus.OPEN
                    || status == AgentTaskStatus.PLANNING;
            boolean eligible = assignableState && unassigned;
            String reason = eligible ? null : !assignableState
                    ? "TASK_NOT_OPEN" : "TASK_ALREADY_ASSIGNED";
            return new Snapshot(taskId, targetAgentId, status.value(), eligible, reason);
        } catch (Failure failure) {
            throw failure;
        } catch (AgentTaskCollaborationException | AgentServiceImpl.AgentBizException invalidScope) {
            throw notFound();
        } catch (DataAccessException unavailable) {
            throw new Failure(Failure.Reason.SOURCE_UNAVAILABLE, unavailable);
        } catch (IllegalArgumentException corrupt) {
            throw integrity(corrupt);
        } catch (RuntimeException unavailable) {
            throw new Failure(Failure.Reason.SOURCE_UNAVAILABLE, unavailable);
        }
    }

    private static void exactScope(AgentTaskExecutionGrantService.Scope scope) {
        if (scope == null || !"0".equals(scope.tenantId())) throw new IllegalArgumentException();
        exact(scope.clientId(), 50); exact(scope.ownerJiacn(), 50);
    }
    private static void exact(String value, int max) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0," + (max - 1) + "}"))
            throw new IllegalArgumentException();
    }
    private static Failure notFound() { return new Failure(Failure.Reason.NOT_FOUND); }
    private static Failure integrity() { return new Failure(Failure.Reason.INTEGRITY_ERROR); }
    private static Failure integrity(Throwable cause) {
        return new Failure(Failure.Reason.INTEGRITY_ERROR, cause);
    }
}
