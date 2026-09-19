package cn.jia.agent.service.impl;

import cn.jia.agent.config.PersonalWorkspaceExecutionProperties;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.dao.PersonalWorkspaceDao;
import cn.jia.agent.dao.PersonalWorkspaceExecutionDao;
import cn.jia.agent.dao.PersonalWorkspaceTaskLinkDao;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.entity.AgentTaskArtifactPublishDTO;
import cn.jia.agent.entity.AgentTaskArtifactViewDTO;
import cn.jia.agent.entity.AgentTaskFormalDeliveryItemDTO;
import cn.jia.agent.entity.AgentTaskFormalDeliverySubmitDTO;
import cn.jia.agent.entity.AgentTaskFormalDeliveryViewDTO;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.entity.AgentWorkItemLeaseCommandDTO;
import cn.jia.agent.entity.AgentWorkItemLeaseDTO;
import cn.jia.agent.entity.PersonalWorkspaceExecutionEntity;
import cn.jia.agent.entity.PersonalWorkspaceExecutionInputEntity;
import cn.jia.agent.entity.PersonalWorkspaceExecutionOutputEntity;
import cn.jia.agent.entity.PersonalWorkspaceFileEntity;
import cn.jia.agent.entity.PersonalWorkspaceVersionEntity;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.service.AgentWorkItemLeaseService;
import cn.jia.agent.service.AgentTaskArtifactService;
import cn.jia.agent.service.AgentTaskFormalDeliveryService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.exception.AgentTaskStateException;
import cn.jia.chat.service.WorkspaceConversationAccessService;
import cn.jia.agent.service.PersonalWorkspaceStorage;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Owner-scoped runtime bridge. PRIVATE executions have no task-root side effect; TASK executions
 * hold a real existing work-item lease but remain distinct from artifacts and formal delivery.
 * A runtime-authenticated client polls its durable queue; browser responses never contain a lease.
 */
@Named
public class PersonalWorkspaceExecutionServiceImpl implements PersonalWorkspaceExecutionService {
    private static final String INTERNAL_PREFIX = "/internal/agent/tasks/";
    private static final String RUNTIME_FAILURE_CODE = "AGENT_DELIVERY_FAILED";
    private static final String RUNTIME_FAILURE_MESSAGE = "Agent 未能完成本次交付，请调整需求后重新创建执行。";
    /** Matches the runtime bridge manifest limit; every input remains independently owner-scoped and version-pinned. */
    private static final int MAX_EXECUTION_INPUTS = 128;
    private static final long TASK_LEASE_DURATION_MILLIS = 900_000L;
    /** Every runtime output type is independently configuration-gated; upload availability does not imply execution. */
    private static final Set<String> SUPPORTED_EXECUTION_MIME_TYPES = Set.of(
            "image/png", "image/jpeg", "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation");
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/png", ".png", "image/jpeg", ".jpg", "text/plain", ".txt", "application/pdf", ".pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation", ".pptx");
    private final PersonalWorkspaceExecutionDao executions;
    private final PersonalWorkspaceDao workspace;
    private final PersonalWorkspaceTaskLinkDao taskLinks;
    private final AgentRuntimeDao runtimes;
    private final PersonalWorkspaceStorage storage;
    private final PersonalWorkspaceWriteService writes;
    private final Set<String> executionMimeTypes;
    private final boolean executionAvailable;
    /** Set by the task collaboration module; private execution remains available when this lane is absent. */
    private WorkspaceConversationAccessService conversationAccess;
    private AgentTaskWorkItemDao workItems;
    private AgentWorkItemLeaseService leases;
    /** TASK publication is opt-in only when all authoritative artifact/formal-delivery boundaries exist. */
    private AgentTaskArtifactService taskArtifacts;
    private AgentTaskFormalDeliveryService formalDeliveries;
    private AgentTaskMutationTransaction taskMutations;

    @Inject
    public PersonalWorkspaceExecutionServiceImpl(PersonalWorkspaceExecutionDao executions,
            PersonalWorkspaceDao workspace, PersonalWorkspaceTaskLinkDao taskLinks,
            AgentRuntimeDao runtimes, PersonalWorkspaceStorage storage,
            PersonalWorkspaceWriteService writes, PersonalWorkspaceExecutionProperties properties) {
        this.executions = Objects.requireNonNull(executions, "executions");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.taskLinks = Objects.requireNonNull(taskLinks, "taskLinks");
        this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.writes = Objects.requireNonNull(writes, "writes");
        this.executionMimeTypes = allowedMimeTypes(properties);
        long maxOutputBytes = storage.maxContentBytes();
        this.executionAvailable = maxOutputBytes >= 1 && maxOutputBytes <= 9_007_199_254_740_991L;
    }

    @Autowired(required = false)
    public void setTaskExecutionDependencies(WorkspaceConversationAccessService conversationAccess,
            AgentTaskWorkItemDao workItems, AgentWorkItemLeaseService leases) {
        this.conversationAccess = Objects.requireNonNull(conversationAccess, "conversationAccess");
        this.workItems = Objects.requireNonNull(workItems, "workItems");
        this.leases = Objects.requireNonNull(leases, "leases");
    }

    @Autowired(required = false)
    public void setTaskPublicationDependencies(AgentTaskArtifactService taskArtifacts,
            AgentTaskFormalDeliveryService formalDeliveries,
            AgentTaskMutationTransaction taskMutations) {
        this.taskArtifacts = Objects.requireNonNull(taskArtifacts, "taskArtifacts");
        this.formalDeliveries = Objects.requireNonNull(formalDeliveries, "formalDeliveries");
        this.taskMutations = Objects.requireNonNull(taskMutations, "taskMutations");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExecutionView create(OwnerScope scope, CreateCommand command, String idempotencyKey) {
        validateOwnerScope(scope); validateIdempotency(idempotencyKey); ValidCreate valid = validCreate(command);
        String requestHash = hash("CREATE", valid.conversationId(), valid.targetAgentId(), valid.taskId(),
                valid.instruction(), valid.outputContentMimeType(), selectionsWire(valid.inputs()));
        PersonalWorkspaceExecutionEntity prior = executions.findByIdempotency(scope.tenantId(),
                scope.clientId(), scope.ownerJiacn(), idempotencyKey);
        if (prior != null) {
            if (!same(prior.getRequestHash(), requestHash)) throw failure(Reason.IDEMPOTENCY_CONFLICT);
            return view(scope, prior);
        }
        requireOwnedTarget(scope, valid.targetAgentId());
        TaskLease taskLease;
        try {
            taskLease = valid.taskId() == null ? null : beginTaskExecution(scope, valid);
        } catch (Failure taskFailure) {
            // The other request may have held the task-root lock, committed the exact same key,
            // then left this contender observing a running work item. Re-read the unique key
            // before reporting a conflict so a transport retry never creates a second task run.
            PersonalWorkspaceExecutionEntity replay = executions.findByIdempotency(scope.tenantId(),
                    scope.clientId(), scope.ownerJiacn(), idempotencyKey);
            if (replay != null && same(replay.getRequestHash(), requestHash)) return view(scope, replay);
            throw taskFailure;
        }

        List<InputSnapshot> snapshots = new ArrayList<>();
        List<InputSelection> ordered = new ArrayList<>(valid.inputs());
        ordered.sort(Comparator.comparing(InputSelection::fileId).thenComparingInt(InputSelection::version));
        for (InputSelection selected : ordered) {
            PersonalWorkspaceFileEntity file = workspace.lockFile(scope.tenantId(), scope.clientId(),
                    scope.ownerJiacn(), selected.fileId());
            if (file == null || !"ACTIVE".equals(file.getState())) throw failure(Reason.NOT_FOUND);
            PersonalWorkspaceVersionEntity version = workspace.findVersion(scope.tenantId(), scope.clientId(),
                    scope.ownerJiacn(), selected.fileId(), selected.version());
            if (version == null) throw failure(Reason.NOT_FOUND);
            // A source material and the requested deliverable may use different formats: for example,
            // an XLSX/PDF reference can be used to create a PPTX. Both ends must still be explicitly enabled.
            if (!executionMimeTypes.contains(version.getContentMimeType())) throw failure(Reason.CAPABILITY_UNAVAILABLE);
            snapshots.add(new InputSnapshot(file, version));
        }
        String executionId = identifier("pwe_");
        // A private run uses its own bridge namespace. A task run retains the authoritative business
        // task ID but is only linked to (never substituted for) its leased work item.
        String taskId = valid.taskId() == null ? identifier("pwe_task_") : valid.taskId();
        String runId = identifier("pwe_run_");
        long now = System.currentTimeMillis();
        PersonalWorkspaceExecutionEntity execution = new PersonalWorkspaceExecutionEntity()
                .setExecutionId(executionId).setOwnerJiacn(scope.ownerJiacn()).setTaskId(taskId).setRunId(runId)
                .setExecutionMode(taskLease == null ? "PRIVATE" : "TASK")
                .setWorkItemId(taskLease == null ? null : taskLease.workItemId())
                .setLeaseToken(taskLease == null ? null : taskLease.leaseToken())
                .setLeaseWorkItemVersion(taskLease == null ? null : taskLease.version())
                .setLeaseExpiresAt(taskLease == null ? null : taskLease.leaseUntil())
                .setConversationId(valid.conversationId()).setTargetAgentId(valid.targetAgentId())
                .setInstruction(valid.instruction()).setOutputContentMimeType(valid.outputContentMimeType())
                .setExecutionState("QUEUED").setFailureCode(null).setFailureMessage(null).setGrantRevision(1L)
                .setIdempotencyKey(idempotencyKey).setRequestHash(requestHash)
                .setRevokeIdempotencyKey(null).setRevokeRequestHash(null)
                .setCreatedAt(now).setRevokedAt(null).setFailedAt(null);
        scoped(execution, scope);
        try { executions.insert(execution); }
        catch (DuplicateKeyException raced) {
            PersonalWorkspaceExecutionEntity replay = executions.findByIdempotency(scope.tenantId(),
                    scope.clientId(), scope.ownerJiacn(), idempotencyKey);
            if (replay == null || !same(replay.getRequestHash(), requestHash)) throw failure(Reason.IDEMPOTENCY_CONFLICT);
            return view(scope, replay);
        }
        int index = 0;
        for (InputSnapshot snapshot : snapshots) {
            PersonalWorkspaceVersionEntity version = snapshot.version();
            PersonalWorkspaceExecutionInputEntity input = new PersonalWorkspaceExecutionInputEntity()
                    .setInputRef("input_" + (++index)).setExecutionId(executionId)
                    .setOwnerJiacn(scope.ownerJiacn()).setFileId(version.getFileId())
                    .setFileVersion(version.getVersion()).setOriginalFilename(version.getOriginalFilename())
                    .setContentMimeType(version.getContentMimeType()).setByteLength(version.getByteLength())
                    .setContentHash(version.getContentHash()).setStorageUri(version.getStorageUri())
                    .setGrantState("ACTIVE").setCreatedAt(now).setRevokedAt(null);
            scoped(input, scope); executions.insertInput(input);
            PersonalWorkspaceFileEntity file = snapshot.file();
            file.setMetadataRevision(Math.addExact(file.getMetadataRevision(), 1L)); workspace.updateFile(file);
        }
        return view(scope, execution);
    }

    @Override
    @Transactional(readOnly = true)
    public ExecutionCapabilities capabilities() {
        return executionAvailable
                ? new ExecutionCapabilities(executionMimeTypes.stream().sorted().toList(), true)
                : new ExecutionCapabilities(List.of(), false);
    }

    @Override
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public ExecutionView get(OwnerScope scope, String executionId) {
        validateOwnerScope(scope); id(executionId, "executionId", 100);
        PersonalWorkspaceExecutionEntity execution = executions.find(scope.tenantId(), scope.clientId(),
                scope.ownerJiacn(), executionId);
        if (execution == null) throw failure(Reason.NOT_FOUND); return view(scope, execution);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExecutionView revokeInputs(OwnerScope scope, String executionId, long expectedGrantRevision,
            String idempotencyKey) {
        validateOwnerScope(scope); id(executionId, "executionId", 100); validateIdempotency(idempotencyKey);
        if (expectedGrantRevision < 1) throw failure(Reason.BAD_REQUEST);
        String requestHash = hash("REVOKE", executionId, Long.toString(expectedGrantRevision));
        PersonalWorkspaceExecutionEntity keyed = executions.findByRevokeIdempotency(scope.tenantId(),
                scope.clientId(), scope.ownerJiacn(), idempotencyKey);
        if (keyed != null && (!same(keyed.getExecutionId(), executionId)
                || !same(keyed.getRevokeRequestHash(), requestHash))) {
            throw failure(Reason.IDEMPOTENCY_CONFLICT);
        }
        // TASK leases are released while holding the task root before this execution/file lock.
        // A private execution retains the legacy execution-first revoke path.
        PersonalWorkspaceExecutionEntity candidate = executions.find(scope.tenantId(), scope.clientId(),
                scope.ownerJiacn(), executionId);
        if (candidate == null) throw failure(Reason.NOT_FOUND);
        if ("TASK".equals(candidate.getExecutionMode()) && "QUEUED".equals(candidate.getExecutionState())) {
            releaseTaskLease(scope, candidate);
        }
        PersonalWorkspaceExecutionEntity execution = executions.lock(scope.tenantId(), scope.clientId(),
                scope.ownerJiacn(), executionId);
        if (execution == null) throw failure(Reason.NOT_FOUND);
        if (execution.getRevokeIdempotencyKey() != null
                && (!same(execution.getRevokeIdempotencyKey(), idempotencyKey)
                || !same(execution.getRevokeRequestHash(), requestHash))) {
            throw failure(Reason.IDEMPOTENCY_CONFLICT);
        }
        if ("INPUTS_REVOKED".equals(execution.getExecutionState())) {
            if (execution.getGrantRevision() != expectedGrantRevision + 1L) throw failure(Reason.GRANT_CHANGED);
            return view(scope, execution);
        }
        if (!"QUEUED".equals(execution.getExecutionState()) || execution.getGrantRevision() != expectedGrantRevision) {
            throw failure(Reason.GRANT_CHANGED);
        }
        List<PersonalWorkspaceExecutionInputEntity> inputs = executions.listInputs(scope.tenantId(),
                scope.clientId(), scope.ownerJiacn(), executionId);
        Map<String, PersonalWorkspaceFileEntity> lockedFiles = new LinkedHashMap<>();
        for (PersonalWorkspaceExecutionInputEntity input : inputs.stream().sorted(Comparator.comparing(PersonalWorkspaceExecutionInputEntity::getFileId)).toList()) {
            if (!"ACTIVE".equals(input.getGrantState())) throw failure(Reason.GRANT_CHANGED);
            PersonalWorkspaceFileEntity file = lockedFiles.computeIfAbsent(input.getFileId(), key -> workspace.lockFile(
                    scope.tenantId(), scope.clientId(), scope.ownerJiacn(), key));
            if (file == null) throw failure(Reason.NOT_FOUND);
        }
        long now = System.currentTimeMillis();
        for (PersonalWorkspaceExecutionInputEntity input : inputs) {
            input.setGrantState("REVOKED").setRevokedAt(now); executions.updateInput(input);
        }
        for (PersonalWorkspaceFileEntity file : lockedFiles.values()) {
            file.setMetadataRevision(Math.addExact(file.getMetadataRevision(), 1L)); workspace.updateFile(file);
        }
        execution.setExecutionState("INPUTS_REVOKED").setGrantRevision(Math.addExact(execution.getGrantRevision(), 1L))
                .setRevokeIdempotencyKey(idempotencyKey).setRevokeRequestHash(requestHash).setRevokedAt(now);
        executions.update(execution);
        return view(scope, execution);
    }

    @Override
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<RuntimeInput> runtimeInputs(RuntimeScope scope, String taskId, String runId) {
        PersonalWorkspaceExecutionEntity execution = runtimeExecution(scope, taskId, runId, false);
        return runtimeInputs(new OwnerScope(scope.tenantId(), scope.clientId(), scope.ownerJiacn()), execution.getExecutionId());
    }

    @Override
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<RuntimeQueuedCommand> runtimeQueuedCommands(RuntimeScope scope, int limit) {
        validateRuntimeScope(scope);
        if (limit < 1 || limit > 16) throw failure(Reason.BAD_REQUEST);
        OwnerScope owner = new OwnerScope(scope.tenantId(), scope.clientId(), scope.ownerJiacn());
        return executions.listQueuedByTarget(scope.tenantId(), scope.clientId(), scope.ownerJiacn(),
                        scope.agentId(), limit).stream()
                // DAO filtering is the primary isolation boundary; retain an in-service exact check
                // so a mapper regression can never hand a queue item to a different runtime Agent.
                .filter(execution -> execution != null && "QUEUED".equals(execution.getExecutionState())
                        && same(execution.getTargetAgentId(), scope.agentId())
                        && same(execution.getTenantId(), scope.tenantId())
                        && same(execution.getClientId(), scope.clientId())
                        && same(execution.getOwnerJiacn(), scope.ownerJiacn())
                        && runtimeDispatchAllowed(scope, execution))
                .map(execution -> queuedCommand(owner, execution))
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RuntimeContent runtimeInputContent(RuntimeScope scope, String taskId, String runId, String inputRef) {
        PersonalWorkspaceExecutionEntity execution = runtimeExecution(scope, taskId, runId, true);
        id(inputRef, "inputRef", 100);
        PersonalWorkspaceExecutionInputEntity input = executions.lockInput(scope.tenantId(), scope.clientId(),
                scope.ownerJiacn(), execution.getExecutionId(), inputRef);
        if (input == null || !"ACTIVE".equals(input.getGrantState())) throw failure(Reason.NOT_FOUND);
        PersonalWorkspaceStorage.StoredContent content = storage.read(storageScope(scope), input.getStorageUri(),
                input.getContentHash(), input.getByteLength(), input.getContentMimeType());
        return new RuntimeContent(input.getOriginalFilename(), input.getContentMimeType(), content.content());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StagedOutput stageOutput(RuntimeScope scope, String taskId, String runId, String outputId,
            String originalFilename, String contentMimeType, byte[] content) {
        PersonalWorkspaceExecutionEntity execution = runtimeExecution(scope, taskId, runId, true);
        id(outputId, "outputId", 100); filename(originalFilename, contentMimeType); validMime(contentMimeType);
        if (!"output_1".equals(outputId) || content == null || content.length == 0
                || content.length > storage.maxContentBytes()
                || !same(contentMimeType, execution.getOutputContentMimeType())
                || !PersonalWorkspaceOutputFormatValidator.isValid(contentMimeType, content)) {
            throw failure(Reason.BAD_REQUEST);
        }
        PersonalWorkspaceExecutionOutputEntity previous = executions.lockOutput(scope.tenantId(), scope.clientId(),
                scope.ownerJiacn(), execution.getExecutionId(), outputId);
        PersonalWorkspaceStorage.StoredObject stored = storage.store(storageScope(scope), content, contentMimeType);
        if (previous != null) {
            if (!same(previous.getContentHash(), stored.sha256()) || previous.getByteLength() != stored.byteLength()
                    || !"STAGED".equals(previous.getOutputState())) throw failure(Reason.OUTPUT_CONFLICT);
            return new StagedOutput(outputId, previous.getContentHash(), previous.getByteLength(), previous.getOutputState());
        }
        long now = System.currentTimeMillis();
        PersonalWorkspaceExecutionOutputEntity output = new PersonalWorkspaceExecutionOutputEntity()
                .setOutputId(outputId).setExecutionId(execution.getExecutionId()).setOwnerJiacn(scope.ownerJiacn())
                .setOriginalFilename(originalFilename).setContentMimeType(contentMimeType).setByteLength(stored.byteLength())
                .setContentHash(stored.sha256()).setStorageUri(stored.storageUri()).setOutputState("STAGED")
                .setWorkspaceFileId(null).setWorkspaceFileVersion(null).setArtifactId(null).setArtifactVersion(null)
                .setFormalDeliveryId(null).setPublicationState("PENDING").setPublicationRevision(0L)
                .setPublicationFailureCode(null).setStagedAt(now).setCommittedAt(null);
        scoped(output, scope); executions.insertOutput(output);
        return new StagedOutput(outputId, stored.sha256(), stored.byteLength(), "STAGED");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommitView commitOutputs(RuntimeScope scope, String taskId, String runId, String manifestId,
            List<OutputDeclaration> declarations) {
        validateRuntimeScope(scope); id(taskId, "taskId", 100); id(runId, "runId", 100);
        id(manifestId, "manifestId", 100); validateManifest(declarations);
        // Read only enough to choose the lock hierarchy. TASK publication always starts from the
        // business task root; it never locks an execution/output before that root.
        PersonalWorkspaceExecutionEntity candidate = executions.findByTaskRun(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), taskId, runId);
        if (candidate == null || !same(candidate.getTargetAgentId(), scope.agentId())) {
            throw failure(Reason.NOT_FOUND);
        }
        if ("TASK".equals(candidate.getExecutionMode())) {
            requireTaskPublicationDependencies();
            return taskMutations.executeWithLockedTaskRootInOwnerScope(
                    scope.tenantId(), scope.clientId(), scope.ownerJiacn(), taskId,
                    root -> commitTaskOutputsLocked(scope, taskId, runId, manifestId, declarations, root));
        }
        PersonalWorkspaceExecutionEntity execution = runtimeExecution(scope, taskId, runId, true, true);
        return commitPrivateOutputs(scope, execution, manifestId, declarations);
    }

    /** Private output archiving remains source-compatible and never reaches task artifact state. */
    private CommitView commitPrivateOutputs(RuntimeScope scope, PersonalWorkspaceExecutionEntity execution,
            String manifestId, List<OutputDeclaration> declarations) {
        List<PersonalWorkspaceExecutionOutputEntity> outputs = lockAndVerifyManifest(
                scope, execution, manifestId, declarations);
        PersonalWorkspaceExecutionOutputEntity output = outputs.getFirst();
        if ("COMMITTED".equals(output.getOutputState())) return committed(manifestId, outputs);
        if (!"STAGED".equals(output.getOutputState())) throw failure(Reason.OUTPUT_CONFLICT);
        long now = System.currentTimeMillis();
        String fileId = identifier("pws_");
        PersonalWorkspaceFileEntity file = new PersonalWorkspaceFileEntity().setFileId(fileId)
                .setOwnerJiacn(scope.ownerJiacn()).setSourceKind("UPLOAD").setOriginKind("AGENT_DELIVERY")
                .setDisplayName(output.getOriginalFilename())
                .setMediaFamily(family(output.getContentMimeType())).setState("ACTIVE").setMetadataRevision(1L)
                .setLatestVersion(1).setCreatedAt(now);
        scoped(file, scope);
        PersonalWorkspaceVersionEntity version = outputVersion(scope, output, fileId, now);
        writes.archiveRuntimeOutput(writeScope(scope), file, version);
        output.setOutputState("COMMITTED").setWorkspaceFileId(fileId).setWorkspaceFileVersion(1)
                .setCommittedAt(now);
        executions.updateOutput(output);
        execution.setExecutionState("OUTPUT_COMMITTED").setFailureCode(null)
                .setFailureMessage(null).setFailedAt(null);
        executions.update(execution);
        return committed(manifestId, outputs);
    }

    /**
     * Trusted TASK publication path. The order is task root -> execution -> output -> workspace
     * file/version. It reads staged bytes only on the server, creates exact task artifacts, then
     * submits the existing formal-delivery protocol. A retry only resumes this mapping and never
     * invokes a Provider or exposes a lease/storage URI to the browser.
     */
    private CommitView commitTaskOutputsLocked(RuntimeScope scope, String taskId, String runId,
            String manifestId, List<OutputDeclaration> declarations, AgentTaskMetaEntity root) {
        if (root == null || !same(taskId, root.getTaskId()) || root.getTaskVersion() == null
                || root.getTaskVersion() < 0) {
            throw failure(Reason.TASK_CONFLICT);
        }
        PersonalWorkspaceExecutionEntity execution = executions.lockByTaskRun(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), taskId, runId);
        if (execution == null || !"TASK".equals(execution.getExecutionMode())
                || !same(scope.agentId(), execution.getTargetAgentId())) {
            throw failure(Reason.NOT_FOUND);
        }
        List<PersonalWorkspaceExecutionOutputEntity> outputs = lockAndVerifyManifest(
                scope, execution, manifestId, declarations);
        PersonalWorkspaceExecutionOutputEntity output = outputs.getFirst();
        if ("COMMITTED".equals(output.getOutputState())
                && "PUBLISHED".equals(output.getPublicationState())) {
            return committed(manifestId, outputs);
        }
        if (!("QUEUED".equals(execution.getExecutionState())
                || "OUTPUT_STAGED".equals(execution.getExecutionState()))
                || !"STAGED".equals(output.getOutputState())
                || !"PENDING".equals(output.getPublicationState())) {
            throw failure(Reason.OUTPUT_CONFLICT);
        }
        requireLiveTaskLease(scope, execution);
        // Persisted immediately before mapping so an interrupted response is explicitly recoverable
        // by the same runtime manifest, without restarting the agent/provider work.
        execution.setExecutionState("OUTPUT_STAGED").setFailureCode(null)
                .setFailureMessage(null).setFailedAt(null);
        executions.update(execution);

        long now = System.currentTimeMillis();
        String fileId = output.getWorkspaceFileId();
        if (fileId == null || output.getWorkspaceFileVersion() == null) {
            fileId = identifier("pws_");
            PersonalWorkspaceFileEntity file = new PersonalWorkspaceFileEntity().setFileId(fileId)
                    .setOwnerJiacn(scope.ownerJiacn()).setSourceKind("UPLOAD").setOriginKind("AGENT_DELIVERY")
                    .setDisplayName(output.getOriginalFilename()).setMediaFamily(family(output.getContentMimeType()))
                    .setState("ACTIVE").setMetadataRevision(1L).setLatestVersion(1).setCreatedAt(now);
            scoped(file, scope);
            writes.archiveRuntimeOutput(writeScope(scope), file, outputVersion(scope, output, fileId, now));
            output.setWorkspaceFileId(fileId).setWorkspaceFileVersion(1);
        } else if (output.getWorkspaceFileVersion() != 1) {
            throw failure(Reason.OUTPUT_CONFLICT);
        }

        PersonalWorkspaceStorage.StoredContent staged = storage.read(storageScope(scope), output.getStorageUri(),
                output.getContentHash(), output.getByteLength(), output.getContentMimeType());
        byte[] content = staged.content();
        if (content == null || content.length != output.getByteLength()) throw failure(Reason.STORAGE_UNAVAILABLE);
        String artifactId = "pwe_art_" + execution.getExecutionId();
        AgentTaskArtifactViewDTO deliverable = publishTaskArtifact(scope, execution, artifactId,
                "document", output.getOriginalFilename(), output, content);
        String manifestArtifactId = "pwe_manifest_" + execution.getExecutionId();
        byte[] manifestContent = taskManifest(execution, output).getBytes(StandardCharsets.UTF_8);
        AgentTaskArtifactViewDTO manifest = publishTaskArtifact(scope, execution, manifestArtifactId,
                "summary", "Delivery manifest", "application/json", manifestContent);
        AgentTaskFormalDeliveryViewDTO formal = submitFormalDelivery(
                scope, execution, root, deliverable, manifest, output);
        if (formal == null || !same(taskId, formal.getTaskId())
                || !same(execution.getWorkItemId(), formal.getWorkItemId())
                || !same(formal.getDeliveryId(), deliveryId(execution))
                || !"submitted".equals(formal.getState())) {
            throw failure(Reason.TASK_CONFLICT);
        }
        output.setOutputState("COMMITTED").setArtifactId(deliverable.getArtifactId())
                .setArtifactVersion(deliverable.getArtifactVersion()).setFormalDeliveryId(formal.getDeliveryId())
                .setPublicationState("PUBLISHED")
                .setPublicationRevision(Math.addExact(output.getPublicationRevision(), 1L))
                .setPublicationFailureCode(null).setCommittedAt(now);
        executions.updateOutput(output);
        execution.setExecutionState("OUTPUT_COMMITTED").setFailureCode(null)
                .setFailureMessage(null).setFailedAt(null);
        executions.update(execution);
        return committed(manifestId, outputs);
    }

    private List<PersonalWorkspaceExecutionOutputEntity> lockAndVerifyManifest(RuntimeScope scope,
            PersonalWorkspaceExecutionEntity execution, String manifestId, List<OutputDeclaration> declarations) {
        List<PersonalWorkspaceExecutionOutputEntity> outputs = executions.lockOutputs(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), execution.getExecutionId());
        if (outputs.size() != 1 || !"output_1".equals(outputs.getFirst().getOutputId())) {
            throw failure(Reason.OUTPUT_MISSING);
        }
        PersonalWorkspaceExecutionOutputEntity output = outputs.getFirst();
        OutputDeclaration declaration = declarations.getFirst();
        if (!same(output.getContentHash(), declaration.sha256()) || output.getByteLength() != declaration.byteLength()) {
            throw failure(Reason.OUTPUT_CONFLICT);
        }
        if (!same(manifestId(execution.getTaskId(), execution.getRunId(), outputs), manifestId)) {
            throw failure(Reason.OUTPUT_CONFLICT);
        }
        return outputs;
    }

    private static void validateManifest(List<OutputDeclaration> declarations) {
        if (declarations == null || declarations.size() != 1 || declarations.getFirst() == null
                || !"output_1".equals(declarations.getFirst().outputId())
                || !sha(declarations.getFirst().sha256()) || declarations.getFirst().byteLength() < 0) {
            throw failure(Reason.BAD_REQUEST);
        }
    }

    private PersonalWorkspaceVersionEntity outputVersion(RuntimeScope scope,
            PersonalWorkspaceExecutionOutputEntity output, String fileId, long now) {
        PersonalWorkspaceVersionEntity version = new PersonalWorkspaceVersionEntity().setFileId(fileId)
                .setOwnerJiacn(scope.ownerJiacn()).setVersion(1).setOriginalFilename(output.getOriginalFilename())
                .setContentMimeType(output.getContentMimeType()).setByteLength(output.getByteLength())
                .setContentHash(output.getContentHash()).setStorageUri(output.getStorageUri()).setCreatedAt(now);
        scoped(version, scope);
        return version;
    }

    private AgentTaskArtifactViewDTO publishTaskArtifact(RuntimeScope scope,
            PersonalWorkspaceExecutionEntity execution, String artifactId, String artifactType,
            String title, PersonalWorkspaceExecutionOutputEntity output, byte[] content) {
        return publishTaskArtifact(scope, execution, artifactId, artifactType, title,
                output.getContentMimeType(), content);
    }

    private AgentTaskArtifactViewDTO publishTaskArtifact(RuntimeScope scope,
            PersonalWorkspaceExecutionEntity execution, String artifactId, String artifactType,
            String title, String mimeType, byte[] content) {
        AgentTaskArtifactPublishDTO command = new AgentTaskArtifactPublishDTO();
        command.setArtifactId(artifactId);
        command.setWorkItemId(execution.getWorkItemId());
        command.setProducerAgentId(execution.getTargetAgentId());
        command.setArtifactType(artifactType);
        command.setTitle(title);
        command.setContentBytes(content);
        command.setContentMimeType(mimeType);
        command.setContentHash(plainSha(content));
        command.setContentByteLength((long) content.length);
        command.setArtifactVersion(1);
        command.setExpectedPreviousVersion(0);
        command.setVisibility("task_members");
        AgentTaskArtifactViewDTO result = taskArtifacts.publish(scope.tenantId(), scope.clientId(),
                scope.ownerJiacn(), execution.getTaskId(), execution.getTargetAgentId(), command);
        if (result == null || !same(artifactId, result.getArtifactId())
                || result.getArtifactVersion() == null || result.getArtifactVersion() != 1
                || !same(command.getContentHash(), result.getContentHash())) {
            throw failure(Reason.TASK_CONFLICT);
        }
        return result;
    }

    private AgentTaskFormalDeliveryViewDTO submitFormalDelivery(RuntimeScope scope,
            PersonalWorkspaceExecutionEntity execution, AgentTaskMetaEntity root,
            AgentTaskArtifactViewDTO deliverable, AgentTaskArtifactViewDTO manifest,
            PersonalWorkspaceExecutionOutputEntity output) {
        if (execution.getLeaseToken() == null || execution.getLeaseWorkItemVersion() == null) {
            throw failure(Reason.TASK_CONFLICT);
        }
        AgentTaskFormalDeliverySubmitDTO command = new AgentTaskFormalDeliverySubmitDTO();
        command.setDeliveryId(deliveryId(execution));
        command.setRunId(execution.getRunId());
        command.setWorkItemId(execution.getWorkItemId());
        command.setLeaseToken(execution.getLeaseToken());
        command.setExpectedTaskVersion(root.getTaskVersion());
        command.setExpectedWorkItemVersion(execution.getLeaseWorkItemVersion());
        command.setSummary("Agent delivery: " + output.getOriginalFilename());
        command.setManifestArtifactId(manifest.getArtifactId());
        command.setManifestArtifactVersion(manifest.getArtifactVersion());
        command.setItems(List.of(formalItem(deliverable, "deliverable"), formalItem(manifest, "manifest")));
        return formalDeliveries.submit(scope.tenantId(), scope.clientId(), scope.ownerJiacn(),
                execution.getTaskId(), execution.getTargetAgentId(), command);
    }

    private static AgentTaskFormalDeliveryItemDTO formalItem(AgentTaskArtifactViewDTO artifact, String purpose) {
        AgentTaskFormalDeliveryItemDTO item = new AgentTaskFormalDeliveryItemDTO();
        item.setArtifactId(artifact.getArtifactId()); item.setArtifactVersion(artifact.getArtifactVersion());
        item.setContentHash(artifact.getContentHash()); item.setPurpose(purpose); return item;
    }

    private static String deliveryId(PersonalWorkspaceExecutionEntity execution) {
        return "pwe_delivery_" + plainSha(execution.getExecutionId() + "\n" + execution.getRunId());
    }

    private static String taskManifest(PersonalWorkspaceExecutionEntity execution,
            PersonalWorkspaceExecutionOutputEntity output) {
        return "{\"schemaVersion\":1,\"executionId\":\"" + execution.getExecutionId()
                + "\",\"outputId\":\"" + output.getOutputId() + "\",\"sha256\":\""
                + output.getContentHash() + "\",\"byteLength\":" + output.getByteLength()
                + ",\"contentMimeType\":\"" + output.getContentMimeType() + "\"}";
    }

    private void requireTaskPublicationDependencies() {
        if (taskArtifacts == null || formalDeliveries == null || taskMutations == null) {
            throw failure(Reason.CAPABILITY_UNAVAILABLE);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExecutionView fail(RuntimeScope scope, String taskId, String runId, String code) {
        runtimeFailureCode(code); validateRuntimeScope(scope); id(taskId, "taskId", 100); id(runId, "runId", 100);
        // Do not take an execution lock before releasing the authoritative task-root lease.
        PersonalWorkspaceExecutionEntity candidate = executions.findByTaskRun(scope.tenantId(), scope.clientId(),
                scope.ownerJiacn(), taskId, runId);
        if (candidate == null || !same(candidate.getTargetAgentId(), scope.agentId())) throw failure(Reason.NOT_FOUND);
        if ("TASK".equals(candidate.getExecutionMode()) && "QUEUED".equals(candidate.getExecutionState())) {
            releaseTaskLease(new OwnerScope(scope.tenantId(), scope.clientId(), scope.ownerJiacn()), candidate);
        }
        PersonalWorkspaceExecutionEntity execution = executions.lockByTaskRun(scope.tenantId(), scope.clientId(),
                scope.ownerJiacn(), taskId, runId);
        if (execution == null || !same(execution.getTargetAgentId(), scope.agentId())) throw failure(Reason.NOT_FOUND);
        if ("FAILED".equals(execution.getExecutionState())) {
            return view(new OwnerScope(scope.tenantId(), scope.clientId(), scope.ownerJiacn()), execution);
        }
        if (!"QUEUED".equals(execution.getExecutionState())) throw failure(Reason.NOT_FOUND);
        execution.setExecutionState("FAILED").setFailureCode(RUNTIME_FAILURE_CODE)
                .setFailureMessage(RUNTIME_FAILURE_MESSAGE).setFailedAt(System.currentTimeMillis());
        executions.update(execution);
        return view(new OwnerScope(scope.tenantId(), scope.clientId(), scope.ownerJiacn()), execution);
    }

    private PersonalWorkspaceExecutionEntity runtimeExecution(RuntimeScope scope, String taskId, String runId,
            boolean lock) {
        return runtimeExecution(scope, taskId, runId, lock, false);
    }
    private PersonalWorkspaceExecutionEntity runtimeExecution(RuntimeScope scope, String taskId, String runId,
            boolean lock, boolean allowCommitted) {
        return runtimeExecution(scope, taskId, runId, lock, allowCommitted, false);
    }
    private PersonalWorkspaceExecutionEntity runtimeExecution(RuntimeScope scope, String taskId, String runId,
            boolean lock, boolean allowCommitted, boolean allowFailed) {
        validateRuntimeScope(scope); id(taskId, "taskId", 100); id(runId, "runId", 100);
        PersonalWorkspaceExecutionEntity execution = lock
                ? executions.lockByTaskRun(scope.tenantId(), scope.clientId(), scope.ownerJiacn(), taskId, runId)
                : executions.findByTaskRun(scope.tenantId(), scope.clientId(), scope.ownerJiacn(), taskId, runId);
        if (execution == null) throw failure(Reason.NOT_FOUND);
        boolean stateAllowed = "QUEUED".equals(execution.getExecutionState())
                || (allowCommitted && "OUTPUT_COMMITTED".equals(execution.getExecutionState()))
                || (allowFailed && "FAILED".equals(execution.getExecutionState()));
        if (!same(execution.getTargetAgentId(), scope.agentId()) || !stateAllowed) {
            throw failure(Reason.NOT_FOUND);
        }
        if ("TASK".equals(execution.getExecutionMode())) requireLiveTaskLease(scope, execution);
        return execution;
    }

    private ExecutionView view(OwnerScope scope, PersonalWorkspaceExecutionEntity execution) {
        List<RuntimeInput> inputs = runtimeInputs(scope, execution.getExecutionId());
        String mode = execution.getExecutionMode() == null ? "PRIVATE" : execution.getExecutionMode();
        String workItemState = "TASK".equals(mode) ? taskWorkItemState(scope, execution) : null;
        return new ExecutionView(execution.getExecutionId(), execution.getTaskId(), execution.getRunId(),
                execution.getConversationId(), execution.getTargetAgentId(), execution.getExecutionState(),
                execution.getFailureCode(), execution.getFailureMessage(), execution.getGrantRevision(),
                execution.getOutputContentMimeType(), inputs, command(execution, inputs), mode,
                "TASK".equals(mode) ? execution.getTaskId() : null, execution.getWorkItemId(), workItemState);
    }
    private List<RuntimeInput> runtimeInputs(OwnerScope scope, String executionId) {
        return executions.listInputs(scope.tenantId(), scope.clientId(), scope.ownerJiacn(), executionId).stream()
                .map(input -> new RuntimeInput(input.getInputRef(), input.getFileId(), input.getFileVersion(),
                        input.getOriginalFilename(), input.getContentMimeType(), input.getByteLength(), input.getContentHash()))
                .toList();
    }
    private RuntimeQueuedCommand queuedCommand(OwnerScope scope, PersonalWorkspaceExecutionEntity execution) {
        RuntimeCommand payload = command(execution, runtimeInputs(scope, execution.getExecutionId()));
        if (payload == null) return null;
        String seed = execution.getExecutionId();
        return new RuntimeQueuedCommand(1, "command.dispatch", "pwe_msg_" + plainSha("message\n" + seed),
                "pwe_cmd_" + plainSha("command\n" + seed), execution.getTenantId(), execution.getClientId(),
                execution.getOwnerJiacn(), execution.getTaskId(), execution.getRunId(), execution.getTargetAgentId(),
                "WORKSPACE_FILE_EXECUTE", execution.getInstruction(), payload);
    }

    private RuntimeCommand command(PersonalWorkspaceExecutionEntity execution, List<RuntimeInput> inputs) {
        if (inputs.size() > MAX_EXECUTION_INPUTS || !"QUEUED".equals(execution.getExecutionState())) return null;
        String outputMime = execution.getOutputContentMimeType();
        String extension = EXTENSIONS.get(outputMime);
        if (!executionMimeTypes.contains(outputMime) || extension == null || storage.maxContentBytes() < 1
                || storage.maxContentBytes() > 9_007_199_254_740_991L) return null;
        String outputPath = INTERNAL_PREFIX + execution.getTaskId() + "/runs/" + execution.getRunId()
                + "/outputs/output_1/content";
        List<RuntimeInputCommand> inputManifest = new ArrayList<>();
        for (RuntimeInput input : inputs) {
            String inputExtension = EXTENSIONS.get(input.contentMimeType());
            if (inputExtension == null || !executionMimeTypes.contains(input.contentMimeType())) return null;
            String inputPath = INTERNAL_PREFIX + execution.getTaskId() + "/runs/" + execution.getRunId()
                    + "/inputs/" + input.inputRef() + "/content";
            inputManifest.add(new RuntimeInputCommand(input.inputRef(), "inputs/" + input.inputRef() + inputExtension,
                    inputPath, input.byteLength(), input.sha256()));
        }
        return new RuntimeCommand(execution.getTaskId(), execution.getRunId(), List.copyOf(inputManifest),
                List.of(new RuntimeOutput("output_1", "outputs/result" + extension, outputMime,
                        storage.maxContentBytes(), outputPath)));
    }
    private CommitView committed(String manifestId, List<PersonalWorkspaceExecutionOutputEntity> outputs) {
        List<CommitItem> items = outputs.stream().map(output -> new CommitItem(output.getOutputId(),
                output.getWorkspaceFileId(), output.getWorkspaceFileVersion(), output.getContentHash(),
                output.getContentMimeType(), output.getByteLength())).toList();
        return new CommitView(manifestId, "COMMITTED", items);
    }
    private void requireOwnedTarget(OwnerScope scope, String targetAgentId) {
        List<AgentRuntimeEntity> candidates = runtimes.findCandidateRosterByOwner(scope.clientId(), scope.ownerJiacn());
        boolean found = candidates != null && candidates.stream().anyMatch(candidate -> candidate != null
                && same(candidate.getAgentId(), targetAgentId) && same(candidate.getClientId(), scope.clientId())
                && same(candidate.getOwnerJiacn(), scope.ownerJiacn()));
        if (!found) throw failure(Reason.NOT_FOUND);
    }
    /**
     * Starts the existing authoritative work-item lease before any execution/file row is locked.
     * The resulting token remains server-side only; the runtime authenticates separately and is
     * revalidated against this persisted snapshot for each content operation.
     */
    private TaskLease beginTaskExecution(OwnerScope scope, ValidCreate valid) {
        if (conversationAccess == null || workItems == null || leases == null || valid.conversationId() == null) {
            throw failure(Reason.CAPABILITY_UNAVAILABLE);
        }
        WorkspaceConversationAccessService.ConversationView conversation;
        try {
            conversation = conversationAccess.requireAccessible(new WorkspaceConversationAccessService.Scope(
                    scope.tenantId(), scope.clientId(), scope.ownerJiacn()), valid.conversationId());
        } catch (RuntimeException denied) {
            throw failure(Reason.NOT_FOUND);
        }
        if (conversation == null || !same(valid.taskId(), conversation.taskId())
                || conversation.targetAgentIds() == null
                || !conversation.targetAgentIds().contains(valid.targetAgentId())) {
            throw failure(Reason.NOT_FOUND);
        }
        List<AgentTaskWorkItemEntity> candidates = workItems.listByTask(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), valid.taskId(), "ready", 32);
        List<AgentTaskWorkItemEntity> eligible = candidates.stream().filter(item -> item != null
                && Boolean.TRUE.equals(item.getRequiredItem())
                && (item.getAssigneeAgentId() == null || same(valid.targetAgentId(), item.getAssigneeAgentId())))
                .toList();
        if (eligible.size() != 1) throw failure(Reason.TASK_CONFLICT);
        AgentTaskWorkItemEntity item = eligible.getFirst();
        try {
            AgentWorkItemLeaseCommandDTO claim = new AgentWorkItemLeaseCommandDTO();
            claim.setAgentId(valid.targetAgentId()); claim.setExpectedVersion(item.getVersion());
            claim.setLeaseDurationMillis(TASK_LEASE_DURATION_MILLIS);
            AgentWorkItemLeaseDTO claimed = leases.claim(scope.tenantId(), scope.clientId(), scope.ownerJiacn(),
                    valid.taskId(), item.getWorkItemId(), claim);
            AgentWorkItemLeaseCommandDTO start = new AgentWorkItemLeaseCommandDTO();
            start.setAgentId(valid.targetAgentId()); start.setLeaseToken(claimed.getLeaseToken());
            start.setExpectedVersion(claimed.getVersion());
            AgentWorkItemLeaseDTO running = leases.start(scope.tenantId(), scope.clientId(), scope.ownerJiacn(),
                    valid.taskId(), item.getWorkItemId(), start);
            if (running == null || !"running".equals(running.getStatus())
                    || !same(valid.targetAgentId(), running.getAgentId())
                    || !same(item.getWorkItemId(), running.getWorkItemId())
                    || running.getLeaseToken() == null || running.getVersion() == null || running.getLeaseUntil() == null) {
                throw failure(Reason.TASK_CONFLICT);
            }
            return new TaskLease(item.getWorkItemId(), running.getLeaseToken(), running.getVersion(), running.getLeaseUntil());
        } catch (AgentTaskCollaborationException conflict) {
            throw failure(conflict.getReason() == AgentTaskCollaborationException.Reason.NOT_FOUND
                    ? Reason.NOT_FOUND : Reason.TASK_CONFLICT);
        } catch (AgentTaskStateException conflict) {
            throw failure(conflict.getReason() == AgentTaskStateException.Reason.NOT_FOUND
                    ? Reason.NOT_FOUND : Reason.TASK_CONFLICT);
        }
    }

    /** Releases only the persisted TASK bridge lease; it never touches a formal delivery. */
    private void releaseTaskLease(OwnerScope scope, PersonalWorkspaceExecutionEntity execution) {
        if (leases == null || execution.getWorkItemId() == null || execution.getLeaseToken() == null
                || execution.getLeaseWorkItemVersion() == null || !"TASK".equals(execution.getExecutionMode())) {
            throw failure(Reason.TASK_CONFLICT);
        }
        try {
            AgentWorkItemLeaseCommandDTO command = new AgentWorkItemLeaseCommandDTO();
            command.setAgentId(execution.getTargetAgentId()); command.setLeaseToken(execution.getLeaseToken());
            command.setExpectedVersion(execution.getLeaseWorkItemVersion());
            leases.release(scope.tenantId(), scope.clientId(), scope.ownerJiacn(), execution.getTaskId(),
                    execution.getWorkItemId(), command);
        } catch (AgentTaskCollaborationException | AgentTaskStateException conflict) {
            throw failure(Reason.TASK_CONFLICT);
        }
    }

    private boolean runtimeDispatchAllowed(RuntimeScope scope, PersonalWorkspaceExecutionEntity execution) {
        if (!"TASK".equals(execution.getExecutionMode())) return true;
        try { requireLiveTaskLease(scope, execution); return true; }
        catch (Failure ignored) { return false; }
    }

    private void requireLiveTaskLease(RuntimeScope scope, PersonalWorkspaceExecutionEntity execution) {
        if (workItems == null || execution.getWorkItemId() == null || execution.getLeaseToken() == null
                || execution.getLeaseWorkItemVersion() == null || execution.getLeaseExpiresAt() == null) {
            throw failure(Reason.NOT_FOUND);
        }
        AgentTaskWorkItemEntity item = workItems.findByTaskAndWorkItemId(scope.tenantId(), scope.clientId(),
                scope.ownerJiacn(), execution.getTaskId(), execution.getWorkItemId());
        if (item == null || !"running".equals(item.getStatus())
                || !same(execution.getTargetAgentId(), item.getAssigneeAgentId())
                || !same(execution.getLeaseToken(), item.getLeaseToken())
                || !execution.getLeaseWorkItemVersion().equals(item.getVersion())
                || item.getLeaseUntil() == null || !execution.getLeaseExpiresAt().equals(item.getLeaseUntil())
                || item.getLeaseUntil() <= System.currentTimeMillis()) {
            throw failure(Reason.NOT_FOUND);
        }
    }

    private String taskWorkItemState(OwnerScope scope, PersonalWorkspaceExecutionEntity execution) {
        if (workItems == null || execution.getWorkItemId() == null) return "UNAVAILABLE";
        AgentTaskWorkItemEntity item = workItems.findByTaskAndWorkItemId(scope.tenantId(), scope.clientId(),
                scope.ownerJiacn(), execution.getTaskId(), execution.getWorkItemId());
        return item == null ? "UNAVAILABLE" : item.getStatus();
    }

    private record TaskLease(String workItemId, String leaseToken, Long version, Long leaseUntil) { }

    private ValidCreate validCreate(CreateCommand command) {
        if (command == null) throw failure(Reason.BAD_REQUEST);
        String conversation = optional(command.conversationId(), 100);
        id(command.targetAgentId(), "targetAgentId", 100); String task = optional(command.taskId(), 100);
        text(command.instruction(), "instruction", 4000);
        String outputMime = command.outputContentMimeType() == null
                ? PersonalWorkspaceExecutionProperties.DOCX : command.outputContentMimeType();
        if (!executionAvailable || !SUPPORTED_EXECUTION_MIME_TYPES.contains(outputMime)
                || !executionMimeTypes.contains(outputMime)) {
            throw failure(Reason.CAPABILITY_UNAVAILABLE);
        }
        if (command.inputs() == null || command.inputs().size() > MAX_EXECUTION_INPUTS) throw failure(Reason.BAD_REQUEST);
        List<InputSelection> selections = List.copyOf(command.inputs());
        Set<String> selectedFiles = new LinkedHashSet<>();
        for (InputSelection selected : selections) {
            if (selected == null) throw failure(Reason.BAD_REQUEST);
            id(selected.fileId(), "fileId", 100);
            if (selected.version() < 1 || !selectedFiles.add(selected.fileId())) throw failure(Reason.BAD_REQUEST);
        }
        if (task != null && conversation == null) throw failure(Reason.BAD_REQUEST);
        return new ValidCreate(conversation, command.targetAgentId(), task, command.instruction(), outputMime, selections);
    }
    private static String selectionsWire(List<InputSelection> inputs) { return inputs.stream().sorted(Comparator.comparing(InputSelection::fileId).thenComparingInt(InputSelection::version)).map(i -> i.fileId()+":"+i.version()).reduce("", (a,b)->a+"\n"+b); }
    private static String manifestId(String taskId, String runId, List<PersonalWorkspaceExecutionOutputEntity> outputs) {
        StringBuilder source = new StringBuilder(taskId).append('\n').append(runId).append('\n');
        outputs.stream().sorted(Comparator.comparing(PersonalWorkspaceExecutionOutputEntity::getOutputId)).forEach(output -> source
                .append(output.getOutputId()).append('\n').append(output.getContentHash()).append('\n').append(output.getByteLength()).append('\n'));
        return "pwe_m_" + plainSha(source.toString());
    }
    private static String plainSha(String value) {
        return plainSha(value.getBytes(StandardCharsets.UTF_8));
    }
    private static String plainSha(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String hash(String... values) {
        try { MessageDigest digest=MessageDigest.getInstance("SHA-256"); for (String value:values) { byte[] bytes=Objects.requireNonNullElse(value,"").getBytes(StandardCharsets.UTF_8); digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes); } return HexFormat.of().formatHex(digest.digest()); }
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static boolean same(String left, String right) { return left != null && right != null && MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8)); }
    private static boolean sha(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static String identifier(String prefix) { return prefix + UUID.randomUUID().toString().replace("-", ""); }
    private static String family(String mime) { if(mime.startsWith("image/"))return "IMAGE"; if("text/plain".equals(mime))return "TEXT"; if("application/pdf".equals(mime))return "PDF"; if(mime.contains("spreadsheet"))return "SPREADSHEET"; if(mime.contains("presentation"))return "PRESENTATION"; return "DOCUMENT"; }
    private static void filename(String value, String mime) { text(value,"filename",255); String expected=EXTENSIONS.get(mime); if(expected==null||value.contains("/")||value.contains("\\")||!value.toLowerCase(Locale.ROOT).endsWith(expected)) throw failure(Reason.BAD_REQUEST); }
    private void validMime(String mime) {
        if (!executionMimeTypes.contains(mime)) throw failure(Reason.BAD_REQUEST);
    }
    private static void runtimeFailureCode(String code) {
        // The code is diagnostic only: the persisted public state and message are fixed server values.
        // Keep accepting stable native failure identifiers so every no-delivery path can terminate the queue.
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{2,63}")) throw failure(Reason.BAD_REQUEST);
    }
    private static Set<String> allowedMimeTypes(PersonalWorkspaceExecutionProperties properties) {
        if (properties == null || properties.allowedMimeTypes() == null || properties.allowedMimeTypes().isEmpty()) {
            throw new IllegalStateException("Personal workspace execution MIME configuration is unavailable");
        }
        LinkedHashSet<String> allowed = new LinkedHashSet<>();
        for (String mime : properties.allowedMimeTypes()) {
            if (!SUPPORTED_EXECUTION_MIME_TYPES.contains(mime) || !allowed.add(mime)) {
                throw new IllegalStateException("Personal workspace execution MIME configuration is invalid");
            }
        }
        return Set.copyOf(allowed);
    }
    private static void validateOwnerScope(OwnerScope scope) { if(scope==null||!"0".equals(scope.tenantId()))throw failure(Reason.BAD_REQUEST); id(scope.clientId(),"clientId",50);id(scope.ownerJiacn(),"owner",50);if("0".equals(scope.ownerJiacn()))throw failure(Reason.BAD_REQUEST); }
    private static void validateRuntimeScope(RuntimeScope scope) { if(scope==null||!"0".equals(scope.tenantId()))throw failure(Reason.NOT_FOUND); id(scope.clientId(),"clientId",50);id(scope.ownerJiacn(),"owner",50);id(scope.agentId(),"agentId",100);id(scope.runtimeInstanceId(),"runtimeInstanceId",100);if("0".equals(scope.ownerJiacn()))throw failure(Reason.NOT_FOUND); }
    private static void validateIdempotency(String key) { id(key,"Idempotency-Key",100); }
    private static void id(String value,String name,int max) { if(value==null||value.isBlank()||!value.equals(value.strip())||value.codePointCount(0,value.length())>max||value.chars().anyMatch(Character::isISOControl))throw failure(Reason.BAD_REQUEST); }
    private static String optional(String value,int max) { if(value==null)return null;id(value,"optional",max);return value; }
    private static void text(String value,String name,int max) { if(value==null||value.isBlank()||value.codePointCount(0,value.length())>max||value.chars().anyMatch(Character::isISOControl))throw failure(Reason.BAD_REQUEST); }
    private static PersonalWorkspaceStorage.Scope storageScope(OwnerScope scope) { return new PersonalWorkspaceStorage.Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn()); }
    private static PersonalWorkspaceStorage.Scope storageScope(RuntimeScope scope) { return new PersonalWorkspaceStorage.Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn()); }
    private static PersonalWorkspaceWriteService.Scope writeScope(RuntimeScope scope) { return new PersonalWorkspaceWriteService.Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn()); }
    private static void scoped(cn.jia.core.entity.BaseEntity entity,OwnerScope scope){entity.setTenantId(scope.tenantId());entity.setClientId(scope.clientId());}
    private static void scoped(cn.jia.core.entity.BaseEntity entity,RuntimeScope scope){entity.setTenantId(scope.tenantId());entity.setClientId(scope.clientId());}
    private static Failure failure(Reason reason){return new Failure(reason);}
    private record ValidCreate(String conversationId,String targetAgentId,String taskId,String instruction,
                               String outputContentMimeType,List<InputSelection> inputs){}
    private record InputSnapshot(PersonalWorkspaceFileEntity file, PersonalWorkspaceVersionEntity version){}
}
