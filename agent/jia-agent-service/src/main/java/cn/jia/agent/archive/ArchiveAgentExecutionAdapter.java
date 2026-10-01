package cn.jia.agent.archive;

import cn.jia.agent.dao.AgentCommandTransportDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.*;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.*;
import cn.jia.agent.service.impl.AgentCommandCanonicalCodec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

/** Identity/install/transport adapter. Archive business authorization remains in chat-service. */
@Service
@ConditionalOnProperty(prefix = "archive.maintenance", name = "execution-enabled", havingValue = "true")
public final class ArchiveAgentExecutionAdapter implements ArchiveAgentExecutionPort {
    private static final Set<String> AUTHORIZED_DELIVERY = Set.of("SENT", "RECEIVED", "STARTED", "SUCCEEDED");
    private static final Set<String> RESULT_DELIVERY = Set.of("SENT", "RECEIVED", "STARTED",
            "SUCCEEDED", "FAILED", "REJECTED");
    private static final Set<String> DISPATCHABLE_DELIVERY = Set.of("CONSUMED", "SENT", "RECEIVED", "STARTED");
    private final AgentIdentityService identities;
    private final AgentRuntimeDao runtimes;
    private final AgentRuntimeAuthenticationService authentication;
    private final InstalledSkillResolver skills;
    private final ObjectProvider<AgentCommandTransportWriter> writers;
    private final AgentCommandTransportDao deliveries;
    private final ObjectProvider<AgentManagedSessionLookup> sessions;
    private final TransactionTemplate tx;
    private final Clock clock;

    @Autowired
    public ArchiveAgentExecutionAdapter(AgentIdentityService identities, AgentRuntimeDao runtimes,
            AgentRuntimeAuthenticationService authentication, InstalledSkillResolver skills,
            ObjectProvider<AgentCommandTransportWriter> writers, AgentCommandTransportDao deliveries,
            ObjectProvider<AgentManagedSessionLookup> sessions, PlatformTransactionManager manager) {
        this(identities, runtimes, authentication, skills, writers, deliveries, sessions, manager,
                Clock.systemUTC());
    }

    ArchiveAgentExecutionAdapter(AgentIdentityService identities, AgentRuntimeDao runtimes,
            AgentRuntimeAuthenticationService authentication, InstalledSkillResolver skills,
            ObjectProvider<AgentCommandTransportWriter> writers, AgentCommandTransportDao deliveries,
            ObjectProvider<AgentManagedSessionLookup> sessions, PlatformTransactionManager manager,
            Clock clock) {
        this.identities = Objects.requireNonNull(identities);
        this.runtimes = Objects.requireNonNull(runtimes);
        this.authentication = Objects.requireNonNull(authentication);
        this.skills = Objects.requireNonNull(skills);
        this.writers = Objects.requireNonNull(writers);
        this.deliveries = Objects.requireNonNull(deliveries);
        this.sessions = Objects.requireNonNull(sessions);
        this.tx = new TransactionTemplate(Objects.requireNonNull(manager));
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public LockedIdentityRoot lockIdentityRoot(TargetRequest request) {
        Objects.requireNonNull(request, "request");
        requireWriteTransaction();
        try {
            AgentIdentityRegistryEntity identity = identities.lockPersistedIdentityForBinding(
                    request.tenant(), request.client(), request.owner(), request.binding(),
                    request.canonicalAgent());
            require(identity != null && request.canonicalAgent().equals(identity.getCanonicalAgentId()),
                    "ARCHIVE_EXECUTION_TARGET_FENCED");
            // This is the canonical runtime aggregate root lock only. A retired historical binding
            // must still be able to fence its old run after the current runtime projection moves.
            // Current scope/binding/session/protocol validation belongs exclusively to the
            // positive requireControlledTarget path below.
            runtimes.findByAgentIdForUpdate(request.canonicalAgent());
            return new LockedIdentityRoot(request.tenant(), request.client(), request.owner(),
                    request.canonicalAgent(), request.binding());
        } catch (RuntimeException denied) {
            if (denied instanceof ArchiveAgentExecutionPort.Denied) throw denied;
            throw new ArchiveAgentExecutionPort.Denied("ARCHIVE_EXECUTION_TARGET_FENCED");
        }
    }

    @Override
    public LockedTarget requireControlledTarget(TargetRequest request, LockedIdentityRoot root) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(root, "root");
        requireWriteTransaction();
        exactRoot(request, root);
        try {
            AgentIdentityRegistryEntity identity = identities.requireActiveIdentityForBinding(
                    request.tenant(), request.client(), request.owner(), request.binding(),
                    request.canonicalAgent());
            require(identity != null && request.canonicalAgent().equals(identity.getCanonicalAgentId()),
                    "ARCHIVE_EXECUTION_TARGET_FENCED");
            AgentRuntimeEntity runtime = runtimes.findByAgentIdForUpdate(request.canonicalAgent());
            require(runtime != null && request.tenant().equals(runtime.getTenantId())
                    && request.client().equals(runtime.getClientId())
                    && request.owner().equals(runtime.getOwnerJiacn())
                    && Long.valueOf(request.binding()).equals(runtime.getBindingId()),
                    "ARCHIVE_EXECUTION_TARGET_FENCED");
            AgentRuntimeAuthenticationService.ControlledTarget target = authentication.requireControlledTarget(
                    request.tenant(), request.client(), request.owner(), request.canonicalAgent(),
                    request.binding(), REQUIRED_PROTOCOL);
            return new LockedTarget(request.tenant(), request.client(), request.owner(),
                    request.canonicalAgent(), request.binding(), target.runtimeInstanceId(),
                    target.registrationHash());
        } catch (RuntimeException denied) {
            if (denied instanceof ArchiveAgentExecutionPort.Denied) throw denied;
            throw new ArchiveAgentExecutionPort.Denied("ARCHIVE_EXECUTION_TARGET_FENCED");
        }
    }

    @Override
    public Grant ensureExecution(Request request, LockedTarget lockedTarget) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(lockedTarget, "lockedTarget");
        require(TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                "ARCHIVE_EXECUTION_TRANSACTION_REQUIRED");
        exactLockedTarget(request.target(), lockedTarget);
        InstalledSkillResolver.Proof proof = verifiedSkill(request.tenant(), request.client(), request.owner(),
                request.canonicalAgent(), request.binding(), request.skillOrigin(), request.skillKey(),
                request.skillVersion(), request.packageDigest());
        String commandId = AgentCommandCanonicalCodec.controlledCommandId(request.tenant(), request.client(),
                request.owner(), request.runId(), request.canonicalAgent(), COMMAND_TYPE);
        long issuedAt = clock.millis();
        long expiresAt = Math.addExact(issuedAt, AgentCommandCanonicalCodec.TASK_INVITE_TTL_MILLIS);
        AgentArchiveMaintenancePayload payload = new AgentArchiveMaintenancePayload(1,
                request.jobId(), request.runId(), Long.toString(request.executionEpoch()),
                request.appointmentId(), Long.toString(request.appointmentRevision()),
                Long.toString(request.managerAuthorizationRevision()), Long.toString(request.binding()),
                request.grantRef(), request.executionRef(),
                request.dispatchKey(), proof.installationRef(), proof.packageDigest(), request.contextRef());
        AgentCommandDraft draft = new AgentCommandDraft(1, commandId, request.jobId(), request.runId(),
                request.tenant(), request.client(), request.owner(), request.jobId(), null,
                request.canonicalAgent(), COMMAND_TYPE, issuedAt, expiresAt, payload);
        AgentCommandTransportWriter writer = writers.getIfAvailable();
        require(writer != null, "ARCHIVE_EXECUTION_TRANSPORT_UNAVAILABLE");
        AgentCommandTransportWriteResult written = writer.write(draft);
        AgentCommandDeliveryEntity delivery = deliveries.lockDelivery(request.tenant(), request.client(),
                request.owner(), commandId);
        require(delivery != null && "PENDING".equals(delivery.getStatus())
                && delivery.getActiveAttempt() != null && delivery.getActiveAttempt() > 0,
                "ARCHIVE_EXECUTION_TRANSPORT_UNAVAILABLE");
        verifyDelivery(delivery, expected(request, proof, commandId, delivery.getActiveAttempt(),
                expiresAt, lockedTarget), Set.of("PENDING"));
        return new Grant(request.grantRef(), request.executionRef(), commandId,
                delivery.getActiveAttempt(), request.executionEpoch(), expiresAt,
                lockedTarget.runtimeInstanceId(), lockedTarget.registrationHash(), proof,
                written.duplicate());
    }

    @Override
    public Inspection inspectExecution(Expected expected, LockedTarget lockedTarget) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(lockedTarget, "lockedTarget");
        require(TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                "ARCHIVE_EXECUTION_TRANSACTION_REQUIRED");
        exactLockedTarget(expected.target(), lockedTarget);
        require(expected.runtimeInstanceId().equals(lockedTarget.runtimeInstanceId())
                && MessageDigest.isEqual(expected.registrationHash(), lockedTarget.registrationHash()),
                "ARCHIVE_EXECUTION_TARGET_FENCED");
        exactProof(expected, verifiedSkill(expected.tenant(), expected.client(), expected.owner(),
                expected.canonicalAgent(), expected.binding(), expected.skillOrigin(),
                expected.skillProof().key(), expected.skillProof().version(),
                expected.skillProof().packageDigest()));
        AgentCommandDeliveryEntity delivery = deliveries.lockDelivery(expected.tenant(), expected.client(),
                expected.owner(), expected.commandId());
        verifyDelivery(delivery, expected, AUTHORIZED_DELIVERY);
        require(clock.millis() < expected.expiresAt(), "ARCHIVE_EXECUTION_EXPIRED");
        return new Inspection(true, delivery.getStatus(), delivery.getActiveMessageId());
    }

    @Override
    public Inspection inspectResult(Expected expected, LockedTarget lockedTarget) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(lockedTarget, "lockedTarget");
        require(TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                "ARCHIVE_EXECUTION_TRANSACTION_REQUIRED");
        exactLockedTarget(expected.target(), lockedTarget);
        require(expected.runtimeInstanceId().equals(lockedTarget.runtimeInstanceId())
                && MessageDigest.isEqual(expected.registrationHash(), lockedTarget.registrationHash()),
                "ARCHIVE_EXECUTION_TARGET_FENCED");
        exactProof(expected, verifiedSkill(expected.tenant(), expected.client(), expected.owner(),
                expected.canonicalAgent(), expected.binding(), expected.skillOrigin(),
                expected.skillProof().key(), expected.skillProof().version(),
                expected.skillProof().packageDigest()));
        AgentCommandDeliveryEntity delivery = deliveries.lockDelivery(expected.tenant(), expected.client(),
                expected.owner(), expected.commandId());
        verifyDelivery(delivery, expected, RESULT_DELIVERY);
        // Terminal READ_ONLY receipts remain recoverable after the producer deadline. The caller
        // admits only a durable COMPLETED/FAILED run and still revalidates every live scope proof.
        return new Inspection(true, delivery.getStatus(), delivery.getActiveMessageId());
    }

    @Override
    public AgentRawCommandDispatchResult dispatch(Expected expected, byte[] exactWire) {
        Objects.requireNonNull(expected, "expected");
        require(exactWire != null && exactWire.length > 0, "ARCHIVE_EXECUTION_WIRE_INVALID");
        require(!TransactionSynchronizationManager.isActualTransactionActive(),
                "ARCHIVE_EXECUTION_DISPATCH_TRANSACTION_OPEN");
        DispatchTarget current = tx.execute(status -> {
            TargetRequest request = expected.target();
            LockedIdentityRoot root = lockIdentityRoot(request);
            LockedTarget controlled = requireControlledTarget(request, root);
            exactTarget(expected, controlled);
            exactProof(expected, verifiedSkill(expected.tenant(), expected.client(), expected.owner(),
                    expected.canonicalAgent(), expected.binding(), expected.skillOrigin(),
                    expected.skillProof().key(), expected.skillProof().version(),
                    expected.skillProof().packageDigest()));
            AgentCommandDeliveryEntity delivery = deliveries.lockDelivery(expected.tenant(), expected.client(),
                    expected.owner(), expected.commandId());
            AgentCommandDraft draft = verifyDelivery(delivery, expected, DISPATCHABLE_DELIVERY);
            byte[] canonical = AgentCommandCanonicalCodec.wireBytes(draft,
                    delivery.getActiveMessageId(), delivery.getActiveAttempt());
            require(Arrays.equals(canonical, exactWire), "ARCHIVE_EXECUTION_WIRE_INVALID");
            require(clock.millis() < expected.expiresAt(), "ARCHIVE_EXECUTION_EXPIRED");
            AgentRuntimeAuthenticationService.ControlledTarget live = authentication.requireControlledTarget(
                    expected.tenant(), expected.client(), expected.owner(), expected.canonicalAgent(),
                    expected.binding(), REQUIRED_PROTOCOL);
            require(expected.runtimeInstanceId().equals(live.runtimeInstanceId())
                    && MessageDigest.isEqual(expected.registrationHash(), live.registrationHash()),
                    "ARCHIVE_EXECUTION_TARGET_FENCED");
            return new DispatchTarget(controlled, live.apiKeyId());
        });
        AgentManagedSessionLookup managed = sessions.getIfAvailable();
        require(managed != null, "ARCHIVE_EXECUTION_TRANSPORT_UNAVAILABLE");
        AgentRawCommandDispatchResult result = managed.dispatch(expected.tenant(), expected.client(),
                expected.canonicalAgent(), current.apiKeyId(),
                current.target().registrationHash(), exactWire);
        return result == null ? AgentRawCommandDispatchResult.rejected() : result;
    }

    private InstalledSkillResolver.Proof verifiedSkill(String tenant, String client, String owner,
            String agent, long binding, InstalledSkillResolver.Origin origin, String key,
            String version, String digest) {
        InstalledSkillResolver.Resolution resolution = skills.resolve(new InstalledSkillResolver.Request(
                tenant, client, owner, agent, binding, origin, key, version, digest));
        require(resolution != null && resolution.state() == InstalledSkillResolver.State.VERIFIED
                && resolution.proof() != null, "ARCHIVE_EXECUTION_SKILL_NOT_VERIFIED");
        return resolution.proof();
    }

    private AgentCommandDraft verifyDelivery(AgentCommandDeliveryEntity delivery, Expected expected,
            Set<String> allowedStates) {
        require(delivery != null && expected.commandId().equals(delivery.getCommandId())
                && expected.owner().equals(delivery.getOwnerJiacn())
                && expected.jobId().equals(delivery.getTaskId()) && delivery.getWorkItemId() == null
                && expected.canonicalAgent().equals(delivery.getTargetAgentId())
                && COMMAND_TYPE.equals(delivery.getCommandType())
                && allowedStates.contains(delivery.getStatus())
                && delivery.getActiveAttempt() != null
                && delivery.getActiveAttempt() == expected.activeAttempt()
                && Objects.equals(delivery.getExpiresAt(), expected.expiresAt())
                && delivery.getActiveMessageId() != null && delivery.getCommandPayload() != null
                && delivery.getCommandPayloadHash() != null
                && MessageDigest.isEqual(AgentCommandCanonicalCodec.sha256(delivery.getCommandPayload()),
                        delivery.getCommandPayloadHash()), "ARCHIVE_EXECUTION_DELIVERY_FENCED");
        AgentCommandDraft draft;
        try { draft = AgentCommandCanonicalCodec.decodeBusinessBytes(delivery.getCommandPayload()); }
        catch (RuntimeException corrupt) { throw new ArchiveAgentExecutionPort.Denied("ARCHIVE_EXECUTION_DELIVERY_FENCED"); }
        require(expected.commandId().equals(draft.commandId())
                && expected.jobId().equals(draft.correlationId())
                && expected.runId().equals(draft.causationId())
                && expected.tenant().equals(draft.tenantId()) && expected.client().equals(draft.clientId())
                && expected.owner().equals(draft.ownerJiacn()) && expected.jobId().equals(draft.taskId())
                && expected.canonicalAgent().equals(draft.targetAgentId())
                && COMMAND_TYPE.equals(draft.commandType()) && draft.intentId() == null
                && draft.payload() instanceof AgentArchiveMaintenancePayload,
                "ARCHIVE_EXECUTION_DELIVERY_FENCED");
        AgentArchiveMaintenancePayload payload = (AgentArchiveMaintenancePayload) draft.payload();
        require(payload.schemaVersion() == 1 && expected.jobId().equals(payload.jobId())
                && expected.runId().equals(payload.runId())
                && Long.toString(expected.executionEpoch()).equals(payload.executionEpoch())
                && expected.appointmentId().equals(payload.appointmentId())
                && Long.toString(expected.appointmentRevision()).equals(payload.appointmentRevision())
                && Long.toString(expected.managerAuthorizationRevision()).equals(payload.managerAuthorizationRevision())
                && Long.toString(expected.binding()).equals(payload.bindingVersion())
                && expected.grantRef().equals(payload.grantRef())
                && expected.executionRef().equals(payload.executionRef())
                && expected.dispatchKey().equals(payload.dispatchKey())
                && expected.skillProof().installationRef().equals(payload.skillInstallationId())
                && expected.skillProof().packageDigest().equals(payload.skillPackageSha256())
                && expected.contextRef().equals(payload.contextRef()),
                "ARCHIVE_EXECUTION_DELIVERY_FENCED");
        return draft;
    }

    private static Expected expected(Request request, InstalledSkillResolver.Proof proof,
            String commandId, int attempt, long expiresAt,
            LockedTarget target) {
        return new Expected(request.tenant(), request.client(), request.owner(), request.canonicalAgent(),
                request.binding(), request.jobId(), request.runId(), request.appointmentId(),
                request.appointmentRevision(), request.managerAuthorizationRevision(), request.grantRef(),
                request.executionRef(), request.executionEpoch(), request.dispatchKey(), commandId,
                attempt, expiresAt, target.runtimeInstanceId(), target.registrationHash(),
                request.skillOrigin(), proof,
                request.contextRef());
    }

    private static void requireWriteTransaction() {
        require(TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                "ARCHIVE_EXECUTION_TRANSACTION_REQUIRED");
    }

    private static void exactRoot(TargetRequest expected, LockedIdentityRoot actual) {
        require(expected.equals(actual.target()), "ARCHIVE_EXECUTION_TARGET_FENCED");
    }

    private static void exactLockedTarget(TargetRequest expected, LockedTarget actual) {
        require(expected.tenant().equals(actual.tenant()) && expected.client().equals(actual.client())
                && expected.owner().equals(actual.owner())
                && expected.canonicalAgent().equals(actual.canonicalAgent())
                && expected.binding() == actual.binding(), "ARCHIVE_EXECUTION_TARGET_FENCED");
    }

    private static void exactTarget(Expected expected, LockedTarget target) {
        require(expected.runtimeInstanceId().equals(target.runtimeInstanceId())
                && MessageDigest.isEqual(expected.registrationHash(), target.registrationHash()),
                "ARCHIVE_EXECUTION_TARGET_FENCED");
    }
    private static void exactProof(Expected expected, InstalledSkillResolver.Proof proof) {
        require(expected.skillProof().equals(proof), "ARCHIVE_EXECUTION_SKILL_FENCED");
    }
    private record DispatchTarget(LockedTarget target, String apiKeyId) { }

    private static void require(boolean condition, String code) {
        if (!condition) throw new ArchiveAgentExecutionPort.Denied(code);
    }
}
