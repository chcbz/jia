package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.PersonalWorkspaceDao;
import cn.jia.agent.dao.PersonalWorkspaceExecutionDao;
import cn.jia.agent.dao.PersonalWorkspaceTaskLinkDao;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.entity.PersonalWorkspaceExecutionEntity;
import cn.jia.agent.entity.PersonalWorkspaceExecutionInputEntity;
import cn.jia.agent.entity.PersonalWorkspaceExecutionOutputEntity;
import cn.jia.agent.entity.PersonalWorkspaceFileEntity;
import cn.jia.agent.entity.PersonalWorkspaceVersionEntity;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.service.PersonalWorkspaceStorage;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.dao.DuplicateKeyException;
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
 * C1-C/D private run grant boundary. It has no public task-root/event/search side effect: the
 * generated taskId is only a stable runtime bridge namespace. A runtime-authenticated client polls
 * its own durable queue; acceptance never pretends that a model has already run.
 */
@Named
public class PersonalWorkspaceExecutionServiceImpl implements PersonalWorkspaceExecutionService {
    private static final String INTERNAL_PREFIX = "/internal/agent/tasks/";
    private static final Set<String> MIME_TYPES = Set.of("image/png", "image/jpeg", "text/plain", "application/pdf",
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

    @Inject
    public PersonalWorkspaceExecutionServiceImpl(PersonalWorkspaceExecutionDao executions,
            PersonalWorkspaceDao workspace, PersonalWorkspaceTaskLinkDao taskLinks,
            AgentRuntimeDao runtimes, PersonalWorkspaceStorage storage,
            PersonalWorkspaceWriteService writes) {
        this.executions = Objects.requireNonNull(executions, "executions");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.taskLinks = Objects.requireNonNull(taskLinks, "taskLinks");
        this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.writes = Objects.requireNonNull(writes, "writes");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExecutionView create(OwnerScope scope, CreateCommand command, String idempotencyKey) {
        validateOwnerScope(scope); validateIdempotency(idempotencyKey); ValidCreate valid = validCreate(command);
        String requestHash = hash("CREATE", valid.conversationId(), valid.targetAgentId(), valid.taskId(),
                valid.instruction(), selectionsWire(valid.inputs()));
        PersonalWorkspaceExecutionEntity prior = executions.findByIdempotency(scope.tenantId(),
                scope.clientId(), scope.ownerJiacn(), idempotencyKey);
        if (prior != null) {
            if (!same(prior.getRequestHash(), requestHash)) throw failure(Reason.IDEMPOTENCY_CONFLICT);
            return view(scope, prior);
        }
        requireOwnedTarget(scope, valid.targetAgentId());
        if (valid.taskId() != null && !taskLinks.lockTask(scope.tenantId(), scope.clientId(),
                scope.ownerJiacn(), valid.taskId())) throw failure(Reason.NOT_FOUND);

        List<InputSnapshot> snapshots = new ArrayList<>();
        List<InputSelection> ordered = new ArrayList<>(valid.inputs());
        ordered.sort(Comparator.comparing(InputSelection::fileId));
        for (InputSelection selected : ordered) {
            PersonalWorkspaceFileEntity file = workspace.lockFile(scope.tenantId(), scope.clientId(),
                    scope.ownerJiacn(), selected.fileId());
            if (file == null || !"ACTIVE".equals(file.getState())) throw failure(Reason.NOT_FOUND);
            PersonalWorkspaceVersionEntity version = workspace.findVersion(scope.tenantId(), scope.clientId(),
                    scope.ownerJiacn(), selected.fileId(), selected.version());
            if (version == null || !MIME_TYPES.contains(version.getContentMimeType())) throw failure(Reason.NOT_FOUND);
            snapshots.add(new InputSnapshot(file, version));
        }
        String executionId = identifier("pwe_");
        String taskId = valid.taskId() == null ? identifier("pwe_task_") : valid.taskId();
        String runId = identifier("pwe_run_");
        long now = System.currentTimeMillis();
        PersonalWorkspaceExecutionEntity execution = new PersonalWorkspaceExecutionEntity()
                .setExecutionId(executionId).setOwnerJiacn(scope.ownerJiacn()).setTaskId(taskId).setRunId(runId)
                .setConversationId(valid.conversationId()).setTargetAgentId(valid.targetAgentId())
                .setInstruction(valid.instruction()).setExecutionState("QUEUED").setGrantRevision(1L)
                .setIdempotencyKey(idempotencyKey).setRequestHash(requestHash)
                .setRevokeIdempotencyKey(null).setRevokeRequestHash(null)
                .setCreatedAt(now).setRevokedAt(null);
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
                || content.length > storage.maxContentBytes()) throw failure(Reason.BAD_REQUEST);
        List<PersonalWorkspaceExecutionInputEntity> inputs = executions.listInputs(scope.tenantId(), scope.clientId(),
                scope.ownerJiacn(), execution.getExecutionId());
        if (inputs.size() != 1 || !contentMimeType.equals(inputs.getFirst().getContentMimeType())) throw failure(Reason.BAD_REQUEST);
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
                .setWorkspaceFileId(null).setWorkspaceFileVersion(null).setStagedAt(now).setCommittedAt(null);
        scoped(output, scope); executions.insertOutput(output);
        return new StagedOutput(outputId, stored.sha256(), stored.byteLength(), "STAGED");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommitView commitOutputs(RuntimeScope scope, String taskId, String runId, String manifestId,
            List<OutputDeclaration> declarations) {
        PersonalWorkspaceExecutionEntity execution = runtimeExecution(scope, taskId, runId, true, true);
        id(manifestId, "manifestId", 100);
        if (declarations == null || declarations.size() != 1 || declarations.getFirst() == null
                || !"output_1".equals(declarations.getFirst().outputId())
                || !sha(declarations.getFirst().sha256()) || declarations.getFirst().byteLength() < 0) {
            throw failure(Reason.BAD_REQUEST);
        }
        List<PersonalWorkspaceExecutionOutputEntity> outputs = executions.lockOutputs(scope.tenantId(), scope.clientId(),
                scope.ownerJiacn(), execution.getExecutionId());
        if (outputs.size() != 1 || !"output_1".equals(outputs.getFirst().getOutputId())) throw failure(Reason.OUTPUT_MISSING);
        PersonalWorkspaceExecutionOutputEntity output = outputs.getFirst();
        OutputDeclaration declaration = declarations.getFirst();
        if (!same(output.getContentHash(), declaration.sha256()) || output.getByteLength() != declaration.byteLength()) {
            throw failure(Reason.OUTPUT_CONFLICT);
        }
        String expected = manifestId(execution.getTaskId(), execution.getRunId(), List.of(output));
        if (!same(expected, manifestId)) throw failure(Reason.OUTPUT_CONFLICT);
        if ("COMMITTED".equals(output.getOutputState())) return committed(manifestId, List.of(output));
        if (!"STAGED".equals(output.getOutputState())) throw failure(Reason.OUTPUT_CONFLICT);
        long now = System.currentTimeMillis();
        String fileId = identifier("pws_");
        PersonalWorkspaceFileEntity file = new PersonalWorkspaceFileEntity().setFileId(fileId)
                .setOwnerJiacn(scope.ownerJiacn()).setSourceKind("UPLOAD").setDisplayName(output.getOriginalFilename())
                .setMediaFamily(family(output.getContentMimeType())).setState("ACTIVE").setMetadataRevision(1L)
                .setLatestVersion(1).setCreatedAt(now);
        scoped(file, scope);
        PersonalWorkspaceVersionEntity version = new PersonalWorkspaceVersionEntity().setFileId(fileId)
                .setOwnerJiacn(scope.ownerJiacn()).setVersion(1).setOriginalFilename(output.getOriginalFilename())
                .setContentMimeType(output.getContentMimeType()).setByteLength(output.getByteLength())
                .setContentHash(output.getContentHash()).setStorageUri(output.getStorageUri()).setCreatedAt(now);
        scoped(version, scope);
        writes.archiveRuntimeOutput(writeScope(scope), file, version);
        output.setOutputState("COMMITTED").setWorkspaceFileId(fileId).setWorkspaceFileVersion(1).setCommittedAt(now);
        executions.updateOutput(output);
        execution.setExecutionState("OUTPUT_COMMITTED"); executions.update(execution);
        return committed(manifestId, List.of(output));
    }

    private PersonalWorkspaceExecutionEntity runtimeExecution(RuntimeScope scope, String taskId, String runId,
            boolean lock) {
        return runtimeExecution(scope, taskId, runId, lock, false);
    }
    private PersonalWorkspaceExecutionEntity runtimeExecution(RuntimeScope scope, String taskId, String runId,
            boolean lock, boolean allowCommitted) {
        validateRuntimeScope(scope); id(taskId, "taskId", 100); id(runId, "runId", 100);
        PersonalWorkspaceExecutionEntity execution = lock
                ? executions.lockByTaskRun(scope.tenantId(), scope.clientId(), scope.ownerJiacn(), taskId, runId)
                : executions.findByTaskRun(scope.tenantId(), scope.clientId(), scope.ownerJiacn(), taskId, runId);
        if (execution == null) throw failure(Reason.NOT_FOUND);
        boolean stateAllowed = "QUEUED".equals(execution.getExecutionState())
                || (allowCommitted && "OUTPUT_COMMITTED".equals(execution.getExecutionState()));
        if (!same(execution.getTargetAgentId(), scope.agentId()) || !stateAllowed) {
            throw failure(Reason.NOT_FOUND);
        }
        return execution;
    }

    private ExecutionView view(OwnerScope scope, PersonalWorkspaceExecutionEntity execution) {
        List<RuntimeInput> inputs = runtimeInputs(scope, execution.getExecutionId());
        return new ExecutionView(execution.getExecutionId(), execution.getTaskId(), execution.getRunId(),
                execution.getConversationId(), execution.getTargetAgentId(), execution.getExecutionState(),
                execution.getGrantRevision(), inputs, command(execution, inputs));
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
        if (inputs.size() != 1 || !"QUEUED".equals(execution.getExecutionState())) return null;
        RuntimeInput input = inputs.getFirst(); String extension = EXTENSIONS.get(input.contentMimeType());
        if (extension == null || storage.maxContentBytes() < 1 || storage.maxContentBytes() > 9_007_199_254_740_991L) return null;
        String inputPath = INTERNAL_PREFIX + execution.getTaskId() + "/runs/" + execution.getRunId()
                + "/inputs/" + input.inputRef() + "/content";
        String outputPath = INTERNAL_PREFIX + execution.getTaskId() + "/runs/" + execution.getRunId()
                + "/outputs/output_1/content";
        return new RuntimeCommand(execution.getTaskId(), execution.getRunId(),
                List.of(new RuntimeInputCommand(input.inputRef(), "inputs/" + input.inputRef() + extension,
                        inputPath, input.byteLength(), input.sha256())),
                List.of(new RuntimeOutput("output_1", "outputs/result" + extension, input.contentMimeType(),
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
    private static ValidCreate validCreate(CreateCommand command) {
        if (command == null) throw failure(Reason.BAD_REQUEST);
        String conversation = optional(command.conversationId(), 100);
        id(command.targetAgentId(), "targetAgentId", 100); String task = optional(command.taskId(), 100);
        text(command.instruction(), "instruction", 4000);
        if (command.inputs() == null || command.inputs().size() != 1) throw failure(Reason.CAPABILITY_UNAVAILABLE);
        InputSelection selected = command.inputs().getFirst(); id(selected.fileId(), "fileId", 100);
        if (selected.version() < 1) throw failure(Reason.BAD_REQUEST);
        return new ValidCreate(conversation, command.targetAgentId(), task, command.instruction(), List.of(selected));
    }
    private static String selectionsWire(List<InputSelection> inputs) { return inputs.stream().sorted(Comparator.comparing(InputSelection::fileId)).map(i -> i.fileId()+":"+i.version()).reduce("", (a,b)->a+"\n"+b); }
    private static String manifestId(String taskId, String runId, List<PersonalWorkspaceExecutionOutputEntity> outputs) {
        StringBuilder source = new StringBuilder(taskId).append('\n').append(runId).append('\n');
        outputs.stream().sorted(Comparator.comparing(PersonalWorkspaceExecutionOutputEntity::getOutputId)).forEach(output -> source
                .append(output.getOutputId()).append('\n').append(output.getContentHash()).append('\n').append(output.getByteLength()).append('\n'));
        return "pwe_m_" + plainSha(source.toString());
    }
    private static String plainSha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
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
    private static void validMime(String mime) { if (!MIME_TYPES.contains(mime)) throw failure(Reason.BAD_REQUEST); }
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
    private record ValidCreate(String conversationId,String targetAgentId,String taskId,String instruction,List<InputSelection> inputs){}
    private record InputSnapshot(PersonalWorkspaceFileEntity file, PersonalWorkspaceVersionEntity version){}
}
