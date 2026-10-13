package cn.jia.agent.service.impl;

import cn.jia.agent.common.TaskEventPayload;
import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.dao.AgentTaskEventDao;
import cn.jia.agent.service.AgentWorkItemReassignmentService;
import cn.jia.agent.service.AgentWorkItemReassignmentService.RuntimeResultSource;
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
import cn.jia.agent.service.AgentWorkItemLeaseService;
import cn.jia.agent.service.AgentWorkItemResultCommitService;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.core.util.StringUtil;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
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
    private final LongSupplier clock;
    private AgentWorkItemReassignmentService reassignments;
    private AgentTaskEventDao taskEvents;
    private static final ObjectMapper MATERIAL_JSON = new ObjectMapper();

    @Inject
    public void setRuntimeResultRecovery(AgentWorkItemReassignmentService reassignments, AgentTaskEventDao taskEvents) {
        this.reassignments = Objects.requireNonNull(reassignments);
        this.taskEvents = Objects.requireNonNull(taskEvents);
    }

    @Inject
    public AgentWorkItemResultCommitServiceImpl(
            AgentWorkItemLeaseService leaseService, AgentTaskArtifactService artifactService,
            AgentTaskWorkItemDao workItemDao, AgentTaskMutationTransaction mutationTransaction,
            AgentTaskEventWriter eventWriter) {
        this(leaseService, artifactService, workItemDao, mutationTransaction, eventWriter,
                System::currentTimeMillis);
    }

    AgentWorkItemResultCommitServiceImpl(
            AgentWorkItemLeaseService leaseService, AgentTaskArtifactService artifactService,
            AgentTaskWorkItemDao workItemDao, LongSupplier clock) {
        this(leaseService, artifactService, workItemDao, directTransaction(), command -> null, clock);
    }

    AgentWorkItemResultCommitServiceImpl(
            AgentWorkItemLeaseService leaseService, AgentTaskArtifactService artifactService,
            AgentTaskWorkItemDao workItemDao, AgentTaskMutationTransaction mutationTransaction,
            AgentTaskEventWriter eventWriter, LongSupplier clock) {
        this.leaseService = Objects.requireNonNull(leaseService, "leaseService");
        this.artifactService = Objects.requireNonNull(artifactService, "artifactService");
        this.workItemDao = Objects.requireNonNull(workItemDao, "workItemDao");
        this.mutationTransaction = Objects.requireNonNull(mutationTransaction, "mutationTransaction");
        this.eventWriter = Objects.requireNonNull(eventWriter, "eventWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentWorkItemResultCommitViewDTO commitResult(
            String tenantId, String clientId, String ownerJiacn, String taskId,
            String actorAgentId, AgentWorkItemResultCommitDTO command) {
        requireScope(tenantId, clientId, ownerJiacn, taskId);
        requireCommand(actorAgentId, command);
        AgentTaskArtifactPublishDTO artifact = command.getArtifact();
        if (!actorAgentId.equals(command.getProducerAgentId())
                || !actorAgentId.equals(artifact.getProducerAgentId())) {
            throw forbidden();
        }
        if (!command.getWorkItemId().equals(artifact.getWorkItemId())) {
            throw invalid("Result artifact must reference the committed work item");
        }

        return mutationTransaction.executeWithLockedTaskRootInOwnerScope(
                tenantId, clientId, ownerJiacn, taskId,
                taskRoot -> commitResultLocked(
                        tenantId, clientId, ownerJiacn, taskId, actorAgentId, command, null, null));
    }

    private AgentWorkItemResultCommitViewDTO commitResultLocked(
            String tenantId, String clientId, String ownerJiacn, String taskId, String actorAgentId,
            AgentWorkItemResultCommitDTO command, PreparedArtifact prepared, RuntimeResultSource source) {
        AgentWorkItemLeaseCommandDTO leaseCommand = new AgentWorkItemLeaseCommandDTO();
        leaseCommand.setAgentId(actorAgentId);
        leaseCommand.setLeaseToken(command.getLeaseToken());
        leaseCommand.setExpectedVersion(command.getExpectedWorkItemVersion());
        AgentWorkItemLeaseDTO lease = leaseService.validateLeaseForResult(
                tenantId, clientId, ownerJiacn, taskId, command.getWorkItemId(), leaseCommand);
        requireExactLeaseSnapshot(taskId, actorAgentId, command, lease);

        AgentTaskArtifactViewDTO published = prepared == null ? artifactService.publish(
                tenantId, clientId, ownerJiacn, taskId, actorAgentId, command.getArtifact())
                : artifactService.publishPrepared(tenantId, clientId, ownerJiacn, taskId, actorAgentId, prepared.publication());

        AgentTaskWorkItemEntity current = workItemDao.findByTaskAndWorkItemId(
                tenantId, clientId, ownerJiacn, taskId, command.getWorkItemId());
        requireCurrentScope(tenantId, clientId, ownerJiacn, current);
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
                tenantId, clientId, ownerJiacn, taskId, command.getWorkItemId(),
                lease.getAgentId(), lease.getLeaseToken(), lease.getStatus(),
                lease.getLeaseUntil(), lease.getVersion(), submittedAt, update);
        if (updated == 0) {
            throw new AgentTaskCollaborationException(
                    Reason.VERSION_CONFLICT, "Lease state changed during result commit; artifact was rolled back");
        }
        if (updated != 1) {
            throw invalidPersisted("Result CAS affected an unexpected row count");
        }

        appendSubmittedEvent(tenantId, clientId, ownerJiacn, taskId, actorAgentId, current,
                lease.getVersion(), lease.getVersion() + 1, submittedAt, published, source,
                source == null ? null : materialDigest(command));

        AgentWorkItemResultCommitViewDTO result = new AgentWorkItemResultCommitViewDTO();
        result.setTaskId(taskId);
        result.setWorkItemId(command.getWorkItemId());
        result.setStatus("submitted");
        result.setWorkItemVersion(lease.getVersion() + 1);
        result.setSubmittedAt(submittedAt);
        result.setArtifact(published);
        return result;
    }

    private void appendSubmittedEvent(
            String tenantId, String clientId, String ownerJiacn, String taskId, String actorAgentId, AgentTaskWorkItemEntity current, long expectedVersion,
            long resultVersion, long occurredAt, AgentTaskArtifactViewDTO artifact,
            RuntimeResultSource source, String submissionDigest) {
        TaskEventPayload.Builder payload = TaskEventPayload.builder()
                .put(TaskEventPayload.Key.WORK_ITEM_ID, current.getWorkItemId())
                .put(TaskEventPayload.Key.ARTIFACT_ID, artifact.getArtifactId())
                .put(TaskEventPayload.Key.FROM_STATUS, current.getStatus())
                .put(TaskEventPayload.Key.TO_STATUS, "submitted")
                .put(TaskEventPayload.Key.EXPECTED_VERSION, expectedVersion)
                .put(TaskEventPayload.Key.RESULT_VERSION, resultVersion);
        if (source != null) {
            if (artifact.getArtifactVersion() == null || artifact.getArtifactVersion() <= 0
                    || artifact.getContentHash() == null || !artifact.getContentHash().matches("[0-9a-f]{64}"))
                throw invalidPersisted("Result artifact lacks durable recovery proof");
            payload.put(TaskEventPayload.Key.COMMAND_ID, source.commandId())
                    .put(TaskEventPayload.Key.REASSIGNMENT_ID, source.reassignmentId())
                    .put(TaskEventPayload.Key.SOURCE_COMMAND_ID, source.sourceCommandId())
                    .put(TaskEventPayload.Key.LEASE_FENCE_SHA256, source.leaseFenceSha256())
                    .put(TaskEventPayload.Key.ATTEMPT_COUNT, source.attemptCount())
                    .put(TaskEventPayload.Key.ARTIFACT_VERSION, artifact.getArtifactVersion())
                    .put(TaskEventPayload.Key.CONTENT_SHA256, artifact.getContentHash())
                    .put(TaskEventPayload.Key.SUBMISSION_DIGEST, submissionDigest);
        }
        eventWriter.append(AgentTaskMutationEventSupport.command(
                tenantId, clientId, ownerJiacn, taskId, TaskEventType.WORK_ITEM_SUBMITTED, TaskEventType.ActorType.AGENT, actorAgentId,
                TaskEventType.Aggregate.WORK_ITEM, current.getWorkItemId(), payload, occurredAt,
                resultVersion));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentWorkItemResultCommitViewDTO preflightRuntimeResult(String tenantId, String clientId,
            String ownerJiacn, String taskId, String workItemId, String actorAgentId,
            String reassignmentId, String commandId, AgentWorkItemResultCommitDTO command) {
        requireRuntimeCommand(tenantId, clientId, ownerJiacn, taskId, workItemId, actorAgentId, command);
        return requireReassignments().withRuntimeResultSource(tenantId, clientId, ownerJiacn, actorAgentId,
                taskId, workItemId, reassignmentId, commandId, true, source -> {
                    var prior = readIfSubmitted(tenantId, clientId, ownerJiacn, taskId, workItemId, actorAgentId, source, command);
                    if (prior != null) return prior;
                    requireSourceToken(source, command);
                    validateRuntimeLease(tenantId, clientId, ownerJiacn, taskId, actorAgentId, command);
                    return null;
                });
    }

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public PreparedRuntimeResult prepareRuntimeResult(String tenantId, String clientId, String ownerJiacn,
            String taskId, String workItemId, String actorAgentId, String reassignmentId,
            String commandId, AgentWorkItemResultCommitDTO command) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Result preparation requires no enclosing transaction");
        var frozen = MATERIAL_JSON.treeToValue(MATERIAL_JSON.valueToTree(command), AgentWorkItemResultCommitDTO.class);
        requireRuntimeCommand(tenantId, clientId, ownerJiacn, taskId, workItemId, actorAgentId, frozen);
        // Controller already completed the short current-session admission. Artifact preparation
        // owns its own short ACL/version check, then releases all DB locks before storage I/O.
        var publication = artifactService.preparePublication(tenantId, clientId, ownerJiacn, taskId,
                actorAgentId, frozen.getArtifact());
        return new PreparedArtifact(this, tenantId, clientId, ownerJiacn, taskId, workItemId,
                actorAgentId, reassignmentId, commandId, frozen, publication);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentWorkItemResultCommitViewDTO commitPreparedRuntimeResult(String tenantId, String clientId,
            String ownerJiacn, String taskId, String workItemId, String actorAgentId,
            String reassignmentId, String commandId, PreparedRuntimeResult prepared) {
        if (!(prepared instanceof PreparedArtifact value) || value.issuer() != this
                || !tenantId.equals(value.tenantId()) || !clientId.equals(value.clientId())
                || !ownerJiacn.equals(value.ownerJiacn()) || !taskId.equals(value.taskId())
                || !workItemId.equals(value.workItemId()) || !actorAgentId.equals(value.actorAgentId())
                || !reassignmentId.equals(value.reassignmentId()) || !commandId.equals(value.commandId())) throw forbidden();
        requireRuntimeCommand(tenantId, clientId, ownerJiacn, taskId, workItemId, actorAgentId, value.command());
        return requireReassignments().withRuntimeResultSource(tenantId, clientId, ownerJiacn, actorAgentId,
                taskId, workItemId, reassignmentId, commandId, true, source -> {
                    var prior = readIfSubmitted(tenantId, clientId, ownerJiacn, taskId, workItemId, actorAgentId, source, value.command());
                    if (prior != null) return prior; // concurrent original commit; no second publication/event
                    requireSourceToken(source, value.command());
                    return commitResultLocked(tenantId, clientId, ownerJiacn, taskId, actorAgentId,
                            value.command(), value, source);
                });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentWorkItemResultCommitViewDTO readRuntimeResult(String tenantId, String clientId,
            String ownerJiacn, String taskId, String workItemId, String actorAgentId,
            String reassignmentId, String commandId) {
        requireScope(tenantId, clientId, ownerJiacn, taskId);
        return requireReassignments().withRuntimeResultSource(tenantId, clientId, ownerJiacn, actorAgentId,
                taskId, workItemId, reassignmentId, commandId, true, source -> {
                    var prior = readIfSubmitted(tenantId, clientId, ownerJiacn, taskId, workItemId, actorAgentId, source, null);
                    if (prior == null) throw new RuntimeFailure(RuntimeFailureReason.NOT_FOUND);
                    return prior;
                });
    }

    private AgentWorkItemResultCommitViewDTO readIfSubmitted(String tenantId, String clientId,
            String ownerJiacn, String taskId, String workItemId, String actorAgentId,
            RuntimeResultSource source, AgentWorkItemResultCommitDTO replay) {
        var current = workItemDao.findByTaskAndWorkItemId(tenantId, clientId, ownerJiacn, taskId, workItemId);
        requireCurrentScope(tenantId, clientId, ownerJiacn, current);
        if (!taskId.equals(current.getTaskId()) || !workItemId.equals(current.getWorkItemId())
                || !actorAgentId.equals(current.getAssigneeAgentId())) throw forbidden();
        if (current.getResultArtifactId() == null) return null;
        if (!"submitted".equals(current.getStatus()) || current.getVersion() == null
                || current.getSubmittedAt() == null || current.getLeaseToken() != null || current.getLeaseUntil() != null
                || !Objects.equals(current.getAttemptCount(), source.attemptCount())
                || !Objects.equals(current.getMaxAttempts(), source.maxAttempts()) || taskEvents == null) throw recoveryRequired();
        var proofCommand = AgentTaskMutationEventSupport.command(tenantId, clientId, ownerJiacn, taskId,
                TaskEventType.WORK_ITEM_SUBMITTED, TaskEventType.ActorType.AGENT, actorAgentId,
                TaskEventType.Aggregate.WORK_ITEM, workItemId, TaskEventPayload.builder(),
                current.getSubmittedAt(), current.getVersion());
        var event = taskEvents.findByEventId(tenantId, clientId, ownerJiacn, proofCommand.getEventId());
        if (event == null || !tenantId.equals(event.getTenantId()) || !clientId.equals(event.getClientId())
                || !ownerJiacn.equals(event.getOwnerJiacn()) || !taskId.equals(event.getTaskId())
                || !proofCommand.getEventId().equals(event.getEventId())
                || !TaskEventType.WORK_ITEM_SUBMITTED.equals(event.getEventType())
                || !TaskEventType.ActorType.AGENT.equals(event.getActorType()) || !actorAgentId.equals(event.getActorId())
                || !TaskEventType.Aggregate.WORK_ITEM.equals(event.getAggregateType()) || !workItemId.equals(event.getAggregateId())
                || !current.getSubmittedAt().equals(event.getOccurredAt())) throw recoveryRequired();
        JsonNode payload;
        try { payload = MATERIAL_JSON.readTree(TaskEventPayload.normalizeAllowedJson(event.getEventJson())); }
        catch (RuntimeException malformed) { throw recoveryRequired(); }
        requireText(payload, TaskEventPayload.Key.WORK_ITEM_ID, workItemId);
        requireText(payload, TaskEventPayload.Key.ARTIFACT_ID, current.getResultArtifactId());
        requireText(payload, TaskEventPayload.Key.FROM_STATUS, "running");
        requireText(payload, TaskEventPayload.Key.TO_STATUS, "submitted");
        requireText(payload, TaskEventPayload.Key.COMMAND_ID, source.commandId());
        requireText(payload, TaskEventPayload.Key.REASSIGNMENT_ID, source.reassignmentId());
        requireText(payload, TaskEventPayload.Key.SOURCE_COMMAND_ID, source.sourceCommandId());
        requireText(payload, TaskEventPayload.Key.LEASE_FENCE_SHA256, source.leaseFenceSha256());
        requireNumber(payload, TaskEventPayload.Key.RESULT_VERSION, current.getVersion());
        long expectedVersion = integral(payload, TaskEventPayload.Key.EXPECTED_VERSION);
        if (expectedVersion < source.claimedVersion() || expectedVersion == Long.MAX_VALUE
                || expectedVersion + 1 != current.getVersion()) throw recoveryRequired();
        requireNumber(payload, TaskEventPayload.Key.ATTEMPT_COUNT, source.attemptCount());
        long version = integral(payload, TaskEventPayload.Key.ARTIFACT_VERSION);
        if (version <= 0 || version > Integer.MAX_VALUE) throw recoveryRequired();
        String hash = payload.path(TaskEventPayload.Key.CONTENT_SHA256).asText();
        String digest = payload.path(TaskEventPayload.Key.SUBMISSION_DIGEST).asText();
        if (!hash.matches("[0-9a-f]{64}") || !digest.matches("[0-9a-f]{64}")) throw recoveryRequired();
        var artifact = artifactService.getVersion(tenantId, clientId, ownerJiacn, taskId,
                actorAgentId, current.getResultArtifactId(), (int) version);
        if (artifact == null || !current.getResultArtifactId().equals(artifact.getArtifactId())
                || !taskId.equals(artifact.getTaskId()) || !workItemId.equals(artifact.getWorkItemId())
                || !actorAgentId.equals(artifact.getProducerAgentId()) || !Integer.valueOf((int) version).equals(artifact.getArtifactVersion())
                || !hash.equals(artifact.getContentHash())) throw recoveryRequired();
        if (replay != null && (!digest.equals(materialDigest(replay))
                || !replay.getArtifact().getArtifactId().equals(artifact.getArtifactId())
                || !replay.getArtifact().getArtifactVersion().equals(artifact.getArtifactVersion())
                || !Objects.equals(replay.getExpectedWorkItemVersion(), expectedVersion))) throw recoveryRequired();
        var result = new AgentWorkItemResultCommitViewDTO();
        result.setTaskId(taskId); result.setWorkItemId(workItemId); result.setStatus("submitted");
        result.setWorkItemVersion(current.getVersion()); result.setSubmittedAt(current.getSubmittedAt()); result.setArtifact(artifact);
        return result;
    }

    private void requireRuntimeCommand(String tenantId, String clientId, String ownerJiacn, String taskId,
            String workItemId, String actorAgentId, AgentWorkItemResultCommitDTO command) {
        requireScope(tenantId, clientId, ownerJiacn, taskId); requireCommand(actorAgentId, command);
        if (!workItemId.equals(command.getWorkItemId()) || !actorAgentId.equals(command.getProducerAgentId())
                || !actorAgentId.equals(command.getArtifact().getProducerAgentId())) throw forbidden();
        if (!workItemId.equals(command.getArtifact().getWorkItemId())) throw invalid("Artifact work item mismatch");
        if (StringUtil.isBlank(command.getArtifact().getArtifactId()) || command.getArtifact().getArtifactVersion() == null)
            throw invalid("Original artifact identity/version required");
    }

    private void requireSourceToken(RuntimeResultSource source, AgentWorkItemResultCommitDTO command) {
        if (!source.leaseFenceSha256().equals(TaskEventPayload.ContentDigest.fromUtf8(command.getLeaseToken()).sha256()))
            throw new AgentTaskCollaborationException(Reason.VERSION_CONFLICT, "Original lease changed");
    }

    private void validateRuntimeLease(String tenantId, String clientId, String ownerJiacn, String taskId,
            String actorAgentId, AgentWorkItemResultCommitDTO command) {
        var leaseCommand = new AgentWorkItemLeaseCommandDTO();
        leaseCommand.setAgentId(actorAgentId); leaseCommand.setLeaseToken(command.getLeaseToken());
        leaseCommand.setExpectedVersion(command.getExpectedWorkItemVersion());
        requireExactLeaseSnapshot(taskId, actorAgentId, command, leaseService.validateLeaseForResult(
                tenantId, clientId, ownerJiacn, taskId, command.getWorkItemId(), leaseCommand));
    }

    private AgentWorkItemReassignmentService requireReassignments() {
        if (reassignments == null) throw invalidPersisted("Runtime result dependency unavailable");
        return reassignments;
    }
    private static RuntimeFailure recoveryRequired() { return new RuntimeFailure(RuntimeFailureReason.RECOVERY_REQUIRED); }
    private static void requireText(JsonNode payload, String key, String value) {
        var node = payload.path(key);
        if (!node.isTextual() || !value.equals(node.asText())) throw recoveryRequired();
    }
    private static long integral(JsonNode payload, String key) {
        var node = payload.path(key);
        if (!node.isIntegralNumber() || !node.canConvertToLong()) throw recoveryRequired();
        return node.longValue();
    }
    private static void requireNumber(JsonNode payload, String key, long value) {
        if (integral(payload, key) != value) throw recoveryRequired();
    }
    private static String materialDigest(AgentWorkItemResultCommitDTO command) {
        return TaskEventPayload.ContentDigest.fromUtf8(MATERIAL_JSON.writeValueAsString(sorted(MATERIAL_JSON.valueToTree(command)))).sha256();
    }
    private static Object sorted(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> result = new TreeMap<>();
            node.properties().forEach(entry -> result.put(entry.getKey(), sorted(entry.getValue())));
            return result;
        }
        if (node.isArray()) { java.util.List<Object> result = new java.util.ArrayList<>(); node.forEach(item -> result.add(sorted(item))); return result; }
        return MATERIAL_JSON.treeToValue(node, Object.class);
    }
    private record PreparedArtifact(AgentWorkItemResultCommitServiceImpl issuer, String tenantId,
            String clientId, String ownerJiacn, String taskId, String workItemId, String actorAgentId,
            String reassignmentId, String commandId, AgentWorkItemResultCommitDTO command,
            AgentTaskArtifactService.PreparedPublication publication) implements PreparedRuntimeResult {
        @Override public String toString() { return "PreparedRuntimeResult[REDACTED]"; }
    }

    private void requireScope(
            String tenantId, String clientId, String ownerJiacn, String taskId) {
        if (!"0".equals(tenantId) || StringUtil.isBlank(clientId)
                || StringUtil.isBlank(ownerJiacn) || "0".equals(ownerJiacn)
                || StringUtil.isBlank(taskId)) {
            throw invalid("strict tenant-zero client, owner and task scope is required");
        }
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

    private void requireCurrentScope(
            String tenantId, String clientId, String ownerJiacn, AgentTaskWorkItemEntity current) {
        if (current == null || !tenantId.equals(current.getTenantId())
                || !clientId.equals(current.getClientId())
                || !ownerJiacn.equals(current.getOwnerJiacn())) {
            throw invalidPersisted("Persisted work item is outside the locked owner scope");
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

    private static AgentTaskMutationTransaction directTransaction() {
        return new AgentTaskMutationTransaction() {
            @Override
            public <T> T executeWithLockedTaskRoot(String tenantId, String clientId, String taskId,
                    LockedTaskMutation<T> mutation) {
                return mutation.apply(null);
            }
            @Override
            public <T> T executeWithLockedTaskRootInOwnerScope(
                    String tenantId, String clientId, String ownerJiacn, String taskId,
                    LockedTaskMutation<T> mutation) {
                return mutation.apply(null);
            }
            @Override
            public <T> T executeWithLockedTaskRootForWorkItem(String tenantId, String clientId,
                    String workItemId, LockedTaskMutation<T> mutation) {
                return mutation.apply(null);
            }
            @Override
            public <T> T executeWithLockedTaskRootForWorkItemInOwnerScope(
                    String tenantId, String clientId, String ownerJiacn, String workItemId,
                    LockedTaskMutation<T> mutation) {
                return mutation.apply(null);
            }
            @Override
            public <T> T executeAfterTaskRootReservation(String tenantId, String clientId,
                    String taskId, TaskRootReservation reservation, ReservedTaskMutation<T> mutation) {
                throw new UnsupportedOperationException();
            }
            @Override
            public <T> T executeAfterTaskRootReservationInOwnerScope(
                    String tenantId, String clientId, String ownerJiacn, String taskId,
                    TaskRootReservation reservation, ReservedTaskMutation<T> mutation) {
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
