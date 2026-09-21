package cn.jia.agent.service.impl;

import cn.jia.agent.config.PersonalWorkspaceExecutionProperties;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.HallPrivateCaseDao;
import cn.jia.agent.dao.HallRequestDraftDao;
import cn.jia.agent.dao.PersonalWorkspaceDao;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.HallCaseExecutionEntity;
import cn.jia.agent.entity.HallExecutionResultRow;
import cn.jia.agent.entity.HallPrivateCaseEntity;
import cn.jia.agent.entity.HallRequestDraftEntity;
import cn.jia.agent.entity.PersonalWorkspaceFileEntity;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.HallRequestDraftService;
import cn.jia.agent.service.HallTaskCreationService;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Hall transaction boundary: draft -> private case or existing task-root -> files.
 * TASK_CREATE joins the existing task application transaction; no Provider runs inside submission.
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
    private final HallPrivateCaseDao cases;
    private final PersonalWorkspaceExecutionService executions;
    private final PersonalWorkspaceDao workspace;
    private final AgentTaskMetaDao tasks;
    private final AgentService agents;
    private final WorkspaceConversationAccessService conversations;
    private final Set<String> allowedOutputMimes;
    private final LongSupplier clock;
    private final HallTaskCreationService taskCreation;

    @Inject
    public HallRequestDraftServiceImpl(HallRequestDraftDao drafts, HallPrivateCaseDao cases,
            PersonalWorkspaceExecutionService executions, PersonalWorkspaceDao workspace,
            AgentTaskMetaDao tasks, AgentService agents,
            ObjectProvider<WorkspaceConversationAccessService> conversations,
            PersonalWorkspaceExecutionProperties executionProperties,
            HallTaskCreationService taskCreation) {
        this(drafts, cases, executions, workspace, tasks, agents,
                conversations == null ? null : conversations.getIfAvailable(),
                executionProperties, System::currentTimeMillis, taskCreation);
    }

    HallRequestDraftServiceImpl(HallRequestDraftDao drafts, HallPrivateCaseDao cases,
            PersonalWorkspaceExecutionService executions, PersonalWorkspaceDao workspace,
            AgentTaskMetaDao tasks, AgentService agents,
            WorkspaceConversationAccessService conversations,
            PersonalWorkspaceExecutionProperties executionProperties, LongSupplier clock) {
        this(drafts, cases, executions, workspace, tasks, agents, conversations,
                executionProperties, clock, null);
    }

    HallRequestDraftServiceImpl(HallRequestDraftDao drafts, HallPrivateCaseDao cases,
            PersonalWorkspaceExecutionService executions, PersonalWorkspaceDao workspace,
            AgentTaskMetaDao tasks, AgentService agents,
            WorkspaceConversationAccessService conversations,
            PersonalWorkspaceExecutionProperties executionProperties, LongSupplier clock,
            HallTaskCreationService taskCreation) {
        this.taskCreation = taskCreation;
        this.drafts = Objects.requireNonNull(drafts, "drafts");
        this.cases = Objects.requireNonNull(cases, "cases");
        this.executions = Objects.requireNonNull(executions, "executions");
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

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public SubmissionReceipt submit(OwnerScope scope, String draftId, long expectedRevision,
            boolean authorizationAcknowledgement, String idempotencyKey) {
        requireScope(scope); requireId(draftId, "draftId", 100);
        requireMutableRevision(expectedRevision); requireId(idempotencyKey, "Idempotency-Key", 100);
        if (!authorizationAcknowledgement) throw failure(Reason.BAD_REQUEST);
        String submitHash = hash("SUBMIT", draftId, Long.toString(expectedRevision), "true");

        HallRequestDraftEntity replay = drafts.findBySubmitKey(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), idempotencyKey);
        if (replay != null) return matchingSubmitReplay(scope, replay, draftId, idempotencyKey, submitHash);

        HallRequestDraftEntity current = drafts.lock(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), draftId);
        if (current == null) throw failure(Reason.NOT_FOUND);
        requirePersistedScope(scope, current); requirePersistedLifecycle(current);
        if ("SUBMITTED".equals(current.getState())) {
            return matchingSubmitReplay(scope, current, draftId, idempotencyKey, submitHash);
        }
        if (!"EDITING".equals(current.getState())) throw failure(Reason.STATE_CONFLICT);
        if (current.getRevision() == null || current.getRevision() != expectedRevision) {
            throw failure(Reason.REVISION_CHANGED);
        }
        EditableFields fields = editable(current);
        if ("TASK_CREATE".equals(current.getKind())) requireTaskCreate(current, fields);
        else requireComplete(fields);
        validatePersistedSources(scope, current, fields);
        long submittedAt = now();
        int reserved = drafts.reserveSubmitIntent(scope.tenantId(), scope.clientId(),
                scope.ownerJiacn(), draftId, expectedRevision, idempotencyKey, submitHash, submittedAt);
        if (reserved != 1) {
            replay = drafts.findBySubmitKey(
                    scope.tenantId(), scope.clientId(), scope.ownerJiacn(), idempotencyKey);
            if (replay != null) return matchingSubmitReplay(scope, replay, draftId, idempotencyKey, submitHash);
            throw mutationFailure(scope, draftId, expectedRevision);
        }

        SubmissionWork work = switch (current.getKind()) {
            case "CREATE" -> submitCreate(scope, current, fields, idempotencyKey, submittedAt);
            case "REVISION" -> submitRevision(scope, current, fields, idempotencyKey, submittedAt);
            case "TASK_ACTION" -> submitTaskAction(scope, current, fields, idempotencyKey);
            case "TASK_CREATE" -> {
                TaskReference task = taskCreation.create(scope, fields.title(), fields.instruction());
                if (task == null || !safePersistedId(task.taskId(), 100)) throw failure(Reason.STORAGE_UNAVAILABLE);
                yield new SubmissionWork(null, new SubmissionReference("TASK", task.taskId()), null);
            }
            default -> throw failure(Reason.SUBMISSION_UNAVAILABLE);
        };
        int changed = drafts.markSubmitted(scope.tenantId(), scope.clientId(), scope.ownerJiacn(),
                draftId, expectedRevision, idempotencyKey, submitHash, work.caseId(),
                work.reference().sourceId(), work.execution() == null ? null : work.execution().executionId(), submittedAt);
        if (changed != 1) throw failure(Reason.STORAGE_UNAVAILABLE);
        HallRequestDraftEntity stored = drafts.find(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), draftId);
        if (stored == null) throw failure(Reason.STORAGE_UNAVAILABLE);
        return matchingSubmitReplay(scope, stored, draftId, idempotencyKey, submitHash);
    }

    @Override
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public SubmissionReceipt getSubmissionByIdempotencyKey(OwnerScope scope, String idempotencyKey) {
        requireScope(scope); requireId(idempotencyKey, "Idempotency-Key", 100);
        HallRequestDraftEntity row = drafts.findBySubmitKey(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), idempotencyKey);
        if (row == null || !"SUBMITTED".equals(row.getState())) throw failure(Reason.NOT_FOUND);
        requirePersistedScope(scope, row); requirePersistedLifecycle(row);
        return receipt(scope, row);
    }

    @Override
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public CaseView getCase(OwnerScope scope, String caseId) {
        requireScope(scope); requireId(caseId, "caseId", 100);
        HallPrivateCaseEntity privateCase = cases.find(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), caseId);
        if (privateCase == null) throw failure(Reason.NOT_FOUND);
        requireCaseScope(scope, privateCase);
        List<HallCaseExecutionEntity> rows = cases.listExecutions(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), caseId);
        if (rows == null || rows.isEmpty()
                || privateCase.getRevision() == null || privateCase.getRevision() != rows.size()) {
            throw failure(Reason.STORAGE_UNAVAILABLE);
        }
        List<CaseExecutionView> views = new ArrayList<>(rows.size());
        Set<String> priorExecutions = new HashSet<>();
        long expected = 1;
        for (HallCaseExecutionEntity row : rows) {
            requireCaseExecutionScope(scope, caseId, row);
            if (row.getRevisionNo() == null || row.getRevisionNo() != expected++) {
                throw failure(Reason.STORAGE_UNAVAILABLE);
            }
            SourceOutputRef source = persistedSourceOutput(row.getSourceOutputRefJson());
            if ((row.getRevisionNo() == 1
                        && (row.getParentExecutionId() != null || source != null))
                    || (row.getRevisionNo() > 1
                        && (!safePersistedId(row.getParentExecutionId(), 100) || source == null
                            || !row.getParentExecutionId().equals(source.executionId())
                            || !priorExecutions.contains(row.getParentExecutionId())))) {
                throw failure(Reason.STORAGE_UNAVAILABLE);
            }
            PersonalWorkspaceExecutionService.ExecutionView execution = execution(scope, row.getExecutionId());
            if (!"PRIVATE".equals(execution.executionMode())
                    || !priorExecutions.add(row.getExecutionId())) {
                throw failure(Reason.STORAGE_UNAVAILABLE);
            }
            views.add(new CaseExecutionView(row.getRevisionNo(), row.getParentExecutionId(),
                    source, execution));
        }
        return new CaseView(privateCase.getCaseId(), privateCase.getTitle(), privateCase.getRevision(),
                views, List.of("VIEW", "CREATE_REVISION"),
                new CaseSourceRef(privateCase.getOriginRef()));
    }

    @Override
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public ExecutionResultsView getExecutionResults(OwnerScope scope, String executionId) {
        requireScope(scope); requireId(executionId, "executionId", 100);
        List<HallExecutionResultRow> rows;
        try {
            rows = drafts.listPrivateExecutionResults(
                    scope.tenantId(), scope.clientId(), scope.ownerJiacn(), executionId);
        } catch (RuntimeException unavailable) {
            throw new Failure(Reason.STORAGE_UNAVAILABLE, unavailable);
        }
        if (rows == null) throw failure(Reason.STORAGE_UNAVAILABLE);
        if (rows.isEmpty()) throw failure(Reason.NOT_FOUND);

        HallExecutionResultRow first = rows.getFirst();
        requireResultExecution(scope, executionId, first);
        String state = first.getExecutionState();
        for (HallExecutionResultRow row : rows) {
            requireResultExecution(scope, executionId, row);
            if (!Objects.equals(first.getTaskId(), row.getTaskId())
                    || !Objects.equals(first.getRunId(), row.getRunId())
                    || !state.equals(row.getExecutionState())) {
                throw failure(Reason.STORAGE_UNAVAILABLE);
            }
        }

        if (!"OUTPUT_COMMITTED".equals(state)) {
            if (rows.stream().anyMatch(HallRequestDraftServiceImpl::isCommittedResultRow)) {
                throw failure(Reason.STORAGE_UNAVAILABLE);
            }
            return new ExecutionResultsView(executionId, state, null, List.of(), List.of());
        }
        if (rows.stream().anyMatch(row -> row.getOutputId() == null)) {
            throw failure(Reason.STORAGE_UNAVAILABLE);
        }

        List<HallExecutionResultRow> ordered = rows.stream()
                .sorted(Comparator.comparing(HallExecutionResultRow::getOutputId))
                .toList();
        Set<String> outputIds = new HashSet<>();
        List<ResultItemView> items = new ArrayList<>(ordered.size());
        boolean revisable = false;
        for (HallExecutionResultRow row : ordered) {
            requireCommittedResultRow(row);
            if (!outputIds.add(row.getOutputId())) throw failure(Reason.STORAGE_UNAVAILABLE);
            boolean available = isAvailableResult(row);
            revisable |= available;
            items.add(new ResultItemView(row.getOutputId(), row.getWorkspaceFileId(),
                    row.getWorkspaceFileVersion(), row.getOutputContentMimeType(),
                    row.getOutputOriginalFilename(), row.getOutputByteLength(),
                    row.getOutputContentHash(), available ? "AVAILABLE" : "UNAVAILABLE"));
        }
        List<String> actions = revisable
                ? List.of("VIEW", "CREATE_REVISION") : List.of("VIEW");
        return new ExecutionResultsView(executionId, state, resultManifestId(first, ordered),
                items, actions);
    }

    private SubmissionWork submitCreate(OwnerScope scope, HallRequestDraftEntity draft,
            EditableFields fields, String key, long submittedAt) {
        String caseId = "hpc_" + UUID.randomUUID().toString().replace("-", "");
        HallPrivateCaseEntity privateCase = new HallPrivateCaseEntity().setCaseId(caseId)
                .setTenantId(scope.tenantId()).setClientId(scope.clientId())
                .setOwnerJiacn(scope.ownerJiacn()).setTitle(fields.title())
                .setOriginRef(draft.getOriginRef()).setRevision(1L)
                .setCreatedAt(submittedAt).setUpdatedAt(submittedAt);
        cases.insert(privateCase);
        PersonalWorkspaceExecutionService.ExecutionView execution = createExecution(
                scope, draft, fields, key, fields.inputs());
        cases.insertExecution(relation(scope, caseId, execution.executionId(), 1L,
                null, null, submittedAt));
        return new SubmissionWork(caseId,
                new SubmissionReference("PRIVATE_CASE", caseId), execution);
    }

    private SubmissionWork submitRevision(OwnerScope scope, HallRequestDraftEntity draft,
            EditableFields fields, String key, long submittedAt) {
        SourceOutputRef source = persistedSourceOutput(draft.getSourceOutputRefJson());
        if (source == null) throw failure(Reason.STORAGE_UNAVAILABLE);
        HallCaseExecutionEntity parent = cases.findByExecution(scope.tenantId(), scope.clientId(),
                scope.ownerJiacn(), source.executionId());
        String requestedCase = draft.getCaseId();
        if (requestedCase != null && parent != null && !requestedCase.equals(parent.getCaseId())) {
            throw sourceUnavailable("caseId", "PRIVATE_CASE");
        }

        HallPrivateCaseEntity privateCase;
        long nextRevision;
        if (requestedCase != null || parent != null) {
            String caseId = requestedCase != null ? requestedCase : parent.getCaseId();
            privateCase = cases.lock(scope.tenantId(), scope.clientId(), scope.ownerJiacn(), caseId);
            if (privateCase == null) throw sourceUnavailable("caseId", "PRIVATE_CASE");
            requireCaseScope(scope, privateCase);
            parent = cases.findByExecution(scope.tenantId(), scope.clientId(),
                    scope.ownerJiacn(), source.executionId());
            if (parent == null || !caseId.equals(parent.getCaseId())) {
                throw sourceUnavailable("sourceOutputRef", "EXECUTION_OUTPUT");
            }
            requireCaseExecutionScope(scope, caseId, parent);
            if (privateCase.getRevision() >= MAX_SAFE_REVISION) {
                throw failure(Reason.STATE_CONFLICT);
            }
            nextRevision = privateCase.getRevision() + 1;
        } else {
            if (!drafts.lockPrivateCommittedOutputExists(scope.tenantId(), scope.clientId(),
                    scope.ownerJiacn(), source.executionId(), source.outputId(),
                    source.fileId(), source.fileVersion())) {
                throw sourceUnavailable("sourceOutputRef", "EXECUTION_OUTPUT");
            }
            parent = cases.findByExecution(scope.tenantId(), scope.clientId(),
                    scope.ownerJiacn(), source.executionId());
            if (parent != null) throw failure(Reason.EXECUTION_CONFLICT);
            String caseId = "hpc_" + UUID.randomUUID().toString().replace("-", "");
            privateCase = new HallPrivateCaseEntity().setCaseId(caseId)
                    .setTenantId(scope.tenantId()).setClientId(scope.clientId())
                    .setOwnerJiacn(scope.ownerJiacn()).setTitle(fields.title())
                    .setOriginRef(draft.getOriginRef()).setRevision(1L)
                    .setCreatedAt(submittedAt).setUpdatedAt(submittedAt);
            cases.insert(privateCase);
            cases.insertExecution(relation(scope, caseId, source.executionId(), 1L,
                    null, null, submittedAt));
            parent = cases.findByExecution(scope.tenantId(), scope.clientId(),
                    scope.ownerJiacn(), source.executionId());
            nextRevision = 2L;
        }

        List<InputSelection> inputs = revisionInputs(fields.inputs(), source);
        PersonalWorkspaceExecutionService.ExecutionView execution = createExecution(
                scope, draft, fields, key, inputs);
        cases.insertExecution(relation(scope, privateCase.getCaseId(), execution.executionId(),
                nextRevision, source.executionId(), json(source), submittedAt));
        int updated = cases.updateRevision(scope.tenantId(), scope.clientId(), scope.ownerJiacn(),
                privateCase.getCaseId(), nextRevision - 1, nextRevision, fields.title(), submittedAt);
        if (updated != 1) throw failure(Reason.EXECUTION_CONFLICT);
        return new SubmissionWork(privateCase.getCaseId(),
                new SubmissionReference("PRIVATE_CASE", privateCase.getCaseId()), execution);
    }

    private SubmissionWork submitTaskAction(OwnerScope scope, HallRequestDraftEntity draft,
            EditableFields fields, String key) {
        if (draft.getTaskId() == null || draft.getConversationId() == null) {
            throw failure(Reason.BAD_REQUEST);
        }
        PersonalWorkspaceExecutionService.ExecutionView execution = createExecution(
                scope, draft, fields, key, fields.inputs());
        if (!"TASK".equals(execution.executionMode())
                || !draft.getTaskId().equals(execution.businessTaskId())) {
            throw failure(Reason.STORAGE_UNAVAILABLE);
        }
        return new SubmissionWork(null,
                new SubmissionReference("TASK", draft.getTaskId()), execution);
    }

    private PersonalWorkspaceExecutionService.ExecutionView createExecution(OwnerScope scope,
            HallRequestDraftEntity draft, EditableFields fields, String key,
            List<InputSelection> inputs) {
        PersonalWorkspaceExecutionService.CreateCommand command =
                new PersonalWorkspaceExecutionService.CreateCommand(
                        draft.getConversationId(), fields.targetAgentId(), draft.getTaskId(),
                        fields.instruction(), fields.outputMime(), inputs.stream().map(input ->
                                new PersonalWorkspaceExecutionService.InputSelection(
                                        input.fileId(), input.version())).toList(), null);
        try {
            return executions.create(executionScope(scope), command, key);
        } catch (PersonalWorkspaceExecutionService.Failure failure) {
            throw switch (failure.getReason()) {
                case IDEMPOTENCY_CONFLICT -> failure(Reason.IDEMPOTENCY_CONFLICT);
                case TASK_CONFLICT, OUTPUT_CONFLICT -> failure(Reason.EXECUTION_CONFLICT);
                case STORAGE_UNAVAILABLE -> failure(Reason.STORAGE_UNAVAILABLE);
                default -> sourceUnavailable("submission", "EXECUTION");
            };
        } catch (RuntimeException unavailable) {
            throw new Failure(Reason.STORAGE_UNAVAILABLE, unavailable);
        }
    }

    private SubmissionReceipt matchingSubmitReplay(OwnerScope scope, HallRequestDraftEntity row,
            String draftId, String key, String hash) {
        requirePersistedScope(scope, row);
        if (!draftId.equals(row.getDraftId()) || !secureEquals(row.getSubmitKey(), key)
                || !secureEquals(row.getSubmitHash(), hash)) {
            throw failure(Reason.IDEMPOTENCY_CONFLICT);
        }
        if (!"SUBMITTED".equals(row.getState())) throw failure(Reason.STATE_CONFLICT);
        requirePersistedLifecycle(row);
        return receipt(scope, row);
    }

    private SubmissionReceipt receipt(OwnerScope scope, HallRequestDraftEntity row) {
        if ("TASK_CREATE".equals(row.getKind())) {
            if (taskCreation == null) throw failure(Reason.STORAGE_UNAVAILABLE);
            String taskId = requirePersistedId(row.getSubmissionRef(), "submissionRef");
            TaskReference task = taskCreation.get(scope, taskId);
            if (task == null || !taskId.equals(task.taskId())) throw failure(Reason.STORAGE_UNAVAILABLE);
            return new SubmissionReceipt(new SubmissionReference("TASK", taskId), null, task, row.getUpdatedAt());
        }
        String executionId = requirePersistedId(row.getSubmittedExecutionId(), "submittedExecutionId");
        String sourceId = requirePersistedId(row.getSubmissionRef(), "submissionRef");
        String sourceType = switch (row.getKind()) {
            case "CREATE", "REVISION" -> "PRIVATE_CASE";
            case "TASK_ACTION" -> "TASK";
            default -> throw failure(Reason.STORAGE_UNAVAILABLE);
        };
        if ("PRIVATE_CASE".equals(sourceType) && !sourceId.equals(row.getCaseId())) {
            throw failure(Reason.STORAGE_UNAVAILABLE);
        }
        PersonalWorkspaceExecutionService.ExecutionView execution = execution(scope, executionId);
        if (("PRIVATE_CASE".equals(sourceType) && !"PRIVATE".equals(execution.executionMode()))
                || ("TASK".equals(sourceType) && (!"TASK".equals(execution.executionMode())
                    || !sourceId.equals(execution.businessTaskId())))) {
            throw failure(Reason.STORAGE_UNAVAILABLE);
        }
        return new SubmissionReceipt(new SubmissionReference(sourceType, sourceId),
                execution, null, row.getUpdatedAt());
    }

    private PersonalWorkspaceExecutionService.ExecutionView execution(OwnerScope scope,
            String executionId) {
        try {
            PersonalWorkspaceExecutionService.ExecutionView view = executions.get(
                    executionScope(scope), requirePersistedId(executionId, "executionId"));
            if (view == null || !executionId.equals(view.executionId())) {
                throw failure(Reason.STORAGE_UNAVAILABLE);
            }
            return view;
        } catch (Failure failure) {
            throw failure;
        } catch (PersonalWorkspaceExecutionService.Failure hidden) {
            throw new Failure(Reason.STORAGE_UNAVAILABLE, hidden);
        }
    }

    private static HallCaseExecutionEntity relation(OwnerScope scope, String caseId,
            String executionId, long revision, String parentExecutionId,
            String sourceOutputJson, long createdAt) {
        return new HallCaseExecutionEntity().setTenantId(scope.tenantId())
                .setClientId(scope.clientId()).setOwnerJiacn(scope.ownerJiacn())
                .setCaseId(caseId).setExecutionId(executionId).setRevisionNo(revision)
                .setParentExecutionId(parentExecutionId)
                .setSourceOutputRefJson(sourceOutputJson).setCreatedAt(createdAt);
    }

    private static List<InputSelection> revisionInputs(List<InputSelection> inputs,
            SourceOutputRef source) {
        List<InputSelection> result = new ArrayList<>(inputs);
        InputSelection pinned = new InputSelection(source.fileId(), source.fileVersion());
        for (InputSelection input : inputs) {
            if (input.fileId().equals(source.fileId())) {
                if (input.version() != source.fileVersion()) throw failure(Reason.BAD_REQUEST);
                return inputs;
            }
        }
        result.add(pinned);
        return List.copyOf(result);
    }

    private static PersonalWorkspaceExecutionService.OwnerScope executionScope(OwnerScope scope) {
        return new PersonalWorkspaceExecutionService.OwnerScope(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn());
    }

    private void requireTaskCreate(HallRequestDraftEntity row, EditableFields fields) {
        if (taskCreation == null) throw failure(Reason.SUBMISSION_UNAVAILABLE);
        if (fields.title() == null || fields.title().isBlank()) throw failure(Reason.BAD_REQUEST);
        // Existing taskPlanFor uses String.substring(0,30/200), not a new product-size gate.
        if (fields.title().length() > 30 || (fields.instruction() != null && fields.instruction().length() > 200)) {
            throw sourceUnavailable("title/instruction", "TASK_CREATE");
        }
        if (fields.targetAgentId() != null) throw sourceUnavailable("targetAgentId", "TASK_CREATE");
        if (fields.outputMime() != null) throw sourceUnavailable("outputMime", "TASK_CREATE");
        if (!fields.inputs().isEmpty()) throw sourceUnavailable("inputs", "TASK_CREATE");
        if (row.getSourceType() != null) throw sourceUnavailable("sourceRef", "TASK_CREATE");
        if (row.getConversationId() != null) throw sourceUnavailable("conversationId", "TASK_CREATE");
    }

    private static void requireComplete(EditableFields fields) {
        if (fields.title() == null || fields.title().isBlank()
                || fields.instruction() == null || fields.instruction().isBlank()
                || fields.targetAgentId() == null || fields.outputMime() == null) {
            throw failure(Reason.BAD_REQUEST);
        }
    }

    private static void requireCaseScope(OwnerScope scope, HallPrivateCaseEntity row) {
        if (row == null || !safePersistedId(row.getCaseId(), 100)
                || !safePersistedText(row.getTitle(), 200)
                || !safePersistedId(row.getOriginRef(), 120)
                || !scope.tenantId().equals(row.getTenantId())
                || !scope.clientId().equals(row.getClientId())
                || !scope.ownerJiacn().equals(row.getOwnerJiacn())
                || row.getRevision() == null || row.getRevision() < 1
                || row.getRevision() > MAX_SAFE_REVISION || row.getCreatedAt() == null
                || row.getUpdatedAt() == null || row.getCreatedAt() < 0
                || row.getUpdatedAt() < row.getCreatedAt()) {
            throw failure(Reason.STORAGE_UNAVAILABLE);
        }
    }

    private static void requireCaseExecutionScope(OwnerScope scope, String caseId,
            HallCaseExecutionEntity row) {
        if (row == null || !safePersistedId(row.getExecutionId(), 100)
                || !scope.tenantId().equals(row.getTenantId())
                || !scope.clientId().equals(row.getClientId())
                || !scope.ownerJiacn().equals(row.getOwnerJiacn())
                || !caseId.equals(row.getCaseId()) || row.getCreatedAt() == null
                || row.getCreatedAt() < 0) {
            throw failure(Reason.STORAGE_UNAVAILABLE);
        }
    }

    private static void requireResultExecution(OwnerScope scope, String executionId,
            HallExecutionResultRow row) {
        if (row == null || !executionId.equals(row.getExecutionId())
                || !scope.tenantId().equals(row.getTenantId())
                || !scope.clientId().equals(row.getClientId())
                || !scope.ownerJiacn().equals(row.getOwnerJiacn())
                || !"PRIVATE".equals(row.getExecutionMode())
                || !safePersistedId(row.getTaskId(), 100)
                || !safePersistedId(row.getRunId(), 100)
                || !Set.of("QUEUED", "INPUTS_REVOKED", "OUTPUT_COMMITTED", "FAILED")
                        .contains(row.getExecutionState())) {
            throw failure(Reason.STORAGE_UNAVAILABLE);
        }
    }

    private static boolean isCommittedResultRow(HallExecutionResultRow row) {
        return row != null && ("COMMITTED".equals(row.getOutputState())
                || row.getWorkspaceFileId() != null || row.getWorkspaceFileVersion() != null);
    }

    private static void requireCommittedResultRow(HallExecutionResultRow row) {
        if (row == null || !safePersistedId(row.getOutputId(), 100)
                || !safePersistedId(row.getWorkspaceFileId(), 100)
                || row.getWorkspaceFileVersion() == null || row.getWorkspaceFileVersion() < 1
                || !safeResultFilename(row.getOutputOriginalFilename())
                || !safeMime(row.getOutputContentMimeType())
                || row.getOutputByteLength() == null || row.getOutputByteLength() < 0
                || row.getOutputByteLength() > MAX_SAFE_REVISION
                || !safeSha256(row.getOutputContentHash())
                || !"COMMITTED".equals(row.getOutputState())
                || !"PENDING".equals(row.getPublicationState())) {
            throw failure(Reason.STORAGE_UNAVAILABLE);
        }
    }

    private static boolean isAvailableResult(HallExecutionResultRow row) {
        return "ACTIVE".equals(row.getFileState())
                && row.getOutputOriginalFilename().equals(row.getFileOriginalFilename())
                && row.getOutputContentMimeType().equals(row.getFileContentMimeType())
                && row.getOutputByteLength().equals(row.getFileByteLength())
                && row.getOutputContentHash().equals(row.getFileContentHash());
    }

    private static String resultManifestId(HallExecutionResultRow execution,
            List<HallExecutionResultRow> outputs) {
        StringBuilder source = new StringBuilder(execution.getTaskId()).append('\n')
                .append(execution.getRunId()).append('\n');
        for (HallExecutionResultRow output : outputs) {
            source.append(output.getOutputId()).append('\n')
                    .append(output.getOutputContentHash()).append('\n')
                    .append(output.getOutputByteLength()).append('\n');
        }
        return "pwe_m_" + plainSha(source.toString());
    }

    private static String plainSha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean safeResultFilename(String value) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.codePointCount(0, value.length()) <= 255
                && value.indexOf('/') < 0 && value.indexOf('\\') < 0
                && !value.chars().anyMatch(Character::isISOControl);
    }

    private static boolean safeMime(String value) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.length() <= 160 && value.indexOf('/') > 0
                && !value.chars().anyMatch(Character::isISOControl);
    }

    private static boolean safeSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
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

        if (caseId != null && !"REVISION".equals(kind)) throw failure(Reason.BAD_REQUEST);
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
        if (valid.caseId() != null) {
            HallPrivateCaseEntity privateCase = cases.find(
                    scope.tenantId(), scope.clientId(), scope.ownerJiacn(), valid.caseId());
            if (privateCase == null) throw sourceUnavailable("caseId", "PRIVATE_CASE");
            requireCaseScope(scope, privateCase);
            HallCaseExecutionEntity parent = cases.findByExecution(scope.tenantId(),
                    scope.clientId(), scope.ownerJiacn(), valid.sourceOutputRef().executionId());
            if (parent == null || !valid.caseId().equals(parent.getCaseId())) {
                throw sourceUnavailable("sourceOutputRef", "EXECUTION_OUTPUT");
            }
            requireCaseExecutionScope(scope, valid.caseId(), parent);
        }
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
        if (row.getCaseId() != null) {
            HallPrivateCaseEntity privateCase = cases.find(scope.tenantId(), scope.clientId(),
                    scope.ownerJiacn(), requirePersistedId(row.getCaseId(), "caseId"));
            if (privateCase == null) throw sourceUnavailable("caseId", "PRIVATE_CASE");
            requireCaseScope(scope, privateCase);
            HallCaseExecutionEntity parent = cases.findByExecution(scope.tenantId(),
                    scope.clientId(), scope.ownerJiacn(), output.executionId());
            if (parent == null || !row.getCaseId().equals(parent.getCaseId())) {
                throw sourceUnavailable("sourceOutputRef", "EXECUTION_OUTPUT");
            }
        }
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
                || !("EDITING".equals(row.getState()) || "DISCARDED".equals(row.getState())
                    || "SUBMITTED".equals(row.getState()))) {
            throw failure(Reason.STORAGE_UNAVAILABLE);
        }
        if ("EDITING".equals(row.getState())
                && (row.getSubmitKey() != null || row.getSubmitHash() != null
                    || row.getDiscardKey() != null || row.getDiscardHash() != null
                    || row.getSubmissionRef() != null || row.getSubmittedExecutionId() != null)) {
            throw failure(Reason.STORAGE_UNAVAILABLE);
        }
        if ("DISCARDED".equals(row.getState())
                && (row.getDiscardKey() == null || row.getDiscardHash() == null
                    || row.getSubmitKey() != null || row.getSubmitHash() != null
                    || row.getSubmissionRef() != null || row.getSubmittedExecutionId() != null)) {
            throw failure(Reason.STORAGE_UNAVAILABLE);
        }
        if ("SUBMITTED".equals(row.getState())
                && (row.getSubmitKey() == null || row.getSubmitHash() == null
                    || row.getSubmissionRef() == null
                    || ("TASK_CREATE".equals(row.getKind())
                        ? row.getSubmittedExecutionId() != null || row.getCaseId() != null || row.getTaskId() != null
                        : row.getSubmittedExecutionId() == null)
                    || row.getDiscardKey() != null || row.getDiscardHash() != null)) {
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
    private static boolean safePersistedId(String value, int max) {
        try { requireId(value, "persisted", max); return true; }
        catch (Failure corrupt) { return false; }
    }
    private static boolean safePersistedText(String value, int max) {
        try { optionalText(value, "persisted", max); return value != null; }
        catch (Failure corrupt) { return false; }
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
    private record SubmissionWork(String caseId, SubmissionReference reference,
            PersonalWorkspaceExecutionService.ExecutionView execution) { }
}
