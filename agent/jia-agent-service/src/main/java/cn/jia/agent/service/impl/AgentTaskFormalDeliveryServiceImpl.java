package cn.jia.agent.service.impl;

import cn.jia.agent.common.TaskEventPayload;
import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.dao.AgentTaskArtifactDao;
import cn.jia.agent.dao.AgentTaskFormalDeliveryDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.entity.AgentTaskArtifactEntity;
import cn.jia.agent.entity.AgentTaskEventWriteCommand;
import cn.jia.agent.entity.AgentTaskFormalDeliveryEntity;
import cn.jia.agent.entity.AgentTaskFormalDeliveryItemDTO;
import cn.jia.agent.entity.AgentTaskFormalDeliveryItemEntity;
import cn.jia.agent.entity.AgentTaskFormalDeliverySubmitDTO;
import cn.jia.agent.entity.AgentTaskFormalDeliveryViewDTO;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskWorkItemDTO;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.entity.AgentWorkItemLeaseCommandDTO;
import cn.jia.agent.entity.AgentWorkItemLeaseDTO;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.exception.AgentTaskCollaborationException.Reason;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskFormalDeliveryService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentWorkItemLeaseService;
import cn.jia.agent.state.AgentTaskFormalDeliveryState;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * R2 formal-delivery write boundary.
 *
 * <p>The root row is locked before a replay lookup, lease validation, artifact pin validation,
 * formal delivery insertion, both state CAS operations, and event append. A retry with the same
 * stable delivery id and canonical command digest returns its original batch before inspecting a
 * now-cleared lease.</p>
 */
@Named
public class AgentTaskFormalDeliveryServiceImpl implements AgentTaskFormalDeliveryService {
    private static final int MAX_ITEMS = 100;
    private static final String RUNNING = "running";
    private static final String SUBMITTED = "submitted";
    private static final String REVIEWING = "reviewing";

    private final AgentWorkItemLeaseService leaseService;
    private final AgentTaskArtifactDao artifactDao;
    private final AgentTaskFormalDeliveryDao deliveryDao;
    private final AgentTaskWorkItemDao workItemDao;
    private final AgentTaskMetaDao taskMetaDao;
    private final AgentTaskMutationTransaction mutationTransaction;
    private final AgentTaskEventWriter eventWriter;
    private final LongSupplier clock;

    @Inject
    public AgentTaskFormalDeliveryServiceImpl(
            AgentWorkItemLeaseService leaseService,
            AgentTaskArtifactDao artifactDao,
            AgentTaskFormalDeliveryDao deliveryDao,
            AgentTaskWorkItemDao workItemDao,
            AgentTaskMetaDao taskMetaDao,
            AgentTaskMutationTransaction mutationTransaction,
            AgentTaskEventWriter eventWriter) {
        this(leaseService, artifactDao, deliveryDao, workItemDao, taskMetaDao,
                mutationTransaction, eventWriter, System::currentTimeMillis);
    }

    AgentTaskFormalDeliveryServiceImpl(
            AgentWorkItemLeaseService leaseService,
            AgentTaskArtifactDao artifactDao,
            AgentTaskFormalDeliveryDao deliveryDao,
            AgentTaskWorkItemDao workItemDao,
            AgentTaskMetaDao taskMetaDao,
            AgentTaskMutationTransaction mutationTransaction,
            AgentTaskEventWriter eventWriter,
            LongSupplier clock) {
        this.leaseService = Objects.requireNonNull(leaseService, "leaseService");
        this.artifactDao = Objects.requireNonNull(artifactDao, "artifactDao");
        this.deliveryDao = Objects.requireNonNull(deliveryDao, "deliveryDao");
        this.workItemDao = Objects.requireNonNull(workItemDao, "workItemDao");
        this.taskMetaDao = Objects.requireNonNull(taskMetaDao, "taskMetaDao");
        this.mutationTransaction = Objects.requireNonNull(mutationTransaction, "mutationTransaction");
        this.eventWriter = Objects.requireNonNull(eventWriter, "eventWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentTaskFormalDeliveryViewDTO submit(
            String tenantId, String clientId, String taskId, String actorAgentId,
            AgentTaskFormalDeliverySubmitDTO command) {
        requireScope(tenantId, clientId, taskId, actorAgentId);
        RequiredCommand required = requireCommand(command);
        return mutationTransaction.executeWithLockedTaskRoot(tenantId, clientId, taskId,
                root -> submitLocked(tenantId, clientId, taskId, actorAgentId, root, required));
    }

    private AgentTaskFormalDeliveryViewDTO submitLocked(
            String tenantId, String clientId, String taskId, String actorAgentId,
            AgentTaskMetaEntity root, RequiredCommand command) {
        requireRootScope(root, tenantId, clientId, taskId);

        // Replay comes first: a successful response lost after commit must not be rejected merely
        // because its lease was cleared and the task has already moved to reviewing/completed.
        AgentTaskFormalDeliveryEntity existing = deliveryDao.findForUpdate(
                tenantId, clientId, command.deliveryId());
        if (existing != null) {
            requireReplay(existing, tenantId, clientId, taskId, actorAgentId, command);
            return view(existing, deliveryDao.listItems(tenantId, clientId, existing.getDeliveryId()),
                    root.getTaskVersion(), true);
        }

        requireNewDeliveryRoot(root, actorAgentId, command);
        AgentTaskWorkItemEntity workItem = requireSingleRequiredWorkItem(
                tenantId, clientId, taskId, command.workItemId());
        AgentWorkItemLeaseDTO lease = validateLease(
                tenantId, clientId, taskId, actorAgentId, command);
        requireCurrentLease(workItem, tenantId, clientId, taskId, actorAgentId, command, lease);

        List<PinnedArtifact> pins = validateExactArtifacts(
                tenantId, clientId, taskId, actorAgentId, command);
        AgentTaskFormalDeliveryEntity latest = deliveryDao.findLatestTaskForUpdate(
                tenantId, clientId, taskId);
        if (latest != null) {
            throw transition("A formal delivery revision already exists for this task");
        }

        long submittedAt = now();
        AgentTaskFormalDeliveryEntity delivery = new AgentTaskFormalDeliveryEntity()
                .setTaskId(taskId)
                .setWorkItemId(command.workItemId())
                .setDeliveryId(command.deliveryId())
                .setRevision(1L)
                .setProducerAgentId(actorAgentId)
                .setRunId(command.runId())
                .setSummary(command.summary())
                .setState(AgentTaskFormalDeliveryState.SUBMITTED.value())
                .setSubmissionDigest(command.submissionDigest())
                .setManifestArtifactId(command.manifestArtifactId())
                .setManifestArtifactVersion(command.manifestArtifactVersion())
                .setSubmittedAt(submittedAt)
                .setVersion(0L);
        requireOne(deliveryDao.insert(tenantId, clientId, delivery), "Formal delivery insert failed");
        for (int index = 0; index < pins.size(); index++) {
            PinnedArtifact pin = pins.get(index);
            AgentTaskFormalDeliveryItemEntity item = new AgentTaskFormalDeliveryItemEntity()
                    .setDeliveryId(command.deliveryId())
                    .setArtifactId(pin.artifactId())
                    .setArtifactVersion(pin.artifactVersion())
                    .setContentHash(pin.contentHash())
                    .setPurpose(pin.purpose())
                    .setItemOrder(index);
            requireOne(deliveryDao.insertItem(tenantId, clientId, item),
                    "Formal delivery item insert failed");
        }

        AgentTaskWorkItemDTO workUpdate = copyWorkItem(workItem);
        workUpdate.setStatus(SUBMITTED);
        workUpdate.setResultArtifactId(command.manifestArtifactId());
        workUpdate.setSubmittedAt(submittedAt);
        workUpdate.setCompletedAt(null);
        workUpdate.setLeaseToken(null);
        workUpdate.setLeaseUntil(null);
        int workUpdated = workItemDao.updateActiveLeaseByVersion(
                tenantId, clientId, taskId, command.workItemId(), actorAgentId,
                command.leaseToken(), RUNNING, lease.getLeaseUntil(), lease.getVersion(), submittedAt,
                workUpdate);
        if (workUpdated == 0) {
            throw conflict("Lease state changed during formal delivery submission");
        }
        requireOne(workUpdated, "Formal delivery work-item CAS affected an unexpected row count");

        int taskUpdated = taskMetaDao.updateStatusByVersion(
                tenantId, clientId, taskId, command.expectedTaskVersion(), REVIEWING,
                root.getStartedAt(), root.getCompletedAt(), root.getFailureReason());
        if (taskUpdated == 0) {
            throw conflict("Task state changed during formal delivery submission");
        }
        requireOne(taskUpdated, "Formal delivery task CAS affected an unexpected row count");

        appendEvents(tenantId, clientId, taskId, actorAgentId, command, workItem, submittedAt);
        List<AgentTaskFormalDeliveryItemEntity> persisted = deliveryDao.listItems(
                tenantId, clientId, command.deliveryId());
        if (persisted == null || persisted.size() != pins.size()) {
            throw invalidPersisted("Formal delivery items could not be read back exactly");
        }
        AgentTaskFormalDeliveryViewDTO result = view(
                delivery, persisted, command.expectedTaskVersion() + 1, false);
        result.setWorkItemVersion(command.expectedWorkItemVersion() + 1);
        return result;
    }

    private RequiredCommand requireCommand(AgentTaskFormalDeliverySubmitDTO command) {
        if (command == null) throw invalid("Formal delivery command is required");
        requireId(command.getDeliveryId(), "deliveryId");
        requireId(command.getRunId(), "runId");
        requireId(command.getWorkItemId(), "workItemId");
        requireId(command.getLeaseToken(), "leaseToken");
        requireVersion(command.getExpectedTaskVersion(), "expectedTaskVersion");
        requireVersion(command.getExpectedWorkItemVersion(), "expectedWorkItemVersion");
        requireText(command.getSummary(), "summary", 4_000);
        requireId(command.getManifestArtifactId(), "manifestArtifactId");
        if (command.getManifestArtifactVersion() == null || command.getManifestArtifactVersion() < 1) {
            throw invalid("manifestArtifactVersion must be positive");
        }
        List<AgentTaskFormalDeliveryItemDTO> source = command.getItems();
        if (source == null || source.isEmpty() || source.size() > MAX_ITEMS) {
            throw invalid("Formal delivery must contain between 1 and " + MAX_ITEMS + " artifacts");
        }
        List<RequiredItem> items = new ArrayList<>(source.size());
        Set<ArtifactKey> unique = new HashSet<>();
        boolean manifestFound = false;
        for (AgentTaskFormalDeliveryItemDTO item : source) {
            if (item == null) throw invalid("Formal delivery item is required");
            requireId(item.getArtifactId(), "artifactId");
            if (item.getArtifactVersion() == null || item.getArtifactVersion() < 1) {
                throw invalid("artifactVersion must be positive");
            }
            if (item.getContentHash() == null || !item.getContentHash().matches("[0-9a-f]{64}")) {
                throw invalid("contentHash must be lowercase SHA-256");
            }
            requireText(item.getPurpose(), "purpose", 255);
            ArtifactKey key = new ArtifactKey(item.getArtifactId(), item.getArtifactVersion());
            if (!unique.add(key)) throw invalid("Formal delivery contains a duplicate artifact version");
            if (command.getManifestArtifactId().equals(item.getArtifactId())
                    && command.getManifestArtifactVersion().intValue() == item.getArtifactVersion()) {
                manifestFound = true;
            }
            items.add(new RequiredItem(item.getArtifactId(), item.getArtifactVersion(),
                    item.getContentHash(), item.getPurpose()));
        }
        if (!manifestFound) {
            throw invalid("The manifest artifact must be pinned in formal delivery items");
        }
        String digest = submissionDigest(command, items);
        return new RequiredCommand(command.getDeliveryId(), command.getRunId(), command.getWorkItemId(),
                command.getLeaseToken(), command.getExpectedTaskVersion(),
                command.getExpectedWorkItemVersion(), command.getSummary(),
                command.getManifestArtifactId(), command.getManifestArtifactVersion(),
                List.copyOf(items), digest);
    }

    private AgentTaskWorkItemEntity requireSingleRequiredWorkItem(
            String tenantId, String clientId, String taskId, String requestedWorkItemId) {
        List<AgentTaskWorkItemEntity> workItems = workItemDao.listByTaskForUpdate(
                tenantId, clientId, taskId, 2);
        if (workItems == null || workItems.size() != 1) {
            throw transition("Formal delivery currently supports exactly one required work item");
        }
        AgentTaskWorkItemEntity item = workItems.getFirst();
        if (!requestedWorkItemId.equals(item.getWorkItemId()) || !Boolean.TRUE.equals(item.getRequiredItem())) {
            throw transition("Formal delivery work-item topology does not match the task");
        }
        return item;
    }

    private AgentWorkItemLeaseDTO validateLease(
            String tenantId, String clientId, String taskId, String actorAgentId,
            RequiredCommand command) {
        AgentWorkItemLeaseCommandDTO leaseCommand = new AgentWorkItemLeaseCommandDTO();
        leaseCommand.setAgentId(actorAgentId);
        leaseCommand.setLeaseToken(command.leaseToken());
        leaseCommand.setExpectedVersion(command.expectedWorkItemVersion());
        AgentWorkItemLeaseDTO lease = leaseService.validateLeaseForResult(
                tenantId, clientId, taskId, command.workItemId(), leaseCommand);
        if (lease == null || !taskId.equals(lease.getTaskId())
                || !command.workItemId().equals(lease.getWorkItemId())
                || !actorAgentId.equals(lease.getAgentId())
                || !command.leaseToken().equals(lease.getLeaseToken())
                || !RUNNING.equals(lease.getStatus())
                || !Objects.equals(command.expectedWorkItemVersion(), lease.getVersion())
                || lease.getLeaseUntil() == null || lease.getLeaseUntil() <= 0) {
            throw invalidPersisted("Lease validation returned an incomplete formal delivery snapshot");
        }
        return lease;
    }

    private void requireCurrentLease(AgentTaskWorkItemEntity workItem,
            String tenantId, String clientId, String taskId, String actorAgentId,
            RequiredCommand command, AgentWorkItemLeaseDTO lease) {
        if (workItem == null || !tenantId.equals(workItem.getTenantId())
                || !clientId.equals(workItem.getClientId()) || !taskId.equals(workItem.getTaskId())
                || !command.workItemId().equals(workItem.getWorkItemId())
                || !actorAgentId.equals(workItem.getAssigneeAgentId())
                || !RUNNING.equals(workItem.getStatus())
                || !command.leaseToken().equals(workItem.getLeaseToken())
                || !Objects.equals(lease.getLeaseUntil(), workItem.getLeaseUntil())
                || !Objects.equals(lease.getVersion(), workItem.getVersion())
                || workItem.getResultArtifactId() != null || workItem.getSubmittedAt() != null
                || workItem.getCompletedAt() != null) {
            throw conflict("Work-item state changed during formal delivery submission");
        }
    }

    private List<PinnedArtifact> validateExactArtifacts(
            String tenantId, String clientId, String taskId, String actorAgentId,
            RequiredCommand command) {
        List<PinnedArtifact> pins = new ArrayList<>(command.items().size());
        boolean manifestIsSummary = false;
        for (RequiredItem requested : command.items()) {
            AgentTaskArtifactEntity artifact = artifactDao.findVersion(tenantId, clientId, taskId,
                    requested.artifactId(), requested.artifactVersion());
            if (artifact == null) throw notFound("Formal delivery artifact is unavailable");
            if (!tenantId.equals(artifact.getTenantId()) || !clientId.equals(artifact.getClientId())
                    || !taskId.equals(artifact.getTaskId())
                    || !requested.artifactId().equals(artifact.getArtifactId())
                    || !Objects.equals(requested.artifactVersion(), artifact.getArtifactVersion())
                    || !actorAgentId.equals(artifact.getProducerAgentId())
                    || !command.workItemId().equals(artifact.getWorkItemId())
                    || !requested.contentHash().equals(artifact.getContentHash())) {
                throw forbidden("Formal delivery artifacts must be exact scoped agent outputs");
            }
            if (command.manifestArtifactId().equals(artifact.getArtifactId())
                    && Objects.equals(command.manifestArtifactVersion(), artifact.getArtifactVersion())) {
                manifestIsSummary = "summary".equals(artifact.getArtifactType());
            }
            pins.add(new PinnedArtifact(requested.artifactId(), requested.artifactVersion(),
                    requested.contentHash(), requested.purpose()));
        }
        if (!manifestIsSummary) {
            throw invalid("Formal delivery manifest must be a pinned summary artifact");
        }
        return List.copyOf(pins);
    }

    private void requireNewDeliveryRoot(
            AgentTaskMetaEntity root, String actorAgentId, RequiredCommand command) {
        if (!RUNNING.equals(root.getRewardStatus())
                || command.expectedTaskVersion() != root.getTaskVersion()
                || !actorAgentId.equals(root.getAssignedAgentId())) {
            throw transition("Task is not available for formal delivery submission");
        }
    }

    private void requireReplay(AgentTaskFormalDeliveryEntity existing,
            String tenantId, String clientId, String taskId, String actorAgentId,
            RequiredCommand command) {
        if (!tenantId.equals(existing.getTenantId()) || !clientId.equals(existing.getClientId())
                || !taskId.equals(existing.getTaskId())
                || !actorAgentId.equals(existing.getProducerAgentId())
                || !command.workItemId().equals(existing.getWorkItemId())
                || !command.runId().equals(existing.getRunId())
                || !command.manifestArtifactId().equals(existing.getManifestArtifactId())
                || !Objects.equals(command.manifestArtifactVersion(), existing.getManifestArtifactVersion())
                || !command.submissionDigest().equals(existing.getSubmissionDigest())) {
            throw conflict("deliveryId is already bound to another formal delivery command");
        }
        try {
            AgentTaskFormalDeliveryState.fromPersistedValue(existing.getState());
        } catch (IllegalArgumentException invalid) {
            throw invalidPersisted("Persisted formal delivery state is invalid");
        }
    }

    private void appendEvents(String tenantId, String clientId, String taskId, String actorAgentId,
            RequiredCommand command, AgentTaskWorkItemEntity workItem, long occurredAt) {
        eventWriter.append(AgentTaskMutationEventSupport.command(tenantId, clientId, taskId,
                TaskEventType.FORMAL_DELIVERY_SUBMITTED, TaskEventType.ActorType.AGENT, actorAgentId,
                TaskEventType.Aggregate.FORMAL_DELIVERY, command.deliveryId(),
                TaskEventPayload.builder()
                        .put(TaskEventPayload.Key.DELIVERY_ID, command.deliveryId())
                        .put(TaskEventPayload.Key.WORK_ITEM_ID, command.workItemId())
                        .put(TaskEventPayload.Key.PRODUCER_AGENT_ID, actorAgentId)
                        .put(TaskEventPayload.Key.RUN_ID, command.runId())
                        .put(TaskEventPayload.Key.ARTIFACT_ID, command.manifestArtifactId())
                        .put(TaskEventPayload.Key.ARTIFACT_VERSION, command.manifestArtifactVersion())
                        .put(TaskEventPayload.Key.DELIVERY_REVISION, 1L)
                        .put(TaskEventPayload.Key.SUBMISSION_DIGEST, command.submissionDigest())
                        .put(TaskEventPayload.Key.FROM_STATUS, RUNNING)
                        .put(TaskEventPayload.Key.TO_STATUS, SUBMITTED),
                occurredAt, 1L));

        long workResultVersion = command.expectedWorkItemVersion() + 1;
        eventWriter.append(AgentTaskMutationEventSupport.command(tenantId, clientId, taskId,
                TaskEventType.WORK_ITEM_SUBMITTED, TaskEventType.ActorType.AGENT, actorAgentId,
                TaskEventType.Aggregate.WORK_ITEM, command.workItemId(),
                TaskEventPayload.builder()
                        .put(TaskEventPayload.Key.WORK_ITEM_ID, command.workItemId())
                        .put(TaskEventPayload.Key.ARTIFACT_ID, command.manifestArtifactId())
                        .put(TaskEventPayload.Key.FROM_STATUS, workItem.getStatus())
                        .put(TaskEventPayload.Key.TO_STATUS, SUBMITTED)
                        .put(TaskEventPayload.Key.EXPECTED_VERSION, command.expectedWorkItemVersion())
                        .put(TaskEventPayload.Key.RESULT_VERSION, workResultVersion),
                occurredAt, workResultVersion));

        long taskResultVersion = command.expectedTaskVersion() + 1;
        eventWriter.append(AgentTaskMutationEventSupport.command(tenantId, clientId, taskId,
                TaskEventType.TASK_REVIEWING, TaskEventType.ActorType.AGENT, actorAgentId,
                TaskEventType.Aggregate.TASK, taskId,
                TaskEventPayload.builder()
                        .put(TaskEventPayload.Key.FROM_STATUS, RUNNING)
                        .put(TaskEventPayload.Key.TO_STATUS, REVIEWING)
                        .put(TaskEventPayload.Key.EXPECTED_VERSION, command.expectedTaskVersion())
                        .put(TaskEventPayload.Key.RESULT_VERSION, taskResultVersion),
                occurredAt, taskResultVersion));
    }

    private AgentTaskFormalDeliveryViewDTO view(AgentTaskFormalDeliveryEntity delivery,
            List<AgentTaskFormalDeliveryItemEntity> persistedItems, Long taskVersion, boolean replayed) {
        if (delivery == null || persistedItems == null || delivery.getRevision() == null
                || delivery.getRevision() < 1 || delivery.getSubmittedAt() == null
                || delivery.getSubmittedAt() <= 0) {
            throw invalidPersisted("Formal delivery readback is incomplete");
        }
        List<AgentTaskFormalDeliveryItemDTO> items = new ArrayList<>(persistedItems.size());
        for (int index = 0; index < persistedItems.size(); index++) {
            AgentTaskFormalDeliveryItemEntity item = persistedItems.get(index);
            if (item == null || !delivery.getDeliveryId().equals(item.getDeliveryId())
                    || item.getItemOrder() == null || item.getItemOrder() != index
                    || item.getArtifactVersion() == null || item.getArtifactVersion() < 1
                    || item.getContentHash() == null || !item.getContentHash().matches("[0-9a-f]{64}")) {
                throw invalidPersisted("Formal delivery item readback is inconsistent");
            }
            AgentTaskFormalDeliveryItemDTO dto = new AgentTaskFormalDeliveryItemDTO();
            dto.setArtifactId(item.getArtifactId());
            dto.setArtifactVersion(item.getArtifactVersion());
            dto.setContentHash(item.getContentHash());
            dto.setPurpose(item.getPurpose());
            items.add(dto);
        }
        AgentTaskFormalDeliveryViewDTO view = new AgentTaskFormalDeliveryViewDTO();
        view.setTaskId(delivery.getTaskId());
        view.setWorkItemId(delivery.getWorkItemId());
        view.setDeliveryId(delivery.getDeliveryId());
        view.setRevision(delivery.getRevision());
        view.setState(delivery.getState());
        view.setRunId(delivery.getRunId());
        view.setProducerAgentId(delivery.getProducerAgentId());
        view.setSummary(delivery.getSummary());
        view.setManifestArtifactId(delivery.getManifestArtifactId());
        view.setManifestArtifactVersion(delivery.getManifestArtifactVersion());
        view.setSubmittedAt(delivery.getSubmittedAt());
        view.setTaskVersion(taskVersion);
        view.setReplayed(replayed);
        view.setItems(List.copyOf(items));
        return view;
    }

    private AgentTaskWorkItemDTO copyWorkItem(AgentTaskWorkItemEntity current) {
        return new AgentTaskWorkItemDTO()
                .setWorkItemId(current.getWorkItemId())
                .setTaskId(current.getTaskId())
                .setTitle(current.getTitle())
                .setDescription(current.getDescription())
                .setWorkType(current.getWorkType())
                .setRequiredAbilities(current.getRequiredAbilities())
                .setAssigneeAgentId(current.getAssigneeAgentId())
                .setStatus(current.getStatus())
                .setPriority(current.getPriority())
                .setRequiredItem(current.getRequiredItem())
                .setDependencyJson(current.getDependencyJson())
                .setLeaseToken(current.getLeaseToken())
                .setLeaseUntil(current.getLeaseUntil())
                .setAttemptCount(current.getAttemptCount())
                .setMaxAttempts(current.getMaxAttempts())
                .setResultArtifactId(current.getResultArtifactId())
                .setSubmittedAt(current.getSubmittedAt())
                .setCompletedAt(current.getCompletedAt())
                .setVersion(current.getVersion());
    }

    private void requireRootScope(AgentTaskMetaEntity root,
            String tenantId, String clientId, String taskId) {
        if (root == null || !tenantId.equals(root.getTenantId()) || !clientId.equals(root.getClientId())
                || !taskId.equals(root.getTaskId()) || root.getTaskVersion() == null
                || root.getTaskVersion() < 0 || root.getTaskVersion() == Long.MAX_VALUE) {
            throw invalidPersisted("Formal delivery task root is invalid");
        }
    }

    private String submissionDigest(AgentTaskFormalDeliverySubmitDTO command, List<RequiredItem> items) {
        StringBuilder canonical = new StringBuilder("formal-delivery-r2\\n");
        appendCanonical(canonical, command.getDeliveryId());
        appendCanonical(canonical, command.getRunId());
        appendCanonical(canonical, command.getWorkItemId());
        appendCanonical(canonical, Long.toString(command.getExpectedTaskVersion()));
        appendCanonical(canonical, Long.toString(command.getExpectedWorkItemVersion()));
        appendCanonical(canonical, command.getSummary());
        appendCanonical(canonical, command.getManifestArtifactId());
        appendCanonical(canonical, Integer.toString(command.getManifestArtifactVersion()));
        for (RequiredItem item : items) {
            appendCanonical(canonical, item.artifactId());
            appendCanonical(canonical, Integer.toString(item.artifactVersion()));
            appendCanonical(canonical, item.contentHash());
            appendCanonical(canonical, item.purpose());
        }
        return TaskEventPayload.ContentDigest.fromUtf8(canonical.toString()).sha256();
    }

    private void appendCanonical(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value).append('\n');
    }

    private long now() {
        long value = clock.getAsLong();
        if (value <= 0) throw invalidPersisted("Formal delivery clock is invalid");
        return value;
    }

    private static void requireScope(String tenantId, String clientId, String taskId, String actorAgentId) {
        requireId(tenantId, "tenantId");
        requireId(clientId, "clientId");
        requireId(taskId, "taskId");
        requireId(actorAgentId, "actorAgentId");
        if (tenantId.length() > 50 || clientId.length() > 50) throw invalidStatic("scope is invalid");
    }

    private static void requireId(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 100 || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw invalidStatic(name + " is invalid");
        }
    }

    private static void requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw invalidStatic(name + " is invalid");
        }
    }

    private static void requireVersion(Long value, String name) {
        if (value == null || value < 0 || value == Long.MAX_VALUE) {
            throw invalidStatic(name + " is invalid");
        }
    }

    private static void requireOne(int rows, String message) {
        if (rows != 1) throw invalidStatic(message);
    }

    private static AgentTaskCollaborationException invalid(String message) {
        return invalidStatic(message);
    }

    private static AgentTaskCollaborationException invalidStatic(String message) {
        return new AgentTaskCollaborationException(Reason.INVALID_REQUEST, message);
    }

    private static AgentTaskCollaborationException invalidPersisted(String message) {
        return new AgentTaskCollaborationException(Reason.INVALID_PERSISTED_STATE, message);
    }

    private static AgentTaskCollaborationException conflict(String message) {
        return new AgentTaskCollaborationException(Reason.VERSION_CONFLICT, message);
    }

    private static AgentTaskCollaborationException transition(String message) {
        return new AgentTaskCollaborationException(Reason.INVALID_TRANSITION, message);
    }

    private static AgentTaskCollaborationException notFound(String message) {
        return new AgentTaskCollaborationException(Reason.NOT_FOUND, message);
    }

    private static AgentTaskCollaborationException forbidden(String message) {
        return new AgentTaskCollaborationException(Reason.FORBIDDEN, message);
    }

    private record ArtifactKey(String artifactId, int artifactVersion) { }
    private record RequiredItem(String artifactId, int artifactVersion, String contentHash, String purpose) { }
    private record PinnedArtifact(String artifactId, int artifactVersion, String contentHash, String purpose) { }
    private record RequiredCommand(String deliveryId, String runId, String workItemId, String leaseToken,
            long expectedTaskVersion, long expectedWorkItemVersion, String summary,
            String manifestArtifactId, int manifestArtifactVersion, List<RequiredItem> items,
            String submissionDigest) { }
}
