package cn.jia.agent.service.impl;

import cn.jia.agent.config.PersonalWorkspaceExecutionProperties;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.dao.PersonalWorkspaceDao;
import cn.jia.agent.dao.PersonalWorkspaceExecutionDao;
import cn.jia.agent.dao.ControlledImageExecutionSourceV3Dao;
import cn.jia.agent.dao.ControlledImageBridgeOperationDao;
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
import cn.jia.agent.entity.ControlledImageExecutionSourceV3Entity;
import cn.jia.agent.entity.PersonalWorkspaceExecutionInputEntity;
import cn.jia.agent.entity.PersonalWorkspaceExecutionOutputEntity;
import cn.jia.agent.entity.PersonalWorkspaceFileEntity;
import cn.jia.agent.entity.PersonalWorkspaceVersionEntity;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.service.ControlledImageFollowupAuthorityService;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentWorkItemLeaseService;
import cn.jia.agent.service.AgentTaskArtifactService;
import cn.jia.agent.service.AgentTaskFormalDeliveryService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentTaskStateService;
import cn.jia.agent.entity.AgentTaskStateTransitionDTO;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.exception.AgentTaskStateException;
import cn.jia.chat.service.WorkspaceConversationAccessService;
import cn.jia.agent.service.PersonalWorkspaceStorage;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

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
    /** Security fence lease, renewable by the same authenticated runtime, not a performance timeout. */
    private static final long CONVERSATION_LEASE_MILLIS = 900_000L;
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final ObjectMapper V3_JSON=JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    @Value("${jia.agent.conversation-execution.enabled:false}")
    private boolean conversationExecutionEnabled;
    /** Source materials are a fixed bridge contract and never inherit the output allow-list. */
    private static final Set<String> SUPPORTED_INPUT_MIME_TYPES = Set.of(
            "image/png", "image/jpeg", "text/plain", "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation");
    /** Every runtime output type is independently configuration-gated; input support does not enable output. */
    private static final Set<String> SUPPORTED_OUTPUT_MIME_TYPES = Set.of(
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
    private AgentTaskExecutionGrantService conversationGrants;
    private AgentTaskProviderCostConsentServiceImpl controlledConsents;
    private ControlledImageFollowupAuthorityService followupAuthority;
    private ControlledImageExecutionSourceV3Dao followupSources;
    private ControlledImageBridgeOperationDao initialControlledOperations;
    @Autowired(required=false)
    public void setControlledImageFollowupV3(ControlledImageFollowupAuthorityService authority,
            ControlledImageExecutionSourceV3Dao sources) { this.followupAuthority=authority;this.followupSources=sources; }
    @Autowired(required=false)
    public void setInitialControlledImageV3(ControlledImageBridgeOperationDao operations) {
        this.initialControlledOperations=operations;
    }

    @Autowired(required = false)
    public void setControlledConsentLifecycle(AgentTaskProviderCostConsentServiceImpl consents) {
        this.controlledConsents=consents;
    }

    @Autowired(required = false)
    public void setConversationAdmission(AgentTaskExecutionGrantService grants,
            AgentTaskMutationTransaction transactions) {
        this.conversationGrants = Objects.requireNonNull(grants);
        this.taskMutations = Objects.requireNonNull(transactions);
    }
    private AgentTaskStateService taskStates;

    @Autowired(required = false)
    public void setTaskStateService(AgentTaskStateService taskStates) {
        this.taskStates = Objects.requireNonNull(taskStates, "taskStates");
    }

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
        String requestHash = valid.sourceOutputRef() == null
                ? hash("CREATE", valid.conversationId(), valid.targetAgentId(), valid.taskId(),
                        valid.instruction(), valid.outputContentMimeType(), selectionsWire(valid.inputs()))
                : hash("CREATE_REWORK", valid.conversationId(), valid.targetAgentId(), valid.taskId(),
                        valid.instruction(), valid.outputContentMimeType(), selectionsWire(valid.inputs()),
                        sourceOutputWire(valid.sourceOutputRef()));
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
            // TASK create already holds the task root through the authoritative lease transition.
            // Check the exact version only after that root and this file lock: detach/create follows
            // task root -> file too, so no file-to-task lock inversion or stale relationship grant.
            if (taskLease != null && !taskLinks.hasActiveExecutionInputLink(scope.tenantId(),
                    scope.clientId(), scope.ownerJiacn(), valid.taskId(), selected.fileId(),
                    selected.version())) {
                throw failure(Reason.NOT_FOUND);
            }
            PersonalWorkspaceVersionEntity version = workspace.findVersion(scope.tenantId(), scope.clientId(),
                    scope.ownerJiacn(), selected.fileId(), selected.version());
            if (version == null) throw failure(Reason.NOT_FOUND);
            // Source and deliverable formats are independent: a supported XLSX/PDF/image source
            // must not require that MIME to be enabled by the output allow-list.
            if (!SUPPORTED_INPUT_MIME_TYPES.contains(version.getContentMimeType())) {
                throw failure(Reason.CAPABILITY_UNAVAILABLE);
            }
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

    /** Fixed-version materialization from the live grant; paid generation still requires
     * persisted, server-issued cost authority before the execution is inserted. */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExecutionView createConversation(OwnerScope scope, ConversationCreate command) {
        validateOwnerScope(scope);
        if (command == null || conversationGrants == null || taskMutations == null || conversationAccess == null)
            throw failure(Reason.CAPABILITY_UNAVAILABLE);
        id(command.intentId(), "intentId", 90);
        id(command.grantId(), "grantId", 100);
        id(command.permittedOperation(), "permittedOperation", 40);
        int controlledProtocol=command.controlledImage()?command.controlledImageProtocolVersion():1;
        if (command.grantVersion() < 1 || command.assignmentRevision() < 0
                || !"GENERATE_IMAGE".equals(command.permittedOperation())
                || command.controlledImage()&&!Set.of(2,3).contains(controlledProtocol)
                || !command.controlledImage()&&controlledProtocol!=1)
            throw failure(Reason.BAD_REQUEST);
        ValidCreate valid=validCreate(new CreateCommand(command.conversationId(), command.targetAgentId(),
                command.taskId(), command.instruction(), command.outputContentMimeType(), List.of()));
        if (valid.taskId() == null || !Set.of("image/png", "image/jpeg").contains(valid.outputContentMimeType())
                || command.controlledImage() && !"image/png".equals(valid.outputContentMimeType()))
            throw failure(Reason.CAPABILITY_UNAVAILABLE);
        if (command.references().size()>(command.controlledImage()?16:32)) throw failure(Reason.BAD_REQUEST);
        if(controlledProtocol==3&&(followupSources==null||initialControlledOperations==null))
            throw failure(Reason.CAPABILITY_UNAVAILABLE);
        for (var ref:command.references()) {
            id(ref.fileId(),"fileId",100);
            if (ref.version()<1
                    || controlledProtocol==3 && !"REFERENCE".equals(ref.purpose())
                    || controlledProtocol!=3 && !Set.of("INPUT","REFERENCE").contains(ref.purpose())
                    || !Set.of("image/png","image/jpeg").contains(ref.contentMimeType())
                    || ref.byteLength()<(controlledProtocol==3?1:0) || ref.contentHash()==null
                    || !ref.contentHash().matches("[0-9a-f]{64}")) throw failure(Reason.BAD_REQUEST);
        }
        String key="conv_"+plainSha(command.intentId());
        String requestHash=hash("CONVERSATION",valid.conversationId(),valid.taskId(),
                valid.targetAgentId(),command.intentId(),command.grantId(),
                Long.toString(command.grantVersion()),Long.toString(command.assignmentRevision()),
                command.permittedOperation(),valid.instruction(),valid.outputContentMimeType(),
                command.controlledImage()?(controlledProtocol==3?"CONTROLLED_IMAGE_HTTP_V3":"CONTROLLED_IMAGE_HTTP_V2")
                        :"NATIVE_CONVERSATION_HTTP_V1",
                referenceWire(command.references()));
        // Resolve additive catalog shape before taking the task root. This query is server-owned
        // metadata only; it performs no migration and never executes while Agent/Chat rows are locked.
        boolean executionProtocolColumn=command.controlledImage()
                && executions.hasExecutionProtocolVersionColumn();
        if(controlledProtocol==3&&!executionProtocolColumn)
            throw failure(Reason.CAPABILITY_UNAVAILABLE);
        return taskMutations.executeWithLockedTaskRootInOwnerScope(scope.tenantId(), scope.clientId(),
                scope.ownerJiacn(),valid.taskId(),root -> {
                    requireConversationRoot(root,scope,valid.taskId(),valid.targetAgentId(),command.assignmentRevision());
                    var prior=executions.findByIdempotency(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),key);
                    if (prior!=null) {
                        if (!same(prior.getRequestHash(),requestHash)
                                || !"CONVERSATION".equals(prior.getExecutionMode())) throw failure(Reason.IDEMPOTENCY_CONFLICT);
                        // Exact controlled create replay is reconciliation only. The persisted
                        // consent-to-execution uniqueness is authoritative; an offline runtime must
                        // not turn a committed ACK-loss recovery into a second execution attempt.
                        if(prior.getControlledConsentId()==null)
                            requireConversationGrant(scope,prior,"NEW_EXECUTION",null);
                        return view(scope,prior);
                    }
                    requireOwnedTarget(scope,valid.targetAgentId());
                    try {
                        var conversation=conversationAccess.requireAccessible(
                                new WorkspaceConversationAccessService.Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn()),
                                valid.conversationId());
                        if (conversation==null || !same(valid.taskId(),conversation.taskId())
                                || !same("bounty",conversation.scopeType())
                                || !same("task:"+valid.taskId(),conversation.scopeKey())
                                || conversation.targetAgentIds()==null
                                || !conversation.targetAgentIds().contains(valid.targetAgentId()))
                            throw failure(Reason.NOT_FOUND);
                    } catch (RuntimeException denied) { throw failure(Reason.NOT_FOUND); }
                    AgentTaskExecutionGrantService.Admission controlledAdmission=null;
                    cn.jia.agent.entity.ControlledImageBridgeOperationEntity initialV3Authority=null;
                    if (command.controlledImage()) {
                        if (controlledConsents==null) throw failure(Reason.CAPABILITY_UNAVAILABLE);
                        var grantScope=new AgentTaskExecutionGrantService.Scope(
                                scope.tenantId(),scope.clientId(),scope.ownerJiacn());
                        controlledAdmission=controlledProtocol==3
                                ?conversationGrants.admitControlledV3(grantScope,valid.taskId(),command.grantId(),
                                    command.grantVersion(),command.assignmentRevision(),valid.targetAgentId(),
                                    command.permittedOperation(),"NEW_EXECUTION",null,null,null)
                                :conversationGrants.admitControlled(grantScope,valid.taskId(),command.grantId(),
                                    command.grantVersion(),command.assignmentRevision(),valid.targetAgentId(),
                                    command.permittedOperation(),"NEW_EXECUTION",null,null,null);
                        if (controlledAdmission==null || controlledAdmission.costAuthorizationRef()==null
                                || !controlledAdmission.costAuthorizationRef().matches("mmd-ci-v1:consent_[0-9a-f]{32}")
                                || controlledAdmission.costAuthorizationVersion()==null)
                            throw failure(Reason.GRANT_REVOKED);
                        if(controlledProtocol==3) {
                            String consentId=controlledAdmission.costAuthorizationRef().substring("mmd-ci-v1:".length());
                            initialV3Authority=initialControlledOperations.lockByAuthority(scope.tenantId(),scope.clientId(),
                                    scope.ownerJiacn(),valid.taskId(),consentId,command.grantId());
                            if(initialV3Authority==null||!Objects.equals(3,initialV3Authority.getExecutionProtocolVersion())
                                    ||initialV3Authority.getOperationGrantId()==null
                                    ||!initialV3Authority.getOperationGrantId().matches("opgrant_[0-9a-f]{32}")
                                    ||!same(command.grantId(),initialV3Authority.getGrantId())
                                    ||!Objects.equals(command.grantVersion(),initialV3Authority.getGrantVersion())
                                    ||!Objects.equals(command.assignmentRevision(),initialV3Authority.getAssignmentRevision()))
                                throw failure(Reason.GRANT_REVOKED);
                        }
                    }
                    PersonalWorkspaceExecutionEntity row=new PersonalWorkspaceExecutionEntity()
                            .setExecutionId(identifier("pwe_")).setOwnerJiacn(scope.ownerJiacn())
                            .setTaskId(valid.taskId()).setRunId(identifier("pwe_run_"))
                            .setExecutionMode("CONVERSATION")
                            .setConversationId(valid.conversationId())
                            .setTargetAgentId(valid.targetAgentId()).setInstruction(valid.instruction())
                            .setOutputContentMimeType(valid.outputContentMimeType())
                            .setExecutionState("QUEUED").setGrantRevision(1L)
                            .setTaskGrantId(command.grantId()).setTaskGrantVersion(command.grantVersion())
                            .setAssignmentRevision(command.assignmentRevision())
                            .setPermittedOperation(command.permittedOperation())
                            .setControlledConsentId(controlledAdmission==null?null:
                                    controlledAdmission.costAuthorizationRef().substring("mmd-ci-v1:".length()))
                            .setIdempotencyKey(key).setRequestHash(requestHash).setCreatedAt(System.currentTimeMillis());
                    // The execution_protocol_version column belongs to the additive v3 catalog.
                    // Old/default-off schemas must not see it in generated INSERT SQL; on the full
                    // catalog protocol 1 is the database default, while controlled v2 is explicit.
                    if(executionProtocolColumn) row.setExecutionProtocolVersion(controlledProtocol);
                    if(controlledProtocol==3) {
                        row.setOperationGrantId(initialV3Authority.getOperationGrantId());
                        row.setRuntimeInputSnapshotDigest(initialV3Digest(row,command.references()));
                    }
                    scoped(row,scope);
                    var authorized=controlledAdmission==null?requireConversationGrant(scope,row,"NEW_EXECUTION",null)
                            :controlledAdmission;
                    if (!sameReferences(command.references(),authorized.inputs())) throw failure(Reason.GRANT_REVOKED);
                    try { executions.insert(row); }
                    catch (DuplicateKeyException collision) { throw failure(Reason.TASK_CONFLICT); }
                    int index=0;
                    for (var ref:command.references()) {
                        // admitControlled already locked canonical files in deterministic order.
                        // Legacy native create keeps its historical file-lock path.
                        var file=command.controlledImage()
                                ? workspace.findFile(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),ref.fileId())
                                : workspace.lockFile(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),ref.fileId());
                        var version=workspace.findVersion(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),
                                ref.fileId(),ref.version());
                        if (file==null || !"ACTIVE".equals(file.getState()) || version==null
                                || !same(ref.contentHash(),version.getContentHash())
                                || !same(ref.contentMimeType(),version.getContentMimeType())
                                || version.getByteLength()==null || version.getByteLength()!=ref.byteLength()
                                || version.getStorageUri()==null) throw failure(Reason.GRANT_REVOKED);
                        long now=System.currentTimeMillis();
                        var input=new PersonalWorkspaceExecutionInputEntity()
                                .setInputRef("input_"+(++index)).setExecutionId(row.getExecutionId())
                                .setOwnerJiacn(scope.ownerJiacn()).setFileId(ref.fileId())
                                .setFileVersion(ref.version()).setOriginalFilename(version.getOriginalFilename())
                                .setContentMimeType(ref.contentMimeType()).setByteLength(ref.byteLength())
                                .setContentHash(ref.contentHash()).setStorageUri(version.getStorageUri())
                                .setGrantState("ACTIVE").setCreatedAt(now).setRevokedAt(null);
                        scoped(input,scope);executions.insertInput(input);
                    }
                    if(controlledProtocol==3) {
                        int sourceOrdinal=0;long sourceCreatedAt=row.getCreatedAt();
                        for(var ref:command.references()) followupSources.insert(
                                initialV3Source(scope,row,++sourceOrdinal,ref,sourceCreatedAt));
                    }
                    if (controlledAdmission!=null) {
                        String consentId=row.getControlledConsentId();
                        var consentScope=new cn.jia.agent.service.AgentTaskProviderCostConsentService.Scope(
                                scope.tenantId(),scope.clientId(),scope.ownerJiacn());
                        var consent=controlledConsents.lockForBridge(consentScope,valid.taskId(),consentId);
                        controlledConsents.reserveWithinLockedRoot(consentScope,valid.taskId(),consent,
                                controlledAdmission.costAuthorizationVersion(),row.getExecutionId(),row.getRunId());
                    }
                    return view(scope,row);
                });
    }


    private static ControlledImageExecutionSourceV3Entity initialV3Source(OwnerScope scope,
            PersonalWorkspaceExecutionEntity execution,int ordinal,ReferenceSelection ref,long now) {
        Map<String,Object> descriptor=new LinkedHashMap<>();descriptor.put("fileId",ref.fileId());
        descriptor.put("kind","TASK_LINKED_WORKSPACE_VERSION");descriptor.put("purpose","REFERENCE");
        descriptor.put("version",Integer.toString(ref.version()));
        var row=new ControlledImageExecutionSourceV3Entity().setOwnerJiacn(scope.ownerJiacn())
                .setExecutionId(execution.getExecutionId()).setInputRef("input_"+ordinal)
                .setInputOrdinal(ordinal).setSourceKind("TASK_LINKED_WORKSPACE_VERSION")
                .setContentMimeType(ref.contentMimeType()).setByteLength(ref.byteLength())
                .setContentSha256(ref.contentHash()).setSourceJson(v3Json(descriptor))
                .setFileId(ref.fileId()).setFileVersion(ref.version()).setPurpose("REFERENCE")
                .setCreatedAt(now);
        row.setTenantId(scope.tenantId());row.setClientId(scope.clientId());return row;
    }
    private static String initialV3Digest(PersonalWorkspaceExecutionEntity execution,
            List<ReferenceSelection> references) {
        List<Map<String,Object>> inputs=new ArrayList<>();int ordinal=0;
        for(var ref:references) {
            Map<String,Object> descriptor=new LinkedHashMap<>();descriptor.put("fileId",ref.fileId());
            descriptor.put("kind","TASK_LINKED_WORKSPACE_VERSION");descriptor.put("purpose","REFERENCE");
            descriptor.put("version",Integer.toString(ref.version()));
            Map<String,Object> input=new LinkedHashMap<>();input.put("byteLength",Long.toString(ref.byteLength()));
            input.put("contentMimeType",ref.contentMimeType());input.put("inputRef","input_"+(++ordinal));
            input.put("sha256",ref.contentHash());input.put("source",descriptor);inputs.add(input);
        }
        Map<String,Object> domain=new LinkedHashMap<>();domain.put("conversationId",execution.getConversationId());
        domain.put("executionId",execution.getExecutionId());domain.put("inputs",inputs);
        domain.put("noReferencedMaterials",inputs.isEmpty());domain.put("operation",execution.getPermittedOperation());
        domain.put("runId",execution.getRunId());domain.put("schemaVersion",1);domain.put("taskId",execution.getTaskId());
        return plainSha(v3Json(domain));
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConversationOutput readConversationOutput(OwnerScope scope,String taskId,String runId,String outputId) {
        validateOwnerScope(scope); id(taskId,"taskId",100);id(runId,"runId",100);id(outputId,"outputId",100);
        if (taskMutations==null || conversationGrants==null) throw failure(Reason.CAPABILITY_UNAVAILABLE);
        return taskMutations.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),scope.clientId(),
                scope.ownerJiacn(),taskId,root -> {
                    var row=executions.findByTaskRun(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,runId);
                    if (row==null || !"CONVERSATION".equals(row.getExecutionMode())
                            || !"OUTPUT_COMMITTED".equals(row.getExecutionState())) throw failure(Reason.NOT_FOUND);
                    requireConversationRoot(root,scope,taskId,row.getTargetAgentId(),row.getAssignmentRevision());
                    requireConversationGrant(scope,row,"EXISTING_RUN",null);
                    requireCurrentControlledConversationAccess(scope,row);
                    var locked=executions.lockByTaskRun(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,runId);
                    if (locked==null || !same(row.getExecutionId(),locked.getExecutionId())
                            || !"OUTPUT_COMMITTED".equals(locked.getExecutionState())) throw failure(Reason.NOT_FOUND);
                    var output=executions.lockOutput(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),
                            row.getExecutionId(),outputId);
                    if (output==null || !same(row.getExecutionId(),output.getExecutionId())
                            || !same(scope.ownerJiacn(),output.getOwnerJiacn())
                            || !"CONVERSATION".equals(output.getOutputPurpose())
                            || !"COMMITTED".equals(output.getOutputState())
                            || output.getWorkspaceFileId()!=null || output.getArtifactId()!=null
                            || output.getFormalDeliveryId()!=null) throw failure(Reason.NOT_FOUND);
                    var content=storage.read(storageScope(scope),output.getStorageUri(),output.getContentHash(),
                            output.getByteLength(),output.getContentMimeType());
                    return new ConversationOutput(row.getExecutionId(),outputId,output.getOriginalFilename(),
                            output.getContentMimeType(),output.getContentHash(),output.getByteLength(),content.content());
                });
    }

    /** No browser-provided output IDs, URIs or filenames. Verify each stored byte object before listing it. */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<ConversationOutputInfo> listConversationOutputs(OwnerScope scope, String taskId, String runId) {
        validateOwnerScope(scope); id(taskId,"taskId",100); id(runId,"runId",100);
        if (taskMutations==null || conversationGrants==null) throw failure(Reason.CAPABILITY_UNAVAILABLE);
        return taskMutations.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),scope.clientId(),
                scope.ownerJiacn(),taskId,root -> {
                    var row=executions.findByTaskRun(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,runId);
                    if (row==null || !"CONVERSATION".equals(row.getExecutionMode())
                            || !"OUTPUT_COMMITTED".equals(row.getExecutionState())) throw failure(Reason.NOT_FOUND);
                    requireConversationRoot(root,scope,taskId,row.getTargetAgentId(),row.getAssignmentRevision());
                    requireConversationGrant(scope,row,"EXISTING_RUN",null);
                    requireCurrentControlledConversationAccess(scope,row);
                    var locked=executions.lockByTaskRun(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,runId);
                    if (locked==null || !same(row.getExecutionId(),locked.getExecutionId())
                            || !"OUTPUT_COMMITTED".equals(locked.getExecutionState())) throw failure(Reason.NOT_FOUND);
                    var outputs=executions.lockOutputs(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),row.getExecutionId());
                    if (outputs==null || outputs.isEmpty() || outputs.size()>128) throw failure(Reason.NOT_FOUND);
                    var result=new java.util.ArrayList<ConversationOutputInfo>(outputs.size());
                    for (var output: outputs) {
                        if (output==null || !same(row.getExecutionId(),output.getExecutionId())
                                || !same(scope.ownerJiacn(),output.getOwnerJiacn())
                                || !"CONVERSATION".equals(output.getOutputPurpose())
                                || !"COMMITTED".equals(output.getOutputState())
                                || output.getWorkspaceFileId()!=null || output.getArtifactId()!=null
                                || output.getFormalDeliveryId()!=null || output.getByteLength()==null
                                || output.getByteLength()<0 || !sha(output.getContentHash()))
                            throw failure(Reason.NOT_FOUND);
                        id(output.getOutputId(),"outputId",100);
                        validMime(output.getContentMimeType());
                        var stored=storage.read(storageScope(scope),output.getStorageUri(),output.getContentHash(),
                                output.getByteLength(),output.getContentMimeType());
                        byte[] bytes=stored==null ? null : stored.content();
                        if (bytes==null || bytes.length!=output.getByteLength()
                                || !same(plainSha(bytes),output.getContentHash())) throw failure(Reason.STORAGE_UNAVAILABLE);
                        result.add(new ConversationOutputInfo(row.getExecutionId(),output.getOutputId(),
                                output.getContentMimeType(),output.getContentHash(),output.getByteLength()));
                    }
                    return List.copyOf(result);
                });
    }

    private static void requireConversationRoot(AgentTaskMetaEntity root,OwnerScope scope,
            String taskId,String agentId,Long assignmentRevision) {
        if (root==null || !same(taskId,root.getTaskId())
                || !same(scope.tenantId(),root.getTenantId()) || !same(scope.clientId(),root.getClientId())
                || !same(scope.ownerJiacn(),root.getOwnerJiacn())
                || !same(agentId,root.getAssignedAgentId())
                || assignmentRevision==null || assignmentRevision<0
                || root.getTaskVersion()==null || root.getTaskVersion()<assignmentRevision)
            throw failure(Reason.GRANT_REVOKED);
    }

    private AgentTaskExecutionGrantService.Admission requireConversationGrant(
            OwnerScope scope,PersonalWorkspaceExecutionEntity execution,String purpose,String runtimeInstanceId) {
        if (conversationGrants==null || !"CONVERSATION".equals(execution.getExecutionMode())
                || !same(scope.tenantId(),execution.getTenantId()) || !same(scope.clientId(),execution.getClientId())
                || !same(scope.ownerJiacn(),execution.getOwnerJiacn()) || execution.getTaskGrantId()==null
                || execution.getTaskGrantVersion()==null || execution.getAssignmentRevision()==null
                || execution.getPermittedOperation()==null) throw failure(Reason.NOT_FOUND);
        try {
            var grantScope=new AgentTaskExecutionGrantService.Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn());
            if(Objects.equals(3,execution.getExecutionProtocolVersion())) {
                if(followupAuthority==null||runtimeInstanceId==null)throw failure(Reason.CAPABILITY_UNAVAILABLE);
                String authorityPurpose="EXISTING_RUN".equals(purpose)?"EXISTING_RUN":"COMMAND";
                var exact=followupAuthority.runtimeAuthority(new ControlledImageFollowupAuthorityService.RuntimeScope(
                        scope.tenantId(),scope.clientId(),scope.ownerJiacn(),execution.getTargetAgentId(),runtimeInstanceId),
                        execution.getTaskId(),execution.getRunId(),authorityPurpose);
                if(exact==null||!same(execution.getExecutionId(),exact.executionId())
                        ||!same(execution.getPermittedOperation(),exact.operation())
                        ||!same(execution.getRuntimeInputSnapshotDigest(),exact.inputSnapshotDigest()))
                    throw failure(Reason.GRANT_REVOKED);
                return new AgentTaskExecutionGrantService.Admission(execution.getTaskGrantId(),
                        execution.getTaskGrantVersion(),execution.getAssignmentRevision(),
                        execution.getTargetAgentId(),execution.getPermittedOperation(),true);
            }
            var admission=execution.getControlledConsentId()==null
                    ? conversationGrants.admit(grantScope,execution.getTaskId(),execution.getTaskGrantId(),
                        execution.getTaskGrantVersion(),execution.getAssignmentRevision(),
                        execution.getTargetAgentId(),execution.getPermittedOperation(),true)
                    : conversationGrants.admitControlled(grantScope,execution.getTaskId(),execution.getTaskGrantId(),
                        execution.getTaskGrantVersion(),execution.getAssignmentRevision(),
                        execution.getTargetAgentId(),execution.getPermittedOperation(),purpose,
                        execution.getExecutionId(),execution.getRunId(),runtimeInstanceId);
            if (admission==null || !same(execution.getTaskGrantId(),admission.grantId())
                    || execution.getTaskGrantVersion()!=admission.grantVersion()
                    || execution.getAssignmentRevision()!=admission.assignmentRevision()
                    || !same(execution.getTargetAgentId(),admission.targetAgentId())
                    || !same(execution.getPermittedOperation(),admission.operation())
                    || !admission.paidExecutionAuthorized())
                throw failure(Reason.GRANT_REVOKED);
            return admission;
        } catch (RuntimeException denied) { throw failure(Reason.GRANT_REVOKED); }
    }

    private static String v3ReadPurpose(PersonalWorkspaceExecutionEntity execution) {
        return execution.getConversationProviderStartedAt()==null ? "INPUTS" : "EXISTING_RUN";
    }

    private static String conversationAuthorityPurpose(PersonalWorkspaceExecutionEntity execution) {
        if (execution.getControlledConsentId()==null) return "NEW_EXECUTION";
        return execution.getConversationProviderStartedAt()==null ? "PROVIDER_START" : "EXISTING_RUN";
    }

    /** Current Chat ACL is independent from consumed cost authority and is checked without a Chat row lock. */
    private void requireCurrentControlledConversationAccess(OwnerScope scope,
            PersonalWorkspaceExecutionEntity execution) {
        if (execution.getControlledConsentId()==null) return;
        if (conversationAccess==null || execution.getConversationId()==null)
            throw failure(Reason.CAPABILITY_UNAVAILABLE);
        try {
            var conversation=conversationAccess.requireAccessible(
                    new WorkspaceConversationAccessService.Scope(
                            scope.tenantId(),scope.clientId(),scope.ownerJiacn()),
                    execution.getConversationId());
            if (conversation==null || !same(execution.getConversationId(),conversation.conversationId())
                    || !same("bounty",conversation.scopeType())
                    || !same(execution.getTaskId(),conversation.taskId())
                    || !same("task:"+execution.getTaskId(),conversation.scopeKey())
                    || conversation.targetAgentIds()==null
                    || !conversation.targetAgentIds().contains(execution.getTargetAgentId()))
                throw failure(Reason.NOT_FOUND);
        } catch (Failure denied) { throw denied; }
        catch (RuntimeException denied) { throw failure(Reason.NOT_FOUND); }
    }

    private static boolean sameReferences(List<ReferenceSelection> expected,
            List<AgentTaskExecutionGrantService.AuthorizedInput> authorized) {
        if (expected==null || authorized==null || expected.size()!=authorized.size()) return false;
        for (int i=0;i<expected.size();i++) {
            var left=expected.get(i);var right=authorized.get(i);
            if (left==null || right==null || !same(left.fileId(),right.fileId())
                    || left.version()!=right.version() || !same(left.purpose(),right.purpose())
                    || !same(left.contentMimeType(),right.contentMimeType())
                    || left.byteLength()!=right.byteLength()
                    || !same(left.contentHash(),right.contentHash())) return false;
        }
        return true;
    }
    private static String referenceWire(List<ReferenceSelection> refs) {
        String[] wire=new String[refs.size()*6];int n=0;
        for (var ref:refs) {
            wire[n++]=ref.fileId();wire[n++]=Integer.toString(ref.version());wire[n++]=ref.purpose();
            wire[n++]=ref.contentMimeType();wire[n++]=Long.toString(ref.byteLength());wire[n++]=ref.contentHash();
        }
        return hash(wire);
    }

    @Override
    @Transactional(readOnly = true)
    public ExecutionCapabilities capabilities() {
        return executionAvailable
                ? new ExecutionCapabilities(executionMimeTypes.stream().sorted().toList(),
                        SUPPORTED_INPUT_MIME_TYPES.stream().sorted().toList(), true)
                : new ExecutionCapabilities(List.of(), List.of(), false);
    }

    @Override
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public ExecutionHistoryView list(OwnerScope scope, int limit, Long beforeCreatedAt,
            String beforeExecutionId) {
        validateOwnerScope(scope);
        if (limit < 1 || limit > 100
                || (beforeCreatedAt == null) != (beforeExecutionId == null)
                || (beforeCreatedAt != null && beforeCreatedAt < 0)) {
            throw failure(Reason.BAD_REQUEST);
        }
        if (beforeExecutionId != null) id(beforeExecutionId, "beforeExecutionId", 100);
        List<PersonalWorkspaceExecutionEntity> rows = executions.listHistory(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(),
                beforeCreatedAt, beforeExecutionId, limit + 1);
        if (rows == null || rows.size() > limit + 1) throw failure(Reason.STORAGE_UNAVAILABLE);
        List<PersonalWorkspaceExecutionEntity> owned = new ArrayList<>(rows.size());
        Set<String> executionIds = new LinkedHashSet<>();
        for (PersonalWorkspaceExecutionEntity row : rows) {
            if (!isExactOwner(scope, row) || !validHistoryRow(row)
                    || !executionIds.add(row.getExecutionId())
                    || !strictlyBeforeCursor(row, beforeCreatedAt, beforeExecutionId)) {
                // Mapper exactness is the primary boundary. Treat any contaminated row as an
                // unavailable page rather than returning a partial page or advancing past it.
                throw failure(Reason.STORAGE_UNAVAILABLE);
            }
            owned.add(row);
        }
        owned.sort(PersonalWorkspaceExecutionServiceImpl::compareHistoryRows);
        boolean more = owned.size() > limit;
        if (more) owned = new ArrayList<>(owned.subList(0, limit));
        List<ExecutionSummary> items = owned.stream().map(row -> new ExecutionSummary(
                row.getExecutionId(), row.getTargetAgentId(), row.getExecutionState(),
                row.getOutputContentMimeType(), row.getCreatedAt())).toList();
        ExecutionCursor next = more
                ? new ExecutionCursor(owned.getLast().getCreatedAt(), owned.getLast().getExecutionId())
                : null;
        return new ExecutionHistoryView(items, next);
    }

    @Override
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public ExecutionView getByIdempotencyKey(OwnerScope scope, String idempotencyKey) {
        validateOwnerScope(scope); validateIdempotency(idempotencyKey);
        PersonalWorkspaceExecutionEntity execution = executions.findRequestByIdempotency(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), idempotencyKey);
        if (execution == null || !isExactOwner(scope, execution)
                || !same(execution.getIdempotencyKey(), idempotencyKey)) {
            throw failure(Reason.NOT_FOUND);
        }
        return requestView(scope, execution);
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
    public RuntimeStartView start(RuntimeScope scope, String taskId, String runId,
            String commandId, String messageId) {
        // Keep this lookup outside a method-level transaction. TASK start must let the canonical
        // task-root mutation own the complete root -> execution -> state/event transaction.
        PersonalWorkspaceExecutionEntity candidate = runtimeExecution(scope, taskId, runId, false);
        requireStartCommand(candidate, commandId, messageId);
        // Legacy HTTP/native bridge has no attempt fence. Never let it begin a conversation run.
        if ("CONVERSATION".equals(candidate.getExecutionMode())) throw failure(Reason.CAPABILITY_UNAVAILABLE);
        if (!"TASK".equals(candidate.getExecutionMode())) {
            // PRIVATE start is validation-only. A row lock without a surrounding transaction is
            // ineffective and would imply a transaction boundary that this path does not need.
            PersonalWorkspaceExecutionEntity current = runtimeExecution(scope, taskId, runId, false);
            requireStartCommand(current, commandId, messageId);
            return new RuntimeStartView(current.getExecutionId(), taskId, runId, "STARTED");
        }
        if (taskMutations == null || taskStates == null) throw failure(Reason.TASK_CONFLICT);
        return taskMutations.executeWithLockedTaskRootInOwnerScope(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), taskId, root -> {
                    PersonalWorkspaceExecutionEntity current = runtimeExecution(scope, taskId, runId, true);
                    requireStartCommand(current, commandId, messageId);
                    if (!"TASK".equals(current.getExecutionMode())
                            || !same(root.getTaskId(), taskId)
                            || !same(root.getAssignedAgentId(), scope.agentId())) {
                        throw failure(Reason.TASK_CONFLICT);
                    }
                    if ("assigned".equals(root.getRewardStatus())) {
                        AgentTaskStateTransitionDTO transition = new AgentTaskStateTransitionDTO();
                        transition.setTargetStatus("running");
                        transition.setExpectedVersion(root.getTaskVersion());
                        taskStates.transitionTask(scope.tenantId(), scope.clientId(), scope.ownerJiacn(),
                                taskId, transition);
                    } else if (!"running".equals(root.getRewardStatus())) {
                        // Rework requires an authoritative decision; never resume blocked/terminal tasks.
                        throw failure(Reason.TASK_CONFLICT);
                    }
                    return new RuntimeStartView(current.getExecutionId(), taskId, runId, "STARTED");
                });
    }

    /** Dedicated native inbox; each candidate takes an independent root transaction so a
     * revoked row can roll back without poisoning a subsequent valid queue item. */
    @Override
    public List<ConversationRuntimeCommand> runtimeConversationCommands(RuntimeScope scope,int limit) {
        requireConversationExecutionEnabled();validateRuntimeScope(scope);
        if (limit<1 || limit>16) throw failure(Reason.BAD_REQUEST);
        List<ConversationRuntimeCommand> result=new ArrayList<>();
        Long afterCreatedAt=null;
        String afterExecutionId=null;
        while (result.size()<limit) {
            var candidates=executions.listQueuedConversationsByTarget(scope.tenantId(),scope.clientId(),
                    scope.ownerJiacn(),scope.agentId(),afterCreatedAt,afterExecutionId,16);
            if (candidates==null) throw failure(Reason.NOT_FOUND);
            if (candidates.isEmpty()) break;
            if (candidates.size()>16) throw failure(Reason.TASK_CONFLICT);
            for (var candidate:candidates) {
                // Advance on raw rows, including revoked ones; neither an invalid head nor
                // equal timestamps may indefinitely hide a later authorized command.
                if (candidate==null || candidate.getCreatedAt()==null || candidate.getCreatedAt()<0
                        || !safeId(candidate.getExecutionId(),100)
                        || (afterCreatedAt!=null && (candidate.getCreatedAt()<afterCreatedAt
                            || (candidate.getCreatedAt().equals(afterCreatedAt)
                                && compareUtf8(candidate.getExecutionId(),afterExecutionId)<=0))))
                    throw failure(Reason.TASK_CONFLICT);
                afterCreatedAt=candidate.getCreatedAt();
                afterExecutionId=candidate.getExecutionId();
                if (result.size()>=limit) continue;
                if (!"CONVERSATION".equals(candidate.getExecutionMode())
                        || candidate.getControlledConsentId()!=null
                        || candidate.getConversationProviderStartedAt()!=null
                        || !"QUEUED".equals(candidate.getExecutionState())
                        || !same(candidate.getTenantId(),scope.tenantId())
                        || !same(candidate.getClientId(),scope.clientId())
                        || !same(candidate.getOwnerJiacn(),scope.ownerJiacn())
                        || !same(candidate.getTargetAgentId(),scope.agentId())) continue;
                try {
                    result.add(withConversationRoot(scope,candidate.getTaskId(),candidate.getRunId(),false,
                            execution -> {
                                if (!same(candidate.getExecutionId(),execution.getExecutionId()))
                                    throw failure(Reason.NOT_FOUND);
                                String seed=execution.getExecutionId();
                                return new ConversationRuntimeCommand(1,execution.getTaskId(),execution.getRunId(),
                                        execution.getConversationId(),"pwe_cmd_"+plainSha("command\n"+seed),
                                        "pwe_msg_"+plainSha("message\n"+seed),execution.getInstruction(),
                                        execution.getOutputContentMimeType(),"output_1");
                            }));
                } catch (Failure stale) { // One revoked candidate must not suppress other owned work.
                    if (stale.getReason()!=Reason.NOT_FOUND && stale.getReason()!=Reason.GRANT_REVOKED
                            && stale.getReason()!=Reason.TASK_CONFLICT) throw stale;
                }
            }
            if (candidates.size()<16) break;
        }
        return List.copyOf(result);
    }

    @Override
    public List<? extends ConversationCommandView> runtimeConversationCommandViews(RuntimeScope scope,int limit) {
        requireConversationExecutionEnabled();validateRuntimeScope(scope);
        if (limit<1 || limit>16) throw failure(Reason.BAD_REQUEST);
        List<ConversationCommandView> result=new ArrayList<>();
        Long afterCreatedAt=null;
        String afterExecutionId=null;
        while (result.size()<limit) {
            var rows=executions.listQueuedConversationsByTarget(scope.tenantId(),scope.clientId(),
                    scope.ownerJiacn(),scope.agentId(),afterCreatedAt,afterExecutionId,16);
            if (rows==null) throw failure(Reason.NOT_FOUND);
            if (rows.isEmpty()) break;
            if (rows.size()>16) throw failure(Reason.TASK_CONFLICT);
            for (var candidate:rows) {
                if (candidate==null || candidate.getCreatedAt()==null || candidate.getCreatedAt()<0
                        || !safeId(candidate.getExecutionId(),100)
                        || (afterCreatedAt!=null && (candidate.getCreatedAt()<afterCreatedAt
                            || (candidate.getCreatedAt().equals(afterCreatedAt)
                                && compareUtf8(candidate.getExecutionId(),afterExecutionId)<=0))))
                    throw failure(Reason.TASK_CONFLICT);
                afterCreatedAt=candidate.getCreatedAt();
                afterExecutionId=candidate.getExecutionId();
                if (result.size()>=limit || !"CONVERSATION".equals(candidate.getExecutionMode())
                        || candidate.getConversationProviderStartedAt()!=null
                        || !"QUEUED".equals(candidate.getExecutionState())
                        || !same(candidate.getTenantId(),scope.tenantId())
                        || !same(candidate.getClientId(),scope.clientId())
                        || !same(candidate.getOwnerJiacn(),scope.ownerJiacn())
                        || !same(candidate.getTargetAgentId(),scope.agentId())) continue;
                try {
                    result.add(withConversationRoot(scope,candidate.getTaskId(),candidate.getRunId(),false,
                            execution -> commandView(scope,candidate,execution)));
                } catch (Failure stale) {
                    if (stale.getReason()!=Reason.NOT_FOUND && stale.getReason()!=Reason.GRANT_REVOKED
                            && stale.getReason()!=Reason.TASK_CONFLICT) throw stale;
                }
            }
            if (rows.size()<16) break;
        }
        return List.copyOf(result);
    }

    private ConversationCommandView commandView(RuntimeScope scope,
            PersonalWorkspaceExecutionEntity candidate,PersonalWorkspaceExecutionEntity execution) {
        if (!same(candidate.getExecutionId(),execution.getExecutionId())
                || !same(candidate.getTaskId(),execution.getTaskId())
                || !same(candidate.getRunId(),execution.getRunId())
                || !Objects.equals(candidate.getControlledConsentId(),execution.getControlledConsentId()))
            throw failure(Reason.TASK_CONFLICT);
        if(Objects.equals(3,execution.getExecutionProtocolVersion()))throw failure(Reason.NOT_FOUND);
        String seed=execution.getExecutionId();
        String commandId="pwe_cmd_"+plainSha("command\n"+seed);
        String messageId="pwe_msg_"+plainSha("message\n"+seed);
        if (execution.getControlledConsentId()==null) {
            return new ConversationRuntimeCommand(1,execution.getTaskId(),execution.getRunId(),
                    execution.getConversationId(),commandId,messageId,execution.getInstruction(),
                    execution.getOutputContentMimeType(),"output_1");
        }
        OwnerScope owner=new OwnerScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn());
        requireCurrentControlledConversationAccess(owner,execution);
        var admission=requireConversationGrant(owner,execution,"PROVIDER_START",scope.runtimeInstanceId());
        ProviderExecution provider=providerExecution(admission,owner,execution.getTaskId(),
                execution.getControlledConsentId());
        if (!"image/png".equals(execution.getOutputContentMimeType()))
            throw failure(Reason.GRANT_REVOKED);
        return new ControlledConversationRuntimeCommand(2,execution.getTaskId(),execution.getRunId(),
                execution.getConversationId(),commandId,messageId,execution.getInstruction(),
                "image/png","output_1",provider);
    }

    private static long parseControlledConsentVersion(String value) {
        if(value==null||!value.matches("[1-9][0-9]*"))throw failure(Reason.GRANT_REVOKED);
        try {
            long parsed=Long.parseLong(value);
            if(parsed>MAX_SAFE_INTEGER)throw failure(Reason.GRANT_REVOKED);
            return parsed;
        } catch(NumberFormatException invalid) { throw failure(Reason.GRANT_REVOKED); }
    }

    private ProviderExecution providerExecution(AgentTaskExecutionGrantService.Admission admission,
            OwnerScope scope,String taskId,String consentId){
        if(admission==null||admission.costAuthorizationRef()==null||controlledConsents==null
                ||!admission.costAuthorizationRef().equals("mmd-ci-v1:"+consentId))throw failure(Reason.GRANT_REVOKED);
        var consent=controlledConsents.get(new cn.jia.agent.service.AgentTaskProviderCostConsentService.Scope(
                scope.tenantId(),scope.clientId(),scope.ownerJiacn()),taskId,consentId);
        if(consent==null||!"RESERVED".equals(consent.state())||consent.providerBinding()==null
                ||consent.maxOutboundRequestAttempts()!=1
                ||admission.costAuthorizationVersion()==null
                ||parseControlledConsentVersion(consent.version())!=admission.costAuthorizationVersion())
            throw failure(Reason.GRANT_REVOKED);
        return new ProviderExecution("CONTROLLED_IMAGE_HTTP_V1",consentId,
                consent.providerBinding().bindingId(),consent.providerBinding().bindingEpoch(),
                consent.modelId(),16,1,1);
    }

    @Override
    public List<ControlledConversationRuntimeCommandV3> runtimeControlledImageV3Commands(
            RuntimeScope scope,int limit) {
        requireConversationExecutionEnabled(); validateRuntimeScope(scope);
        if(limit<1||limit>16) throw failure(Reason.BAD_REQUEST);
        if(followupAuthority==null||followupSources==null) throw failure(Reason.CAPABILITY_UNAVAILABLE);
        List<ControlledConversationRuntimeCommandV3> result=new ArrayList<>();
        Long after=null; String afterId=null;
        while(result.size()<limit) {
            var rows=executions.listQueuedConversationsByTarget(scope.tenantId(),scope.clientId(),
                    scope.ownerJiacn(),scope.agentId(),after,afterId,16);
            if(rows==null) throw failure(Reason.STORAGE_UNAVAILABLE);
            if(rows.isEmpty()) break;
            for(var row:rows) {
                if(row==null||row.getCreatedAt()==null||row.getCreatedAt()<0
                        ||row.getExecutionId()==null||row.getTaskId()==null||row.getRunId()==null
                        ||row.getConversationId()==null||!isExactOwner(new OwnerScope(scope.tenantId(),
                                scope.clientId(),scope.ownerJiacn()),row)
                        ||!same(scope.agentId(),row.getTargetAgentId())
                        ||!"CONVERSATION".equals(row.getExecutionMode())
                        ||!"QUEUED".equals(row.getExecutionState()))
                    throw failure(Reason.STORAGE_UNAVAILABLE);
                if(after!=null&&(row.getCreatedAt()<after||(row.getCreatedAt().equals(after)
                        &&row.getExecutionId().compareTo(afterId)<=0)))
                    throw failure(Reason.STORAGE_UNAVAILABLE);
                after=row.getCreatedAt(); afterId=row.getExecutionId();
                if(result.size()>=limit||!Objects.equals(3,row.getExecutionProtocolVersion())
                        ||row.getConversationProviderStartedAt()!=null) continue;
                try {
                    requireCurrentControlledConversationAccess(new OwnerScope(scope.tenantId(),
                            scope.clientId(),scope.ownerJiacn()),row);
                    var authority=followupAuthority.runtimeAuthority(
                            new ControlledImageFollowupAuthorityService.RuntimeScope(scope.tenantId(),
                                    scope.clientId(),scope.ownerJiacn(),scope.agentId(),scope.runtimeInstanceId()),
                            row.getTaskId(),row.getRunId(),"COMMAND");
                    verifiedV3SourceRows(scope,row);
                    String seed=row.getExecutionId();
                    result.add(new ControlledConversationRuntimeCommandV3(3,row.getExecutionId(),
                            row.getTaskId(),row.getRunId(),row.getConversationId(),
                            "pwe_cmd_"+plainSha("command\n"+seed),
                            "pwe_msg_"+plainSha("message\n"+seed),row.getPermittedOperation(),
                            row.getInstruction(),row.getRuntimeInputSnapshotDigest(),"image/png","output_1",
                            provider(authority.providerExecution())));
                } catch(Failure stale) {
                    if(stale.getReason()!=Reason.NOT_FOUND&&stale.getReason()!=Reason.GRANT_REVOKED
                            &&stale.getReason()!=Reason.TASK_CONFLICT) throw stale;
                } catch(ControlledImageFollowupAuthorityService.Failure stale) {
                    if(stale.reason()!=ControlledImageFollowupAuthorityService.Reason.NOT_FOUND_OR_FORBIDDEN
                            &&stale.reason()!=ControlledImageFollowupAuthorityService.Reason.CONFLICT)
                        throw failure(Reason.CAPABILITY_UNAVAILABLE);
                }
            }
            if(rows.size()<16) break;
        }
        return List.copyOf(result);
    }

    @Override
    @Transactional(rollbackFor=Exception.class)
    public ConversationInputSnapshotV3 conversationInputsV3(RuntimeScope scope,String taskId,String runId,ConversationFence fence){
        requireConversationExecutionEnabled();if(followupAuthority==null||followupSources==null)throw failure(Reason.CAPABILITY_UNAVAILABLE);
        return withConversationRoot(scope,taskId,runId,true,execution->{requireConversationFence(scope,execution,fence,false);if(!Objects.equals(3,execution.getExecutionProtocolVersion()))throw failure(Reason.NOT_FOUND);
            var authority=followupAuthority.runtimeAuthority(new ControlledImageFollowupAuthorityService.RuntimeScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),scope.agentId(),scope.runtimeInstanceId()),taskId,runId,v3ReadPurpose(execution));
            if(!same(authority.executionId(),execution.getExecutionId())||!same(authority.inputSnapshotDigest(),execution.getRuntimeInputSnapshotDigest()))throw failure(Reason.GRANT_REVOKED);
            var rows=verifiedV3SourceRows(scope,execution);List<RuntimeInputV3> inputs=rows.stream().map(PersonalWorkspaceExecutionServiceImpl::runtimeInputV3).toList();
            return new ConversationInputSnapshotV3(3,execution.getExecutionId(),fence.version(),execution.getPermittedOperation(),execution.getRuntimeInputSnapshotDigest(),inputs.isEmpty(),inputs);
        });
    }

    @Override
    public ControlledProviderStartReceiptV3 beginControlledConversationProviderStartV3(RuntimeScope scope,String taskId,String runId,ControlledProviderStartV3 command){
        requireConversationExecutionEnabled();validateRuntimeScope(scope);if(followupAuthority==null||command==null||command.schemaVersion()!=3||command.fence()==null||command.providerExecution()==null)throw failure(Reason.BAD_REQUEST);
        var candidate=runtimeExecution(scope,taskId,runId,false);if(!Objects.equals(3,candidate.getExecutionProtocolVersion())||!same(candidate.getExecutionId(),command.executionId()))throw failure(Reason.NOT_FOUND);requireStartCommand(candidate,command.commandId(),command.messageId());
        // Private storage may perform external I/O. Validate the immutable, content-addressed bytes
        // before the root-first transaction so no Agent/Chat row lock is held across that boundary.
        // The late check revalidates the exact persisted source rows/digest under the execution lock.
        verifiedV3SourceBytes(scope,candidate);
        var receipt=followupAuthority.consumeForStart(new ControlledImageFollowupAuthorityService.RuntimeScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),scope.agentId(),scope.runtimeInstanceId()),new ControlledImageFollowupAuthorityService.StartCommand(taskId,runId,command.executionId(),command.commandId(),command.messageId(),command.operation(),command.inputSnapshotDigest(),authorityProvider(command.providerExecution()),command.fence().version(),"pwe_lease_"+plainSha("controlled-provider-start-v3\n"+command.executionId()+"\n"+scope.runtimeInstanceId()+"\n"+command.fence().version())),()->withConversationRoot(scope,taskId,runId,true,execution->{requireConversationFence(scope,execution,command.fence(),false);requireStartCommand(execution,command.commandId(),command.messageId());if(!same(command.inputSnapshotDigest(),execution.getRuntimeInputSnapshotDigest())||!same(command.operation(),execution.getPermittedOperation())||execution.getConversationProviderStartedAt()!=null)throw failure(Reason.TASK_CONFLICT);verifiedV3SourceRows(scope,execution);return null;}));
        return new ControlledProviderStartReceiptV3(3,true,receipt.taskId(),receipt.runId(),receipt.conversationId(),receipt.executionId(),receipt.commandId(),receipt.messageId(),receipt.operation(),receipt.inputSnapshotDigest(),provider(receipt.providerExecution()),receipt.leaseVersion());
    }

    private static ProviderExecution provider(ControlledImageFollowupAuthorityService.ProviderExecution p){return new ProviderExecution(p.providerLane(),p.consentId(),p.bindingId(),p.bindingEpoch(),p.modelId(),p.maxInputItems(),p.maxOutboundRequestAttempts(),p.precallFenceVersion());}
    private static ControlledImageFollowupAuthorityService.ProviderExecution authorityProvider(ProviderExecution p){return new ControlledImageFollowupAuthorityService.ProviderExecution(p.providerLane(),p.consentId(),p.bindingId(),p.bindingEpoch(),p.modelId(),p.maxInputItems(),p.maxOutboundRequestAttempts(),p.precallFenceVersion());}
    private static RuntimeInputV3 runtimeInputV3(ControlledImageExecutionSourceV3Entity r){return new RuntimeInputV3(r.getInputRef(),new RuntimeSource(r.getSourceKind(),r.getFileId(),r.getFileVersion()==null?null:Integer.toString(r.getFileVersion()),r.getPurpose(),r.getConversationId(),r.getConversationGeneration()==null?null:Long.toString(r.getConversationGeneration()),r.getAssetId(),r.getAssetRevision()==null?null:Long.toString(r.getAssetRevision()),r.getProducerRequestId(),r.getProducerStepId(),r.getProducerExecutionId(),r.getProducerRunId(),r.getProducerOutputId()),r.getContentMimeType(),Long.toString(r.getByteLength()),r.getContentSha256());}
    private void verifiedV3SourceBytes(RuntimeScope scope,PersonalWorkspaceExecutionEntity execution){
        for(var source:verifiedV3SourceRows(scope,execution))readV3Source(scope,execution,source);
    }
    private List<ControlledImageExecutionSourceV3Entity> verifiedV3SourceRows(
            RuntimeScope scope,PersonalWorkspaceExecutionEntity execution) {
        var rows=followupSources.list(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),
                execution.getExecutionId());
        if(rows==null||rows.size()>16||!Objects.equals(3,execution.getExecutionProtocolVersion())
                ||!Set.of("GENERATE_IMAGE","EDIT_IMAGE").contains(execution.getPermittedOperation())
                ||execution.getRuntimeInputSnapshotDigest()==null
                ||!execution.getRuntimeInputSnapshotDigest().matches("[0-9a-f]{64}"))
            throw failure(Reason.GRANT_REVOKED);
        if("EDIT_IMAGE".equals(execution.getPermittedOperation())&&rows.size()!=1)
            throw failure(Reason.GRANT_REVOKED);
        List<Map<String,Object>> inputs=new ArrayList<>(); int ordinal=0;
        for(var row:rows) {
            ordinal++;
            if(row==null||!same(scope.tenantId(),row.getTenantId())
                    ||!same(scope.clientId(),row.getClientId())||!same(scope.ownerJiacn(),row.getOwnerJiacn())
                    ||!same(execution.getExecutionId(),row.getExecutionId())
                    ||!Objects.equals(ordinal,row.getInputOrdinal())
                    ||!same("input_"+ordinal,row.getInputRef())||row.getCreatedAt()==null||row.getCreatedAt()<1
                    ||row.getByteLength()==null||row.getByteLength()<1
                    ||!Set.of("image/jpeg","image/png").contains(row.getContentMimeType())
                    ||row.getContentSha256()==null||!row.getContentSha256().matches("[0-9a-f]{64}"))
                throw failure(Reason.GRANT_REVOKED);
            Map<String,Object> descriptor=v3SourceDescriptor(row,execution.getPermittedOperation());
            try {
                if(row.getSourceJson()==null||!V3_JSON.readTree(row.getSourceJson())
                        .equals(V3_JSON.valueToTree(descriptor))) throw failure(Reason.GRANT_REVOKED);
            } catch(Failure exact) { throw exact; }
            catch(Exception malformed) { throw failure(Reason.GRANT_REVOKED); }
            Map<String,Object> input=new LinkedHashMap<>();
            input.put("byteLength",Long.toString(row.getByteLength()));
            input.put("contentMimeType",row.getContentMimeType());input.put("inputRef",row.getInputRef());
            input.put("sha256",row.getContentSha256());input.put("source",descriptor);inputs.add(input);
        }
        Map<String,Object> domain=new LinkedHashMap<>();domain.put("conversationId",execution.getConversationId());
        domain.put("executionId",execution.getExecutionId());domain.put("inputs",inputs);
        domain.put("noReferencedMaterials",inputs.isEmpty());domain.put("operation",execution.getPermittedOperation());
        domain.put("runId",execution.getRunId());domain.put("schemaVersion",1);domain.put("taskId",execution.getTaskId());
        if(!same(execution.getRuntimeInputSnapshotDigest(),plainSha(v3Json(domain))))
            throw failure(Reason.GRANT_REVOKED);
        return List.copyOf(rows);
    }
    private static Map<String,Object> v3SourceDescriptor(ControlledImageExecutionSourceV3Entity row,
            String operation) {
        Map<String,Object> value=new LinkedHashMap<>();
        if("TASK_LINKED_WORKSPACE_VERSION".equals(row.getSourceKind())) {
            if(!"GENERATE_IMAGE".equals(operation)||row.getFileId()==null||row.getFileVersion()==null
                    ||row.getFileVersion()<1||!"REFERENCE".equals(row.getPurpose())
                    ||row.getConversationId()!=null||row.getConversationGeneration()!=null
                    ||row.getAssetId()!=null||row.getAssetRevision()!=null||row.getProducerRequestId()!=null
                    ||row.getProducerRequestRevision()!=null||row.getProducerStepId()!=null
                    ||row.getProducerExecutionId()!=null||row.getProducerRunId()!=null
                    ||row.getProducerOutputId()!=null) throw failure(Reason.GRANT_REVOKED);
            value.put("fileId",row.getFileId());value.put("kind",row.getSourceKind());
            value.put("purpose",row.getPurpose());value.put("version",Integer.toString(row.getFileVersion()));
        } else if("CURRENT_CONVERSATION_ASSET".equals(row.getSourceKind())) {
            if(!"EDIT_IMAGE".equals(operation)||row.getFileId()!=null||row.getFileVersion()!=null
                    ||row.getPurpose()!=null||row.getConversationId()==null
                    ||row.getConversationGeneration()==null||row.getConversationGeneration()<1
                    ||row.getAssetId()==null||row.getAssetRevision()==null||row.getAssetRevision()<1
                    ||row.getProducerRequestId()==null||row.getProducerRequestRevision()==null
                    ||row.getProducerRequestRevision()<1||row.getProducerStepId()==null
                    ||row.getProducerExecutionId()==null||row.getProducerRunId()==null
                    ||row.getProducerOutputId()==null) throw failure(Reason.GRANT_REVOKED);
            value.put("assetId",row.getAssetId());value.put("assetRevision",Long.toString(row.getAssetRevision()));
            value.put("conversationGeneration",Long.toString(row.getConversationGeneration()));
            value.put("conversationId",row.getConversationId());value.put("kind",row.getSourceKind());
            value.put("producerExecutionId",row.getProducerExecutionId());value.put("producerOutputId",row.getProducerOutputId());
            value.put("producerRequestId",row.getProducerRequestId());value.put("producerRunId",row.getProducerRunId());
            value.put("producerStepId",row.getProducerStepId());
        } else throw failure(Reason.GRANT_REVOKED);
        return value;
    }
    private static String v3Json(Object value) {
        try { return V3_JSON.writeValueAsString(value); }
        catch(Exception impossible) { throw failure(Reason.GRANT_REVOKED); }
    }
    private RuntimeContent readV3Source(RuntimeScope scope,PersonalWorkspaceExecutionEntity execution,ControlledImageExecutionSourceV3Entity source){
        if("TASK_LINKED_WORKSPACE_VERSION".equals(source.getSourceKind())){var file=workspace.findFile(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),source.getFileId());var version=workspace.findVersion(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),source.getFileId(),source.getFileVersion());if(file==null||!"ACTIVE".equals(file.getState())||version==null||!taskLinks.hasActiveExecutionInputLink(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),execution.getTaskId(),source.getFileId(),source.getFileVersion())||!same(version.getContentHash(),source.getContentSha256())||!Objects.equals(version.getByteLength(),source.getByteLength())||!same(version.getContentMimeType(),source.getContentMimeType()))throw failure(Reason.GRANT_REVOKED);var stored=storage.read(storageScope(new OwnerScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn())),version.getStorageUri(),version.getContentHash(),version.getByteLength(),version.getContentMimeType());return new RuntimeContent(version.getOriginalFilename(),version.getContentMimeType(),stored.content());}
        if("CURRENT_CONVERSATION_ASSET".equals(source.getSourceKind())){var output=executions.lockOutput(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),source.getProducerExecutionId(),source.getProducerOutputId());if(output==null||!"COMMITTED".equals(output.getOutputState())||!"CONVERSATION".equals(output.getOutputPurpose())||!same(output.getContentHash(),source.getContentSha256())||!Objects.equals(output.getByteLength(),source.getByteLength())||!same(output.getContentMimeType(),source.getContentMimeType()))throw failure(Reason.GRANT_REVOKED);var stored=storage.read(storageScope(new OwnerScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn())),output.getStorageUri(),output.getContentHash(),output.getByteLength(),output.getContentMimeType());return new RuntimeContent(output.getOriginalFilename(),output.getContentMimeType(),stored.content());}
        throw failure(Reason.GRANT_REVOKED);
    }

    /** Root -> grant -> execution FOR UPDATE; one native claim per unexpired lease. No provider calls. */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConversationLease claimConversationStart(RuntimeScope scope, String taskId, String runId,
            String commandId, String messageId) {
        requireConversationExecutionEnabled();
        var candidate=runtimeExecution(scope,taskId,runId,false);
        if (!"CONVERSATION".equals(candidate.getExecutionMode())) throw failure(Reason.NOT_FOUND);
        requireStartCommand(candidate,commandId,messageId);
        return withConversationRoot(scope,taskId,runId,true,execution -> {
            requireStartCommand(execution,commandId,messageId);
            // A previously admitted Provider call may be complete, in flight or outcome-unknown.
            // Do not reissue it merely because the client or lease changed.
            if (execution.getConversationProviderStartedAt()!=null) throw failure(Reason.TASK_CONFLICT);
            long now=System.currentTimeMillis();
            Long expiry=execution.getConversationLeaseExpiresAt();
            if (expiry!=null && expiry>now) {
                if (!same(scope.runtimeInstanceId(),execution.getConversationLeaseRuntimeId()))
                    throw failure(Reason.TASK_CONFLICT);
                return conversationLease(execution);
            }
            long next;
            try { next=Math.addExact(Objects.requireNonNullElse(execution.getConversationLeaseVersion(),0L),1L); }
            catch (ArithmeticException overflow) { throw failure(Reason.TASK_CONFLICT); }
            if(next>MAX_SAFE_INTEGER)throw failure(Reason.TASK_CONFLICT);
            execution.setConversationLeaseVersion(next).setConversationLeaseToken(UUID.randomUUID().toString())
                    .setConversationLeaseRuntimeId(scope.runtimeInstanceId())
                    .setConversationLeaseExpiresAt(Math.addExact(now,CONVERSATION_LEASE_MILLIS));
            executions.update(execution);
            return conversationLease(execution);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConversationLease renewConversationLease(RuntimeScope scope, String taskId, String runId,
            ConversationFence fence) {
        requireConversationExecutionEnabled();
        return withConversationRoot(scope,taskId,runId,true,execution -> {
            requireConversationFence(scope,execution,fence,false);
            execution.setConversationLeaseExpiresAt(Math.addExact(System.currentTimeMillis(),CONVERSATION_LEASE_MILLIS));
            executions.update(execution);
            return conversationLease(execution);
        });
    }

    /** Persist before any paid call, under the task-root/grant/execution lock. A lost ACK
     * is outcome-unknown: neither the same lease nor a fresh runtime may pay twice. */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void beginConversationProviderStart(RuntimeScope scope, String taskId, String runId,
            ConversationFence fence) {
        requireConversationExecutionEnabled();
        withConversationRoot(scope,taskId,runId,true,execution -> {
            requireConversationFence(scope,execution,fence,false);
            if (execution.getControlledConsentId()!=null) throw failure(Reason.CAPABILITY_UNAVAILABLE);
            verifiedConversationInputs(scope,execution);
            if (execution.getConversationProviderStartedAt()!=null ||
                    execution.getConversationProviderLeaseVersion()!=null)
                throw failure(Reason.TASK_CONFLICT);
            execution.setConversationProviderStartedAt(System.currentTimeMillis())
                    .setConversationProviderLeaseVersion(fence.version());
            executions.update(execution);
            return null;
        });
    }

    @Override
    public ControlledProviderStartReceipt beginControlledConversationProviderStart(RuntimeScope scope,
            String taskId,String runId,ControlledProviderStart command) {
        requireConversationExecutionEnabled();validateRuntimeScope(scope);id(taskId,"taskId",100);id(runId,"runId",100);
        if(command==null||command.schemaVersion()!=2||command.providerExecution()==null||command.fence()==null
                ||!safeId(command.executionId(),100)||!safeId(command.commandId(),100)||!safeId(command.messageId(),100))
            throw failure(Reason.BAD_REQUEST);
        var candidate=runtimeExecution(scope,taskId,runId,false);
        if(candidate.getControlledConsentId()==null||!same(candidate.getExecutionId(),command.executionId()))
            throw failure(Reason.NOT_FOUND);
        requireStartCommand(candidate,command.commandId(),command.messageId());
        if(taskMutations==null||conversationGrants==null||controlledConsents==null)throw failure(Reason.CAPABILITY_UNAVAILABLE);
        return taskMutations.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,root->{
            var grantScope=new AgentTaskExecutionGrantService.Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn());
            var admission=conversationGrants.admitControlled(grantScope,taskId,candidate.getTaskGrantId(),
                    candidate.getTaskGrantVersion(),candidate.getAssignmentRevision(),candidate.getTargetAgentId(),
                    candidate.getPermittedOperation(),"PROVIDER_START",candidate.getExecutionId(),candidate.getRunId(),
                    scope.runtimeInstanceId());
            ProviderExecution expected=providerExecution(admission,new OwnerScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn()),
                    taskId,candidate.getControlledConsentId());
            if(!expected.equals(command.providerExecution()))throw failure(Reason.TASK_CONFLICT);
            var consentScope=new cn.jia.agent.service.AgentTaskProviderCostConsentService.Scope(
                    scope.tenantId(),scope.clientId(),scope.ownerJiacn());
            var consent=controlledConsents.lockForBridge(consentScope,taskId,candidate.getControlledConsentId());
            var execution=runtimeExecution(scope,taskId,runId,true);
            if(!same(candidate.getExecutionId(),execution.getExecutionId())
                    ||!same(candidate.getTaskGrantId(),execution.getTaskGrantId())
                    ||!Objects.equals(candidate.getTaskGrantVersion(),execution.getTaskGrantVersion())
                    ||!Objects.equals(candidate.getAssignmentRevision(),execution.getAssignmentRevision())
                    ||!same(candidate.getControlledConsentId(),execution.getControlledConsentId()))
                throw failure(Reason.TASK_CONFLICT);
            requireConversationRoot(root,new OwnerScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn()),taskId,
                    execution.getTargetAgentId(),execution.getAssignmentRevision());
            requireStartCommand(execution,command.commandId(),command.messageId());
            requireConversationFence(scope,execution,command.fence(),false);
            requireCurrentControlledConversationAccess(
                    new OwnerScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn()),execution);
            if(execution.getConversationProviderStartedAt()!=null||execution.getConversationProviderLeaseVersion()!=null)
                throw failure(Reason.TASK_CONFLICT);
            verifiedConversationInputsAgainst(scope,execution,admission.inputs());
            long leaseVersion=command.fence().version();
            controlledConsents.consumeWithinLockedRoot(consentScope,taskId,consent,
                    Objects.requireNonNull(admission.costAuthorizationVersion()),execution.getExecutionId(),
                    execution.getRunId(),"pwe_lease_"+plainSha("controlled-provider-start\n"
                            +execution.getExecutionId()+"\n"+scope.runtimeInstanceId()+"\n"+leaseVersion));
            long startedAt=System.currentTimeMillis();
            if(!executions.markControlledProviderStarted(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),
                    taskId,runId,execution.getExecutionId(),execution.getControlledConsentId(),
                    leaseVersion,startedAt))throw failure(Reason.TASK_CONFLICT);
            execution.setConversationProviderStartedAt(startedAt)
                    .setConversationProviderLeaseVersion(leaseVersion);
            return new ControlledProviderStartReceipt(2,true,taskId,runId,execution.getExecutionId(),
                    command.commandId(),command.messageId(),expected,leaseVersion);
        });
    }

    private List<RuntimeInput> verifiedConversationInputsAgainst(RuntimeScope scope,
            PersonalWorkspaceExecutionEntity execution,
            List<AgentTaskExecutionGrantService.AuthorizedInput> authorized) {
        OwnerScope owner=new OwnerScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn());
        var rows=executions.listInputs(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),
                execution.getExecutionId());
        if (rows==null || authorized==null || rows.size()!=authorized.size() || rows.size()>16)
            throw failure(Reason.GRANT_REVOKED);
        var indexed=new LinkedHashMap<String,PersonalWorkspaceExecutionInputEntity>();
        for (var input:rows) {
            if (input==null || input.getInputRef()==null
                    || indexed.putIfAbsent(input.getInputRef(),input)!=null)
                throw failure(Reason.GRANT_REVOKED);
        }
        var result=new ArrayList<RuntimeInput>();
        for (int index=0;index<authorized.size();index++) {
            String inputRef="input_"+(index+1);
            var input=indexed.get(inputRef);
            var expected=authorized.get(index);
            if (!exactControlledInput(owner,execution.getExecutionId(),inputRef,input,expected))
                throw failure(Reason.GRANT_REVOKED);
            // The grant admission immediately above already holds the canonical file locks in
            // deterministic order. These owner-scoped reads verify the same current rows without
            // acquiring a file lock after the consent/execution locks.
            var file=workspace.findFile(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),
                    input.getFileId());
            var version=workspace.findVersion(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),
                    input.getFileId(),input.getFileVersion());
            if (file==null || !"ACTIVE".equals(file.getState()) || version==null
                    || !same(version.getFileId(),input.getFileId())
                    || !Objects.equals(version.getVersion(),input.getFileVersion())
                    || !same(version.getOriginalFilename(),input.getOriginalFilename())
                    || !same(version.getContentMimeType(),input.getContentMimeType())
                    || !Objects.equals(version.getByteLength(),input.getByteLength())
                    || !same(version.getContentHash(),input.getContentHash())
                    || !same(version.getStorageUri(),input.getStorageUri()))
                throw failure(Reason.GRANT_REVOKED);
            PersonalWorkspaceStorage.StoredContent stored;
            try {
                stored=storage.read(storageScope(scope),input.getStorageUri(),input.getContentHash(),
                        input.getByteLength(),input.getContentMimeType());
            } catch (RuntimeException unavailable) {
                throw failure(Reason.STORAGE_UNAVAILABLE);
            }
            byte[] bytes=stored==null?null:stored.content();
            if (stored==null || bytes==null || bytes.length!=input.getByteLength()
                    || stored.byteLength()!=input.getByteLength()
                    || !same(stored.mimeType(),input.getContentMimeType())
                    || !same(stored.sha256(),input.getContentHash())
                    || !same(plainSha(bytes),input.getContentHash()))
                throw failure(Reason.STORAGE_UNAVAILABLE);
            result.add(runtimeInput(input));
        }
        return List.copyOf(result);
    }

    private static boolean exactControlledInput(OwnerScope scope,String executionId,String inputRef,
            PersonalWorkspaceExecutionInputEntity input,
            AgentTaskExecutionGrantService.AuthorizedInput expected) {
        return expected!=null && isExactInput(scope,executionId,input)
                && same(inputRef,input.getInputRef()) && "ACTIVE".equals(input.getGrantState())
                && same(input.getFileId(),expected.fileId())
                && input.getFileVersion()!=null && input.getFileVersion()==expected.version()
                && Set.of("INPUT","REFERENCE").contains(expected.purpose())
                && same(input.getContentMimeType(),expected.contentMimeType())
                && Set.of("image/png","image/jpeg").contains(input.getContentMimeType())
                && input.getByteLength()!=null && input.getByteLength()==expected.byteLength()
                && same(input.getContentHash(),expected.contentHash())
                && input.getContentHash()!=null && input.getContentHash().matches("[0-9a-f]{64}")
                && input.getStorageUri()!=null && !input.getStorageUri().isBlank()
                && input.getOriginalFilename()!=null && !input.getOriginalFilename().isBlank();
    }

    /** Only persisted ACTIVE rows exactly matching the still-valid grant can leave this boundary. */
    private List<RuntimeInput> verifiedConversationInputs(RuntimeScope scope,
            PersonalWorkspaceExecutionEntity execution) {
        String purpose=conversationAuthorityPurpose(execution);
        var authorized=requireConversationGrant(new OwnerScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn()),
                execution,purpose,"EXISTING_RUN".equals(purpose)?null:scope.runtimeInstanceId()).inputs();
        var rows=executions.listInputs(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),
                execution.getExecutionId());
        int max=execution.getControlledConsentId()==null?32:16;
        if (rows==null || rows.size()!=authorized.size() || rows.size()>max)
            throw failure(Reason.CAPABILITY_UNAVAILABLE);
        var indexed=new LinkedHashMap<String,PersonalWorkspaceExecutionInputEntity>();
        for (var input:rows) {
            if (input==null || input.getInputRef()==null
                    || indexed.putIfAbsent(input.getInputRef(),input)!=null)
                throw failure(Reason.CAPABILITY_UNAVAILABLE);
        }
        var result=new ArrayList<RuntimeInput>();
        for (int i=0;i<rows.size();i++) {
            var input=indexed.get("input_"+(i+1));var expected=authorized.get(i);
            if (!isExactInput(new OwnerScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn()),
                    execution.getExecutionId(),input)
                    || !same(input.getInputRef(),"input_"+(i+1))
                    || !"ACTIVE".equals(input.getGrantState())
                    || !same(input.getFileId(),expected.fileId())
                    || input.getFileVersion()==null || input.getFileVersion()!=expected.version()
                    || !same(input.getContentMimeType(),expected.contentMimeType())
                    || !Set.of("image/png","image/jpeg").contains(input.getContentMimeType())
                    || input.getByteLength()==null || input.getByteLength()!=expected.byteLength()
                    || !same(input.getContentHash(),expected.contentHash())
                    || input.getStorageUri()==null) throw failure(Reason.CAPABILITY_UNAVAILABLE);
            result.add(runtimeInput(input));
        }
        return List.copyOf(result);
    }

    /** The no-reference fact comes from persisted execution inputs, never inbox/model text. */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConversationInputSnapshot conversationInputs(RuntimeScope scope, String taskId, String runId,
            ConversationFence fence) {
        requireConversationExecutionEnabled();
        return withConversationRoot(scope, taskId, runId, true, execution -> {
            requireConversationFence(scope, execution, fence, false);
            if (Objects.equals(3, execution.getExecutionProtocolVersion()))
                throw failure(Reason.CAPABILITY_UNAVAILABLE);
            var inputs=verifiedConversationInputs(scope,execution);
            return new ConversationInputSnapshot(execution.getExecutionId(), fence.version(),
                    inputs.isEmpty(),inputs);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RuntimeContent conversationInputContent(RuntimeScope scope, String taskId, String runId,
            ConversationFence fence, String inputRef) {
        requireConversationExecutionEnabled();id(inputRef,"inputRef",100);
        return withConversationRoot(scope,taskId,runId,true,execution -> {
            requireConversationFence(scope,execution,fence,false);
            if(Objects.equals(3,execution.getExecutionProtocolVersion())) {
                if(followupAuthority==null||followupSources==null)throw failure(Reason.CAPABILITY_UNAVAILABLE);
                followupAuthority.runtimeAuthority(new ControlledImageFollowupAuthorityService.RuntimeScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),scope.agentId(),scope.runtimeInstanceId()),taskId,runId,v3ReadPurpose(execution));
                var source=verifiedV3SourceRows(scope,execution).stream().filter(v->same(inputRef,v.getInputRef())).findFirst().orElseThrow(()->failure(Reason.NOT_FOUND));
                return readV3Source(scope,execution,source);
            }
            var inputs=verifiedConversationInputs(scope,execution);
            var match=inputs.stream().filter(input -> same(input.inputRef(),inputRef)).findFirst()
                    .orElseThrow(() -> failure(Reason.NOT_FOUND));
            var content=inputContent(scope,execution,inputRef);
            if (content==null || content.bytes()==null || content.bytes().length!=match.byteLength()
                    || !same(plainSha(content.bytes()),match.sha256())
                    || !same(content.contentMimeType(),match.contentMimeType()))
                throw failure(Reason.STORAGE_UNAVAILABLE);
            return content;
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StagedOutput stageConversationOutput(RuntimeScope scope, String taskId, String runId,
            ConversationFence fence, String outputId, String filename, String mimeType, byte[] content) {
        requireConversationExecutionEnabled();
        return withConversationRoot(scope,taskId,runId,true,execution -> {
            requireConversationFence(scope,execution,fence,false);
            requireControlledV3StartedForResult(execution);
            return stageOutputLocked(scope,execution,outputId,filename,mimeType,content);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CommitView commitConversationOutput(RuntimeScope scope, String taskId, String runId,
            ConversationFence fence, String manifestId, List<OutputDeclaration> outputs) {
        requireConversationExecutionEnabled();id(manifestId,"manifestId",100);validateManifest(outputs);
        return withConversationRoot(scope,taskId,runId,true,true,false,execution -> {
            requireConversationFence(scope,execution,fence,"OUTPUT_COMMITTED".equals(execution.getExecutionState()));
            requireControlledV3StartedForResult(execution);
            return commitConversationOutputs(scope,execution,manifestId,outputs);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExecutionView failConversation(RuntimeScope scope, String taskId, String runId,
            ConversationFence fence, String code) {
        requireConversationExecutionEnabled();runtimeFailureCode(code);
        return withConversationRoot(scope,taskId,runId,true,false,true,execution -> {
            requireConversationFence(scope,execution,fence,"FAILED".equals(execution.getExecutionState()));
            return failLocked(scope,execution);
        });
    }

    private static void requireControlledV3StartedForResult(PersonalWorkspaceExecutionEntity execution) {
        if (Objects.equals(3, execution.getExecutionProtocolVersion())
                && (execution.getConversationProviderStartedAt()==null
                    || execution.getConversationProviderLeaseVersion()==null))
            throw failure(Reason.TASK_CONFLICT);
    }

    private void requireConversationExecutionEnabled() {
        if (!conversationExecutionEnabled) throw failure(Reason.CAPABILITY_UNAVAILABLE);
    }

    private static ConversationLease conversationLease(PersonalWorkspaceExecutionEntity execution) {
        return new ConversationLease(execution.getExecutionId(),execution.getConversationLeaseVersion(),
                execution.getConversationLeaseToken(),execution.getConversationLeaseExpiresAt());
    }

    private static void requireConversationFence(RuntimeScope scope,PersonalWorkspaceExecutionEntity execution,
            ConversationFence fence, boolean terminalReplay) {
        Long expiry=execution.getConversationLeaseExpiresAt();
        if (fence==null || fence.version()<1 || fence.version()>MAX_SAFE_INTEGER || fence.token()==null || fence.token().isBlank()
                || execution.getConversationLeaseVersion()==null || execution.getConversationLeaseVersion()!=fence.version()
                || !same(fence.token(),execution.getConversationLeaseToken())
                || !same(scope.runtimeInstanceId(),execution.getConversationLeaseRuntimeId())
                || expiry==null || (!terminalReplay && expiry<=System.currentTimeMillis()))
            throw failure(Reason.TASK_CONFLICT);
    }

    private static void requireStartCommand(PersonalWorkspaceExecutionEntity execution,
            String commandId, String messageId) {
        if (!same("pwe_cmd_" + plainSha("command\n" + execution.getExecutionId()), commandId)
                || !same("pwe_msg_" + plainSha("message\n" + execution.getExecutionId()), messageId)) {
            throw failure(Reason.NOT_FOUND);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<RuntimeInput> runtimeInputs(RuntimeScope scope, String taskId, String runId) {
        PersonalWorkspaceExecutionEntity candidate = runtimeExecution(scope, taskId, runId, false);
        if ("CONVERSATION".equals(candidate.getExecutionMode())) throw failure(Reason.CAPABILITY_UNAVAILABLE);
        return runtimeInputs(new OwnerScope(scope.tenantId(), scope.clientId(), scope.ownerJiacn()), candidate.getExecutionId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<RuntimeQueuedCommand> runtimeQueuedCommands(RuntimeScope scope, int limit) {
        validateRuntimeScope(scope);
        if (limit < 1 || limit > 16) throw failure(Reason.BAD_REQUEST);
        OwnerScope owner = new OwnerScope(scope.tenantId(), scope.clientId(), scope.ownerJiacn());
        return executions.listQueuedByTarget(scope.tenantId(), scope.clientId(), scope.ownerJiacn(),
                        scope.agentId(), limit).stream()
                // DAO filtering is the primary isolation boundary; retain an in-service exact check
                // so a mapper regression can never hand a queue item to a different runtime Agent.
                // Existing HTTP pickup has no fence-bearing upload/commit contract; never dispatch CONVERSATION.
                .filter(execution -> execution != null && !"CONVERSATION".equals(execution.getExecutionMode())
                        && "QUEUED".equals(execution.getExecutionState())
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
        PersonalWorkspaceExecutionEntity candidate=runtimeExecution(scope,taskId,runId,false);
        if ("CONVERSATION".equals(candidate.getExecutionMode())) throw failure(Reason.CAPABILITY_UNAVAILABLE);
        return inputContent(scope,runtimeExecution(scope,taskId,runId,true),inputRef);
    }

    private RuntimeContent inputContent(RuntimeScope scope,PersonalWorkspaceExecutionEntity execution,String inputRef) {
        id(inputRef, "inputRef", 100);
        PersonalWorkspaceExecutionInputEntity input = executions.lockInput(scope.tenantId(), scope.clientId(),
                scope.ownerJiacn(), execution.getExecutionId(), inputRef);
        if (input == null || !"ACTIVE".equals(input.getGrantState())
                || !SUPPORTED_INPUT_MIME_TYPES.contains(input.getContentMimeType())) {
            throw failure(Reason.NOT_FOUND);
        }
        PersonalWorkspaceStorage.StoredContent content = storage.read(storageScope(scope), input.getStorageUri(),
                input.getContentHash(), input.getByteLength(), input.getContentMimeType());
        return new RuntimeContent(input.getOriginalFilename(), input.getContentMimeType(), content.content());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StagedOutput stageOutput(RuntimeScope scope, String taskId, String runId, String outputId,
            String originalFilename, String contentMimeType, byte[] content) {
        PersonalWorkspaceExecutionEntity candidate=runtimeExecution(scope,taskId,runId,false);
        if ("CONVERSATION".equals(candidate.getExecutionMode())) throw failure(Reason.CAPABILITY_UNAVAILABLE);
        return stageOutputLocked(scope,runtimeExecution(scope,taskId,runId,true),outputId,
                originalFilename,contentMimeType,content);
    }

    private StagedOutput stageOutputLocked(RuntimeScope scope,PersonalWorkspaceExecutionEntity execution,
            String outputId,String originalFilename,String contentMimeType,byte[] content) {
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
                    || !same(filePurpose(previous),"CONVERSATION".equals(execution.getExecutionMode())
                        ? "CONVERSATION" : "FILE") || !"STAGED".equals(previous.getOutputState())) throw failure(Reason.OUTPUT_CONFLICT);
            return new StagedOutput(outputId, previous.getContentHash(), previous.getByteLength(), previous.getOutputState());
        }
        long now = System.currentTimeMillis();
        PersonalWorkspaceExecutionOutputEntity output = new PersonalWorkspaceExecutionOutputEntity()
                .setOutputId(outputId).setExecutionId(execution.getExecutionId()).setOwnerJiacn(scope.ownerJiacn())
                .setOriginalFilename(originalFilename).setContentMimeType(contentMimeType).setByteLength(stored.byteLength())
                .setContentHash(stored.sha256()).setStorageUri(stored.storageUri())
                .setOutputPurpose("CONVERSATION".equals(execution.getExecutionMode()) ? "CONVERSATION" : "FILE")
                .setOutputState("STAGED")
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
        if ("CONVERSATION".equals(candidate.getExecutionMode())) throw failure(Reason.CAPABILITY_UNAVAILABLE);
        if ("TASK".equals(candidate.getExecutionMode())) {
            requireTaskPublicationDependencies();
            return taskMutations.executeWithLockedTaskRootInOwnerScope(
                    scope.tenantId(), scope.clientId(), scope.ownerJiacn(), taskId,
                    root -> commitTaskOutputsLocked(scope, taskId, runId, manifestId, declarations, root));
        }
        PersonalWorkspaceExecutionEntity execution = runtimeExecution(scope, taskId, runId, true, true);
        return commitPrivateOutputs(scope, execution, manifestId, declarations);
    }

    private CommitView commitConversationOutputs(RuntimeScope scope, PersonalWorkspaceExecutionEntity execution,
            String manifestId,List<OutputDeclaration> declarations) {
        List<PersonalWorkspaceExecutionOutputEntity> outputs=lockAndVerifyManifest(scope,execution,manifestId,declarations);
        var output=outputs.getFirst();
        if (!"CONVERSATION".equals(output.getOutputPurpose()) || output.getWorkspaceFileId()!=null
                || output.getWorkspaceFileVersion()!=null || output.getArtifactId()!=null
                || output.getFormalDeliveryId()!=null || !"PENDING".equals(output.getPublicationState()))
            throw failure(Reason.OUTPUT_CONFLICT);
        if ("COMMITTED".equals(output.getOutputState())) return committed(manifestId,outputs);
        if (!"STAGED".equals(output.getOutputState()) || !"QUEUED".equals(execution.getExecutionState()))
            throw failure(Reason.OUTPUT_CONFLICT);
        var content=storage.read(storageScope(scope),output.getStorageUri(),output.getContentHash(),
                output.getByteLength(),output.getContentMimeType());
        if (content.content()==null || content.content().length!=output.getByteLength())
            throw failure(Reason.STORAGE_UNAVAILABLE);
        output.setOutputState("COMMITTED").setCommittedAt(System.currentTimeMillis());
        executions.updateOutput(output);
        execution.setExecutionState("OUTPUT_COMMITTED");executions.update(execution);
        return committed(manifestId,outputs);
    }

    /** Root -> grant admission -> execution/output; no execution-to-task lock inversion. */
    private <T> T withConversationRoot(RuntimeScope scope,String taskId,String runId,boolean lock,
            java.util.function.Function<PersonalWorkspaceExecutionEntity,T> action) {
        return withConversationRoot(scope,taskId,runId,lock,false,false,action);
    }
    private <T> T withConversationRoot(RuntimeScope scope,String taskId,String runId,boolean lock,
            boolean allowCommitted,boolean allowFailed,
            java.util.function.Function<PersonalWorkspaceExecutionEntity,T> action) {
        validateRuntimeScope(scope);id(taskId,"taskId",100);id(runId,"runId",100);
        if (taskMutations==null || conversationGrants==null) throw failure(Reason.CAPABILITY_UNAVAILABLE);
        // The unlocked candidate chooses the lane only. Controlled execution always re-verifies
        // every persisted identity under root -> grant -> consent before taking the execution lock.
        var candidate=runtimeExecution(scope,taskId,runId,false,allowCommitted,allowFailed);
        return taskMutations.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),scope.clientId(),
                scope.ownerJiacn(),taskId,root -> {
                    var owner=new OwnerScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn());
                    if (!"CONVERSATION".equals(candidate.getExecutionMode())) throw failure(Reason.NOT_FOUND);
                    requireConversationRoot(root,owner,taskId,candidate.getTargetAgentId(),
                            candidate.getAssignmentRevision());
                    boolean controlled=candidate.getControlledConsentId()!=null;
                    String purpose=controlled
                            ? candidate.getConversationProviderStartedAt()==null
                                ? "PROVIDER_START" : "EXISTING_RUN"
                            : "NEW_EXECUTION";
                    if (controlled) requireConversationGrant(owner,candidate,purpose,
                            Objects.equals(3,candidate.getExecutionProtocolVersion())?scope.runtimeInstanceId()
                                    :("EXISTING_RUN".equals(purpose)?null:scope.runtimeInstanceId()));
                    var execution=runtimeExecution(scope,taskId,runId,lock,allowCommitted,allowFailed);
                    if (!same(candidate.getExecutionId(),execution.getExecutionId())
                            || !same(candidate.getTaskGrantId(),execution.getTaskGrantId())
                            || !Objects.equals(candidate.getTaskGrantVersion(),execution.getTaskGrantVersion())
                            || !Objects.equals(candidate.getAssignmentRevision(),execution.getAssignmentRevision())
                            || !Objects.equals(candidate.getControlledConsentId(),execution.getControlledConsentId()))
                        throw failure(Reason.TASK_CONFLICT);
                    requireConversationRoot(root,owner,taskId,execution.getTargetAgentId(),
                            execution.getAssignmentRevision());
                    if (!controlled) requireConversationGrant(owner,execution,purpose,
                            scope.runtimeInstanceId());
                    else requireCurrentControlledConversationAccess(owner,execution);
                    return action.apply(execution);
                });
    }

    /** Private output archiving remains source-compatible and never reaches task artifact state. */
    private CommitView commitPrivateOutputs(RuntimeScope scope, PersonalWorkspaceExecutionEntity execution,
            String manifestId, List<OutputDeclaration> declarations) {
        List<PersonalWorkspaceExecutionOutputEntity> outputs = lockAndVerifyManifest(
                scope, execution, manifestId, declarations);
        PersonalWorkspaceExecutionOutputEntity output = outputs.getFirst();
        if (!"FILE".equals(filePurpose(output))) throw failure(Reason.OUTPUT_CONFLICT);
        if ("COMMITTED".equals(output.getOutputState())) return committed(manifestId, outputs);
        if (!"STAGED".equals(output.getOutputState()))
            throw failure(Reason.OUTPUT_CONFLICT);
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
        if (!"FILE".equals(filePurpose(output))) throw failure(Reason.OUTPUT_CONFLICT);
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
        if ("CONVERSATION".equals(candidate.getExecutionMode())) throw failure(Reason.CAPABILITY_UNAVAILABLE);
        if ("TASK".equals(candidate.getExecutionMode()) && "QUEUED".equals(candidate.getExecutionState())) {
            releaseTaskLease(new OwnerScope(scope.tenantId(), scope.clientId(), scope.ownerJiacn()), candidate);
        }
        PersonalWorkspaceExecutionEntity execution = executions.lockByTaskRun(scope.tenantId(), scope.clientId(),
                scope.ownerJiacn(), taskId, runId);
        if (execution == null || !same(execution.getTargetAgentId(), scope.agentId())) throw failure(Reason.NOT_FOUND);
        return failLocked(scope,execution);
    }

    private ExecutionView failLocked(RuntimeScope scope,PersonalWorkspaceExecutionEntity execution) {
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
        if (execution.getExecutionMode()!=null
                && !Set.of("PRIVATE","TASK","CONVERSATION").contains(execution.getExecutionMode()))
            throw failure(Reason.NOT_FOUND);
        return execution;
    }

    private ExecutionView view(OwnerScope scope, PersonalWorkspaceExecutionEntity execution) {
        return view(scope, execution, runtimeInputs(scope, execution.getExecutionId()));
    }
    private ExecutionView requestView(OwnerScope scope, PersonalWorkspaceExecutionEntity execution) {
        List<PersonalWorkspaceExecutionInputEntity> rows = executions.listInputs(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), execution.getExecutionId());
        if (rows == null || rows.stream().anyMatch(row -> !isExactInput(
                scope, execution.getExecutionId(), row))) {
            throw failure(Reason.NOT_FOUND);
        }
        return view(scope, execution, rows.stream().map(PersonalWorkspaceExecutionServiceImpl::runtimeInput)
                .toList());
    }
    private ExecutionView view(OwnerScope scope, PersonalWorkspaceExecutionEntity execution,
            List<RuntimeInput> inputs) {
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
                .map(PersonalWorkspaceExecutionServiceImpl::runtimeInput).toList();
    }
    private static RuntimeInput runtimeInput(PersonalWorkspaceExecutionInputEntity input) {
        return new RuntimeInput(input.getInputRef(), input.getFileId(), input.getFileVersion(),
                input.getOriginalFilename(), input.getContentMimeType(), input.getByteLength(),
                input.getContentHash());
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
            if (inputExtension == null || !SUPPORTED_INPUT_MIME_TYPES.contains(input.contentMimeType())) return null;
            String inputPath = INTERNAL_PREFIX + execution.getTaskId() + "/runs/" + execution.getRunId()
                    + "/inputs/" + input.inputRef() + "/content";
            inputManifest.add(new RuntimeInputCommand(input.inputRef(), "inputs/" + input.inputRef() + inputExtension,
                    inputPath, input.byteLength(), input.sha256()));
        }
        return new RuntimeCommand(execution.getTaskId(), execution.getRunId(), List.copyOf(inputManifest),
                List.of(new RuntimeOutput("output_1", "outputs/result" + extension, outputMime,
                        storage.maxContentBytes(), outputPath)));
    }
    // Historical unit fixtures omit this new NOT NULL database column; the migration defaults it to FILE.
    private static String filePurpose(PersonalWorkspaceExecutionOutputEntity row) {
        return row.getOutputPurpose()==null ? "FILE" : row.getOutputPurpose();
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
            if (execution.getLeaseExpiresAt() != null && execution.getLeaseExpiresAt() <= System.currentTimeMillis()) {
                // The exact expiry service rechecks owner/task/token/version and the live
                // clock under the task root. Never extend the stale lease or scan others.
                leases.expireExactLease(scope.tenantId(), scope.clientId(), scope.ownerJiacn(), execution.getTaskId(),
                        execution.getWorkItemId(), command);
            } else {
                leases.release(scope.tenantId(), scope.clientId(), scope.ownerJiacn(), execution.getTaskId(),
                        execution.getWorkItemId(), command);
            }
        } catch (AgentTaskCollaborationException | AgentTaskStateException conflict) {
            throw failure(Reason.TASK_CONFLICT);
        }
    }

    private boolean runtimeDispatchAllowed(RuntimeScope scope, PersonalWorkspaceExecutionEntity execution) {
        if ("PRIVATE".equals(execution.getExecutionMode()) || execution.getExecutionMode()==null) return true;
        try {
            if ("CONVERSATION".equals(execution.getExecutionMode())) {
                OwnerScope owner=new OwnerScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn());
                String purpose=conversationAuthorityPurpose(execution);
                requireConversationGrant(owner,execution,purpose,
                        Objects.equals(3,execution.getExecutionProtocolVersion())?scope.runtimeInstanceId()
                                :("EXISTING_RUN".equals(purpose)?null:scope.runtimeInstanceId()));
                if (execution.getControlledConsentId()!=null)
                    requireCurrentControlledConversationAccess(owner,execution);
            }
            else if ("TASK".equals(execution.getExecutionMode())) requireLiveTaskLease(scope, execution);
            else return false;
            return true;
        }
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
        if (!executionAvailable || !SUPPORTED_OUTPUT_MIME_TYPES.contains(outputMime)
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
        SourceOutputRef source = command.sourceOutputRef();
        if (source != null) {
            if (task == null || source.decisionVersion() < 1 || selections.size() != 1) {
                throw failure(Reason.BAD_REQUEST);
            }
            id(source.formalDeliveryId(), "formalDeliveryId", 100);
            id(source.outputId(), "outputId", 100);
            id(source.fileId(), "sourceFileId", 100);
            if (source.fileVersion() < 1
                    || !same(source.fileId(), selections.getFirst().fileId())
                    || source.fileVersion() != selections.getFirst().version()) {
                throw failure(Reason.BAD_REQUEST);
            }
        }
        return new ValidCreate(conversation, command.targetAgentId(), task, command.instruction(), outputMime,
                selections, source);
    }
    private static String selectionsWire(List<InputSelection> inputs) { return inputs.stream().sorted(Comparator.comparing(InputSelection::fileId).thenComparingInt(InputSelection::version)).map(i -> i.fileId()+":"+i.version()).reduce("", (a,b)->a+"\n"+b); }
    private static String sourceOutputWire(SourceOutputRef source) {
        return source.formalDeliveryId() + "\n" + source.decisionVersion() + "\n"
                + source.outputId() + "\n" + source.fileId() + "\n" + source.fileVersion();
    }
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
    private static boolean isExactOwner(OwnerScope scope, PersonalWorkspaceExecutionEntity execution) {
        return execution != null && same(execution.getTenantId(), scope.tenantId())
                && same(execution.getClientId(), scope.clientId())
                && same(execution.getOwnerJiacn(), scope.ownerJiacn());
    }
    private static boolean isExactInput(OwnerScope scope, String executionId,
            PersonalWorkspaceExecutionInputEntity input) {
        return input != null && same(input.getTenantId(), scope.tenantId())
                && same(input.getClientId(), scope.clientId())
                && same(input.getOwnerJiacn(), scope.ownerJiacn())
                && same(input.getExecutionId(), executionId);
    }
    private static boolean validHistoryRow(PersonalWorkspaceExecutionEntity row) {
        return safeId(row.getExecutionId(), 100) && safeId(row.getTargetAgentId(), 100)
                && safeId(row.getExecutionState(), 40)
                && safeId(row.getOutputContentMimeType(), 127)
                && row.getCreatedAt() != null && row.getCreatedAt() >= 0;
    }
    private static boolean strictlyBeforeCursor(PersonalWorkspaceExecutionEntity row,
            Long beforeCreatedAt, String beforeExecutionId) {
        if (beforeCreatedAt == null) return true;
        return row.getCreatedAt() < beforeCreatedAt
                || (row.getCreatedAt().equals(beforeCreatedAt)
                    && compareUtf8(row.getExecutionId(), beforeExecutionId) < 0);
    }
    private static int compareHistoryRows(PersonalWorkspaceExecutionEntity left,
            PersonalWorkspaceExecutionEntity right) {
        int created = Long.compare(right.getCreatedAt(), left.getCreatedAt());
        return created != 0 ? created : compareUtf8(right.getExecutionId(), left.getExecutionId());
    }
    private static int compareUtf8(String left, String right) {
        byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
        byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
        int length = Math.min(leftBytes.length, rightBytes.length);
        for (int index = 0; index < length; index++) {
            int compared = Integer.compare(Byte.toUnsignedInt(leftBytes[index]),
                    Byte.toUnsignedInt(rightBytes[index]));
            if (compared != 0) return compared;
        }
        return Integer.compare(leftBytes.length, rightBytes.length);
    }
    private static boolean safeId(String value, int max) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.codePointCount(0, value.length()) <= max
                && value.chars().noneMatch(Character::isISOControl);
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
            if (!SUPPORTED_OUTPUT_MIME_TYPES.contains(mime) || !allowed.add(mime)) {
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
                               String outputContentMimeType,List<InputSelection> inputs,
                               SourceOutputRef sourceOutputRef){}
    private record InputSnapshot(PersonalWorkspaceFileEntity file, PersonalWorkspaceVersionEntity version){}
}
