package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskBountyBootstrapOutboxDao;
import cn.jia.agent.dao.AgentTaskExecutionGrantDao;
import cn.jia.agent.dao.PersonalWorkspaceDao;
import cn.jia.agent.dao.PersonalWorkspaceTaskLinkDao;
import cn.jia.agent.entity.AgentTaskAssignDTO;
import cn.jia.agent.entity.AgentTaskBountyBootstrapClaimDTO;
import cn.jia.agent.entity.AgentTaskBountyBootstrapOutboxEntity;
import cn.jia.agent.entity.AgentTaskExecutionGrantDTO;
import cn.jia.agent.entity.AgentTaskExecutionGrantEntity;
import cn.jia.agent.entity.AgentTaskGrantInputDTO;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.PersonalWorkspaceFileEntity;
import cn.jia.agent.entity.PersonalWorkspaceTaskFileLinkEntity;
import cn.jia.agent.entity.PersonalWorkspaceVersionEntity;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.service.AgentTaskExecutionGrantException;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentTaskRequirementSnapshotService;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.dao.DataIntegrityViolationException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static cn.jia.agent.service.AgentTaskExecutionGrantException.Reason;

@Named
public final class AgentTaskExecutionGrantServiceImpl implements AgentTaskExecutionGrantService {
    static final String ACTION = "assign_and_start";
    static final String POLICY_REVISION = "MMD_U1_V1";
    static final String TOOL_POLICY = "NO_TOOLS_V1";
    static final String ASSIGN_HASH_DOMAIN = "ASSIGN_AND_START";
    static final String INITIAL_OPERATION_HASH_DOMAIN = "ASSIGN_AND_START_INITIAL_OPERATION_V1";
    private static final Set<String> OPERATIONS = Set.of(
            "INSPECT_INPUTS", "GENERATE_IMAGE", "EDIT_IMAGE", "GENERATE_AUDIO", "EDIT_AUDIO");
    private static final Set<String> PURPOSES = Set.of("INPUT", "REFERENCE");
    private static final int MAX_INPUTS = 32;
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    private final AgentTaskExecutionGrantDao grants;
    private final AgentTaskBountyBootstrapOutboxDao bootstrapOutbox;
    private final AgentTaskRequirementSnapshotService requirementSnapshots;
    private final PersonalWorkspaceTaskLinkDao taskLinks;
    private final PersonalWorkspaceDao workspace;
    private final AgentLegacyTaskCompatibilityService legacyAssignments;
    private final AgentIdentityService identities;
    private final AgentTaskMutationTransaction transactions;
    private final ObjectMapper json;

    @Inject
    public AgentTaskExecutionGrantServiceImpl(AgentTaskExecutionGrantDao grants,
            AgentTaskBountyBootstrapOutboxDao bootstrapOutbox,
            PersonalWorkspaceTaskLinkDao taskLinks, PersonalWorkspaceDao workspace,
            AgentLegacyTaskCompatibilityService legacyAssignments, AgentIdentityService identities,
            AgentTaskMutationTransaction transactions, ObjectMapper json,
            AgentTaskRequirementSnapshotService requirementSnapshots) {
        this.grants = Objects.requireNonNull(grants,"grants");
        this.bootstrapOutbox = Objects.requireNonNull(bootstrapOutbox,"bootstrapOutbox");
        this.taskLinks = Objects.requireNonNull(taskLinks,"taskLinks");
        this.workspace = Objects.requireNonNull(workspace,"workspace");
        this.legacyAssignments = Objects.requireNonNull(legacyAssignments,"legacyAssignments");
        this.identities = Objects.requireNonNull(identities,"identities");
        this.transactions = Objects.requireNonNull(transactions,"transactions");
        this.json = Objects.requireNonNull(json,"json");
        this.requirementSnapshots = Objects.requireNonNull(requirementSnapshots,"requirementSnapshots");
    }

    @Override
    public AgentTaskExecutionGrantDTO assignAndGrant(Scope scope, String taskId,
            String idempotencyKey, AgentTaskAssignDTO request) {
        ValidAssign valid = validateAssign(scope,taskId,idempotencyKey,request);
        String canonicalAgent = legacyAssignments.resolveAgentId(scope.tenantId(),scope.clientId(),
                scope.ownerJiacn(),valid.requestedAgentId());
        String requestHash = hashAssign(valid,canonicalAgent);
        String actionId = "ASSIGN_AND_START:" + valid.idempotencyKey();
        try {
            return transactions.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),scope.clientId(),
                    scope.ownerJiacn(),valid.taskId(),root -> assignLocked(scope,valid,canonicalAgent,
                            requestHash,actionId,root));
        } catch (AgentTaskCollaborationException failure) {
            throw translate(failure);
        }
    }

    private AgentTaskExecutionGrantDTO assignLocked(Scope scope, ValidAssign valid,
            String canonicalAgent, String requestHash, String actionId, AgentTaskMetaEntity root) {
        // Frozen task-root lock order: requirement latest read -> grant action ->
        // bootstrap action -> target/input rows. Requirement history is append-only.
        // The command-delivery outbox is intentionally not reused: this row coordinates Chat
        // admission and must never acquire Rabbit/Agent delivery semantics.
        // Root is already locked. Client revision is only a hint: compare with latest
        // server-owned immutable requirement before taking grant/outbox action locks.
        try {
            var confirmed = requirementSnapshots.requireCurrent(scope,valid.taskId(),valid.requirementRevision());
            if (confirmed == null || confirmed.revision()!=valid.requirementRevision()
                    || !scope.tenantId().equals(confirmed.tenantId())
                    || !scope.clientId().equals(confirmed.clientId())
                    || !scope.ownerJiacn().equals(confirmed.ownerJiacn())
                    || !valid.taskId().equals(confirmed.taskId()))
                throw new IllegalStateException("Requirement snapshot scope/revision mismatch");
        }
        catch (IllegalArgumentException | IllegalStateException absent) {
            throw invalidState("Current owner-confirmed requirement revision is missing or stale");
        }
        AgentTaskExecutionGrantEntity replay = grants.findByActionForUpdate(scope.tenantId(),
                scope.clientId(),scope.ownerJiacn(),actionId);
        AgentTaskBountyBootstrapOutboxEntity existingBootstrap =
                bootstrapOutbox.findByActionForUpdate(scope.tenantId(), scope.clientId(),
                        scope.ownerJiacn(), actionId);
        if (replay != null) {
            AgentTaskExecutionGrantDTO result = replay(requestHash,valid.taskId(),replay);
            if (valid.initialOperation()==null) {
                throw bad("assign_and_start requires exactly one initial permitted operation");
            }
            if (existingBootstrap == null) {
                throw invalidState("Authorization fact is missing its bootstrap intent");
            }
            ensureBootstrapIntent(scope,replay,existingBootstrap,valid.initialOperation(),false);
            return result;
        }
        if (existingBootstrap != null) {
            throw invalidState("Bootstrap intent exists without its authorization fact");
        }
        if (valid.initialOperation()==null) {
            throw bad("assign_and_start requires exactly one initial permitted operation");
        }

        AtomicReference<List<InputSnapshot>> snapshot = new AtomicReference<>();
        AgentLegacyTaskCompatibilityService.AssignOutcome assignment =
                legacyAssignments.assignResolvedVersionedWithLockedTask(scope.tenantId(),
                        scope.clientId(),scope.ownerJiacn(),valid.taskId(),List.of(canonicalAgent),
                        false,valid.expectedTaskVersion(),(task,agentIds) -> snapshot.set(
                                validateAndSnapshotInputs(scope,valid.taskId(),valid.inputs())),root);
        long assignmentRevision = assignment.changed()
                ? Math.addExact(valid.expectedTaskVersion(),1L) : valid.expectedTaskVersion();
        if (snapshot.get() == null) throw invalidState("Grant input snapshot was not produced");
        // Event is emitted by the controlled assignment inside this root transaction. An
        // idempotent assignment may have an older event, but never a future one.
        requireAssignmentEpoch(scope,valid.taskId(),assignmentRevision,root);

        long now=System.currentTimeMillis();
        grants.supersedeActiveForTask(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),
                valid.taskId(),now);
        AgentTaskExecutionGrantEntity entity = new AgentTaskExecutionGrantEntity()
                .setGrantId("grant_" + UUID.randomUUID().toString().replace("-",""));
        // BaseEntity's fluent setters return BaseEntity, so crossing that boundary in the
        // subclass chain hides grant-specific setters at compile time. Keep scope assignment
        // explicit, then resume the grant entity chain.
        entity.setTenantId(scope.tenantId());
        entity.setClientId(scope.clientId());
        entity.setOwnerJiacn(scope.ownerJiacn()).setTaskId(valid.taskId())
                .setRequirementRevision(valid.requirementRevision())
                .setAssignmentRevision(assignmentRevision).setTargetAgentId(canonicalAgent)
                .setPermittedOperationsJson(write(valid.operations()))
                .setPermittedToolPolicyRef(TOOL_POLICY).setInputScopeJson(write(snapshot.get()))
                .setAllowOwnTaskDerivedAssets(false).setCostAuthorizationRef(null)
                .setSourceBusinessActionId(actionId).setIdempotencyKey(valid.idempotencyKey())
                .setRequestHash(requestHash).setPolicyRevision(POLICY_REVISION)
                .setGrantVersion(1L).setState("ACTIVE").setIssuedBy(scope.ownerJiacn())
                .setCreatedAt(now).setRevokedAt(null);
        try {
            grants.insert(entity);
        } catch (DataIntegrityViolationException collision) {
            // The task root and both action keys were locked before mutation. Treat any late
            // grant uniqueness failure as a conflicting authorization and roll back assignment.
            throw new AgentTaskExecutionGrantException(Reason.CONFLICT,
                    "A concurrent task authorization changed the grant intent");
        }
        // Keep this outside the grant collision handler: schema/payload failures must remain
        // attributable while the surrounding task-root transaction rolls back both mutations.
        ensureBootstrapIntent(scope,entity,null,valid.initialOperation(),true);
        return view(entity);
    }

    private void ensureBootstrapIntent(Scope scope, AgentTaskExecutionGrantEntity grant,
            AgentTaskBountyBootstrapOutboxEntity existing, String requestedInitialOperation,
            boolean create) {
        List<String> operations = readOperations(grant.getPermittedOperationsJson());
        if (!operations.contains(requestedInitialOperation)) {
            throw invalidState("Initial operation is outside the persisted authorization set");
        }
        List<AgentTaskBountyBootstrapClaimDTO.ReferenceSummary> references = readInputs(
                grant.getInputScopeJson()).stream().map(input ->
                new AgentTaskBountyBootstrapClaimDTO.ReferenceSummary(input.fileId(), input.version(),
                        input.purpose(), input.contentMimeType(), input.byteLength(),
                        input.contentHash())).toList();
        String referencesJson = AgentTaskBountyBootstrapPayload.referencesJson(json, references);
        String referencesHash = AgentTaskBountyBootstrapPayload.referenceHash(json, references);
        long intentGrantVersion = existing == null ? grant.getGrantVersion()
                : existing.getGrantVersion() == null ? -1L : existing.getGrantVersion();
        String initialOperation = existing == null
                ? requestedInitialOperation : existing.getPermittedOperation();
        String payloadHash = AgentTaskBountyBootstrapPayload.payloadHash(scope.tenantId(),
                scope.clientId(), scope.ownerJiacn(), grant.getTaskId(),
                grant.getSourceBusinessActionId(), grant.getRequirementRevision(),
                grant.getAssignmentRevision(), grant.getTargetAgentId(), grant.getGrantId(),
                intentGrantVersion, initialOperation, referencesHash);
        if (existing != null) {
            try {
                AgentTaskBountyBootstrapPayload.validateAndRead(existing, json);
            } catch (RuntimeException corrupt) {
                throw invalidState("Persisted bootstrap intent is corrupt");
            }
            if (intentGrantVersion < 1 || intentGrantVersion > grant.getGrantVersion()
                    || !operations.contains(initialOperation)
                    || !same(requestedInitialOperation,initialOperation)
                    || !same(payloadHash, existing.getPayloadHash())
                    || !same(grant.getSourceBusinessActionId(), existing.getSourceBusinessActionId())) {
                throw invalidState("Bootstrap intent conflicts with the authorization fact");
            }
            return;
        }
        if (!create) {
            throw invalidState("Authorization fact is missing its bootstrap intent");
        }
        if (!"ACTIVE".equals(grant.getState())) {
            throw invalidState("Historical inactive grant has no bootstrap intent");
        }
        AgentTaskBountyBootstrapOutboxEntity intent =
                new AgentTaskBountyBootstrapOutboxEntity()
                        .setBootstrapId("bootstrap_" + payloadHash.substring(0, 32));
        intent.setTenantId(scope.tenantId());
        intent.setClientId(scope.clientId());
        intent.setOwnerJiacn(scope.ownerJiacn()).setTaskId(grant.getTaskId())
                .setSourceBusinessActionId(grant.getSourceBusinessActionId())
                .setPayloadHash(payloadHash).setRequirementRevision(grant.getRequirementRevision())
                .setRequirementAnchor(AgentTaskBountyBootstrapPayload.REQUIREMENT_ANCHOR)
                .setAssignmentRevision(grant.getAssignmentRevision())
                .setTargetAgentId(grant.getTargetAgentId()).setGrantId(grant.getGrantId())
                .setGrantVersion(grant.getGrantVersion()).setPermittedOperation(initialOperation)
                .setReferenceSummaryJson(referencesJson)
                .setReferenceSummarySha256(referencesHash).setStatus("PENDING")
                .setAttemptCount(0).setNextRetryAt(null).setLeaseOwner(null)
                .setLeaseUntil(null).setAdmittedConversationId(null)
                .setAdmittedRequestId(null).setLastErrorCode(null).setVersion(0L)
                .setCreatedAt(grant.getCreatedAt()).setReconciledAt(null);
        bootstrapOutbox.insert(intent);
    }

    @Override
    public AssignmentPreview previewAssignmentWithinLockedTask(Scope scope, String taskId,
            long lockedTaskVersion, String assignmentIdempotencyKey, AgentTaskAssignDTO request) {
        ValidAssign valid = validateAssign(scope, taskId, assignmentIdempotencyKey, request);
        if (valid.expectedTaskVersion() != lockedTaskVersion) {
            throw conflict("Task changed before consent preview");
        }
        if (!List.of("GENERATE_IMAGE").equals(valid.operations())
                || !"GENERATE_IMAGE".equals(valid.initialOperation())) {
            throw bad("Provider consent preview supports only GENERATE_IMAGE");
        }
        String canonicalAgent = legacyAssignments.resolveAgentId(scope.tenantId(), scope.clientId(),
                scope.ownerJiacn(), valid.requestedAgentId());
        var confirmed = requirementSnapshots.requireCurrent(scope, valid.taskId(),
                valid.requirementRevision());
        if (confirmed == null || confirmed.revision() != valid.requirementRevision()
                || !scope.tenantId().equals(confirmed.tenantId())
                || !scope.clientId().equals(confirmed.clientId())
                || !scope.ownerJiacn().equals(confirmed.ownerJiacn())
                || !valid.taskId().equals(confirmed.taskId())
                || confirmed.sha256() == null
                || !confirmed.sha256().matches("[0-9a-f]{64}")) {
            throw invalidState("Current owner-confirmed requirement is missing or stale");
        }
        List<String> locked = identities.lockActiveCanonicalAgentIdsInScope(scope.tenantId(),
                scope.clientId(), scope.ownerJiacn(), List.of(canonicalAgent));
        if (!List.of(canonicalAgent).equals(locked)) throw notFound();
        List<InputSnapshot> snapshots = validateAndSnapshotInputs(scope, taskId, valid.inputs());
        List<AuthorizedInput> authorized = snapshots.stream().map(input -> new AuthorizedInput(
                input.fileId(), input.version(), input.purpose(), input.contentMimeType(),
                input.byteLength(), input.contentHash())).toList();
        return new AssignmentPreview(canonicalAgent, lockedTaskVersion,
                confirmed.revision(), confirmed.sha256(), hashAssign(valid, canonicalAgent),
                sha256("TASK_LINKED_INPUT_SNAPSHOT_V1\n" + write(snapshots)), authorized);
    }

    @Override
    public AgentTaskExecutionGrantDTO revoke(Scope scope, String taskId, String grantId,
            String idempotencyKey, long expectedGrantVersion) {
        validateScope(scope); exact(taskId,"taskId",100); exact(grantId,"grantId",100);
        exact(idempotencyKey,"Idempotency-Key",100);
        if (expectedGrantVersion < 1) throw bad("expectedVersion is invalid");
        String requestHash=sha256("REVOKE\n"+taskId+"\n"+grantId+"\n"+expectedGrantVersion);
        try {
            return transactions.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),scope.clientId(),
                    scope.ownerJiacn(),taskId,root -> revokeLocked(scope,taskId,grantId,
                            idempotencyKey,expectedGrantVersion,requestHash));
        } catch (AgentTaskCollaborationException failure) { throw translate(failure); }
    }

    private AgentTaskExecutionGrantDTO revokeLocked(Scope scope,String taskId,String grantId,
            String idempotencyKey,long expectedVersion,String requestHash) {
        AgentTaskExecutionGrantEntity grant=grants.findByGrantForUpdate(scope.tenantId(),
                scope.clientId(),scope.ownerJiacn(),taskId,grantId);
        if (grant==null) throw notFound();
        if (grant.getRevokeIdempotencyKey()!=null) {
            if (same(idempotencyKey,grant.getRevokeIdempotencyKey())
                    && same(requestHash,grant.getRevokeRequestHash())) return view(grant);
            throw conflict("Grant revoke idempotency key or payload conflicts");
        }
        if (!"ACTIVE".equals(grant.getState()) || !Objects.equals(expectedVersion,grant.getGrantVersion())) {
            throw conflict("Grant version or state changed before revoke");
        }
        long now=System.currentTimeMillis();
        if (!grants.revoke(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),taskId,grantId,
                expectedVersion,idempotencyKey,requestHash,now)) {
            throw conflict("Grant changed before revoke");
        }
        grant.setState("REVOKED").setGrantVersion(expectedVersion+1).setRevokedAt(now)
                .setRevokeIdempotencyKey(idempotencyKey).setRevokeRequestHash(requestHash);
        return view(grant);
    }

    @Override
    public Admission admitSelectedOutputPromotion(Scope scope, String taskId, String grantId,
            long expectedGrantVersion, long expectedAssignmentRevision, String targetAgentId) {
        validateScope(scope); exact(taskId,"taskId",100); exact(grantId,"grantId",100);
        exact(targetAgentId,"targetAgentId",100);
        if (expectedGrantVersion < 1 || expectedGrantVersion > MAX_SAFE_INTEGER
                || expectedAssignmentRevision < 0 || expectedAssignmentRevision > MAX_SAFE_INTEGER)
            throw bad("Expected version is invalid");
        try {
            return transactions.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),scope.clientId(),
                    scope.ownerJiacn(),taskId,root -> {
                        AgentTaskExecutionGrantEntity observed=grants.findByGrant(scope.tenantId(),
                                scope.clientId(),scope.ownerJiacn(),taskId,grantId);
                        if (observed==null) throw notFound();
                        lockTargetAndPersistedInputs(scope,taskId,targetAgentId,observed);
                        AgentTaskExecutionGrantEntity grant=grants.findByGrantForUpdate(scope.tenantId(),
                                scope.clientId(),scope.ownerJiacn(),taskId,grantId);
                        if (grant==null || !same(observed.getRequestHash(),grant.getRequestHash()))
                            throw conflict("Grant changed during promotion admission");
                        return verifyPromotionAdmission(scope,root,grant,expectedGrantVersion,
                                expectedAssignmentRevision,targetAgentId);
                    });
        } catch (AgentTaskCollaborationException failure) { throw translate(failure); }
    }

    private Admission verifyPromotionAdmission(Scope scope, AgentTaskMetaEntity root,
            AgentTaskExecutionGrantEntity grant, long grantVersion, long assignmentRevision,
            String targetAgentId) {
        if (!"ACTIVE".equals(grant.getState()) || !Objects.equals(grantVersion,grant.getGrantVersion())
                || !Objects.equals(assignmentRevision,grant.getAssignmentRevision())
                || root.getTaskVersion()==null || root.getTaskVersion()<assignmentRevision
                || !same(targetAgentId,grant.getTargetAgentId())
                || !same(targetAgentId,root.getAssignedAgentId()))
            throw conflict("Grant, assignment, or target is stale");
        requireAssignmentEpoch(scope,grant.getTaskId(),assignmentRevision,root);
        AgentTaskExecutionGrantEntity active=grants.findActiveByTask(scope.tenantId(),scope.clientId(),
                scope.ownerJiacn(),grant.getTaskId());
        if (active==null || !same(active.getGrantId(),grant.getGrantId())
                || !Objects.equals(active.getGrantVersion(),grantVersion))
            throw conflict("Grant is not the current assignment authorization");
        try {
            var current=requirementSnapshots.requireCurrent(scope,grant.getTaskId(),grant.getRequirementRevision());
            if (current==null || current.revision()!=grant.getRequirementRevision())
                throw new IllegalStateException("Requirement revision drift");
        } catch (IllegalArgumentException | IllegalStateException absent) {
            throw invalidState("Current requirement revision is missing or stale");
        }
        var inputs=readInputs(grant.getInputScopeJson()).stream()
                .map(input -> new AuthorizedInput(input.fileId(),input.version(),input.purpose(),
                        input.contentMimeType(),input.byteLength(),input.contentHash())).toList();
        return new Admission(grant.getGrantId(),grantVersion,assignmentRevision,targetAgentId,
                "FINALIZE_SELECTED_OUTPUTS",false,inputs);
    }

    @Override
    public Admission admitProviderConsentBinding(Scope scope, String taskId, String grantId,
            long expectedGrantVersion, long expectedAssignmentRevision, String targetAgentId,
            String expectedAssignmentBaseHash, String expectedInputSnapshotDigest) {
        validateScope(scope); exact(taskId,"taskId",100); exact(grantId,"grantId",100);
        exact(targetAgentId,"targetAgentId",100);
        if (expectedGrantVersion < 1 || expectedGrantVersion > MAX_SAFE_INTEGER
                || expectedAssignmentRevision < 0
                || expectedAssignmentRevision > MAX_SAFE_INTEGER
                || expectedAssignmentBaseHash == null
                || !expectedAssignmentBaseHash.matches("[0-9a-f]{64}")
                || expectedInputSnapshotDigest == null
                || !expectedInputSnapshotDigest.matches("[0-9a-f]{64}")) {
            throw bad("Provider consent binding identity is invalid");
        }
        try {
            return transactions.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),
                    scope.clientId(), scope.ownerJiacn(), taskId, root -> {
                        AgentTaskExecutionGrantEntity observed = grants.findByGrant(
                                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), taskId,
                                grantId);
                        if (observed == null) throw notFound();
                        lockTargetAndPersistedInputs(scope, taskId, targetAgentId, observed);
                        AgentTaskExecutionGrantEntity locked = grants.findByGrantForUpdate(
                                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), taskId,
                                grantId);
                        String persistedInputDigest = locked == null ? null : sha256(
                                "TASK_LINKED_INPUT_SNAPSHOT_V1\n"
                                        + write(readInputs(locked.getInputScopeJson())));
                        if (locked == null || !same(observed.getRequestHash(), locked.getRequestHash())
                                || !same(expectedAssignmentBaseHash, locked.getRequestHash())
                                || !same(expectedInputSnapshotDigest, persistedInputDigest)) {
                            throw conflict("Grant does not match the consent preview");
                        }
                        return verifyAdmission(scope, root, locked, expectedGrantVersion,
                                expectedAssignmentRevision, targetAgentId, "GENERATE_IMAGE", false);
                    });
        } catch (AgentTaskCollaborationException failure) { throw translate(failure); }
    }

    @Override
    public Admission admit(Scope scope, String taskId, String grantId, long expectedGrantVersion,
            long expectedAssignmentRevision, String targetAgentId, String operation,
            boolean paidExecution) {
        validateScope(scope); exact(taskId,"taskId",100); exact(grantId,"grantId",100);
        exact(targetAgentId,"targetAgentId",100); exact(operation,"operation",40);
        if (expectedGrantVersion < 1 || expectedAssignmentRevision < 0) throw bad("Expected version is invalid");
        try {
            return transactions.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),scope.clientId(),
                    scope.ownerJiacn(),taskId,root -> admitLocked(scope,taskId,grantId,
                            expectedGrantVersion,expectedAssignmentRevision,targetAgentId,operation,
                            paidExecution,root));
        } catch (AgentTaskCollaborationException failure) { throw translate(failure); }
    }

    private Admission admitLocked(Scope scope,String taskId,String grantId,long grantVersion,
            long assignmentRevision,String targetAgentId,String operation,boolean paid,
            AgentTaskMetaEntity root) {
        AgentTaskExecutionGrantEntity observed=grants.findByGrant(scope.tenantId(),
                scope.clientId(),scope.ownerJiacn(),taskId,grantId);
        if (observed==null) throw notFound();
        lockTargetAndPersistedInputs(scope,taskId,targetAgentId,observed);
        AgentTaskExecutionGrantEntity grant=grants.findByGrantForUpdate(scope.tenantId(),
                scope.clientId(),scope.ownerJiacn(),taskId,grantId);
        if (grant==null || !same(observed.getRequestHash(),grant.getRequestHash())) throw conflict("Grant changed during admission");
        return verifyAdmission(scope,root,grant,grantVersion,assignmentRevision,targetAgentId,operation,paid);
    }

    private Admission verifyAdmission(Scope scope,AgentTaskMetaEntity root,AgentTaskExecutionGrantEntity grant,
            long grantVersion,long assignmentRevision,String targetAgentId,String operation,boolean paid) {
        if (!"ACTIVE".equals(grant.getState()) || !Objects.equals(grantVersion,grant.getGrantVersion())
                || !Objects.equals(assignmentRevision,grant.getAssignmentRevision())
                || root.getTaskVersion()==null || root.getTaskVersion()<assignmentRevision
                || !same(targetAgentId,grant.getTargetAgentId())
                || !same(targetAgentId,root.getAssignedAgentId())) {
            throw conflict("Grant, assignment, or target is stale");
        }
        // taskVersion also advances on ordinary status events. The last TASK_ASSIGNED
        // event is the durable assignment epoch: unlike a matching Agent ID, it
        // distinguishes a later re-point to the same Agent. All event writes lock root.
        requireAssignmentEpoch(scope,grant.getTaskId(),assignmentRevision,root);
        AgentTaskExecutionGrantEntity active=grants.findActiveByTask(scope.tenantId(),
                scope.clientId(),scope.ownerJiacn(),grant.getTaskId());
        if (active==null || !same(active.getGrantId(),grant.getGrantId())
                || !Objects.equals(active.getGrantVersion(),grantVersion))
            throw conflict("Grant is not the current assignment authorization");
        // Root lock serializes owner re-confirmation with admission. A previously
        // issued grant cannot authorize a newer, differently confirmed requirement.
        try {
            var current=requirementSnapshots.requireCurrent(scope,grant.getTaskId(),grant.getRequirementRevision());
            if (current==null || current.revision()!=grant.getRequirementRevision()
                    || !scope.tenantId().equals(current.tenantId())
                    || !scope.clientId().equals(current.clientId())
                    || !scope.ownerJiacn().equals(current.ownerJiacn())
                    || !grant.getTaskId().equals(current.taskId()))
                throw new IllegalStateException("Requirement revision drift");
        } catch (IllegalArgumentException | IllegalStateException absent) {
            throw invalidState("Current requirement revision is missing or stale");
        }
        List<String> allowed=readOperations(grant.getPermittedOperationsJson());
        if (!allowed.contains(operation)) throw new AgentTaskExecutionGrantException(
                Reason.FORBIDDEN_OPERATION,"Operation is outside the persisted grant/capability policy");
        if (paid && (grant.getCostAuthorizationRef()==null || grant.getCostAuthorizationRef().isBlank())) {
            throw new AgentTaskExecutionGrantException(Reason.PAID_EXECUTION_NOT_AUTHORIZED,
                    "Paid execution has no persisted cost authorization");
        }
        var authorizedInputs=readInputs(grant.getInputScopeJson()).stream()
                .map(input -> new AuthorizedInput(input.fileId(),input.version(),input.purpose(),
                        input.contentMimeType(),input.byteLength(),input.contentHash())).toList();
        return new Admission(grant.getGrantId(),grantVersion,assignmentRevision,targetAgentId,operation,
                paid && grant.getCostAuthorizationRef()!=null,authorizedInputs);
    }

    private void requireAssignmentEpoch(Scope scope,String taskId,long assignmentRevision,
            AgentTaskMetaEntity root) {
        if (root==null || root.getTaskVersion()==null || root.getTaskVersion()<assignmentRevision)
            throw conflict("Task predates the granted assignment");
        String payload=grants.latestAssignmentEventJson(scope.tenantId(),scope.clientId(),
                scope.ownerJiacn(),taskId);
        // Do not use taskVersion as a proxy for assignment, nor infer a missing
        // event from the currently assigned Agent. Legacy rows without this proof
        // need a separately verified historical event repair; reconfirming the
        // requirement alone cannot manufacture an assignment epoch.
        try {
            if (payload==null) throw new IllegalArgumentException("missing assignment event");
            var event=json.readTree(payload);
            var revision=event.get("resultVersion");
            if (revision==null || !revision.isIntegralNumber() || !revision.canConvertToLong()
                    || revision.longValue()<0 || revision.longValue()>assignmentRevision)
                throw new IllegalArgumentException("stale or invalid assignment event");
        } catch (RuntimeException badEvent) {
            throw conflict("Current assignment epoch cannot be proven");
        }
    }

    @Override
    public Admission resolveAndAdmit(Scope scope, String taskId, long expectedAssignmentRevision,
            String targetAgentId, String operation, boolean paidExecution) {
        validateScope(scope); exact(taskId,"taskId",100); exact(targetAgentId,"targetAgentId",100);
        exact(operation,"operation",40);
        if (expectedAssignmentRevision < 0) throw bad("expectedAssignmentRevision is invalid");
        try {
            return transactions.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),scope.clientId(),
                    scope.ownerJiacn(),taskId,root -> {
                        AgentTaskExecutionGrantEntity observed=grants.findActiveByTask(scope.tenantId(),
                                scope.clientId(),scope.ownerJiacn(),taskId);
                        if (observed==null) throw notFound();
                        lockTargetAndPersistedInputs(scope,taskId,targetAgentId,observed);
                        AgentTaskExecutionGrantEntity locked=grants.findByGrantForUpdate(scope.tenantId(),
                                scope.clientId(),scope.ownerJiacn(),taskId,observed.getGrantId());
                        if (locked==null || !same(observed.getRequestHash(),locked.getRequestHash())) {
                            throw conflict("Active grant changed during server-side resolution");
                        }
                        return verifyAdmission(scope,root,locked,locked.getGrantVersion(),expectedAssignmentRevision,
                                targetAgentId,operation,paidExecution);
                    });
        } catch (AgentTaskCollaborationException failure) { throw translate(failure); }
    }

    private void lockTargetAndPersistedInputs(Scope scope,String taskId,String targetAgentId,
            AgentTaskExecutionGrantEntity observed) {
        List<String> locked=identities.lockActiveCanonicalAgentIdsInScope(scope.tenantId(),
                scope.clientId(),scope.ownerJiacn(),List.of(targetAgentId));
        if (!List.of(targetAgentId).equals(locked) || !same(targetAgentId,observed.getTargetAgentId())) throw notFound();
        for (InputSnapshot input:readInputs(observed.getInputScopeJson())) {
            PersonalWorkspaceFileEntity file=workspace.lockFile(scope.tenantId(),scope.clientId(),
                    scope.ownerJiacn(),input.fileId());
            if (file==null || !"ACTIVE".equals(file.getState())) throw notFound();
            PersonalWorkspaceTaskFileLinkEntity link=taskLinks.lockBySelection(scope.tenantId(),
                    scope.clientId(),scope.ownerJiacn(),taskId,input.fileId(),input.version(),input.purpose());
            PersonalWorkspaceVersionEntity version=workspace.findVersion(scope.tenantId(),scope.clientId(),
                    scope.ownerJiacn(),input.fileId(),input.version());
            if (link==null || !"ACTIVE".equals(link.getLinkState()) || version==null
                    || !same(input.contentHash(),version.getContentHash())
                    || !same(input.contentMimeType(),version.getContentMimeType())
                    || !Objects.equals(input.byteLength(),version.getByteLength())) throw notFound();
        }
    }

    private List<InputSnapshot> validateAndSnapshotInputs(Scope scope,String taskId,
            List<ValidInput> inputs) {
        List<InputSnapshot> result=new ArrayList<>();
        for (ValidInput input:inputs) {
            PersonalWorkspaceFileEntity file=workspace.lockFile(scope.tenantId(),scope.clientId(),
                    scope.ownerJiacn(),input.fileId());
            PersonalWorkspaceTaskFileLinkEntity link=taskLinks.lockBySelection(scope.tenantId(),
                    scope.clientId(),scope.ownerJiacn(),taskId,input.fileId(),input.version(),input.purpose());
            if (link==null || !"ACTIVE".equals(link.getLinkState())) throw notFound();
            if (file==null || !"ACTIVE".equals(file.getState())) throw notFound();
            PersonalWorkspaceVersionEntity version=workspace.findVersion(scope.tenantId(),
                    scope.clientId(),scope.ownerJiacn(),input.fileId(),input.version());
            if (version==null || version.getContentHash()==null
                    || !version.getContentHash().matches("[0-9a-f]{64}")
                    || version.getContentMimeType()==null || version.getContentMimeType().isBlank()
                    || version.getByteLength()==null || version.getByteLength()<0) throw notFound();
            result.add(new InputSnapshot(input.fileId(),input.version(),input.purpose(),
                    version.getContentMimeType(),version.getByteLength(),version.getContentHash()));
        }
        return List.copyOf(result);
    }

    private ValidAssign validateAssign(Scope scope,String taskId,String key,AgentTaskAssignDTO request) {
        validateScope(scope); exact(taskId,"taskId",100); exact(key,"Idempotency-Key",100);
        if (request==null || !Objects.equals(request.getWorkflowVersion(),2)
                || !ACTION.equals(request.getBusinessAction())) throw bad("Unsupported v2 assignment action");
        if (request.getExpectedTaskVersion()==null || request.getExpectedTaskVersion()<0
                || request.getExpectedTaskVersion()==Long.MAX_VALUE) throw bad("expectedTaskVersion is invalid");
        if (request.getRequirementRevision()==null || request.getRequirementRevision()<1) throw bad("requirementRevision is invalid");
        if (request.getAgentIds()!=null || Boolean.TRUE.equals(request.getAllowQueue())) throw bad("v2 assignment requires one explicit agentId");
        exact(request.getAgentId(),"agentId",100);
        if (request.getExistingCostAuthorizationRef()!=null || request.getCostAuthorizationRef()!=null
                || request.getPermittedToolPolicyRef()!=null || request.getTools()!=null
                || request.getAuthorized()!=null || request.getPaidExecutionAuthorized()!=null) {
            throw bad("Client authority fields are forbidden");
        }
        List<String> operations=canonicalOperations(request.getRequestedOperations());
        boolean initialOperationPresent=request.getInitialOperation()!=null;
        String initialOperation=initialOperationPresent
                ? explicitInitialOperation(request.getInitialOperation(),operations)
                : legacyInitialOperationOrNull(operations);
        List<ValidInput> inputs=canonicalInputs(request.getInputRefs());
        return new ValidAssign(taskId,key,request.getAgentId(),request.getExpectedTaskVersion(),
                request.getRequirementRevision(),operations,inputs,initialOperation,
                initialOperationPresent);
    }

    private static String explicitInitialOperation(String selected,List<String> operations) {
        exact(selected,"initialOperation",40);
        if (!OPERATIONS.contains(selected) || !operations.contains(selected)) {
            throw bad("initialOperation is outside requestedOperations");
        }
        return selected;
    }

    private static String legacyInitialOperationOrNull(List<String> operations) {
        try {
            return AgentTaskBountyBootstrapPayload.initialOperation(operations);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static List<String> canonicalOperations(List<String> source) {
        if (source==null || source.isEmpty() || source.size()>OPERATIONS.size()) throw bad("requestedOperations is invalid");
        LinkedHashSet<String> result=new LinkedHashSet<>();
        for (String operation:source) {
            exact(operation,"requestedOperation",40);
            if (!OPERATIONS.contains(operation) || !result.add(operation)) throw bad("requestedOperations is invalid");
        }
        return result.stream().sorted().toList();
    }
    private static List<ValidInput> canonicalInputs(List<AgentTaskGrantInputDTO> source) {
        if (source==null) return List.of();
        if (source.size()>MAX_INPUTS) throw bad("inputRefs exceeds the safe limit");
        LinkedHashSet<String> unique=new LinkedHashSet<>(); List<ValidInput> result=new ArrayList<>();
        for (AgentTaskGrantInputDTO input:source) {
            if (input==null) throw bad("inputRef is invalid");
            exact(input.getFileId(),"fileId",100);
            if (input.getVersion()==null || input.getVersion()<1 || !PURPOSES.contains(input.getPurpose())) throw bad("inputRef is invalid");
            String identity=input.getFileId()+"\u0000"+input.getVersion()+"\u0000"+input.getPurpose();
            if (!unique.add(identity)) throw bad("Duplicate inputRef");
            result.add(new ValidInput(input.getFileId(),input.getVersion(),input.getPurpose()));
        }
        result.sort(Comparator.comparing(ValidInput::fileId,AgentTaskExecutionGrantServiceImpl::compareUtf8)
                .thenComparingInt(ValidInput::version).thenComparing(ValidInput::purpose));
        return List.copyOf(result);
    }

    private String hashAssign(ValidAssign valid,String canonicalAgent) {
        String payload=(valid.initialOperationPresent()
                ? INITIAL_OPERATION_HASH_DOMAIN : ASSIGN_HASH_DOMAIN)+"\n"
                +valid.taskId()+"\n"+canonicalAgent+"\n"
                +valid.expectedTaskVersion()+"\n"+valid.requirementRevision()+"\n"
                +write(valid.operations())+"\n"+write(valid.inputs());
        if (valid.initialOperationPresent()) payload += "\n"+valid.initialOperation();
        return sha256(payload);
    }
    private AgentTaskExecutionGrantDTO replay(String requestHash,String taskId,
            AgentTaskExecutionGrantEntity entity) {
        if (!same(requestHash,entity.getRequestHash()) || !same(taskId,entity.getTaskId())) {
            throw new AgentTaskExecutionGrantException(Reason.IDEMPOTENCY_CONFLICT,
                    "Idempotency key was already used with another payload");
        }
        return view(entity);
    }
    private AgentTaskExecutionGrantDTO view(AgentTaskExecutionGrantEntity entity) {
        List<InputSnapshot> inputs=readInputs(entity.getInputScopeJson());
        return new AgentTaskExecutionGrantDTO().setGrantId(entity.getGrantId()).setTaskId(entity.getTaskId())
                .setRequirementRevision(entity.getRequirementRevision()).setAssignmentRevision(entity.getAssignmentRevision())
                .setTargetAgentId(entity.getTargetAgentId()).setPermittedOperations(readOperations(entity.getPermittedOperationsJson()))
                .setInputs(inputs.stream().map(i -> new AgentTaskExecutionGrantDTO.InputSummary(i.fileId(),i.version(),i.purpose(),i.contentMimeType(),i.byteLength(),i.contentHash())).toList())
                .setState(entity.getState()).setGrantVersion(entity.getGrantVersion())
                .setPaidExecutionAuthorized(false).setCreatedAt(entity.getCreatedAt()).setRevokedAt(entity.getRevokedAt());
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("Grant canonical JSON failed",failure); }
    }
    private List<String> readOperations(String value) {
        try {
            List<String> result=json.readValue(value,new TypeReference<List<String>>(){});
            if (result==null || result.isEmpty() || result.size()>OPERATIONS.size()) {
                throw invalidState("Persisted grant operations are invalid");
            }
            LinkedHashSet<String> unique=new LinkedHashSet<>();
            for (String operation:result) {
                if (operation==null || operation.isBlank() || !operation.equals(operation.strip())
                        || operation.codePointCount(0,operation.length())>40
                        || hasUnpairedSurrogate(operation)
                        || operation.chars().anyMatch(Character::isISOControl)
                        || !OPERATIONS.contains(operation) || !unique.add(operation)) {
                    throw invalidState("Persisted grant operations are invalid");
                }
            }
            if (!result.equals(result.stream().sorted().toList())) {
                throw invalidState("Persisted grant operations are not canonical");
            }
            return List.copyOf(result);
        } catch (AgentTaskExecutionGrantException failure) { throw failure; }
        catch (Exception failure) { throw invalidState("Persisted grant operations are invalid"); }
    }
    private List<InputSnapshot> readInputs(String value) {
        try { return List.copyOf(json.readValue(value,new TypeReference<List<InputSnapshot>>(){})); }
        catch (Exception failure) { throw invalidState("Persisted grant inputs are invalid"); }
    }
    private static void validateScope(Scope scope) {
        if (scope==null || !"0".equals(scope.tenantId())) throw bad("JWT scope is invalid");
        exact(scope.clientId(),"clientId",50); exact(scope.ownerJiacn(),"ownerJiacn",50);
        if ("0".equals(scope.ownerJiacn())) throw bad("JWT scope is invalid");
    }
    private static void exact(String value,String name,int max) {
        if (value==null || value.isBlank() || !value.equals(value.strip())
                || value.codePointCount(0,value.length())>max || hasUnpairedSurrogate(value)
                || value.chars().anyMatch(Character::isISOControl)) throw bad(name+" is invalid");
    }
    private static boolean hasUnpairedSurrogate(String value) {
        for (int index=0;index<value.length();index++) {
            char current=value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index+1>=value.length() || !Character.isLowSurrogate(value.charAt(index+1))) return true;
                index++;
            } else if (Character.isLowSurrogate(current)) return true;
        }
        return false;
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static boolean same(String left,String right) {
        return left!=null && right!=null && MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),right.getBytes(StandardCharsets.UTF_8));
    }
    private static int compareUtf8(String left,String right) {
        byte[] a=left.getBytes(StandardCharsets.UTF_8),b=right.getBytes(StandardCharsets.UTF_8);
        for (int i=0;i<Math.min(a.length,b.length);i++) { int c=Integer.compare(Byte.toUnsignedInt(a[i]),Byte.toUnsignedInt(b[i])); if(c!=0)return c; }
        return Integer.compare(a.length,b.length);
    }
    private static AgentTaskExecutionGrantException translate(AgentTaskCollaborationException failure) {
        return switch (failure.getReason()) {
            case NOT_FOUND, FORBIDDEN -> notFound();
            case VERSION_CONFLICT, INVALID_TRANSITION -> conflict("Task assignment changed");
            case INVALID_REQUEST -> bad("Task assignment request is invalid");
            case INVALID_PERSISTED_STATE -> invalidState("Task assignment state is invalid");
            case RESERVED_FOR_LEASE_PROTOCOL -> conflict("Task is reserved for another protocol");
        };
    }
    private static AgentTaskExecutionGrantException bad(String message) { return new AgentTaskExecutionGrantException(Reason.BAD_REQUEST,message); }
    private static AgentTaskExecutionGrantException conflict(String message) { return new AgentTaskExecutionGrantException(Reason.CONFLICT,message); }
    private static AgentTaskExecutionGrantException notFound() { return new AgentTaskExecutionGrantException(Reason.NOT_FOUND,"Task authorization resource was not found"); }
    private static AgentTaskExecutionGrantException invalidState(String message) { return new AgentTaskExecutionGrantException(Reason.INVALID_PERSISTED_STATE,message); }

    private record ValidAssign(String taskId,String idempotencyKey,String requestedAgentId,
            long expectedTaskVersion,long requirementRevision,List<String> operations,
            List<ValidInput> inputs,String initialOperation,boolean initialOperationPresent) { }
    private record ValidInput(String fileId,int version,String purpose) { }
    private record InputSnapshot(String fileId,int version,String purpose,String contentMimeType,
            long byteLength,String contentHash) { }
}
