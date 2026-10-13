package cn.jia.agent.service;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Live runtime facts, NOT installation ownership, task authorization or a callable grant.
 * Agent owns this interface; chat adapts authenticated current sessions without a reverse
 * agent-to-chat dependency. Fees are deliberately absent from runtime readiness. */
public interface SkillCapabilityLookup {
    record Scope(String tenantId, String clientId, String ownerJiacn, String agentId) {
        public Scope {
            exact(tenantId, 50); exact(clientId, 50); exact(ownerJiacn, 50); exact(agentId, 100);
        }
    }
    enum Readiness { READY, UNAVAILABLE }
    enum State { AVAILABLE, DISCONNECTED, AMBIGUOUS, FENCED, INVALID_DECLARATION }

    record RuntimeSkill(String skillKey, String skillVersion, String packageSha256,
            String manifestSha256, String runner, Readiness readiness, String reasonCode,
            String toolchainDigest, String bindingRevision) {
        public RuntimeSkill {
            exact(skillKey, 64); exact(skillVersion, 64); hash(packageSha256); hash(manifestSha256);
            if (!List.of("provider-operation", "local-script", "native-model").contains(runner))
                throw new IllegalArgumentException("Unknown skill runner");
            Objects.requireNonNull(readiness);
            if (reasonCode != null && !reasonCode.matches("[A-Z][A-Z0-9_]{0,63}"))
                throw new IllegalArgumentException("Invalid skill reason code");
            if (toolchainDigest != null) hash(toolchainDigest);
            if (bindingRevision != null) exact(bindingRevision, 100);
            // READY is still only a report. Descriptor/dependency/binding entitlement checks
            // belong to the authoritative capability service, not this session index.
        }
    }

    record Catalog(String catalogRevision, List<RuntimeSkill> skills) {
        public Catalog {
            exact(catalogRevision, 100);
            skills = List.copyOf(Objects.requireNonNull(skills));
            var identities = new HashSet<List<String>>();
            for (var skill : skills) {
                if (!identities.add(List.of(skill.skillKey(), skill.skillVersion())))
                    throw new IllegalArgumentException("Ambiguous skill version in runtime catalog");
            }
        }
    }

    record View(State state, String sessionId, AgentManagedSessionLookup.SessionFence fence, Catalog catalog) {
        public View {
            Objects.requireNonNull(state);
            if (state == State.AVAILABLE) {
                exact(sessionId, 128); validateFence(fence); Objects.requireNonNull(catalog);
            } else if (sessionId != null || fence != null || catalog != null) {
                throw new IllegalArgumentException("Unavailable catalog must not expose stale data");
            }
        }
        public static View unavailable(State state) { return new View(state, null, null, null); }
    }

    /** Must select one live exact-scope session before matching the caller's trusted fence.
     * Two live subjects are unavailable even if just one matches the requested generation. */
    View lookup(Scope scope, AgentManagedSessionLookup.SessionFence expectedFence);

    static void validateFence(AgentManagedSessionLookup.SessionFence fence) {
        Objects.requireNonNull(fence);
        exact(fence.runtimeInstallationId(), 100); exact(fence.hostId(), 100); exact(fence.runtimeInstanceId(), 100);
        if (fence.sessionGeneration() <= 0) throw new IllegalArgumentException("Invalid runtime session generation");
    }
    private static void hash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid skill digest");
    }
    private static void exact(String value, int maximum) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.codePointCount(0, value.length()) > maximum
                || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid skill session identity");
    }
}
