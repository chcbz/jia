package cn.jia.agent.service;

import cn.jia.agent.entity.AgentRawCommandDispatchResult;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Exact archive execution boundary. Archive owns business grants; the Agent adapter owns
 * current identity/runtime/installation checks and the shared command transport.
 */
public interface ArchiveAgentExecutionPort {
    String COMMAND_TYPE = "ARCHIVE_MAINTENANCE_EXECUTE";
    String REQUIRED_PROTOCOL = COMMAND_TYPE + "/v1";

    record TargetRequest(String tenant, String client, String owner, String canonicalAgent,
            long binding) {
        public TargetRequest { validateTarget(tenant, client, owner, canonicalAgent, binding); }
    }

    /** Persistent identity/binding/runtime-root lock; valid even when the target is offline or revoked. */
    record LockedIdentityRoot(String tenant, String client, String owner, String canonicalAgent,
            long binding) {
        public LockedIdentityRoot { validateTarget(tenant, client, owner, canonicalAgent, binding); }
        public TargetRequest target() { return new TargetRequest(tenant, client, owner, canonicalAgent, binding); }
    }

    /** Positive-path controlled target snapshot obtained after the persistent root lock. */
    record LockedTarget(String tenant, String client, String owner, String canonicalAgent,
            long binding, String runtimeInstanceId, byte[] registrationHash) {
        public LockedTarget {
            validateTarget(tenant, client, owner, canonicalAgent, binding);
            id(runtimeInstanceId);
            if (registrationHash == null || registrationHash.length != 32) {
                throw new IllegalArgumentException("Invalid locked archive execution target");
            }
            registrationHash = registrationHash.clone();
        }
        @Override public byte[] registrationHash() { return registrationHash.clone(); }
    }

    record Request(String tenant, String client, String owner, String canonicalAgent,
            long binding, String jobId, String runId, String appointmentId,
            long appointmentRevision, long managerAuthorizationRevision,
            String grantRef, String executionRef, long executionEpoch, String dispatchKey,
            InstalledSkillResolver.Origin skillOrigin, String skillKey, String skillVersion,
            String packageDigest, String contextRef) {
        public Request { validate(tenant, client, owner, canonicalAgent, binding, jobId, runId,
                appointmentId, appointmentRevision, managerAuthorizationRevision, grantRef,
                executionRef, executionEpoch, dispatchKey, skillOrigin, skillKey, skillVersion,
                packageDigest, contextRef); }
        public TargetRequest target() { return new TargetRequest(tenant, client, owner, canonicalAgent, binding); }
    }

    record Grant(String grantRef, String executionRef, String commandId, int activeAttempt,
            long executionEpoch, long expiresAt, String runtimeInstanceId, byte[] registrationHash,
            InstalledSkillResolver.Proof skillProof, boolean duplicate) {
        public Grant {
            id(grantRef); id(executionRef); id(commandId); id(runtimeInstanceId);
            if (activeAttempt < 1 || executionEpoch < 1 || expiresAt < 1
                    || registrationHash == null || registrationHash.length != 32 || skillProof == null) {
                throw new IllegalArgumentException("Invalid archive execution grant");
            }
            registrationHash = registrationHash.clone();
        }
        @Override public byte[] registrationHash() { return registrationHash.clone(); }
    }

    /** Server-persisted immutable facts used for native authorization and exact dispatch. */
    record Expected(String tenant, String client, String owner, String canonicalAgent,
            long binding, String jobId, String runId, String appointmentId,
            long appointmentRevision, long managerAuthorizationRevision,
            String grantRef, String executionRef, long executionEpoch, String dispatchKey,
            String commandId, int activeAttempt, long expiresAt, String runtimeInstanceId,
            byte[] registrationHash, InstalledSkillResolver.Origin skillOrigin,
            InstalledSkillResolver.Proof skillProof, String contextRef) {
        public Expected {
            validate(tenant, client, owner, canonicalAgent, binding, jobId, runId,
                    appointmentId, appointmentRevision, managerAuthorizationRevision, grantRef,
                    executionRef, executionEpoch, dispatchKey, skillOrigin,
                    skillProof == null ? null : skillProof.key(),
                    skillProof == null ? null : skillProof.version(),
                    skillProof == null ? null : skillProof.packageDigest(), contextRef);
            id(commandId); id(runtimeInstanceId);
            if (activeAttempt < 1 || expiresAt < 1 || registrationHash == null
                    || registrationHash.length != 32 || skillProof == null) {
                throw new IllegalArgumentException("Invalid expected archive execution");
            }
            registrationHash = registrationHash.clone();
        }
        @Override public byte[] registrationHash() { return registrationHash.clone(); }
        public TargetRequest target() { return new TargetRequest(tenant, client, owner, canonicalAgent, binding); }
    }

    record Inspection(boolean authorized, String deliveryState, String activeMessageId) {
        public Inspection {
            if (!authorized || deliveryState == null || deliveryState.isBlank()
                    || (activeMessageId != null && !ID.matcher(activeMessageId).matches())) {
                throw new IllegalArgumentException("Invalid archive execution inspection");
            }
        }
        public Inspection(boolean authorized, String deliveryState) {
            this(authorized, deliveryState, null);
        }
    }

    /** Must be the first domain lock acquired in the caller's write transaction. */
    LockedIdentityRoot lockIdentityRoot(TargetRequest request);
    /** Positive admission only: requires current online/session/protocol state after root locking. */
    LockedTarget requireControlledTarget(TargetRequest request, LockedIdentityRoot root);
    Grant ensureExecution(Request request, LockedTarget lockedTarget);
    Inspection inspectExecution(Expected expected, LockedTarget lockedTarget);
    default Inspection inspectResult(Expected expected, LockedTarget lockedTarget) {
        return inspectExecution(expected, lockedTarget);
    }
    AgentRawCommandDispatchResult dispatch(Expected expected, byte[] exactWire);

    final class Denied extends IllegalStateException {
        private final String code;
        public Denied(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }

    private static void validate(String tenant, String client, String owner, String agent,
            long binding, String job, String run, String appointment, long appointmentRevision,
            long managerAuthorizationRevision, String grant, String execution, long epoch,
            String dispatchKey, InstalledSkillResolver.Origin origin, String skillKey,
            String skillVersion, String packageDigest, String contextRef) {
        validateTarget(tenant, client, owner, agent, binding);
        if (appointmentRevision < 1 || managerAuthorizationRevision < 1 || epoch < 1
                || origin == null || !exact(dispatchKey, 100) || !exact(skillKey, 64)
                || !exact(skillVersion, 64) || !SHA.matcher(String.valueOf(packageDigest)).matches()) {
            throw new IllegalArgumentException("Invalid archive execution request");
        }
        id(job); id(run); id(appointment); id(grant); id(execution);
        if (!Objects.equals(contextRef, "/internal/archive/v1/jobs/" + job + "/runs/" + run + "/context")) {
            throw new IllegalArgumentException("Invalid archive execution context reference");
        }
    }

    private static void validateTarget(String tenant, String client, String owner, String agent, long binding) {
        if (!"0".equals(tenant) || !exact(client, 50) || "0".equals(client)
                || !exact(owner, 50) || "0".equals(owner) || binding < 1) {
            throw new IllegalArgumentException("Invalid archive execution target");
        }
        id(agent);
    }

    Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}");
    Pattern SHA = Pattern.compile("[0-9a-f]{64}");
    private static void id(String value) {
        if (value == null || !ID.matcher(value).matches())
            throw new IllegalArgumentException("Invalid archive execution identifier");
    }
    private static boolean exact(String value, int max) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.length() <= max && value.chars().noneMatch(c -> Character.isISOControl(c)
                || Character.isSurrogate((char)c));
    }
}
