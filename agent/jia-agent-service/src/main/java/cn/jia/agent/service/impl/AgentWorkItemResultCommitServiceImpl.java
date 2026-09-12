package cn.jia.agent.service.impl;

import cn.jia.agent.common.TaskEventPayload;
import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.entity.AgentTaskArtifactPublishDTO;
import cn.jia.agent.entity.AgentTaskArtifactViewDTO;
import cn.jia.agent.entity.AgentTaskWorkItemDTO;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.entity.AgentWorkItemLeaseCommandDTO;
import cn.jia.agent.entity.AgentWorkItemLeaseDTO;
import cn.jia.agent.entity.AgentWorkItemResultCommitDTO;
import cn.jia.agent.entity.AgentWorkItemResultCommitViewDTO;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.exception.AgentTaskCollaborationException.Reason;
import cn.jia.agent.service.AgentTaskArtifactService;
import cn.jia.agent.service.AgentWorkItemDependencyService;
import cn.jia.agent.service.AgentWorkItemLeaseService;
import cn.jia.agent.service.AgentWorkItemResultCommitService;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.core.util.StringUtil;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Commits an authoritative result without reimplementing B04 lease validation. The B04 snapshot is
 * subsequently used as the exact WHERE predicate of B04's active-lease CAS; an artifact insert is
 * rolled back if that CAS loses to heartbeat/expiry/release/reclaim.
 */
@Named
public class AgentWorkItemResultCommitServiceImpl implements AgentWorkItemResultCommitService {
    private final AgentWorkItemLeaseService leaseService;
    private final AgentTaskArtifactService artifactService;
    private final AgentTaskWorkItemDao workItemDao;
    private final AgentTaskMutationTransaction mutationTransaction;
    private final AgentTaskEventWriter eventWriter;
    private final AgentWorkItemDependencyService dependencyService;
    private final LongSupplier clock;

    @Inject
    public AgentWorkItemResultCommitServiceImpl(
            AgentWorkItemLeaseService leaseService, AgentTaskArtifactService artifactService,
            AgentTaskWorkItemDao workItemDao, AgentTaskMutationTransaction mutationTransaction,
            AgentTaskEventWriter eventWriter, AgentWorkItemDependencyService dependencyService) {
        this(leaseService, artifactService, workItemDao, mutationTransaction, eventWriter,
                dependencyService, System::currentTimeMillis);
    }

    AgentWorkItemResultCommitServiceImpl(
            AgentWorkItemLeaseService leaseService, AgentTaskArtifactService artifactService,
            AgentTaskWorkItemDao workItemDao, LongSupplier clock) {
        this(leaseService, artifactService, workItemDao, directTransaction(), command -> null,
                noDependencyResolution(), clock);
    }

    AgentWorkItemResultCommitServiceImpl(
            AgentWorkItemLeaseService leaseService, AgentTaskArtifactService artifactService,
            AgentTaskWorkItemDao workItemDao, AgentTaskMutationTransaction mutationTransaction,
            AgentTaskEventWriter eventWriter, LongSupplier clock) {
        this(leaseService, artifactService, workItemDao, mutationTransaction, eventWriter,
                noDependencyResolution(), clock);
    }

    AgentWorkItemResultCommitServiceImpl(
            AgentWorkItemLeaseService leaseService, AgentTaskArtifactService artifactService,
            AgentTaskWorkItemDao workItemDao, AgentTaskMutationTransaction mutationTransaction,
            AgentTaskEventWriter eventWriter, AgentWorkItemDependencyService dependencyService,
            LongSupplier clock) {
        this.leaseService = Objects.requireNonNull(leaseService, "leaseService");
        this.artifactService = Objects.requireNonNull(artifactService, "artifactService");
        this.workItemDao = Objects.requireNonNull(workItemDao, "workItemDao");
        this.mutationTransaction = Objects.requireNonNull(mutationTransaction, "mutationTransaction");
        this.eventWriter = Objects.requireNonNull(eventWriter, "eventWriter");
        this.dependencyService = Objects.requireNonNull(dependencyService, "dependencyService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentWorkItemResultCommitViewDTO commitResult(
            String tenantId, String clientId, String taskId, String actorAgentId,
            AgentWorkItemResultCommitDTO command) {
        requireCommand(actorAgentId, command);
        AgentTaskArtifactPublishDTO artifact = command.getArtifact();
        if (!actorAgentId.equals(command.getProducerAgentId())
                || !actorAgentId.equals(artifact.getProducerAgentId())) {
            throw forbidden();
        }
        if (!command.getWorkItemId().equals(artifact.getWorkItemId())) {
            throw invalid("Result artifact must reference the committed work item");
        }

        return mutationTransaction.executeWithLockedTaskRoot(tenantId, clientId, taskId,
                taskRoot -> commitResultLocked(tenantId, clientId, taskId, actorAgentId, command));
    }

    private AgentWorkItemResultCommitViewDTO commitResultLocked(
            String tenantId, String clientId, String taskId, String actorAgentId,
            AgentWorkItemResultCommitDTO command) {
        AgentWorkItemLeaseCommandDTO leaseCommand = new AgentWorkItemLeaseCommandDTO();
        leaseCommand.setAgentId(actorAgentId);
        leaseCommand.setLeaseToken(command.getLeaseToken());
        leaseCommand.setExpectedVersion(command.getExpectedWorkItemVersion());
        AgentWorkItemLeaseDTO lease = leaseService.validateLeaseForResult(
                tenantId, clientId, taskId, command.getWorkItemId(), leaseCommand);
        requireExactLeaseSnapshot(taskId, actorAgentId, command, lease);

        AgentTaskArtifactViewDTO published = artifactService.publish(
                tenantId, clientId, taskId, actorAgentId, command.getArtifact());

        AgentTaskWorkItemEntity current = workItemDao.findByTaskAndWorkItemId(
                tenantId, clientId, taskId, command.getWorkItemId());
        requireCurrentMatchesValidatedLease(current, lease);
        long submittedAt = now();
        AgentTaskWorkItemDTO update = copyWorkItem(current);
        update.setStatus("submitted");
        update.setResultArtifactId(published.getArtifactId());
        update.setSubmittedAt(submittedAt);
        update.setCompletedAt(null);
        update.setLeaseToken(null);
        update.setLeaseUntil(null);

        int updated = workItemDao.updateActiveLeaseByVersion(
                tenantId, clientId, taskId, command.getWorkItemId(),
                lease.getAgentId(), lease.getLeaseToken(), lease.getStatus(),
                lease.getLeaseUntil(), lease.getVersion(), submittedAt, update);
        if (updated == 0) {
            throw new AgentTaskCollaborationException(
                    Reason.VERSION_CONFLICT, "Lease state changed during result commit; artifact was rolled back");
        }
        if (updated != 1) {
            throw invalidPersisted("Result CAS affected an unexpected row count");
        }

        appendSubmittedEvent(tenantId, clientId, taskId, actorAgentId, current,
                lease.getVersion(), lease.getVersion() + 1, submittedAt, published.getArtifactId());

        // The resolver joins this already-active REQUIRED transaction and task-root lock. A result
        // commit only reaches SUBMITTED, so it never fabricates completion; this call validates the
        // graph and catches up dependents whose authoritative prerequisites were completed earlier.
        dependencyService.resolveReady(tenantId, clientId, taskId);

        AgentWorkItemResultCommitViewDTO result = new AgentWorkItemResultCommitViewDTO();
        result.setTaskId(taskId);
        result.setWorkItemId(command.getWorkItemId());
        result.setStatus("submitted");
        result.setWorkItemVersion(lease.getVersion() + 1);
        result.setSubmittedAt(submittedAt);
        result.setArtifact(published);
        return result;
    }

    private void appendSubmittedEvent(String tenantId, String clientId, String taskId,
            String actorAgentId, AgentTaskWorkItemEntity current, long expectedVersion,
            long resultVersion, long occurredAt, String artifactId) {
        TaskEventPayload.Builder payload = TaskEventPayload.builder()
                .put(TaskEventPayload.Key.WORK_ITEM_ID, current.getWorkItemId())
                .put(TaskEventPayload.Key.ARTIFACT_ID, artifactId)
                .put(TaskEventPayload.Key.FROM_STATUS, current.getStatus())
                .put(TaskEventPayload.Key.TO_STATUS, "submitted")
                .put(TaskEventPayload.Key.EXPECTED_VERSION, expectedVersion)
                .put(TaskEventPayload.Key.RESULT_VERSION, resultVersion);
        eventWriter.append(AgentTaskMutationEventSupport.command(tenantId, clientId, taskId,
                TaskEventType.WORK_ITEM_SUBMITTED, TaskEventType.ActorType.AGENT, actorAgentId,
                TaskEventType.Aggregate.WORK_ITEM, current.getWorkItemId(), payload, occurredAt,
                resultVersion));
    }

    private void requireCommand(String actorAgentId, AgentWorkItemResultCommitDTO command) {
        if (StringUtil.isBlank(actorAgentId) || command == null
                || StringUtil.isBlank(command.getWorkItemId())
                || StringUtil.isBlank(command.getProducerAgentId())
                || StringUtil.isBlank(command.getLeaseToken())
                || command.getExpectedWorkItemVersion() == null
                || command.getExpectedWorkItemVersion() < 0
                || command.getExpectedWorkItemVersion() == Long.MAX_VALUE
                || command.getArtifact() == null) {
            throw invalid("workItemId, producerAgentId, leaseToken, expectedWorkItemVersion and artifact are required");
        }
    }

    private void requireExactLeaseSnapshot(
            String taskId, String actorAgentId, AgentWorkItemResultCommitDTO command,
            AgentWorkItemLeaseDTO lease) {
        if (lease == null || !taskId.equals(lease.getTaskId())
                || !command.getWorkItemId().equals(lease.getWorkItemId())
                || !actorAgentId.equals(lease.getAgentId())
                || !command.getLeaseToken().equals(lease.getLeaseToken())
                || !"running".equals(lease.getStatus())
                || !command.getExpectedWorkItemVersion().equals(lease.getVersion())
                || lease.getLeaseUntil() == null || lease.getLeaseUntil() <= 0) {
            throw invalidPersisted("B04 returned an incomplete or mismatched lease snapshot");
        }
    }

    private void requireCurrentMatchesValidatedLease(
            AgentTaskWorkItemEntity current, AgentWorkItemLeaseDTO lease) {
        if (current == null
                || !lease.getTaskId().equals(current.getTaskId())
                || !lease.getWorkItemId().equals(current.getWorkItemId())
                || !lease.getAgentId().equals(current.getAssigneeAgentId())
                || !lease.getLeaseToken().equals(current.getLeaseToken())
                || !lease.getStatus().equals(current.getStatus())
                || !lease.getLeaseUntil().equals(current.getLeaseUntil())
                || !lease.getVersion().equals(current.getVersion())) {
            throw new AgentTaskCollaborationException(
                    Reason.VERSION_CONFLICT, "Lease state changed during result commit; artifact was rolled back");
        }
    }

    private AgentTaskWorkItemDTO copyWorkItem(AgentTaskWorkItemEntity current) {
        AgentTaskWorkItemDTO update = new AgentTaskWorkItemDTO();
        update.setWorkItemId(current.getWorkItemId());
        update.setTaskId(current.getTaskId());
        update.setTitle(current.getTitle());
        update.setDescription(current.getDescription());
        update.setWorkType(current.getWorkType());
        update.setRequiredAbilities(current.getRequiredAbilities());
        update.setAssigneeAgentId(current.getAssigneeAgentId());
        update.setStatus(current.getStatus());
        update.setPriority(current.getPriority());
        update.setRequiredItem(current.getRequiredItem());
        update.setDependencyJson(current.getDependencyJson());
        update.setLeaseToken(current.getLeaseToken());
        update.setLeaseUntil(current.getLeaseUntil());
        update.setAttemptCount(current.getAttemptCount());
        update.setMaxAttempts(current.getMaxAttempts());
        update.setResultArtifactId(current.getResultArtifactId());
        update.setSubmittedAt(current.getSubmittedAt());
        update.setCompletedAt(current.getCompletedAt());
        update.setVersion(current.getVersion());
        return update;
    }

    private long now() {
        long value = clock.getAsLong();
        if (value < 0) {
            throw invalidPersisted("Result commit clock returned a negative timestamp");
        }
        return value;
    }

    private static AgentWorkItemDependencyService noDependencyResolution() {
        return (tenantId, clientId, taskId) -> null;
    }

    private static AgentTaskMutationTransaction directTransaction() {
        return new AgentTaskMutationTransaction() {
            @Override
            public <T> T executeWithLockedTaskRoot(String tenantId, String clientId, String taskId,
                    LockedTaskMutation<T> mutation) {
                return mutation.apply(null);
            }
            @Override
            public <T> T executeWithLockedTaskRootForWorkItem(String tenantId, String clientId,
                    String workItemId, LockedTaskMutation<T> mutation) {
                return mutation.apply(null);
            }
            @Override
            public <T> T executeAfterTaskRootReservation(String tenantId, String clientId,
                    String taskId, TaskRootReservation reservation, ReservedTaskMutation<T> mutation) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private AgentTaskCollaborationException invalid(String message) {
        return new AgentTaskCollaborationException(Reason.INVALID_REQUEST, message);
    }

    private AgentTaskCollaborationException invalidPersisted(String message) {
        return new AgentTaskCollaborationException(Reason.INVALID_PERSISTED_STATE, message);
    }

    private AgentTaskCollaborationException forbidden() {
        return new AgentTaskCollaborationException(
                Reason.FORBIDDEN, "Operation is not permitted in the requested scope");
    }
}
