package cn.jia.agent.security;

import cn.jia.agent.config.AgentTaskEventsGate;
import cn.jia.agent.dao.AgentIdentityRegistryDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.AgentRuntimeV1InstallationDao;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.entity.AgentRuntimeV1InstallationEntity;
import cn.jia.agent.entity.AgentRuntimeV1SessionRequest;
import cn.jia.agent.entity.AgentRuntimeV1SessionResponse;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentManagedSessionLookup;
import cn.jia.user.security.AccountSecurityService;
import cn.jia.user.security.AccountSecuritySnapshot;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Installation-derived credentials. Persistent subject/host/generation/digest are the fence.
 * Local bindings prove channel liveness only, never ownership. No API-key execution fallback.
 */
@Service
public class AgentRuntimeAuthenticationService {
    private static final Pattern AGENT_REFERENCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private final AgentRuntimeDao runtimes;
    private final AgentRuntimeV1InstallationDao installations;
    private final AgentIdentityRegistryDao registry;
    private final AgentIdentityService identities;
    private final AccountSecurityService accounts;
    private final AgentTaskEventsGate gate;
    private final Map<AgentRuntimeAuthentication.Scope, Binding> bindings = new ConcurrentHashMap<>();
    private final Map<AgentRuntimeAuthentication.Scope, CapabilityBinding> readyCommandTypes = new ConcurrentHashMap<>();

    public AgentRuntimeAuthenticationService(AgentRuntimeDao runtimes, AgentRuntimeV1InstallationDao installations,
            AgentIdentityRegistryDao registry, AgentIdentityService identities,
            AccountSecurityService accounts, AgentTaskEventsGate gate) {
        this.runtimes = runtimes; this.installations = installations; this.registry = registry;
        this.identities = identities; this.accounts = accounts; this.gate = gate;
    }

    /** Caller holds the ACTIVE installation lock after verifying its Bearer. */
    public AgentRuntimeV1SessionResponse issue(AgentRuntimeV1InstallationEntity installation,
            AgentRuntimeV1SessionRequest request, long now) {
        if (installation == null || request == null || !"ACTIVE".equals(installation.getStatus())
                || !"0".equals(installation.getTenantId())
                || !exact(request.hostId(), 100) || !exact(request.runtimeInstanceId(), 100)
                || request.runtimeInstanceId().equals(installation.getCanonicalAgentId())) throw denied();
        // The installation binds the full subject, not an owner supplied by the caller.
        var candidates = registry.selectByMap(Map.of("tenant_id", installation.getTenantId(),
                "client_id", installation.getClientId(), "canonical_agent_id", installation.getCanonicalAgentId()));
        if (candidates == null || candidates.size() != 1) throw denied();
        var candidate = candidates.getFirst();
        if (!Objects.equals(installation.getTenantId(), candidate.getTenantId())
                || !Objects.equals(installation.getClientId(), candidate.getClientId())
                || !Objects.equals(installation.getCanonicalAgentId(), candidate.getCanonicalAgentId())) throw denied();
        var identity = identities.requireRegistrationIdentityInScope(installation.getTenantId(),
                installation.getClientId(), candidate.getOwnerJiacn(), installation.getCanonicalAgentId());
        var binding = identities.requireActiveBinding(identity, null);
        if (identity == null || binding == null || binding.getId() == null
                || !installation.getCanonicalAgentId().equals(identity.getCanonicalAgentId())) throw denied();
        var account = account(candidate.getOwnerJiacn());
        // Preserve binding/identity-before-runtime locking used by first registration.
        var row = runtimes.lockInScope(installation.getTenantId(), installation.getClientId(), installation.getCanonicalAgentId());
        if (row == null) {
            // The global legacy unique-agent key must not be used to steal another scope's row.
            if (runtimes.findByAgentId(installation.getCanonicalAgentId()) != null) throw denied();
            row = new AgentRuntimeEntity().setAgentId(installation.getCanonicalAgentId())
                    .setName(installation.getCanonicalAgentId()).setOwnerJiacn(candidate.getOwnerJiacn())
                    .setBindingId(binding.getId()).setStatus("offline");
            row.setTenantId(installation.getTenantId()); row.setClientId(installation.getClientId());
        } else if (!candidate.getOwnerJiacn().equals(row.getOwnerJiacn())
                || !Objects.equals(binding.getId(), row.getBindingId())) throw denied();
        if (row.getRuntimeInstallationId() != null || row.getRuntimeHostId() != null
                || row.getRuntimeInstanceId() != null || row.getRuntimeSessionGeneration() != null) {
            if (!installation.getInstallationId().equals(row.getRuntimeInstallationId())
                    || !request.hostId().equals(row.getRuntimeHostId()) || row.getRuntimeSessionGeneration() == null
                    || row.getRuntimeSessionGeneration() <= 0 || row.getRuntimeInstanceId() == null) throw denied();
        }
        long generation;
        try { generation = Math.addExact(row.getRuntimeSessionGeneration() == null ? 0 : row.getRuntimeSessionGeneration(), 1); }
        catch (ArithmeticException exhausted) { throw denied(); }
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        String token = "rts1_" + HexFormat.of().formatHex(bytes);
        // Fits existing token_hash VARCHAR(200); epoch survives API restart without extra schema.
        String verifier = "urs1:" + digestHex(token) + ":" + account.userId() + ":" + account.authEpoch();
        row.setRuntimeInstallationId(installation.getInstallationId()).setRuntimeHostId(request.hostId())
                .setRuntimeInstanceId(request.runtimeInstanceId()).setRuntimeSessionGeneration(generation)
                .setTokenHash(verifier).setStatus("offline").setLastSeenAt(now);
        int changed = row.getId() == null ? runtimes.insert(row) : runtimes.updateById(row);
        if (changed != 1) throw denied();
        return new AgentRuntimeV1SessionResponse(installation.getInstallationId(), installation.getTenantId(),
                installation.getClientId(), installation.getCanonicalAgentId(), request.hostId(),
                request.runtimeInstanceId(), generation, "AgentRuntime", token, "/ws/agent/channel", "CHANNEL_PENDING");
    }

    /** Native proof validated without depending on a live channel: WS handshake and terminal ACK recovery. */
    @Transactional(rollbackFor = Exception.class)
    public Proof verify(AgentRuntimeAuthenticationFilter.SessionHeaders headers) {
        if (headers == null || !validToken(headers.token()) || !validAgentReference(headers.agentId())
                || headers.installationId() == null || !headers.installationId().matches("rti_[0-9a-f]{32}")
                || !exact(headers.hostId(), 100) || !exact(headers.runtimeInstanceId(), 100)
                || headers.runtimeInstanceId().equals(headers.agentId()) || headers.sessionGeneration() <= 0) throw denied();
        var installation = installations.lock(headers.installationId());
        return verifyInstallation(headers, installation);
    }

    private Proof verifyInstallation(AgentRuntimeAuthenticationFilter.SessionHeaders headers,
            AgentRuntimeV1InstallationEntity installation) {
        if (installation == null || !"ACTIVE".equals(installation.getStatus())
                || !headers.installationId().equals(installation.getInstallationId())
                || !headers.agentId().equals(installation.getCanonicalAgentId())) throw denied();
        var row = runtimes.findInScope(installation.getTenantId(), installation.getClientId(), headers.agentId());
        if (row == null || row.getTokenHash() == null) throw denied();
        String[] verifier = row.getTokenHash().split(":", -1);
        if (verifier.length != 4 || !"urs1".equals(verifier[0])
                || !MessageDigest.isEqual(verifier[1].getBytes(StandardCharsets.US_ASCII),
                        digestHex(headers.token()).getBytes(StandardCharsets.US_ASCII))) throw denied();
        long userId, epoch;
        try { userId = Long.parseLong(verifier[2]); epoch = Long.parseLong(verifier[3]); }
        catch (NumberFormatException invalid) { throw denied(); }
        var proof = new Proof(new AgentRuntimeAuthentication.Scope(installation.getTenantId(), installation.getClientId(),
                row.getOwnerJiacn(), headers.agentId(), headers.runtimeInstanceId()), installation.getInstallationId(),
                headers.hostId(), headers.sessionGeneration(), verifier[1], userId, epoch);
        validateRow(proof, installation, row, false);
        return proof;
    }

    // Filter entry point must own the transaction: its call to verify() is a self-invocation.
    @Transactional(rollbackFor = Exception.class)
    public AgentRuntimeAuthentication authenticate(AgentRuntimeAuthenticationFilter.SessionHeaders headers, boolean requireChannel) {
        Proof proof = verify(headers);
        if (requireChannel && !hasChannel(proof)) throw denied();
        var authentication = new AgentRuntimeAuthentication(proof.scope());
        authentication.setDetails(proof);
        return authentication;
    }

    /** Authenticated HTTP proof must be the filter's own full-scope details, not body fields. */
    public static Proof requireProof(org.springframework.security.core.Authentication authentication) {
        if (!(authentication instanceof AgentRuntimeAuthentication runtime) || !runtime.isAuthenticated()
                || !(runtime.getDetails() instanceof Proof proof)
                || !runtime.getPrincipal().equals(proof.scope())) throw denied();
        return proof;
    }

    /** Native business commit boundary. This entry point owns a proxy transaction;
     * the internal call to withFence() must never rely on self-invocation advice. */
    @Transactional(rollbackFor = Exception.class)
    public <T> T withNativeFence(org.springframework.security.core.Authentication authentication, Supplier<T> operation) {
        return withFence(requireProof(authentication), false, operation);
    }

    /** Money admission may consult live evidence; it grants no authority to an arbitrary caller. */
    public Proof currentRegisteredProof(String tenantId, String clientId, String agentId) {
        Proof result = null;
        for (var binding : bindings.values()) {
            var candidate = binding.proof();
            var scope = candidate.scope();
            if (!tenantId.equals(scope.tenantId()) || !clientId.equals(scope.clientId())
                    || !agentId.equals(scope.agentId())) continue;
            try {
                if (!hasChannel(candidate)) continue;
                validateCurrent(candidate, true);
            } catch (IllegalArgumentException stale) {
                // Older boot entries are liveness hints, not authority. A valid current boot
                // must not be starved by the previous one still awaiting socket close.
                continue;
            }
            if (result != null) throw denied();
            result = candidate;
        }
        if (result == null) throw denied();
        return result;
    }

    /** No secret is retained in WS attributes or the registry. */
    public Receipt bind(String sessionId, Proof proof, BooleanSupplier connected) {
        if (!exact(sessionId, 128) || proof == null || connected == null || !connected.getAsBoolean()) throw denied();
        validateCurrent(proof, true);
        bindings.put(proof.scope(), new Binding(sessionId, proof, connected));
        readyCommandTypes.remove(proof.scope());
        var scope = proof.scope();
        return new Receipt("native-runtime-v1", scope.tenantId(), scope.clientId(), scope.ownerJiacn(), scope.agentId(),
                scope.runtimeInstanceId(), proof.installationId(), proof.hostId(), proof.sessionGeneration(),
                gate.allows(scope.tenantId(), scope.clientId()));
    }

    public void disconnect(String sessionId) {
        readyCommandTypes.entrySet().removeIf(entry -> entry.getValue().binding().sessionId().equals(sessionId));
        bindings.entrySet().removeIf(entry -> entry.getValue().sessionId().equals(sessionId));
    }

    /** Registration readiness is server-parsed from readyCommandTypes, never a legacy API-key protocol claim. */
    public void registerReadyCommandTypes(String sessionId, Proof proof, Set<String> advertised) {
        if (proof == null || advertised == null || advertised.size() > 16) throw denied();
        Binding binding = bindings.get(proof.scope());
        if (binding == null || !binding.sessionId().equals(sessionId) || !binding.proof().equals(proof)
                || !binding.connected().getAsBoolean()) throw denied();
        var types = new java.util.HashSet<String>();
        for (String type : advertised) {
            if (type == null || type.length() > 64 || !type.matches("[A-Z][A-Z0-9_]*") || !types.add(type)) throw denied();
        }
        validateCurrent(proof, true);
        if (bindings.get(proof.scope()) != binding || !binding.connected().getAsBoolean()) throw denied();
        readyCommandTypes.put(proof.scope(), new CapabilityBinding(binding, Set.copyOf(types)));
    }

    /** Read-only current controlled-session projection over the installation-derived session fence. */
    public ControlledReadiness inspectControlledTarget(String tenant, String client, String owner,
            String agentId, long bindingId, String requiredProtocol) {
        if (!"0".equals(tenant) || !exact(client, 50) || "0".equals(client)
                || !exact(owner, 50) || "0".equals(owner) || !validAgentReference(agentId)
                || bindingId < 1 || !exact(requiredProtocol, 100)
                || !requiredProtocol.matches("[A-Z][A-Z0-9_]*/v[1-9][0-9]*")) throw denied();
        var row = runtimes.findInScope(tenant, client, agentId);
        if (row == null || !agentId.equals(row.getAgentId()) || !owner.equals(row.getOwnerJiacn())
                || !Long.valueOf(bindingId).equals(row.getBindingId())) return ControlledReadiness.bindingChanged();
        if (!Set.of("online", "busy").contains(row.getStatus())) return ControlledReadiness.offline();
        if (!exact(row.getRuntimeInstallationId(), 100) || !exact(row.getRuntimeHostId(), 100)
                || !exact(row.getRuntimeInstanceId(), 100) || row.getRuntimeSessionGeneration() == null
                || row.getRuntimeSessionGeneration() <= 0) return ControlledReadiness.authenticationChanged();
        var scope = new AgentRuntimeAuthentication.Scope(tenant, client, owner, agentId, row.getRuntimeInstanceId());
        Binding binding = bindings.get(scope);
        if (binding == null || !binding.connected().getAsBoolean()) return ControlledReadiness.offline();
        Proof proof = binding.proof();
        try {
            var identity = identities.requireActiveIdentityForBinding(tenant, client, owner, bindingId, agentId);
            if (identity == null || !agentId.equals(identity.getCanonicalAgentId())
                    || identities.requireActiveBinding(identity, null) == null) return ControlledReadiness.bindingChanged();
        } catch (DataAccessException unavailable) { throw unavailable; }
        catch (RuntimeException changed) { return ControlledReadiness.bindingChanged(); }
        try { validateCurrent(proof, true); }
        catch (DataAccessException unavailable) { throw unavailable; }
        catch (IllegalArgumentException changed) { return ControlledReadiness.authenticationChanged(); }
        String commandType = requiredProtocol.substring(0, requiredProtocol.indexOf('/'));
        CapabilityBinding capabilities = readyCommandTypes.get(scope);
        if (capabilities == null || capabilities.binding() != binding || !capabilities.types().contains(commandType)) {
            return ControlledReadiness.clientUpdateRequired();
        }
        if (bindings.get(scope) != binding || readyCommandTypes.get(scope) != capabilities
                || !binding.connected().getAsBoolean()) return ControlledReadiness.offline();
        return ControlledReadiness.ready(new ControlledTarget(scope.runtimeInstanceId(), sessionFence(proof),
                registrationHash(proof)));
    }

    public ControlledTarget requireControlledTarget(String tenant, String client, String owner,
            String agentId, long bindingId, String requiredProtocol) {
        ControlledReadiness readiness = inspectControlledTarget(tenant, client, owner, agentId, bindingId, requiredProtocol);
        if (readiness.state() != ControlledReadinessState.READY) throw denied();
        return readiness.target();
    }

    public ControlledTarget requireControlledSession(String sessionId, String tenant, String client,
            String owner, String agentId, long bindingId, String requiredProtocol) {
        ControlledTarget target = requireControlledTarget(tenant, client, owner, agentId, bindingId, requiredProtocol);
        Binding binding = bindings.get(new AgentRuntimeAuthentication.Scope(
                tenant, client, owner, agentId, target.runtimeInstanceId()));
        if (binding == null || !binding.sessionId().equals(sessionId)
                || !target.sessionFence().equals(sessionFence(binding.proof()))) throw denied();
        return target;
    }

    public enum ControlledReadinessState {
        READY, AGENT_OFFLINE, BINDING_CHANGED, CLIENT_UPDATE_REQUIRED, AUTHENTICATION_CHANGED
    }

    public record ControlledReadiness(ControlledReadinessState state, ControlledTarget target) {
        public ControlledReadiness {
            if (state == null || (state == ControlledReadinessState.READY) != (target != null)) {
                throw new IllegalArgumentException("Invalid controlled readiness");
            }
        }
        public static ControlledReadiness ready(ControlledTarget target) { return new ControlledReadiness(ControlledReadinessState.READY, target); }
        public static ControlledReadiness offline() { return new ControlledReadiness(ControlledReadinessState.AGENT_OFFLINE, null); }
        public static ControlledReadiness bindingChanged() { return new ControlledReadiness(ControlledReadinessState.BINDING_CHANGED, null); }
        public static ControlledReadiness clientUpdateRequired() { return new ControlledReadiness(ControlledReadinessState.CLIENT_UPDATE_REQUIRED, null); }
        public static ControlledReadiness authenticationChanged() { return new ControlledReadiness(ControlledReadinessState.AUTHENTICATION_CHANGED, null); }
    }

    public record ControlledTarget(String runtimeInstanceId, AgentManagedSessionLookup.SessionFence sessionFence,
            byte[] registrationHash) {
        public ControlledTarget {
            Objects.requireNonNull(runtimeInstanceId); Objects.requireNonNull(sessionFence);
            registrationHash = registrationHash.clone();
        }
        @Override public byte[] registrationHash() { return registrationHash.clone(); }
    }

    private static AgentManagedSessionLookup.SessionFence sessionFence(Proof proof) {
        return new AgentManagedSessionLookup.SessionFence(proof.installationId(), proof.hostId(),
                proof.scope().runtimeInstanceId(), proof.sessionGeneration());
    }

    private static byte[] registrationHash(Proof proof) {
        String value = proof.installationId() + "\0" + proof.hostId() + "\0"
                + proof.scope().runtimeInstanceId() + "\0" + proof.sessionGeneration() + "\0" + proof.tokenDigest();
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    public boolean isCurrentBinding(String sessionId, String tenantId, String clientId,
            String ownerJiacn, String agentId, String runtimeInstanceId) {
        var scope = new AgentRuntimeAuthentication.Scope(tenantId, clientId, ownerJiacn, agentId, runtimeInstanceId);
        Binding binding = bindings.get(scope);
        if (binding == null || !binding.sessionId().equals(sessionId) || !binding.connected().getAsBoolean()) return false;
        validateCurrent(binding.proof(), true);
        return bindings.get(scope) == binding && binding.connected().getAsBoolean();
    }

    private boolean hasChannel(Proof proof) {
        Binding binding = bindings.get(proof.scope());
        return binding != null && binding.proof().equals(proof) && binding.connected().getAsBoolean()
                && isCurrentBinding(binding.sessionId(), proof.scope().tenantId(), proof.scope().clientId(),
                        proof.scope().ownerJiacn(), proof.scope().agentId(), proof.scope().runtimeInstanceId());
    }

    public void validateCurrent(Proof proof, boolean registered) {
        var installation = installations.findInScope(proof.scope().tenantId(), proof.scope().clientId(), proof.installationId());
        var row = runtimes.findInScope(proof.scope().tenantId(), proof.scope().clientId(), proof.scope().agentId());
        validateRow(proof, installation, row, registered);
    }

    /** Full-scope fences remain held through the callback's joined business/D06 transaction. */
    @Transactional(rollbackFor = Exception.class)
    public <T> T withFence(Proof proof, boolean registration, Supplier<T> operation) {
        if (proof == null) throw denied();
        var scope = proof.scope();
        var installation = installations.lock(proof.installationId());
        if (registration) {
            var identity = identities.requireRegistrationIdentityInScope(scope.tenantId(), scope.clientId(), scope.ownerJiacn(), scope.agentId());
            identities.activateForFirstRegistration(identity);
        }
        var row = runtimes.lockInScope(scope.tenantId(), scope.clientId(), scope.agentId());
        validateRow(proof, installation, row, false);
        return operation.get();
    }

    private void validateRow(Proof proof, AgentRuntimeV1InstallationEntity installation, AgentRuntimeEntity row, boolean registered) {
        var scope = proof.scope();
        String verifier = "urs1:" + proof.tokenDigest() + ":" + proof.userId() + ":" + proof.authEpoch();
        if (installation == null || row == null || !"ACTIVE".equals(installation.getStatus())
                || !"0".equals(scope.tenantId()) || !scope.tenantId().equals(installation.getTenantId())
                || !scope.clientId().equals(installation.getClientId()) || !scope.agentId().equals(installation.getCanonicalAgentId())
                || !proof.installationId().equals(installation.getInstallationId())
                || !scope.agentId().equals(row.getAgentId()) || !scope.tenantId().equals(row.getTenantId())
                || !scope.clientId().equals(row.getClientId()) || !Objects.equals(scope.ownerJiacn(), row.getOwnerJiacn())
                || !proof.installationId().equals(row.getRuntimeInstallationId()) || !proof.hostId().equals(row.getRuntimeHostId())
                || !scope.runtimeInstanceId().equals(row.getRuntimeInstanceId())
                || !Objects.equals(proof.sessionGeneration(), row.getRuntimeSessionGeneration())
                || row.getBindingId() == null || !verifier.equals(row.getTokenHash())
                || (registered && !Set.of("online", "busy").contains(row.getStatus()))
                || !account(scope.ownerJiacn()).matches(proof.userId(), scope.ownerJiacn(), proof.authEpoch())) throw denied();
        try {
            var identity = registered ? identities.requireActiveIdentityForBinding(scope.tenantId(), scope.clientId(),
                    scope.ownerJiacn(), row.getBindingId(), scope.agentId())
                    : identities.requireRegistrationIdentityInScope(scope.tenantId(), scope.clientId(), scope.ownerJiacn(), scope.agentId());
            if (identity == null || !scope.agentId().equals(identity.getCanonicalAgentId())
                    || !Objects.equals(identity.getBindingId(), row.getBindingId())
                    || identities.requireActiveBinding(identity, null) == null) throw denied();
        } catch (DataAccessException unavailable) { throw unavailable; }
        catch (RuntimeException invalidIdentity) { throw denied(); }
    }

    private AccountSecuritySnapshot account(String ownerJiacn) {
        var account = accounts.findUniqueByExactJiacn(ownerJiacn).orElseThrow(AgentRuntimeAuthenticationService::denied);
        if (!Objects.equals(ownerJiacn, account.jiacn()) || !account.isAuthenticatable()) throw denied();
        return account;
    }
    static String digestHex(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    static boolean exact(String value, int max) {
        return value != null && !value.isBlank() && value.equals(value.strip()) && value.length() <= max
                && value.chars().noneMatch(c -> Character.isISOControl(c) || Character.isSurrogate((char) c));
    }
    static boolean validAgentReference(String agent) { return agent != null && AGENT_REFERENCE.matcher(agent).matches(); }
    static boolean validToken(String token) { return token != null && token.matches("rts1_[0-9a-f]{64}"); }
    static IllegalArgumentException denied() { return new IllegalArgumentException("AGENT_RUNTIME_UNAUTHENTICATED"); }
    private record Binding(String sessionId, Proof proof, BooleanSupplier connected) { }
    private record CapabilityBinding(Binding binding, Set<String> types) { }
    public record Proof(AgentRuntimeAuthentication.Scope scope, String installationId, String hostId,
                        long sessionGeneration, String tokenDigest, long userId, long authEpoch) {
        @Override public String toString() { return "AgentRuntimeProof[sessionGeneration=" + sessionGeneration + ", verifier=REDACTED]"; }
    }
    public record Receipt(String scheme, String tenantId, String clientId, String ownerJiacn, String agentId,
                          String runtimeInstanceId, String installationId, String hostId, long sessionGeneration,
                          boolean contextPackEnabled) { }
}
