package cn.jia.agent.service.impl;

import cn.jia.agent.config.PersonalWorkspaceExecutionProperties;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.HallRequestDraftDao;
import cn.jia.agent.dao.PersonalWorkspaceDao;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.HallRequestDraftEntity;
import cn.jia.agent.entity.PersonalWorkspaceFileEntity;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.HallRequestDraftService;
import cn.jia.chat.service.WorkspaceConversationAccessService;
import cn.jia.core.util.JsonUtil;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * DRAFT-v1 transaction boundary. Lock order is draft-only in this wave: source ACL reads happen
 * before the single draft CAS statement, and no execution, case, task mutation, or Provider call is made.
 */
@Named
public class HallRequestDraftServiceImpl implements HallRequestDraftService {
    private static final int PAGE_SIZE = 20;
    private static final int MAX_INPUTS = 128;
    private static final long MAX_SAFE_REVISION = 9_007_199_254_740_991L;
    private static final Set<String> KINDS = Set.of(
            "CREATE", "REVISION", "TASK_CREATE", "TASK_ACTION");
    private static final Set<String> SOURCE_TYPES = Set.of(
            "FILE", "CONVERSATION", "TASK", "EXECUTION_OUTPUT");
    private static final TypeReference<List<InputSelection>> INPUT_LIST = new TypeReference<>() { };
    private static final TypeReference<SourceOutputRef> SOURCE_OUTPUT = new TypeReference<>() { };

    private final HallRequestDraftDao drafts;
    private final PersonalWorkspaceDao workspace;
    private final AgentTaskMetaDao tasks;
    private final AgentService agents;
    private final WorkspaceConversationAccessService conversations;
    private final Set<String> allowedOutputMimes;
    private final LongSupplier clock;

    @Inject
    public HallRequestDraftServiceImpl(HallRequestDraftDao drafts,
            PersonalWorkspaceDao workspace, AgentTaskMetaDao tasks, AgentService agents,
            ObjectProvider<WorkspaceConversationAccessService> conversations,
            PersonalWorkspaceExecutionProperties executionProperties) {
        this(drafts, workspace, tasks, agents,
                conversations == null ? null : conversations.getIfAvailable(),
                executionProperties, System::currentTimeMillis);
    }

    HallRequestDraftServiceImpl(HallRequestDraftDao drafts,
            PersonalWorkspaceDao workspace, AgentTaskMetaDao tasks, AgentService agents,
            WorkspaceConversationAccessService conversations,
            PersonalWorkspaceExecutionProperties executionProperties, LongSupplier clock) {
        this.drafts = Objects.requireNonNull(drafts, "drafts");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.agents = Objects.requireNonNull(agents, "agents");
        this.conversations = conversations;
        this.allowedOutputMimes = validatedMimes(executionProperties);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public DraftView create(OwnerScope scope, CreateCommand command, String idempotencyKey) {
        requireScope(scope); requireId(idempotencyKey, "Idempotency-Key", 100);
        ValidCreate valid = validCreate(command);
        String createHash = requestHash(valid);

        HallRequestDraftEntity replay = drafts.findByCreateKey(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), idempotencyKey);
        if (replay != null) return matchingCreateReplay(scope, replay, createHash);

        validateSources(scope, valid);
        long now = now();
        HallRequestDraftEntity candidate = new HallRequestDraftEntity()
                .setDraftId("hdr_" + UUID.randomUUID().toString().replace("-", ""))
                .setTenantId(scope.tenantId()).setClientId(scope.clientId())
                .setOwnerJiacn(scope.ownerJiacn()).setKind(valid.kind())
                .setOriginRef(valid.originRef())
                .setSourceType(valid.sourceRef() == null ? null : valid.sourceRef().sourceType())
                .setSourceId(valid.sourceRef() == null ? null : valid.sourceRef().sourceId())
                .setSourceVersion(valid.sourceRef() == null ? null : valid.sourceRef().version())
                .setCaseId(valid.caseId()).setTaskId(valid.taskId())
                .setConversationId(valid.conversationId())
                .setTitle(valid.editableFields().title())
                .setInstruction(valid.editableFields().instruction())
                .setTargetAgentId(valid.editableFields().targetAgentId())
                .setOutputMime(valid.editableFields().outputMime())
                .setInputsJson(json(valid.editableFields().inputs()))
                .setSourceOutputRefJson(valid.sourceOutputRef() == null
                        ? null : json(valid.sourceOutputRef()))
                .setUiCheckpointJson(null).setRevision(1L).setState("EDITING")
                .setSubmissionRef(null).setSubmittedExecutionId(null)
                .setCreateKey(idempotencyKey).setCreateHash(createHash)
                .setSubmitKey(null).setSubmitHash(null).setDiscardKey(null).setDiscardHash(null)
                .setCreatedAt(now).setUpdatedAt(now);
        drafts.reserveCreate(candidate);
        HallRequestDraftEntity stored = drafts.findByCreateKey(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), idempotencyKey);
        if (stored == null) throw failure(Reason.STORAGE_UNAVAILABLE);
        return matchingCreateReplay(scope, stored, createHash);
    }

    @Override
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public DraftView get(OwnerScope scope, String draftId) {
        requireScope(scope); requireId(draftId, "draftId", 100);
        HallRequestDraftEntity row = drafts.find(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), draftId);
        if (row == null) throw failure(Reason.NOT_FOUND);
        return view(scope, row);
    }

    @Override
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public DraftPage list(OwnerScope scope, String cursor) {
        requireScope(scope);
        Cursor decoded = decodeCursor(cursor);
        List<HallRequestDraftEntity> rows = drafts.listEditing(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(),
                decoded == null ? null : decoded.updatedAt(),
                decoded == null ? null : decoded.draftId(), PAGE_SIZE + 1);
        boolean hasMore = rows.size() > PAGE_SIZE;
        List<HallRequestDraftEntity> page = hasMore ? rows.subList(0, PAGE_SIZE) : rows;
        List<DraftSummary> items = page.stream().map(row -> summary(scope, row)).toList();
        String next = hasMore ? encodeCursor(page.getLast().getUpdatedAt(),
                page.getLast().getDraftId()) : null;
        return new DraftPage(items, next);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DraftView replace(OwnerScope scope, String draftId, long expectedRevision,
            EditableFields editableFields) {
        requireScope(scope); requireId(draftId, "draftId", 100);
        requireMutableRevision(expectedRevision);
        HallRequestDraftEntity current = drafts.find(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), draftId);
        if (current == null) throw failure(Reason.NOT_FOUND);
        requirePersistedScope(scope, current);
        requirePersistedLifecycle(current);
        if (!"EDITING".equals(current.getState())) throw failure(Reason.STATE_CONFLICT);
        if (current.getRevision() != expectedRevision) throw failure(Reason.REVISION_CHANGED);

        EditableFields valid = validEditable(editableFields);
        validatePersistedSources(scope, current, valid);
        int changed = drafts.replaceEditing(scope.tenantId(), scope.clientId(), scope.ownerJiacn(),
                draftId, expectedRevision, valid.title(), valid.instruction(), valid.targetAgentId(),
                valid.outputMime(), json(valid.inputs()), now());
        if (changed != 1) throw mutationFailure(scope, draftId, expectedRevision);
        HallRequestDraftEntity stored = drafts.find(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), draftId);
        if (stored == null || stored.getRevision() != expectedRevision + 1) {
            throw failure(Reason.STORAGE_UNAVAILABLE);
        }
        return view(scope, stored);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public DraftView discard(OwnerScope scope, String draftId, long expectedRevision,
            String idempotencyKey) {
        requireScope(scope); requireId(draftId, "draftId", 100);
        requireMutableRevision(expectedRevision); requireId(idempotencyKey, "Idempotency-Key", 100);
        String discardHash = hash("DISCARD", draftId, Long.toString(expectedRevision));
        HallRequestDraftEntity replay = drafts.findByDiscardKey(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), idempotencyKey);
        if (replay != null) return matchingDiscardReplay(scope, replay, draftId, discardHash);

        int changed;
        try {
            changed = drafts.discardEditing(scope.tenantId(), scope.clientId(), scope.ownerJiacn(),
                    draftId, expectedRevision, idempotencyKey, discardHash, now());
        } catch (DataIntegrityViolationException concurrentKey) {
            changed = 0;
        }
        if (changed != 1) {
            replay = drafts.findByDiscardKey(
                    scope.tenantId(), scope.clientId(), scope.ownerJiacn(), idempotencyKey);
            if (replay != null) return matchingDiscardReplay(scope, replay, draftId, discardHash);
            throw mutationFailure(scope, draftId, expectedRevision);
        }
        HallRequestDraftEntity stored = drafts.find(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), draftId);
        if (stored == null) throw failure(Reason.STORAGE_UNAVAILABLE);
        return matchingDiscardReplay(scope, stored, draftId, discardHash);
    }

    private DraftView matchingCreateReplay(OwnerScope scope, HallRequestDraftEntity row, String hash) {
        requirePersistedScope(scope, row);
        if (!secureEquals(row.getCreateHash(), hash)) throw failure(Reason.IDEMPOTENCY_CONFLICT);
        return view(scope, row);
    }

    private DraftView matchingDiscardReplay(OwnerScope scope, HallRequestDraftEntity row,
            String draftId, String hash) {
        requirePersistedScope(scope, row);
        if (!draftId.equals(row.getDraftId()) || !"DISCARDED".equals(row.getState())
                || !secureEquals(row.getDiscardHash(), hash)) {
            throw failure(Reason.IDEMPOTENCY_CONFLICT);
        }
        return view(scope, row);
    }

    private Failure mutationFailure(OwnerScope scope, String draftId, long expectedRevision) {
        HallRequestDraftEntity current = drafts.find(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), draftId);
        if (current == null) return failure(Reason.NOT_FOUND);
        requirePersistedScope(scope, current);
        if (!"EDITING".equals(current.getState())) return failure(Reason.STATE_CONFLICT);
        if (current.getRevision() == null || current.getRevision() != expectedRevision) {
            return failure(Reason.REVISION_CHANGED);
        }
        return failure(Reason.STORAGE_UNAVAILABLE);
    }

    private ValidCreate validCreate(CreateCommand command) {
        if (command == null || command.editableFields() == null) throw failure(Reason.BAD_REQUEST);
        String kind = requireEnum(command.kind(), KINDS, "kind");
        String originRef = requireExact(command.originRef(), "originRef", 120);
        SourceRef sourceRef = validSourceRef(command.sourceRef());
        String caseId = optionalId(command.caseId(), "caseId", 100);
        String taskId = optionalId(command.taskId(), "taskId", 100);
        String conversationId = optionalId(command.conversationId(), "conversationId", 100);
        SourceOutputRef sourceOutput = validSourceOutput(command.sourceOutputRef());
        EditableFields fields = validEditable(command.editableFields());

        if (caseId != null) throw sourceUnavailable("caseId", "PRIVATE_CASE");
        if ("REVISION".equals(kind) && sourceOutput == null) throw failure(Reason.BAD_REQUEST);
        if (!"REVISION".equals(kind) && sourceOutput != null) throw failure(Reason.BAD_REQUEST);
        if ("TASK_ACTION".equals(kind) && taskId == null) throw failure(Reason.BAD_REQUEST);
        if ("TASK_CREATE".equals(kind) && taskId != null) throw failure(Reason.BAD_REQUEST);
        if (("CREATE".equals(kind) || "REVISION".equals(kind)) && taskId != null) {
            throw failure(Reason.BAD_REQUEST);
        }
        return new ValidCreate(kind, originRef, sourceRef, caseId, taskId,
                conversationId, fields, sourceOutput);
    }

    private EditableFields validEditable(EditableFields fields) {
        if (fields == null || fields.inputs() == null) throw failure(Reason.BAD_REQUEST);
        String title = optionalText(fields.title(), "title", 200);
        String instruction = optionalText(fields.instruction(), "instruction", 20_000);
        String target = optionalId(fields.targetAgentId(), "targetAgentId", 100);
        String mime = optionalExact(fields.outputMime(), "outputMime", 160);
        if (mime != null && !allowedOutputMimes.contains(mime)) {
            throw sourceUnavailable("outputMime", "OUTPUT_MIME");
        }
        if (fields.inputs().size() > MAX_INPUTS) throw failure(Reason.BAD_REQUEST);
        List<InputSelection> inputs = new ArrayList<>(fields.inputs().size());
        Set<String> unique = new HashSet<>();
        for (InputSelection input : fields.inputs()) {
            if (input == null) throw failure(Reason.BAD_REQUEST);
            String fileId = requireId(input.fileId(), "fileId", 100);
            if (input.version() < 1) throw failure(Reason.BAD_REQUEST);
            if (!unique.add(fileId + '\u0000' + input.version())) throw failure(Reason.BAD_REQUEST);
            inputs.add(new InputSelection(fileId, input.version()));
        }
        return new EditableFields(title, instruction, target, mime, inputs);
    }

    private SourceRef validSourceRef(SourceRef source) {
        if (source == null) return null;
        String type = requireExact(source.sourceType(), "sourceType", 32);
        if (!SOURCE_TYPES.contains(type)) throw sourceUnavailable("sourceRef", type);
        String id = requireId(source.sourceId(), "sourceId", 100);
        Integer version = source.version();
        if ("FILE".equals(type)) {
            if (version == null || version < 1) throw failure(Reason.BAD_REQUEST);
        } else if (version != null) {
            throw failure(Reason.BAD_REQUEST);
        }
        return new SourceRef(type, id, version);
    }

    private SourceOutputRef validSourceOutput(SourceOutputRef source) {
        if (source == null) return null;
        String executionId = requireId(source.executionId(), "executionId", 100);
        String outputId = requireId(source.outputId(), "outputId", 100);
        String fileId = requireId(source.fileId(), "fileId", 100);
        if (source.fileVersion() < 1) throw failure(Reason.BAD_REQUEST);
        return new SourceOutputRef(executionId, outputId, fileId, source.fileVersion());
    }

    private void validateSources(OwnerScope scope, ValidCreate valid) {
        validateEditableSources(scope, valid.editableFields());
        WorkspaceConversationAccessService.ConversationView conversation = null;
        if (valid.conversationId() != null) {
            conversation = requireConversation(scope, valid.conversationId());
        }
        if (valid.taskId() != null) requireTask(scope, valid.taskId());
        if (conversation != null && conversation.taskId() != null
                && valid.taskId() != null && !valid.taskId().equals(conversation.taskId())) {
            throw sourceUnavailable("conversationId", "CONVERSATION");
        }
        if (valid.sourceOutputRef() != null) requirePrivateOutput(scope, valid.sourceOutputRef());
        if (valid.sourceRef() == null) return;
        switch (valid.sourceRef().sourceType()) {
            case "FILE" -> requireFileVersion(scope, valid.sourceRef().sourceId(),
                    valid.sourceRef().version(), "sourceRef");
            case "CONVERSATION" -> {
                if (valid.conversationId() != null
                        && !valid.conversationId().equals(valid.sourceRef().sourceId())) {
                    throw failure(Reason.BAD_REQUEST);
                }
                requireConversation(scope, valid.sourceRef().sourceId());
            }
            case "TASK" -> {
                if (valid.taskId() != null && !valid.taskId().equals(valid.sourceRef().sourceId())) {
                    throw failure(Reason.BAD_REQUEST);
                }
                requireTask(scope, valid.sourceRef().sourceId());
            }
            case "EXECUTION_OUTPUT" -> {
                if (valid.sourceOutputRef() == null
                        || !valid.sourceRef().sourceId().equals(valid.sourceOutputRef().executionId())) {
                    throw failure(Reason.BAD_REQUEST);
                }
                requirePrivateOutput(scope, valid.sourceOutputRef());
            }
            default -> throw sourceUnavailable("sourceRef", valid.sourceRef().sourceType());
        }
    }

    private void validatePersistedSources(OwnerScope scope, HallRequestDraftEntity row,
            EditableFields fields) {
        validateEditableSources(scope, fields);
        if (row.getCaseId() != null) throw sourceUnavailable("caseId", "PRIVATE_CASE");

        WorkspaceConversationAccessService.ConversationView conversation = null;
        if (row.getConversationId() != null) {
            conversation = requireConversation(scope,
                    requirePersistedId(row.getConversationId(), "conversationId"));
        }
        if (row.getTaskId() != null) {
            requireTask(scope, requirePersistedId(row.getTaskId(), "taskId"));
        }
        if (conversation != null && conversation.taskId() != null && row.getTaskId() != null
                && !row.getTaskId().equals(conversation.taskId())) {
            throw sourceUnavailable("conversationId", "CONVERSATION");
        }

        SourceOutputRef output = persistedSourceOutput(row.getSourceOutputRefJson());
        if (output != null) requirePrivateOutput(scope, output);
        if (row.getSourceType() == null) {
            if (row.getSourceId() != null || row.getSourceVersion() != null) {
                throw failure(Reason.STORAGE_UNAVAILABLE);
            }
            return;
        }
        SourceRef source = persistedSourceRef(
                row.getSourceType(), row.getSourceId(), row.getSourceVersion());
        switch (source.sourceType()) {
            case "FILE" -> requireFileVersion(
                    scope, source.sourceId(), source.version(), "sourceRef");
            case "CONVERSATION" -> {
                if (row.getConversationId() != null
                        && !row.getConversationId().equals(source.sourceId())) {
                    throw failure(Reason.STORAGE_UNAVAILABLE);
                }
                requireConversation(scope, source.sourceId());
            }
            case "TASK" -> {
                if (row.getTaskId() != null && !row.getTaskId().equals(source.sourceId())) {
                    throw failure(Reason.STORAGE_UNAVAILABLE);
                }
                requireTask(scope, source.sourceId());
            }
            case "EXECUTION_OUTPUT" -> {
                if (output == null || !source.sourceId().equals(output.executionId())) {
                    throw failure(Reason.STORAGE_UNAVAILABLE);
                }
                requirePrivateOutput(scope, output);
            }
            default -> throw failure(Reason.STORAGE_UNAVAILABLE);
        }
    }

    private void validateEditableSources(OwnerScope scope, EditableFields fields) {
        if (fields.targetAgentId() != null) {
            try {
                AgentRuntimeDTO target = agents.requireApiKeyOwnedAgent(
                        scope.clientId(), scope.ownerJiacn(), fields.targetAgentId());
                if (target == null || !fields.targetAgentId().equals(target.getAgentId())
                        || (target.getOwnerJiacn() != null
                            && !scope.ownerJiacn().equals(target.getOwnerJiacn()))) {
                    throw sourceUnavailable("targetAgentId", "AGENT");
                }
            } catch (Failure failure) {
                throw failure;
            } catch (RuntimeException denied) {
                throw sourceUnavailable("targetAgentId", "AGENT");
            }
        }
        for (InputSelection input : fields.inputs()) {
            requireFileVersion(scope, input.fileId(), input.version(), "inputs");
        }
    }

    private void requireFileVersion(OwnerScope scope, String fileId, int version, String field) {
        try {
            PersonalWorkspaceFileEntity file = workspace.findFile(
                    scope.tenantId(), scope.clientId(), scope.ownerJiacn(), fileId);
            if (file == null || !"ACTIVE".equals(file.getState())
                    || workspace.findVersion(scope.tenantId(), scope.clientId(),
                            scope.ownerJiacn(), fileId, version) == null) {
                throw sourceUnavailable(field, "FILE");
            }
        } catch (Failure failure) {
            throw failure;
        } catch (RuntimeException denied) {
            throw sourceUnavailable(field, "FILE");
        }
    }

    private WorkspaceConversationAccessService.ConversationView requireConversation(
            OwnerScope scope, String conversationId) {
        try {
            if (conversations == null) {
                throw sourceUnavailable("conversationId", "CONVERSATION");
            }
            WorkspaceConversationAccessService.ConversationView view = conversations.requireAccessible(
                    new WorkspaceConversationAccessService.Scope(scope.tenantId(),
                            scope.clientId(), scope.ownerJiacn()), conversationId);
            if (view == null || !conversationId.equals(view.conversationId())) {
                throw sourceUnavailable("conversationId", "CONVERSATION");
            }
            return view;
        } catch (Failure failure) {
            throw failure;
        } catch (RuntimeException denied) {
            throw sourceUnavailable("conversationId", "CONVERSATION");
        }
    }

    private void requireTask(OwnerScope scope, String taskId) {
        try {
            AgentTaskMetaEntity task = tasks.findByTaskIdInOwnerScope(
                    scope.tenantId(), scope.clientId(), scope.ownerJiacn(), taskId);
            if (task == null || !taskId.equals(task.getTaskId())
                    || !scope.tenantId().equals(task.getTenantId())
                    || !scope.clientId().equals(task.getClientId())
                    || !scope.ownerJiacn().equals(task.getOwnerJiacn())) {
                throw sourceUnavailable("taskId", "TASK");
            }
        } catch (Failure failure) {
            throw failure;
        } catch (RuntimeException denied) {
            throw sourceUnavailable("taskId", "TASK");
        }
    }

    private void requirePrivateOutput(OwnerScope scope, SourceOutputRef source) {
        try {
            if (!drafts.privateCommittedOutputExists(scope.tenantId(), scope.clientId(),
                    scope.ownerJiacn(), source.executionId(), source.outputId(),
                    source.fileId(), source.fileVersion())) {
                throw sourceUnavailable("sourceOutputRef", "EXECUTION_OUTPUT");
            }
        } catch (Failure failure) {
            throw failure;
        } catch (RuntimeException denied) {
            throw sourceUnavailable("sourceOutputRef", "EXECUTION_OUTPUT");
        }
    }

    private DraftView view(OwnerScope scope, HallRequestDraftEntity row) {
        requirePersistedScope(scope, row); requirePersistedLifecycle(row);
        return new DraftView(row.getDraftId(), row.getRevision(), row.getState(), row.getUpdatedAt(),
                row.getKind(), editable(row), sourceSummary(row), row.getSubmissionRef());
    }

    private DraftSummary summary(OwnerScope scope, HallRequestDraftEntity row) {
        requirePersistedScope(scope, row); requirePersistedLifecycle(row);
        if (!"EDITING".equals(row.getState())) throw failure(Reason.STORAGE_UNAVAILABLE);
        return new DraftSummary(row.getDraftId(), row.getRevision(), row.getState(), row.getUpdatedAt(),
                row.getKind(), row.getTitle(), row.getTargetAgentId(), row.getOutputMime(),
                sourceSummary(row));
    }

    private EditableFields editable(HallRequestDraftEntity row) {
        try {
            return validEditable(new EditableFields(row.getTitle(), row.getInstruction(),
                    row.getTargetAgentId(), row.getOutputMime(),
                    parse(row.getInputsJson(), INPUT_LIST)));
        } catch (Failure corrupt) {
            throw failure(Reason.STORAGE_UNAVAILABLE);
        }
    }

    private SourceSummary sourceSummary(HallRequestDraftEntity row) {
        try {
            String originRef = requireExact(row.getOriginRef(), "originRef", 120);
            SourceRef source = row.getSourceType() == null ? null
                    : persistedSourceRef(
                            row.getSourceType(), row.getSourceId(), row.getSourceVersion());
            if (source == null && (row.getSourceId() != null || row.getSourceVersion() != null)) {
                throw failure(Reason.STORAGE_UNAVAILABLE);
            }
            return new SourceSummary(originRef, source,
                    optionalId(row.getCaseId(), "caseId", 100),
                    optionalId(row.getTaskId(), "taskId", 100),
                    optionalId(row.getConversationId(), "conversationId", 100),
                    persistedSourceOutput(row.getSourceOutputRefJson()));
        } catch (Failure corrupt) {
            throw failure(Reason.STORAGE_UNAVAILABLE);
        }
    }

    private void requirePersistedScope(OwnerScope scope, HallRequestDraftEntity row) {
        if (row == null || !scope.tenantId().equals(row.getTenantId())
                || !scope.clientId().equals(row.getClientId())
                || !scope.ownerJiacn().equals(row.getOwnerJiacn())) {
            throw failure(Reason.STORAGE_UNAVAILABLE);
        }
    }

    private void requirePersistedLifecycle(HallRequestDraftEntity row) {
        if (!KINDS.contains(row.getKind()) || row.getRevision() == null
                || row.getRevision() < 1 || row.getRevision() > MAX_SAFE_REVISION
                || row.getUpdatedAt() == null || row.getUpdatedAt() < 0
                || !("EDITING".equals(row.getState()) || "DISCARDED".equals(row.getState()))) {
            throw failure(Reason.STORAGE_UNAVAILABLE);
        }
        if ("EDITING".equals(row.getState())
                && (row.getDiscardKey() != null || row.getDiscardHash() != null)) {
            throw failure(Reason.STORAGE_UNAVAILABLE);
        }
        if ("DISCARDED".equals(row.getState())
                && (row.getDiscardKey() == null || row.getDiscardHash() == null)) {
            throw failure(Reason.STORAGE_UNAVAILABLE);
        }
    }

    private String requestHash(ValidCreate valid) {
        return hash("CREATE", valid.kind(), valid.originRef(), json(valid.sourceRef()),
                valid.caseId(), valid.taskId(), valid.conversationId(),
                valid.editableFields().title(), valid.editableFields().instruction(),
                valid.editableFields().targetAgentId(), valid.editableFields().outputMime(),
                json(valid.editableFields().inputs()), json(valid.sourceOutputRef()));
    }

    private static Set<String> validatedMimes(PersonalWorkspaceExecutionProperties properties) {
        if (properties == null || properties.allowedMimeTypes() == null) {
            throw new IllegalStateException("Execution MIME allow-list is unavailable");
        }
        Set<String> result = new HashSet<>();
        for (String mime : properties.allowedMimeTypes()) {
            if (mime == null || mime.isBlank() || !mime.equals(mime.strip())
                    || mime.length() > 160 || mime.chars().anyMatch(Character::isISOControl)
                    || !result.add(mime)) {
                throw new IllegalStateException("Execution MIME allow-list is invalid");
            }
        }
        return Set.copyOf(result);
    }

    private static String json(Object value) {
        if (value == null) return null;
        try { return JsonUtil.getMapper().writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("Canonical JSON failed", failure); }
    }

    private static <T> T parse(String value, TypeReference<T> type) {
        if (value == null) throw failure(Reason.STORAGE_UNAVAILABLE);
        try { return JsonUtil.getMapper().readValue(value, type); }
        catch (Exception corrupt) { throw failure(Reason.STORAGE_UNAVAILABLE); }
    }

    private static String hash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = Objects.requireNonNullElse(value, "").getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean secureEquals(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decodeCursor(String cursor) {
        if (cursor == null) return null;
        requireId(cursor, "cursor", 300);
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int split = decoded.indexOf('\n');
            if (split < 1 || split != decoded.lastIndexOf('\n')) throw failure(Reason.BAD_REQUEST);
            long updatedAt = Long.parseLong(decoded.substring(0, split));
            String draftId = requireId(decoded.substring(split + 1), "cursorDraftId", 100);
            if (updatedAt < 0) throw failure(Reason.BAD_REQUEST);
            return new Cursor(updatedAt, draftId);
        } catch (Failure failure) {
            throw failure;
        } catch (RuntimeException malformed) {
            throw failure(Reason.BAD_REQUEST);
        }
    }

    private static String encodeCursor(long updatedAt, String draftId) {
        if (updatedAt < 0) throw failure(Reason.STORAGE_UNAVAILABLE);
        requireId(draftId, "draftId", 100);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (updatedAt + "\n" + draftId).getBytes(StandardCharsets.UTF_8));
    }

    private long now() {
        long value = clock.getAsLong();
        if (value < 0) throw failure(Reason.STORAGE_UNAVAILABLE);
        return value;
    }

    private static void requireScope(OwnerScope scope) {
        if (scope == null || !"0".equals(scope.tenantId())) throw failure(Reason.BAD_REQUEST);
        requireId(scope.clientId(), "clientId", 50);
        requireId(scope.ownerJiacn(), "ownerJiacn", 50);
        if ("0".equals(scope.ownerJiacn())) throw failure(Reason.BAD_REQUEST);
    }

    private static void requireMutableRevision(long revision) {
        if (revision < 1 || revision >= MAX_SAFE_REVISION) throw failure(Reason.BAD_REQUEST);
    }

    private static String requireEnum(String value, Set<String> allowed, String field) {
        String exact = requireExact(value, field, 32);
        if (!allowed.contains(exact)) throw failure(Reason.BAD_REQUEST);
        return exact;
    }
    private static String requireId(String value, String field, int max) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.codePointCount(0, value.length()) > max
                || value.chars().anyMatch(Character::isISOControl)) {
            throw failure(Reason.BAD_REQUEST);
        }
        return value;
    }
    private static String optionalId(String value, String field, int max) {
        return value == null ? null : requireId(value, field, max);
    }
    private static String requireExact(String value, String field, int max) {
        if (value == null || value.isEmpty() || !value.equals(value.strip())
                || value.codePointCount(0, value.length()) > max
                || value.chars().anyMatch(Character::isISOControl)) {
            throw failure(Reason.BAD_REQUEST);
        }
        return value;
    }
    private static String optionalExact(String value, String field, int max) {
        return value == null ? null : requireExact(value, field, max);
    }
    private static String optionalText(String value, String field, int max) {
        if (value == null) return null;
        if (value.codePointCount(0, value.length()) > max
                || value.chars().anyMatch(character -> Character.isISOControl(character)
                    && character != '\n' && character != '\r' && character != '\t')) {
            throw failure(Reason.BAD_REQUEST);
        }
        return value;
    }
    private SourceOutputRef persistedSourceOutput(String value) {
        if (value == null) return null;
        try {
            return validSourceOutput(parse(value, SOURCE_OUTPUT));
        } catch (Failure corrupt) {
            throw failure(Reason.STORAGE_UNAVAILABLE);
        }
    }

    private SourceRef persistedSourceRef(String type, String id, Integer version) {
        try {
            return validSourceRef(new SourceRef(type, id, version));
        } catch (Failure corrupt) {
            throw failure(Reason.STORAGE_UNAVAILABLE);
        }
    }

    private static String requirePersistedId(String value, String field) {
        try {
            return requireId(value, field, 100);
        } catch (Failure corrupt) {
            throw failure(Reason.STORAGE_UNAVAILABLE);
        }
    }
    private static Failure sourceUnavailable(String field, String sourceType) {
        return new Failure(Reason.SOURCE_UNAVAILABLE,
                Map.of("field", field, "sourceType", sourceType));
    }
    private static Failure failure(Reason reason) { return new Failure(reason); }

    private record Cursor(long updatedAt, String draftId) { }
    private record ValidCreate(String kind, String originRef, SourceRef sourceRef,
            String caseId, String taskId, String conversationId,
            EditableFields editableFields, SourceOutputRef sourceOutputRef) { }
}
