package cn.jia.chat.archive.maintenance.integration;

import cn.jia.agent.entity.AgentArchiveMaintenancePayload;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentRawCommandDispatchResult;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.ArchiveAgentExecutionPort;
import cn.jia.agent.service.InstalledSkillResolver;
import cn.jia.agent.service.impl.AgentCommandCanonicalCodec;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deliberately narrow prerequisite transport double. Business state remains in the real archive
 * JDBC service; this class replaces only Agent command persistence/WebSocket delivery while still
 * reusing the production codec and real live-runtime authentication checks.
 */
public final class FixtureArchiveExecutionPort implements ArchiveAgentExecutionPort {
    public static final String INSTALLATION_ID = "fixture-installation";
    public static final long INSTALLATION_REVISION = 1L;
    public static final String SKILL_KEY = "archive-maintainer";
    public static final String SKILL_VERSION = "1.0.0";
    public static final String PACKAGE_SHA256 =
            "8894d96341067dd7f9e2f45696eef44057dc61346255a0323b2d713a3c7ea081";

    private final AgentRuntimeAuthenticationService authentication;
    private final Clock clock;
    private final Map<String, Issued> issued = new ConcurrentHashMap<>();

    public FixtureArchiveExecutionPort(AgentRuntimeAuthenticationService authentication, Clock clock) {
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public LockedIdentityRoot lockIdentityRoot(TargetRequest request) {
        requireWriteTransaction();
        return new LockedIdentityRoot(request.tenant(), request.client(), request.owner(),
                request.canonicalAgent(), request.binding());
    }

    @Override
    public LockedTarget requireControlledTarget(TargetRequest request, LockedIdentityRoot root) {
        requireWriteTransaction();
        if (!request.equals(root.target())) throw new Denied("ARCHIVE_EXECUTION_TARGET_FENCED");
        AgentRuntimeAuthenticationService.ControlledTarget target = authentication.requireControlledTarget(
                request.tenant(), request.client(), request.owner(), request.canonicalAgent(),
                request.binding(), REQUIRED_PROTOCOL);
        return new LockedTarget(request.tenant(), request.client(), request.owner(),
                request.canonicalAgent(), request.binding(), target.runtimeInstanceId(),
                target.registrationHash());
    }

    @Override
    public Grant ensureExecution(Request request, LockedTarget lockedTarget) {
        requireWriteTransaction();
        exactTarget(request.target(), lockedTarget);
        if (!SKILL_KEY.equals(request.skillKey()) || !SKILL_VERSION.equals(request.skillVersion())
                || !PACKAGE_SHA256.equals(request.packageDigest())) {
            throw new Denied("ARCHIVE_EXECUTION_SKILL_NOT_VERIFIED");
        }
        String commandId = AgentCommandCanonicalCodec.controlledCommandId(request.tenant(),
                request.client(), request.owner(), request.runId(), request.canonicalAgent(), COMMAND_TYPE);
        String messageId = "fixture-message-" + request.runId();
        int attempt = 1;
        long issuedAt = clock.millis();
        long expiresAt = Math.addExact(issuedAt, 3_600_000L);
        InstalledSkillResolver.Proof proof = new InstalledSkillResolver.Proof(INSTALLATION_ID,
                INSTALLATION_REVISION, SKILL_KEY, SKILL_VERSION, PACKAGE_SHA256);
        AgentArchiveMaintenancePayload payload = new AgentArchiveMaintenancePayload(1,
                request.jobId(), request.runId(), Long.toString(request.executionEpoch()),
                request.appointmentId(), Long.toString(request.appointmentRevision()),
                Long.toString(request.managerAuthorizationRevision()), Long.toString(request.binding()),
                request.grantRef(), request.executionRef(), request.dispatchKey(), INSTALLATION_ID,
                PACKAGE_SHA256, request.contextRef());
        AgentCommandDraft draft = new AgentCommandDraft(1, commandId, request.jobId(), request.runId(),
                request.tenant(), request.client(), request.owner(), request.jobId(), null,
                request.canonicalAgent(), COMMAND_TYPE, issuedAt, expiresAt, payload);
        byte[] wire = AgentCommandCanonicalCodec.wireBytes(draft, messageId, attempt);
        Issued value = new Issued(request, lockedTarget, proof, commandId, messageId, attempt,
                expiresAt, wire);
        Issued previous = issued.putIfAbsent(request.jobId(), value);
        if (previous != null && !previous.same(value)) {
            throw new Denied("ARCHIVE_EXECUTION_DELIVERY_FENCED");
        }
        return new Grant(request.grantRef(), request.executionRef(), commandId, attempt,
                request.executionEpoch(), expiresAt, lockedTarget.runtimeInstanceId(),
                lockedTarget.registrationHash(), proof, previous != null);
    }

    @Override
    public Inspection inspectDispatch(Expected expected, LockedTarget lockedTarget) {
        requireWriteTransaction();
        return inspect(expected, lockedTarget, "CONSUMED");
    }

    @Override
    public Inspection inspectExecution(Expected expected, LockedTarget lockedTarget) {
        requireWriteTransaction();
        return inspect(expected, lockedTarget, "STARTED");
    }

    @Override
    public Inspection inspectResult(Expected expected, LockedTarget lockedTarget) {
        requireWriteTransaction();
        return inspect(expected, lockedTarget, "STARTED");
    }

    @Override
    public AgentRawCommandDispatchResult dispatch(Expected expected, byte[] exactWire) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new Denied("ARCHIVE_EXECUTION_DISPATCH_TRANSACTION_OPEN");
        }
        Issued value = requireIssued(expected, exactWire);
        authentication.requireControlledTarget(expected.tenant(), expected.client(), expected.owner(),
                expected.canonicalAgent(), expected.binding(), REQUIRED_PROTOCOL);
        return value == null ? AgentRawCommandDispatchResult.rejected()
                : AgentRawCommandDispatchResult.sent(1, 1);
    }

    public byte[] wireForJob(String jobId) {
        Issued value = issued.get(jobId);
        if (value == null) throw new IllegalStateException("Fixture command was not issued for " + jobId);
        return value.wire().clone();
    }

    private Inspection inspect(Expected expected, LockedTarget lockedTarget, String deliveryState) {
        exactTarget(expected.target(), lockedTarget);
        Issued value = requireIssued(expected, null);
        AgentRuntimeAuthenticationService.ControlledTarget live = authentication.requireControlledTarget(
                expected.tenant(), expected.client(), expected.owner(), expected.canonicalAgent(),
                expected.binding(), REQUIRED_PROTOCOL);
        if (!expected.runtimeInstanceId().equals(live.runtimeInstanceId())
                || !Arrays.equals(expected.registrationHash(), live.registrationHash())) {
            throw new Denied("ARCHIVE_EXECUTION_TARGET_FENCED");
        }
        return new Inspection(true, deliveryState, value.messageId());
    }

    private Issued requireIssued(Expected expected, byte[] exactWire) {
        Issued value = issued.get(expected.jobId());
        if (value == null || !value.matches(expected)
                || exactWire != null && !Arrays.equals(value.wire(), exactWire)) {
            throw new Denied("ARCHIVE_EXECUTION_DELIVERY_FENCED");
        }
        return value;
    }

    private static void exactTarget(TargetRequest expected, LockedTarget actual) {
        if (!expected.tenant().equals(actual.tenant()) || !expected.client().equals(actual.client())
                || !expected.owner().equals(actual.owner())
                || !expected.canonicalAgent().equals(actual.canonicalAgent())
                || expected.binding() != actual.binding()) {
            throw new Denied("ARCHIVE_EXECUTION_TARGET_FENCED");
        }
    }

    private static void requireWriteTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new Denied("ARCHIVE_EXECUTION_TRANSACTION_REQUIRED");
        }
    }

    private record Issued(Request request, LockedTarget target, InstalledSkillResolver.Proof proof,
            String commandId, String messageId, int attempt, long expiresAt, byte[] wire) {
        private Issued {
            wire = wire.clone();
        }
        @Override public byte[] wire() { return wire.clone(); }
        boolean same(Issued other) {
            return request.equals(other.request) && target.equals(other.target) && proof.equals(other.proof)
                    && commandId.equals(other.commandId) && messageId.equals(other.messageId)
                    && attempt == other.attempt && expiresAt == other.expiresAt
                    && Arrays.equals(wire, other.wire);
        }
        boolean matches(Expected expected) {
            return request.tenant().equals(expected.tenant())
                    && request.client().equals(expected.client())
                    && request.owner().equals(expected.owner())
                    && request.canonicalAgent().equals(expected.canonicalAgent())
                    && request.binding() == expected.binding()
                    && request.jobId().equals(expected.jobId())
                    && request.runId().equals(expected.runId())
                    && request.appointmentId().equals(expected.appointmentId())
                    && request.appointmentRevision() == expected.appointmentRevision()
                    && request.managerAuthorizationRevision() == expected.managerAuthorizationRevision()
                    && request.grantRef().equals(expected.grantRef())
                    && request.executionRef().equals(expected.executionRef())
                    && request.executionEpoch() == expected.executionEpoch()
                    && request.dispatchKey().equals(expected.dispatchKey())
                    && commandId.equals(expected.commandId()) && attempt == expected.activeAttempt()
                    && expiresAt == expected.expiresAt()
                    && target.runtimeInstanceId().equals(expected.runtimeInstanceId())
                    && Arrays.equals(target.registrationHash(), expected.registrationHash())
                    && proof.equals(expected.skillProof())
                    && request.contextRef().equals(expected.contextRef());
        }
    }
}
