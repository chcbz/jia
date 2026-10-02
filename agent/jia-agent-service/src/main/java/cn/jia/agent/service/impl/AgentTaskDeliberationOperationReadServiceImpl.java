package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskBountyBootstrapOutboxDao;
import cn.jia.agent.dao.AgentTaskExecutionGrantDao;
import cn.jia.agent.dao.ControlledImageBridgeOperationDao;
import cn.jia.agent.entity.AgentTaskBountyBootstrapClaimDTO.ReferenceSummary;
import cn.jia.agent.entity.AgentTaskBountyBootstrapOutboxEntity;
import cn.jia.agent.entity.AgentTaskExecutionGrantEntity;
import cn.jia.agent.entity.ControlledImageBridgeOperationEntity;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.service.AgentTaskDeliberationOperationReadService;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentTaskRequirementSnapshotService;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

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

/** Pure metadata read: root lock, grant action lock, then bootstrap action lock. */
@Named
public final class AgentTaskDeliberationOperationReadServiceImpl
        implements AgentTaskDeliberationOperationReadService {
    private static final String ACTION_PREFIX = "ASSIGN_AND_START:";
    private static final Set<String> OPERATIONS = Set.of(
            "INSPECT_INPUTS", "GENERATE_IMAGE", "EDIT_IMAGE", "GENERATE_AUDIO", "EDIT_AUDIO");
    private static final Set<String> PURPOSES = Set.of("INPUT", "REFERENCE");
    private static final Set<String> GRANT_STATES = Set.of("ACTIVE", "REVOKED", "SUPERSEDED");
    private static final Set<String> BOOTSTRAP_STATES = Set.of(
            "PENDING", "CLAIMED", "RETRY", "ADMITTED", "DEAD");

    private final AgentTaskExecutionGrantDao grants;
    private final AgentTaskBountyBootstrapOutboxDao bootstraps;
    private final ControlledImageBridgeOperationDao controlledImageOperations;
    private final AgentTaskRequirementSnapshotService requirements;
    private final AgentTaskMutationTransaction transactions;
    private final ObjectMapper json;

    @Inject
    public AgentTaskDeliberationOperationReadServiceImpl(AgentTaskExecutionGrantDao grants,
            AgentTaskBountyBootstrapOutboxDao bootstraps,
            ControlledImageBridgeOperationDao controlledImageOperations,
            AgentTaskRequirementSnapshotService requirements,
            AgentTaskMutationTransaction transactions, ObjectMapper json) {
        this.grants = Objects.requireNonNull(grants, "grants");
        this.bootstraps = Objects.requireNonNull(bootstraps, "bootstraps");
        this.controlledImageOperations = Objects.requireNonNull(
                controlledImageOperations, "controlledImageOperations");
        this.requirements = Objects.requireNonNull(requirements, "requirements");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public Operation read(AgentTaskExecutionGrantService.Scope scope, String taskId,
            String idempotencyKey) {
        validateScope(scope);
        exact(taskId, 100);
        exact(idempotencyKey, 100);
        String action = ACTION_PREFIX + idempotencyKey;
        try {
            return transactions.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),
                    scope.clientId(), scope.ownerJiacn(), taskId,
                    root -> readLocked(scope, taskId, idempotencyKey, action, root));
        } catch (ReadException failure) {
            throw failure;
        } catch (IntegrityFailure failure) {
            throw new ReadException(ReadException.Reason.INTEGRITY_ERROR, failure);
        } catch (AgentTaskCollaborationException failure) {
            ReadException.Reason reason = switch (failure.getReason()) {
                case NOT_FOUND, FORBIDDEN -> ReadException.Reason.NOT_FOUND;
                case INVALID_PERSISTED_STATE -> ReadException.Reason.INTEGRITY_ERROR;
                default -> ReadException.Reason.SOURCE_UNAVAILABLE;
            };
            throw new ReadException(reason, failure);
        } catch (DataAccessException | TransactionException failure) {
            throw new ReadException(ReadException.Reason.SOURCE_UNAVAILABLE, failure);
        } catch (RuntimeException failure) {
            throw new ReadException(ReadException.Reason.SOURCE_UNAVAILABLE, failure);
        }
    }

    private Operation readLocked(AgentTaskExecutionGrantService.Scope scope, String taskId,
            String idempotencyKey, String action, AgentTaskMetaEntity root) {
        requireRoot(root, scope, taskId);
        // Do not replace these action locks with worker claim/reconcile APIs. This GET owns
        // no lease and performs no mutation, message creation, execution, or Provider call.
        AgentTaskExecutionGrantEntity grant = grants.findByActionForUpdate(scope.tenantId(),
                scope.clientId(), scope.ownerJiacn(), action);
        AgentTaskBountyBootstrapOutboxEntity bootstrap = bootstraps.findByActionForUpdate(
                scope.tenantId(), scope.clientId(), scope.ownerJiacn(), action);
        if (grant == null && bootstrap == null) throw notFound();
        if (grant == null || bootstrap == null) throw integrity();
        // An owner may reuse the same key only as the already persisted action. When that
        // action belongs to another task, answer the same generic 404 as an absent action;
        // never disclose the other task's facts. Cross-row disagreement remains corruption.
        if (!Objects.equals(taskId, grant.getTaskId())
                || !Objects.equals(taskId, bootstrap.getTaskId())) {
            if (Objects.equals(grant.getTaskId(), bootstrap.getTaskId())
                    && Objects.equals(grant.getTenantId(), bootstrap.getTenantId())
                    && Objects.equals(grant.getClientId(), bootstrap.getClientId())
                    && Objects.equals(grant.getOwnerJiacn(), bootstrap.getOwnerJiacn())
                    && Objects.equals(grant.getSourceBusinessActionId(),
                            bootstrap.getSourceBusinessActionId())) {
                throw notFound();
            }
            throw integrity();
        }

        GrantFacts grantFacts = requireGrant(grant, scope, taskId, idempotencyKey, action);
        List<ReferenceSummary> references;
        try {
            references = AgentTaskBountyBootstrapPayload.validateAndRead(bootstrap, json);
        } catch (RuntimeException corrupt) {
            throw integrity(corrupt);
        }
        requireBootstrap(bootstrap, scope, taskId, action, grant, grantFacts, references);
        requireControlledImageAuthority(scope, taskId, idempotencyKey, grant, bootstrap);
        requireRequestHash(grant, grantFacts.operations(), grantFacts.inputs(),
                bootstrap.getPermittedOperation());
        requireSnapshot(scope, taskId, grant);

        long assignmentEventVersion = requireAssignmentEventVersion(scope, taskId,
                root.getTaskVersion());
        AgentTaskExecutionGrantEntity active = grants.findActiveByTask(scope.tenantId(),
                scope.clientId(), scope.ownerJiacn(), taskId);
        boolean current = currentAssignment(root, grant, active, assignmentEventVersion);
        return new Operation(taskId, grant.getTargetAgentId(), grant.getRequirementRevision(),
                grant.getAssignmentRevision(), root.getTaskVersion(), grant.getGrantId(),
                grant.getGrantVersion(), grant.getState(), grantFacts.operations(),
                grantFacts.inputs(), bootstrap.getBootstrapId(), bootstrap.getStatus(),
                bootstrap.getVersion(), bootstrap.getPermittedOperation(),
                bootstrap.getAdmittedConversationId(), bootstrap.getAdmittedRequestId(), current);
    }

    private GrantFacts requireGrant(AgentTaskExecutionGrantEntity grant,
            AgentTaskExecutionGrantService.Scope scope, String taskId, String key, String action) {
        try {
            if (!Objects.equals(scope.tenantId(), grant.getTenantId())
                    || !Objects.equals(scope.clientId(), grant.getClientId())
                    || !Objects.equals(scope.ownerJiacn(), grant.getOwnerJiacn())
                    || !Objects.equals(taskId, grant.getTaskId())
                    || !Objects.equals(action, grant.getSourceBusinessActionId())
                    || !Objects.equals(key, grant.getIdempotencyKey())
                    || !Objects.equals(scope.ownerJiacn(), grant.getIssuedBy())
                    || !"MMD_U1_V1".equals(grant.getPolicyRevision())
                    || !"NO_TOOLS_V1".equals(grant.getPermittedToolPolicyRef())
                    || !Boolean.FALSE.equals(grant.getAllowOwnTaskDerivedAssets())
                    || grant.getRequirementRevision() == null || grant.getRequirementRevision() < 1
                    || grant.getAssignmentRevision() == null || grant.getAssignmentRevision() < 0
                    || grant.getGrantVersion() == null || grant.getGrantVersion() < 1
                    || !GRANT_STATES.contains(grant.getState())
                    || grant.getRequestHash() == null
                    || !grant.getRequestHash().matches("[0-9a-f]{64}")) {
                throw integrity();
            }
            exactPersisted(grant.getGrantId(), 100);
            exactPersisted(grant.getTargetAgentId(), 100);
            List<String> operations = readOperations(grant.getPermittedOperationsJson());
            List<InputSummary> inputs = readInputs(grant.getInputScopeJson());
            return new GrantFacts(operations, inputs);
        } catch (IntegrityFailure failure) {
            throw failure;
        } catch (RuntimeException corrupt) {
            throw integrity(corrupt);
        }
    }

    private void requireBootstrap(AgentTaskBountyBootstrapOutboxEntity row,
            AgentTaskExecutionGrantService.Scope scope, String taskId, String action,
            AgentTaskExecutionGrantEntity grant, GrantFacts facts,
            List<ReferenceSummary> references) {
        try {
            if (!Objects.equals(scope.tenantId(), row.getTenantId())
                    || !Objects.equals(scope.clientId(), row.getClientId())
                    || !Objects.equals(scope.ownerJiacn(), row.getOwnerJiacn())
                    || !Objects.equals(taskId, row.getTaskId())
                    || !Objects.equals(action, row.getSourceBusinessActionId())
                    || !Objects.equals(grant.getRequirementRevision(), row.getRequirementRevision())
                    || !Objects.equals(grant.getAssignmentRevision(), row.getAssignmentRevision())
                    || !Objects.equals(grant.getTargetAgentId(), row.getTargetAgentId())
                    || !Objects.equals(grant.getGrantId(), row.getGrantId())
                    || row.getGrantVersion() == null || row.getGrantVersion() < 1
                    || row.getGrantVersion() > grant.getGrantVersion()
                    || row.getVersion() == null || row.getVersion() < 0
                    || !BOOTSTRAP_STATES.contains(row.getStatus())
                    || !facts.operations().contains(row.getPermittedOperation())
                    || !sameReferences(facts.inputs(), references)) {
                throw integrity();
            }
            exactPersisted(row.getBootstrapId(), 100);
            exactPersisted(row.getStatus(), 20);
            boolean admitted = "ADMITTED".equals(row.getStatus());
            if (admitted) {
                exactPersisted(row.getAdmittedConversationId(), 100);
                exactPersisted(row.getAdmittedRequestId(), 100);
            } else if (row.getAdmittedConversationId() != null
                    || row.getAdmittedRequestId() != null) {
                throw integrity();
            }
        } catch (IntegrityFailure failure) {
            throw failure;
        } catch (RuntimeException corrupt) {
            throw integrity(corrupt);
        }
    }

    private void requireControlledImageAuthority(AgentTaskExecutionGrantService.Scope scope,
            String taskId, String key, AgentTaskExecutionGrantEntity grant,
            AgentTaskBountyBootstrapOutboxEntity bootstrap) {
        String locator = grant.getCostAuthorizationRef();
        if (locator == null) return;
        try {
            if (!locator.matches("mmd-ci-v1:consent_[0-9a-f]{32}")) throw integrity();
            ControlledImageBridgeOperationEntity operation = controlledImageOperations.find(
                    scope.tenantId(), scope.clientId(), scope.ownerJiacn(), taskId, key);
            String consentId = locator.substring("mmd-ci-v1:".length());
            if (operation == null
                    || !Objects.equals(scope.tenantId(), operation.getTenantId())
                    || !Objects.equals(scope.clientId(), operation.getClientId())
                    || !Objects.equals(scope.ownerJiacn(), operation.getOwnerJiacn())
                    || !Objects.equals(taskId, operation.getTaskId())
                    || !Objects.equals(key, operation.getAssignmentIdempotencyKey())
                    || operation.getWrapperDigest() == null
                    || !operation.getWrapperDigest().matches("[0-9a-f]{64}")
                    || !Objects.equals(consentId, operation.getConsentId())
                    || operation.getExpectedConsentVersion() == null
                    || operation.getExpectedConsentVersion() < 1
                    || !Objects.equals(grant.getGrantId(), operation.getGrantId())
                    || operation.getGrantVersion() == null
                    || !Objects.equals(bootstrap.getGrantVersion(), operation.getGrantVersion())
                    || operation.getGrantVersion() > grant.getGrantVersion()
                    || !Objects.equals(grant.getAssignmentRevision(),
                            operation.getAssignmentRevision())
                    || !Objects.equals(locator, operation.getAuthorityLocator())
                    || !Objects.equals(3, operation.getExecutionProtocolVersion())
                    || operation.getOperationGrantId() == null
                    || !operation.getOperationGrantId().matches("opgrant_[0-9a-f]{32}")
                    || operation.getCreatedAt() == null || operation.getCreatedAt() < 1) {
                throw integrity();
            }
        } catch (IntegrityFailure failure) {
            throw failure;
        } catch (RuntimeException corrupt) {
            throw integrity(corrupt);
        }
    }

    private void requireSnapshot(AgentTaskExecutionGrantService.Scope scope, String taskId,
            AgentTaskExecutionGrantEntity grant) {
        try {
            AgentTaskRequirementSnapshotService.Snapshot snapshot = requirements.read(
                    scope, taskId, grant.getRequirementRevision());
            if (snapshot == null || !Objects.equals(scope.tenantId(), snapshot.tenantId())
                    || !Objects.equals(scope.clientId(), snapshot.clientId())
                    || !Objects.equals(scope.ownerJiacn(), snapshot.ownerJiacn())
                    || !Objects.equals(taskId, snapshot.taskId())
                    || snapshot.revision() != grant.getRequirementRevision()
                    || snapshot.sha256() == null || !snapshot.sha256().matches("[0-9a-f]{64}")) {
                throw integrity();
            }
        } catch (DataAccessException failure) {
            throw failure;
        } catch (IntegrityFailure failure) {
            throw failure;
        } catch (RuntimeException corrupt) {
            throw integrity(corrupt);
        }
    }

    private long requireAssignmentEventVersion(AgentTaskExecutionGrantService.Scope scope,
            String taskId, long taskVersion) {
        String payload = grants.latestAssignmentEventJson(scope.tenantId(), scope.clientId(),
                scope.ownerJiacn(), taskId);
        try {
            if (payload == null) throw integrity();
            var event = json.readTree(payload);
            var version = event.get("resultVersion");
            if (version == null || !version.isIntegralNumber() || !version.canConvertToLong()
                    || version.longValue() < 0 || version.longValue() > taskVersion) {
                throw integrity();
            }
            return version.longValue();
        } catch (IntegrityFailure failure) {
            throw failure;
        } catch (RuntimeException corrupt) {
            throw integrity(corrupt);
        }
    }

    private static boolean currentAssignment(AgentTaskMetaEntity root,
            AgentTaskExecutionGrantEntity grant, AgentTaskExecutionGrantEntity active,
            long latestAssignmentVersion) {
        if (!"ACTIVE".equals(grant.getState())
                || !Objects.equals(root.getAssignedAgentId(), grant.getTargetAgentId())
                || active == null
                || !Objects.equals(active.getTenantId(), grant.getTenantId())
                || !Objects.equals(active.getClientId(), grant.getClientId())
                || !Objects.equals(active.getOwnerJiacn(), grant.getOwnerJiacn())
                || !Objects.equals(active.getTaskId(), grant.getTaskId())
                || !Objects.equals(active.getGrantId(), grant.getGrantId())
                || !Objects.equals(active.getGrantVersion(), grant.getGrantVersion())) {
            return false;
        }
        // No-change assignment compatibility: the durable event can predate the grant fence.
        // A future assignment epoch, including a re-point to the same Agent, is never current.
        return latestAssignmentVersion <= grant.getAssignmentRevision();
    }

    private List<String> readOperations(String source) {
        try {
            List<String> values = json.readValue(source, new TypeReference<List<String>>() { });
            if (values == null || values.isEmpty() || values.size() > OPERATIONS.size()) {
                throw integrity();
            }
            LinkedHashSet<String> unique = new LinkedHashSet<>();
            for (String value : values) {
                exactPersisted(value, 40);
                if (!OPERATIONS.contains(value) || !unique.add(value)) throw integrity();
            }
            List<String> canonical = unique.stream().sorted().toList();
            if (!canonical.equals(values) || !Objects.equals(source,
                    json.writeValueAsString(values))) throw integrity();
            return List.copyOf(values);
        } catch (IntegrityFailure failure) {
            throw failure;
        } catch (RuntimeException corrupt) {
            throw integrity(corrupt);
        }
    }

    private List<InputSummary> readInputs(String source) {
        try {
            List<InputSummary> values = json.readValue(source,
                    new TypeReference<List<InputSummary>>() { });
            if (values == null || values.size() > 32) throw integrity();
            Set<String> unique = new LinkedHashSet<>();
            for (InputSummary input : values) {
                if (input == null) throw integrity();
                exactPersisted(input.fileId(), 100);
                exactPersisted(input.purpose(), 20);
                exactPersisted(input.contentMimeType(), 127);
                if (input.version() < 1 || input.byteLength() < 0
                        || !PURPOSES.contains(input.purpose())
                        || input.contentHash() == null
                        || !input.contentHash().matches("[0-9a-f]{64}")
                        || !unique.add(input.fileId() + "\u0000" + input.version()
                                + "\u0000" + input.purpose())) {
                    throw integrity();
                }
            }
            List<InputSummary> canonical = new ArrayList<>(values);
            canonical.sort(Comparator.comparing(InputSummary::fileId,
                    AgentTaskDeliberationOperationReadServiceImpl::compareUtf8)
                    .thenComparingInt(InputSummary::version).thenComparing(InputSummary::purpose));
            if (!canonical.equals(values) || !Objects.equals(source,
                    json.writeValueAsString(values))) throw integrity();
            return List.copyOf(values);
        } catch (IntegrityFailure failure) {
            throw failure;
        } catch (RuntimeException corrupt) {
            throw integrity(corrupt);
        }
    }

    private void requireRequestHash(AgentTaskExecutionGrantEntity grant, List<String> operations,
            List<InputSummary> inputs, String persistedInitialOperation) {
        try {
            String operationsJson = json.writeValueAsString(operations);
            List<RequestInput> requested = inputs.stream().map(input ->
                    new RequestInput(input.fileId(), input.version(), input.purpose())).toList();
            String inputsJson = json.writeValueAsString(requested);
            long assignment = grant.getAssignmentRevision();

            // The old domain is valid only for the old unambiguous request shape. Never use
            // its implicit picker to reduce a complete multi-action authorization set.
            boolean oldDomainAllowed;
            try {
                oldDomainAllowed = Objects.equals(
                        AgentTaskBountyBootstrapPayload.initialOperation(operations),
                        persistedInitialOperation);
            } catch (IllegalArgumentException ambiguousOldRequest) {
                oldDomainAllowed = false;
            }
            boolean oldDomain = oldDomainAllowed && matchesAssignmentHash(grant, assignment,
                    operationsJson, inputsJson, null);
            // The new domain binds the explicit selector already persisted in the outbox.
            // It is a read-only proof: never infer, repair, or rewrite the selector here.
            boolean explicitDomain = matchesAssignmentHash(grant, assignment, operationsJson,
                    inputsJson, persistedInitialOperation);
            if (oldDomain == explicitDomain) throw integrity();
        } catch (IntegrityFailure failure) {
            throw failure;
        } catch (RuntimeException corrupt) {
            throw integrity(corrupt);
        }
    }

    private static boolean matchesAssignmentHash(AgentTaskExecutionGrantEntity grant,
            long assignmentRevision, String operationsJson, String inputsJson,
            String initialOperation) {
        if (constantEquals(grant.getRequestHash(), assignHash(grant, assignmentRevision,
                operationsJson, inputsJson, initialOperation))) return true;
        return assignmentRevision > 0 && constantEquals(grant.getRequestHash(), assignHash(grant,
                assignmentRevision - 1, operationsJson, inputsJson, initialOperation));
    }

    private static String assignHash(AgentTaskExecutionGrantEntity grant, long expectedTaskVersion,
            String operationsJson, String inputsJson, String initialOperation) {
        String domain = initialOperation == null ? "ASSIGN_AND_START"
                : "ASSIGN_AND_START_INITIAL_OPERATION_V1";
        String material = domain + "\n" + grant.getTaskId() + "\n"
                + grant.getTargetAgentId() + "\n" + expectedTaskVersion + "\n"
                + grant.getRequirementRevision() + "\n" + operationsJson + "\n" + inputsJson;
        if (initialOperation != null) material += "\n" + initialOperation;
        return sha256(material);
    }

    private static boolean sameReferences(List<InputSummary> inputs,
            List<ReferenceSummary> references) {
        if (inputs.size() != references.size()) return false;
        for (int index = 0; index < inputs.size(); index++) {
            InputSummary input = inputs.get(index);
            ReferenceSummary reference = references.get(index);
            if (!Objects.equals(input.fileId(), reference.fileId())
                    || input.version() != reference.version()
                    || !Objects.equals(input.purpose(), reference.purpose())
                    || !Objects.equals(input.contentMimeType(), reference.contentMimeType())
                    || input.byteLength() != reference.byteLength()
                    || !Objects.equals(input.contentHash(), reference.contentHash())) return false;
        }
        return true;
    }

    private static void requireRoot(AgentTaskMetaEntity root,
            AgentTaskExecutionGrantService.Scope scope, String taskId) {
        if (root == null || !Objects.equals(scope.tenantId(), root.getTenantId())
                || !Objects.equals(scope.clientId(), root.getClientId())
                || !Objects.equals(scope.ownerJiacn(), root.getOwnerJiacn())
                || !Objects.equals(taskId, root.getTaskId())
                || root.getTaskVersion() == null || root.getTaskVersion() < 0) throw integrity();
    }

    private static void validateScope(AgentTaskExecutionGrantService.Scope scope) {
        if (scope == null || !"0".equals(scope.tenantId())) {
            throw new IllegalArgumentException("scope");
        }
        exact(scope.clientId(), 50);
        exact(scope.ownerJiacn(), 50);
        if ("0".equals(scope.ownerJiacn())) throw new IllegalArgumentException("scope");
    }

    private static void exact(String value, int max) {
        if (!validExact(value, max)) throw new IllegalArgumentException("invalid identity");
    }

    private static void exactPersisted(String value, int max) {
        if (!validExact(value, max)) throw integrity();
    }

    private static boolean validExact(String value, int max) {
        return value != null && !value.isEmpty() && !hasUnpairedSurrogate(value)
                && value.codePointCount(0, value.length()) <= max
                && !isPadding(value.codePointAt(0))
                && !isPadding(value.codePointBefore(value.length()))
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    return true;
                }
            } else if (Character.isLowSurrogate(unit)) return true;
        }
        return false;
    }

    private static boolean isPadding(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static int compareUtf8(String left, String right) {
        byte[] a = left.getBytes(StandardCharsets.UTF_8);
        byte[] b = right.getBytes(StandardCharsets.UTF_8);
        for (int index = 0; index < Math.min(a.length, b.length); index++) {
            int compared = Integer.compare(Byte.toUnsignedInt(a[index]), Byte.toUnsignedInt(b[index]));
            if (compared != 0) return compared;
        }
        return Integer.compare(a.length, b.length);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean constantEquals(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }

    private static ReadException notFound() {
        return new ReadException(ReadException.Reason.NOT_FOUND);
    }

    private static IntegrityFailure integrity() {
        return new IntegrityFailure();
    }

    private static IntegrityFailure integrity(Throwable cause) {
        return new IntegrityFailure(cause);
    }

    private record GrantFacts(List<String> operations, List<InputSummary> inputs) { }
    private record RequestInput(String fileId, int version, String purpose) { }

    private static final class IntegrityFailure extends RuntimeException {
        private IntegrityFailure() { }
        private IntegrityFailure(Throwable cause) { super(cause); }
    }
}
