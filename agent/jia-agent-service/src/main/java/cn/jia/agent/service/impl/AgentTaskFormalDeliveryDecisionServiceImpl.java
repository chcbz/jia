package cn.jia.agent.service.impl;

import cn.jia.agent.common.TaskEventPayload;
import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.dao.AgentTaskFormalDeliveryDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.entity.AgentTaskEventWriteCommand;
import cn.jia.agent.entity.AgentTaskFormalDeliveryDecisionDTO;
import cn.jia.agent.entity.AgentTaskFormalDeliveryEntity;
import cn.jia.agent.entity.AgentTaskFormalDeliveryItemDTO;
import cn.jia.agent.entity.AgentTaskFormalDeliveryItemEntity;
import cn.jia.agent.entity.AgentTaskFormalDeliveryViewDTO;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskWorkItemDTO;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.exception.AgentTaskCollaborationException.Reason;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskFormalDeliveryDecisionService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.state.AgentTaskFormalDeliveryState;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Owner decision and explicit rework boundary for R2 deliveries.
 *
 * <p>A changes-requested decision clears the submitted work item's result and returns it to
 * {@code ready}; an old run cannot resubmit because its active lease was already cleared by the
 * original submission. The next delivery therefore requires a newly claimed lease.</p>
 */
@Named
public class AgentTaskFormalDeliveryDecisionServiceImpl implements AgentTaskFormalDeliveryDecisionService {
    private static final String OWNER_ROLE = "task_owner";
    private static final String REVIEWING = "reviewing";
    private static final String RUNNING = "running";
    private static final String SUBMITTED = "submitted";
    private static final String READY = "ready";
    private static final String COMPLETED = "completed";

    private final AgentTaskFormalDeliveryDao deliveryDao;
    private final AgentTaskMetaDao taskMetaDao;
    private final AgentTaskWorkItemDao workItemDao;
    private final AgentTaskMutationTransaction mutationTransaction;
    private final AgentTaskEventWriter eventWriter;
    private final LongSupplier clock;

    @Inject
    public AgentTaskFormalDeliveryDecisionServiceImpl(AgentTaskFormalDeliveryDao deliveryDao,
            AgentTaskMetaDao taskMetaDao, AgentTaskWorkItemDao workItemDao,
            AgentTaskMutationTransaction mutationTransaction, AgentTaskEventWriter eventWriter) {
        this(deliveryDao, taskMetaDao, workItemDao, mutationTransaction, eventWriter,
                System::currentTimeMillis);
    }

    AgentTaskFormalDeliveryDecisionServiceImpl(AgentTaskFormalDeliveryDao deliveryDao,
            AgentTaskMetaDao taskMetaDao, AgentTaskWorkItemDao workItemDao,
            AgentTaskMutationTransaction mutationTransaction, AgentTaskEventWriter eventWriter,
            LongSupplier clock) {
        this.deliveryDao = Objects.requireNonNull(deliveryDao, "deliveryDao");
        this.taskMetaDao = Objects.requireNonNull(taskMetaDao, "taskMetaDao");
        this.workItemDao = Objects.requireNonNull(workItemDao, "workItemDao");
        this.mutationTransaction = Objects.requireNonNull(mutationTransaction, "mutationTransaction");
        this.eventWriter = Objects.requireNonNull(eventWriter, "eventWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentTaskFormalDeliveryViewDTO decide(String tenantId, String clientId, String taskId,
            String ownerJiacn, AgentTaskFormalDeliveryDecisionDTO command) {
        requireScope(tenantId, clientId, taskId, ownerJiacn);
        Decision decision = normalize(command);
        return mutationTransaction.executeWithLockedTaskRoot(tenantId, clientId, taskId,
                root -> decideLocked(tenantId, clientId, taskId, ownerJiacn, root, decision));
    }

    private AgentTaskFormalDeliveryViewDTO decideLocked(String tenantId, String clientId, String taskId,
            String ownerJiacn, AgentTaskMetaEntity root, Decision decision) {
        requireRootScope(root, tenantId, clientId, taskId);
        AgentTaskFormalDeliveryEntity delivery = deliveryDao.findForUpdate(
                tenantId, clientId, decision.deliveryId());
        if (delivery == null) throw notFound("Formal delivery is unavailable");
        requireDeliveryScope(delivery, tenantId, clientId, taskId);

        if (isReplay(delivery, ownerJiacn, decision)) {
            return view(delivery, deliveryDao.listItems(tenantId, clientId, delivery.getDeliveryId()),
                    root.getTaskVersion(), null, true);
        }
        requirePendingRootAndDelivery(root, delivery, decision);
        AgentTaskWorkItemEntity workItem = workItemDao.findByTaskAndWorkItemId(
                tenantId, clientId, taskId, delivery.getWorkItemId());
        requireSubmittedWorkItem(workItem, tenantId, clientId, taskId, delivery);

        long decidedAt = now();
        int reviewed = deliveryDao.reviewByVersion(tenantId, clientId, delivery.getDeliveryId(),
                AgentTaskFormalDeliveryState.SUBMITTED.value(), decision.expectedDeliveryVersion(),
                decision.state().value(), ownerJiacn, decision.reviewReason(), decidedAt);
        if (reviewed == 0) throw conflict("Formal delivery decision changed concurrently");
        requireOne(reviewed, "Formal delivery decision CAS affected an unexpected row count");

        AgentTaskWorkItemDTO workUpdate = copyWorkItem(workItem);
        if (decision.state() == AgentTaskFormalDeliveryState.ACCEPTED) {
            workUpdate.setStatus(COMPLETED);
            workUpdate.setCompletedAt(decidedAt);
            updateTask(tenantId, clientId, taskId, root, COMPLETED, root.getStartedAt(), decidedAt);
        } else {
            workUpdate.setStatus(READY);
            workUpdate.setResultArtifactId(null);
            workUpdate.setSubmittedAt(null);
            workUpdate.setCompletedAt(null);
            workUpdate.setLeaseToken(null);
            workUpdate.setLeaseUntil(null);
            updateTask(tenantId, clientId, taskId, root, RUNNING, root.getStartedAt(), null);
        }
        int workUpdated = workItemDao.updateByVersion(
                tenantId, clientId, workItem.getWorkItemId(), workItem.getVersion(), workUpdate);
        if (workUpdated == 0) throw conflict("Work item changed during formal delivery decision");
        requireOne(workUpdated, "Formal delivery work-item CAS affected an unexpected row count");

        AgentTaskFormalDeliveryEntity result = copyDecision(delivery, decision, ownerJiacn, decidedAt);
        appendEvents(tenantId, clientId, taskId, decision, workItem, root, decidedAt);
        return view(result, deliveryDao.listItems(tenantId, clientId, delivery.getDeliveryId()),
                root.getTaskVersion() + 1, workItem.getVersion() + 1, false);
    }

    private void updateTask(String tenantId, String clientId, String taskId, AgentTaskMetaEntity root,
            String target, Long startedAt, Long completedAt) {
        int updated = taskMetaDao.updateStatusByVersion(tenantId, clientId, taskId,
                root.getTaskVersion(), target, startedAt, completedAt, null);
        if (updated == 0) throw conflict("Task changed during formal delivery decision");
        requireOne(updated, "Formal delivery task CAS affected an unexpected row count");
    }

    private void appendEvents(String tenantId, String clientId, String taskId, Decision decision,
            AgentTaskWorkItemEntity workItem, AgentTaskMetaEntity root, long occurredAt) {
        String formalEvent = decision.state() == AgentTaskFormalDeliveryState.ACCEPTED
                ? TaskEventType.FORMAL_DELIVERY_ACCEPTED
                : TaskEventType.FORMAL_DELIVERY_CHANGES_REQUESTED;
        TaskEventPayload.Builder formal = TaskEventPayload.builder()
                .put(TaskEventPayload.Key.DELIVERY_ID, decision.deliveryId())
                .put(TaskEventPayload.Key.WORK_ITEM_ID, workItem.getWorkItemId())
                .put(TaskEventPayload.Key.FROM_STATUS, SUBMITTED)
                .put(TaskEventPayload.Key.TO_STATUS, decision.state().value())
                .put(TaskEventPayload.Key.EXPECTED_VERSION, decision.expectedDeliveryVersion())
                .put(TaskEventPayload.Key.RESULT_VERSION, decision.expectedDeliveryVersion() + 1);
        if (decision.state() == AgentTaskFormalDeliveryState.CHANGES_REQUESTED) {
            formal.put(TaskEventPayload.Key.REASON_CODE, "owner_changes_requested");
        }
        eventWriter.append(AgentTaskMutationEventSupport.command(tenantId, clientId, taskId,
                formalEvent, TaskEventType.ActorType.ROLE, OWNER_ROLE,
                TaskEventType.Aggregate.FORMAL_DELIVERY, decision.deliveryId(), formal, occurredAt,
                decision.expectedDeliveryVersion() + 1));

        long workResult = workItem.getVersion() + 1;
        String workTarget = decision.state() == AgentTaskFormalDeliveryState.ACCEPTED
                ? COMPLETED : READY;
        eventWriter.append(AgentTaskMutationEventSupport.command(tenantId, clientId, taskId,
                decision.state() == AgentTaskFormalDeliveryState.ACCEPTED
                        ? TaskEventType.WORK_ITEM_COMPLETED : TaskEventType.WORK_ITEM_READY,
                TaskEventType.ActorType.SYSTEM, null, TaskEventType.Aggregate.WORK_ITEM,
                workItem.getWorkItemId(), TaskEventPayload.builder()
                        .put(TaskEventPayload.Key.WORK_ITEM_ID, workItem.getWorkItemId())
                        .put(TaskEventPayload.Key.FROM_STATUS, SUBMITTED)
                        .put(TaskEventPayload.Key.TO_STATUS, workTarget)
                        .put(TaskEventPayload.Key.EXPECTED_VERSION, workItem.getVersion())
                        .put(TaskEventPayload.Key.RESULT_VERSION, workResult), occurredAt, workResult));

        long taskResult = root.getTaskVersion() + 1;
        String taskTarget = decision.state() == AgentTaskFormalDeliveryState.ACCEPTED
                ? COMPLETED : RUNNING;
        eventWriter.append(AgentTaskMutationEventSupport.command(tenantId, clientId, taskId,
                decision.state() == AgentTaskFormalDeliveryState.ACCEPTED
                        ? TaskEventType.TASK_COMPLETED : TaskEventType.TASK_STARTED,
                TaskEventType.ActorType.SYSTEM, null, TaskEventType.Aggregate.TASK, taskId,
                TaskEventPayload.builder()
                        .put(TaskEventPayload.Key.FROM_STATUS, REVIEWING)
                        .put(TaskEventPayload.Key.TO_STATUS, taskTarget)
                        .put(TaskEventPayload.Key.EXPECTED_VERSION, root.getTaskVersion())
                        .put(TaskEventPayload.Key.RESULT_VERSION, taskResult), occurredAt, taskResult));
    }

    private boolean isReplay(AgentTaskFormalDeliveryEntity delivery, String ownerJiacn,
            Decision decision) {
        return decision.state().value().equals(delivery.getState())
                && Objects.equals(decision.expectedDeliveryVersion() + 1, delivery.getVersion())
                && ownerJiacn.equals(delivery.getReviewedByJiacn())
                && Objects.equals(decision.reviewReason(), delivery.getReviewReason())
                && delivery.getReviewedAt() != null && delivery.getReviewedAt() > 0;
    }

    private void requirePendingRootAndDelivery(AgentTaskMetaEntity root,
            AgentTaskFormalDeliveryEntity delivery, Decision decision) {
        if (!REVIEWING.equals(root.getRewardStatus())
                || root.getTaskVersion() != decision.expectedTaskVersion()
                || !SUBMITTED.equals(delivery.getState())
                || !Objects.equals(decision.expectedDeliveryVersion(), delivery.getVersion())) {
            throw transition("Formal delivery is not awaiting this owner decision");
        }
    }

    private void requireSubmittedWorkItem(AgentTaskWorkItemEntity item, String tenantId,
            String clientId, String taskId, AgentTaskFormalDeliveryEntity delivery) {
        if (item == null || !tenantId.equals(item.getTenantId()) || !clientId.equals(item.getClientId())
                || !taskId.equals(item.getTaskId()) || !delivery.getWorkItemId().equals(item.getWorkItemId())
                || !SUBMITTED.equals(item.getStatus()) || item.getVersion() == null
                || item.getVersion() < 0 || item.getResultArtifactId() == null
                || !delivery.getManifestArtifactId().equals(item.getResultArtifactId())
                || item.getLeaseToken() != null || item.getLeaseUntil() != null) {
            throw invalidPersisted("Submitted work item does not match formal delivery state");
        }
    }

    private Decision normalize(AgentTaskFormalDeliveryDecisionDTO command) {
        if (command == null) throw invalid("Formal delivery decision is required");
        requireId(command.getDeliveryId(), "deliveryId", 100);
        requireVersion(command.getExpectedTaskVersion(), "expectedTaskVersion");
        requireVersion(command.getExpectedDeliveryVersion(), "expectedDeliveryVersion");
        AgentTaskFormalDeliveryState state;
        try { state = AgentTaskFormalDeliveryState.fromPersistedValue(command.getDecision()); }
        catch (IllegalArgumentException invalid) { throw invalid("Formal delivery decision is invalid"); }
        if (state == AgentTaskFormalDeliveryState.SUBMITTED) {
            throw invalid("Formal delivery decision is invalid");
        }
        String reason = command.getReviewReason();
        if (state == AgentTaskFormalDeliveryState.CHANGES_REQUESTED) {
            requireText(reason, "reviewReason", 4_000);
        } else if (reason != null) {
            throw invalid("Accepted formal delivery must not carry a review reason");
        }
        return new Decision(command.getDeliveryId(), command.getExpectedTaskVersion(),
                command.getExpectedDeliveryVersion(), state, reason);
    }

    private AgentTaskFormalDeliveryEntity copyDecision(AgentTaskFormalDeliveryEntity delivery,
            Decision decision, String ownerJiacn, long decidedAt) {
        AgentTaskFormalDeliveryEntity result = new AgentTaskFormalDeliveryEntity();
        result.setTaskId(delivery.getTaskId()); result.setWorkItemId(delivery.getWorkItemId());
        result.setDeliveryId(delivery.getDeliveryId()); result.setRevision(delivery.getRevision());
        result.setProducerAgentId(delivery.getProducerAgentId()); result.setRunId(delivery.getRunId());
        result.setSummary(delivery.getSummary()); result.setSubmissionDigest(delivery.getSubmissionDigest());
        result.setManifestArtifactId(delivery.getManifestArtifactId());
        result.setManifestArtifactVersion(delivery.getManifestArtifactVersion());
        result.setSubmittedAt(delivery.getSubmittedAt()); result.setState(decision.state().value());
        result.setVersion(decision.expectedDeliveryVersion() + 1); result.setReviewedByJiacn(ownerJiacn);
        result.setReviewReason(decision.reviewReason()); result.setReviewedAt(decidedAt);
        return result;
    }

    private AgentTaskFormalDeliveryViewDTO view(AgentTaskFormalDeliveryEntity delivery,
            List<AgentTaskFormalDeliveryItemEntity> rows, Long taskVersion, Long workItemVersion,
            boolean replayed) {
        if (rows == null) throw invalidPersisted("Formal delivery items are unavailable");
        List<AgentTaskFormalDeliveryItemDTO> items = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            AgentTaskFormalDeliveryItemEntity row = rows.get(index);
            if (row == null || !delivery.getDeliveryId().equals(row.getDeliveryId())
                    || !Objects.equals(index, row.getItemOrder()) || row.getArtifactVersion() == null
                    || row.getArtifactVersion() < 1 || row.getContentHash() == null
                    || !row.getContentHash().matches("[0-9a-f]{64}")) {
                throw invalidPersisted("Formal delivery item readback is invalid");
            }
            AgentTaskFormalDeliveryItemDTO item = new AgentTaskFormalDeliveryItemDTO();
            item.setArtifactId(row.getArtifactId()); item.setArtifactVersion(row.getArtifactVersion());
            item.setContentHash(row.getContentHash()); item.setPurpose(row.getPurpose()); items.add(item);
        }
        AgentTaskFormalDeliveryViewDTO view = new AgentTaskFormalDeliveryViewDTO();
        view.setTaskId(delivery.getTaskId()); view.setWorkItemId(delivery.getWorkItemId());
        view.setDeliveryId(delivery.getDeliveryId()); view.setRevision(delivery.getRevision());
        view.setState(delivery.getState()); view.setRunId(delivery.getRunId());
        view.setProducerAgentId(delivery.getProducerAgentId()); view.setSummary(delivery.getSummary());
        view.setManifestArtifactId(delivery.getManifestArtifactId());
        view.setManifestArtifactVersion(delivery.getManifestArtifactVersion());
        view.setSubmittedAt(delivery.getSubmittedAt()); view.setTaskVersion(taskVersion);
        view.setWorkItemVersion(workItemVersion); view.setReplayed(replayed); view.setItems(List.copyOf(items));
        return view;
    }

    private AgentTaskWorkItemDTO copyWorkItem(AgentTaskWorkItemEntity source) {
        AgentTaskWorkItemDTO result = new AgentTaskWorkItemDTO();
        result.setWorkItemId(source.getWorkItemId());
        result.setTaskId(source.getTaskId());
        result.setTitle(source.getTitle());
        result.setDescription(source.getDescription());
        result.setWorkType(source.getWorkType());
        result.setRequiredAbilities(source.getRequiredAbilities());
        result.setAssigneeAgentId(source.getAssigneeAgentId());
        result.setStatus(source.getStatus());
        result.setPriority(source.getPriority());
        result.setRequiredItem(source.getRequiredItem());
        result.setDependencyJson(source.getDependencyJson());
        result.setLeaseToken(source.getLeaseToken());
        result.setLeaseUntil(source.getLeaseUntil());
        result.setAttemptCount(source.getAttemptCount());
        result.setMaxAttempts(source.getMaxAttempts());
        result.setResultArtifactId(source.getResultArtifactId());
        result.setSubmittedAt(source.getSubmittedAt());
        result.setCompletedAt(source.getCompletedAt());
        result.setVersion(source.getVersion());
        return result;
    }

    private void requireRootScope(AgentTaskMetaEntity root, String tenantId, String clientId, String taskId) {
        if (root == null || !tenantId.equals(root.getTenantId()) || !clientId.equals(root.getClientId())
                || !taskId.equals(root.getTaskId()) || root.getTaskVersion() == null
                || root.getTaskVersion() < 0 || root.getTaskVersion() == Long.MAX_VALUE) {
            throw invalidPersisted("Formal delivery task root is invalid");
        }
    }

    private void requireDeliveryScope(AgentTaskFormalDeliveryEntity delivery, String tenantId,
            String clientId, String taskId) {
        if (!tenantId.equals(delivery.getTenantId()) || !clientId.equals(delivery.getClientId())
                || !taskId.equals(delivery.getTaskId()) || delivery.getVersion() == null
                || delivery.getVersion() < 0 || delivery.getRevision() == null || delivery.getRevision() < 1) {
            throw invalidPersisted("Formal delivery scope is invalid");
        }
    }

    private long now() { long value = clock.getAsLong(); if (value <= 0) throw invalidPersisted("Clock is invalid"); return value; }
    private static void requireScope(String tenant, String client, String task, String owner) {
        requireId(tenant, "tenantId", 50); requireId(client, "clientId", 50); requireId(task, "taskId", 100);
        requireId(owner, "ownerJiacn", 50);
        if (!tenant.equals(owner)) throw forbidden("Task owner is outside the requested tenant scope");
    }
    private static void requireId(String value, String name, int max) { if (value == null || value.isBlank() || value.length() > max || !value.equals(value.strip()) || value.codePoints().anyMatch(Character::isISOControl)) throw invalid(name + " is invalid"); }
    private static void requireText(String value, String name, int max) { if (value == null || value.isBlank() || value.length() > max || !value.equals(value.strip()) || value.codePoints().anyMatch(Character::isISOControl)) throw invalid(name + " is invalid"); }
    private static void requireVersion(Long value, String name) { if (value == null || value < 0 || value == Long.MAX_VALUE) throw invalid(name + " is invalid"); }
    private static void requireOne(int rows, String message) { if (rows != 1) throw invalidPersisted(message); }
    private static AgentTaskCollaborationException invalid(String message) { return new AgentTaskCollaborationException(Reason.INVALID_REQUEST, message); }
    private static AgentTaskCollaborationException invalidPersisted(String message) { return new AgentTaskCollaborationException(Reason.INVALID_PERSISTED_STATE, message); }
    private static AgentTaskCollaborationException conflict(String message) { return new AgentTaskCollaborationException(Reason.VERSION_CONFLICT, message); }
    private static AgentTaskCollaborationException transition(String message) { return new AgentTaskCollaborationException(Reason.INVALID_TRANSITION, message); }
    private static AgentTaskCollaborationException notFound(String message) { return new AgentTaskCollaborationException(Reason.NOT_FOUND, message); }
    private static AgentTaskCollaborationException forbidden(String message) { return new AgentTaskCollaborationException(Reason.FORBIDDEN, message); }
    private record Decision(String deliveryId, long expectedTaskVersion, long expectedDeliveryVersion,
                            AgentTaskFormalDeliveryState state, String reviewReason) { }
}
