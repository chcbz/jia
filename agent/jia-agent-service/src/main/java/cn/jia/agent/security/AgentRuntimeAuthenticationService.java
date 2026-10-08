package cn.jia.agent.security;

import cn.jia.agent.common.AgentErrorConstants;
import cn.jia.agent.config.AgentTaskEventsGate;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.impl.AgentServiceImpl;
import cn.jia.oauth.service.ApiKeyService;
import cn.jia.user.security.AccountSecurityService;
import cn.jia.user.security.AccountSecuritySnapshot;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.function.BooleanSupplier;

/**
 * Reuses the existing registration token, NEVER issues JWTs. A process-local live WebSocket
 * binding is also required: restart/another API replica fails closed (HTTP must reach the
 * WebSocket-owning replica). Every request rechecks persistent token, identity, key and account.
 * No secret is retained in the registry or principal. No client-selected scope is accepted.
 */
@Service
public final class AgentRuntimeAuthenticationService {
    private static final String SINGLE_TENANT_ID = "0";
    // Wire syntax is bounded only; persisted direct-canonical authority is mandatory below.
    private static final Pattern AGENT_REFERENCE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}");
    private final AgentRuntimeDao runtimes;
    private final AgentIdentityService identities;
    private final ApiKeyService keys;
    private final AccountSecurityService accounts;
    private final AgentTaskEventsGate gate;
    private final Map<String, Binding> bindings = new ConcurrentHashMap<>();
    private final Map<String, ProtocolBinding> commandProtocols = new ConcurrentHashMap<>();

    public AgentRuntimeAuthenticationService(AgentRuntimeDao runtimes, AgentIdentityService identities,
            ApiKeyService keys, AccountSecurityService accounts, AgentTaskEventsGate gate) {
        this.runtimes = runtimes; this.identities = identities; this.keys = keys;
        this.accounts = accounts; this.gate = gate;
    }

    /** Called ONLY after authenticated WebSocket registration with server session attributes. */
    public Receipt bind(String sessionId, String client, String ownerJiacn, String agent, String runtime,
            String apiKeyId, String token, BooleanSupplier connected) {
        if (!exact(sessionId, 128) || !exact(client, 50) || "0".equals(client)
                || !exact(ownerJiacn, 50) || "0".equals(ownerJiacn)
                || !validAgentReference(agent)
                || !exact(runtime, 100) || runtime.equals(agent) || !exact(apiKeyId, 128)
                || connected == null || !validToken(token)) throw denied();
        var scope = new AgentRuntimeAuthentication.Scope(
                SINGLE_TENANT_ID, client, ownerJiacn, agent, runtime);
        var account = account(ownerJiacn);
        var key = keys.get(apiKeyId);
        if (key == null || key.getApiKey() == null || key.getApiKey().isBlank()) throw denied();
        Binding binding = new Binding(sessionId, scope, apiKeyId, digest(key.getApiKey()),
                digest(token), account.userId(), account.authEpoch(), connected);
        validate(binding, token);
        commandProtocols.remove(agent);
        bindings.put(agent, binding); // exact canonical identity: newer registration supersedes older
        return new Receipt("native-runtime-v1", SINGLE_TENANT_ID, client, ownerJiacn, agent, runtime,
                gate.allows(SINGLE_TENANT_ID, client));
    }

    public void disconnect(String sessionId) {
        commandProtocols.entrySet().removeIf(entry -> entry.getValue().binding.sessionId.equals(sessionId));
        bindings.entrySet().removeIf(entry -> entry.getValue().sessionId.equals(sessionId));
    }

    /** Called only by the authenticated registration handler after bind, never by an HTTP body. */
    public void registerCommandProtocols(String sessionId, String agentId, Object advertised) {
        Binding binding = bindings.get(agentId);
        if (binding == null || !binding.sessionId.equals(sessionId)) throw denied();
        if (advertised == null) return; // legacy client has no new protocol capability
        if (!(advertised instanceof java.util.List<?> list) || list.size()>16) throw denied();
        var protocols = new java.util.HashSet<String>();
        for (Object value : list) {
            if (!(value instanceof String text) || text.length()>100 || !text.matches("[A-Z_]+/v[1-9][0-9]*")
                    || !protocols.add(text)) throw denied();
        }
        if (bindings.get(agentId) != binding || !binding.connected.getAsBoolean()) throw denied();
        commandProtocols.put(agentId, new ProtocolBinding(binding, Set.copyOf(protocols)));
    }

    /**
     * Read-only current controlled-session projection. Expected lifecycle/session/protocol changes
     * are returned as facts; database and other infrastructure failures still propagate.
     */
    public ControlledReadiness inspectControlledTarget(String tenant, String client, String owner,
            String agentId, long bindingId, String requiredProtocol) {
        if (!SINGLE_TENANT_ID.equals(tenant) || !exact(client, 50) || "0".equals(client)
                || !exact(owner, 50) || "0".equals(owner) || !validAgentReference(agentId)
                || bindingId < 1 || !exact(requiredProtocol, 100)) throw denied();
        Binding binding = bindings.get(agentId);
        if (binding == null || !binding.connected.getAsBoolean()) return ControlledReadiness.offline();
        if (!tenant.equals(binding.scope.tenantId()) || !client.equals(binding.scope.clientId())
                || !owner.equals(binding.scope.ownerJiacn())) return ControlledReadiness.bindingChanged();
        var row = runtimes.findByAgentId(agentId);
        if (row == null || !agentId.equals(row.getAgentId())
                || !tenant.equals(row.getTenantId()) || !client.equals(row.getClientId())
                || !owner.equals(row.getOwnerJiacn())
                || !Long.valueOf(bindingId).equals(row.getBindingId())) {
            return ControlledReadiness.bindingChanged();
        }
        if (!Set.of("online", "busy").contains(row.getStatus())) return ControlledReadiness.offline();
        if (!validToken(row.getTokenHash())) return ControlledReadiness.authenticationChanged();
        try {
            validate(binding, row.getTokenHash(), true);
        } catch (AgentServiceImpl.AgentBizException changed) {
            if (!AgentErrorConstants.AGENT_FORBIDDEN.equals(changed.getCode())) throw changed;
            return ControlledReadiness.bindingChanged();
        } catch (IllegalArgumentException changed) {
            return ControlledReadiness.authenticationChanged();
        }
        ProtocolBinding capabilities = commandProtocols.get(agentId);
        if (capabilities == null || capabilities.binding != binding
                || !capabilities.protocols.contains(requiredProtocol)) {
            return ControlledReadiness.clientUpdateRequired();
        }
        if (bindings.get(agentId) != binding || commandProtocols.get(agentId) != capabilities
                || !binding.connected.getAsBoolean()) return ControlledReadiness.offline();
        return ControlledReadiness.ready(new ControlledTarget(binding.scope.runtimeInstanceId(), binding.apiKeyId,
                cn.jia.agent.skill.SkillMarketplaceService.sessionRegistrationHash(agentId, row.getTokenHash())));
    }

    /** Revalidates current key/account/registration, then returns server-only exact-session evidence. */
    public ControlledTarget requireControlledTarget(String tenant, String client, String owner,
            String agentId, long bindingId, String requiredProtocol) {
        ControlledReadiness readiness = inspectControlledTarget(
                tenant, client, owner, agentId, bindingId, requiredProtocol);
        if (readiness.state() != ControlledReadinessState.READY) throw denied();
        return readiness.target();
    }
    /**
     * Revalidates the controlled target and proves that the candidate socket is the exact current
     * authenticated binding. A superseded socket with the same Agent, key and registration token
     * must not receive controlled work.
     */
    public ControlledTarget requireControlledSession(String sessionId, String tenant, String client,
            String owner, String agentId, long bindingId, String requiredProtocol) {
        Binding before = bindings.get(agentId);
        if (before == null || !before.sessionId.equals(sessionId)) throw denied();
        ControlledTarget target = requireControlledTarget(
                tenant, client, owner, agentId, bindingId, requiredProtocol);
        Binding after = bindings.get(agentId);
        if (after != before || !after.sessionId.equals(sessionId)) throw denied();
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
        public static ControlledReadiness ready(ControlledTarget target) {
            return new ControlledReadiness(ControlledReadinessState.READY, target);
        }
        public static ControlledReadiness offline() {
            return new ControlledReadiness(ControlledReadinessState.AGENT_OFFLINE, null);
        }
        public static ControlledReadiness bindingChanged() {
            return new ControlledReadiness(ControlledReadinessState.BINDING_CHANGED, null);
        }
        public static ControlledReadiness clientUpdateRequired() {
            return new ControlledReadiness(ControlledReadinessState.CLIENT_UPDATE_REQUIRED, null);
        }
        public static ControlledReadiness authenticationChanged() {
            return new ControlledReadiness(ControlledReadinessState.AUTHENTICATION_CHANGED, null);
        }
    }

    public record ControlledTarget(String runtimeInstanceId, String apiKeyId, byte[] registrationHash) {
        public ControlledTarget { registrationHash=registrationHash.clone(); }
        @Override public byte[] registrationHash() { return registrationHash.clone(); }
    }
    private record ProtocolBinding(Binding binding, Set<String> protocols) { }

    public AgentRuntimeAuthentication authenticate(String agent, String runtime, String token) {
        if (agent == null || runtime == null || !validToken(token)) throw denied();
        Binding binding = bindings.get(agent);
        if (binding == null || !binding.scope.runtimeInstanceId().equals(runtime)
                || !MessageDigest.isEqual(binding.tokenDigest, digest(token))) throw denied();
        validateCurrent(binding);
        if (bindings.get(agent) != binding || !binding.connected.getAsBoolean()) throw denied();
        return new AgentRuntimeAuthentication(binding.scope);
    }

    /**
     * Non-secret current-session check for server-side capability projection. The originally
     * bound registration-token digest is rechecked against the current persisted token; API key,
     * account epoch, identity and runtime state are revalidated exactly as for native HTTP auth.
     */
    public boolean isCurrentBinding(String sessionId, String tenantId, String clientId,
            String ownerJiacn, String agentId, String runtimeInstanceId) {
        Binding binding = bindings.get(agentId);
        if (binding == null || !binding.sessionId.equals(sessionId)
                || !binding.scope.equals(new AgentRuntimeAuthentication.Scope(
                        tenantId, clientId, ownerJiacn, agentId, runtimeInstanceId))) return false;
        validateCurrent(binding);
        return bindings.get(agentId) == binding && binding.connected.getAsBoolean();
    }

    private void validate(Binding binding, String token) {
        validate(binding, token, false);
    }

    private void validate(Binding binding, String token, boolean projectIdentityFailure) {
        if (!validToken(token) || !MessageDigest.isEqual(binding.tokenDigest, digest(token))) throw denied();
        validateCurrent(binding, projectIdentityFailure);
    }

    private void validateCurrent(Binding binding) {
        validateCurrent(binding, false);
    }

    private void validateCurrent(Binding binding, boolean projectIdentityFailure) {
        var scope = binding.scope;
        if (!binding.connected.getAsBoolean()
                || !SINGLE_TENANT_ID.equals(scope.tenantId())
                || !account(scope.ownerJiacn()).matches(
                        binding.userId, scope.ownerJiacn(), binding.authEpoch)) throw denied();
        var key = keys.get(binding.apiKeyId);
        if (key == null || !binding.apiKeyId.equals(key.getId()) || !Integer.valueOf(1).equals(key.getStatus())
                || !scope.tenantId().equals(key.getTenantId())
                || !scope.ownerJiacn().equals(key.getJiacn())
                || !scope.clientId().equals(key.getClientId()) || key.getApiKey() == null
                || !MessageDigest.isEqual(binding.keyDigest, digest(key.getApiKey()))
                || key.getExpireTime() != null && key.getExpireTime() <= System.currentTimeMillis()) throw denied();
        var row = runtimes.findByAgentId(scope.agentId());
        if (row == null || !scope.agentId().equals(row.getAgentId())
                || !scope.tenantId().equals(row.getTenantId())
                || !scope.ownerJiacn().equals(row.getOwnerJiacn())
                || !scope.clientId().equals(row.getClientId()) || row.getBindingId() == null
                || !Set.of("online", "busy").contains(row.getStatus()) || !validToken(row.getTokenHash())
                || !MessageDigest.isEqual(binding.tokenDigest, digest(row.getTokenHash()))) throw denied();
        try {
            var identity = identities.requireActiveIdentityForBinding(scope.tenantId(), scope.clientId(),
                    scope.ownerJiacn(), row.getBindingId(), scope.agentId());
            if (identity == null || !scope.agentId().equals(identity.getCanonicalAgentId())
                    || identities.requireActiveBinding(identity, null) == null) {
                throw new AgentServiceImpl.AgentBizException(AgentErrorConstants.AGENT_FORBIDDEN,
                        "Agent identity binding is unavailable");
            }
        } catch (DataAccessException unavailable) {
            throw unavailable;
        } catch (RuntimeException invalidIdentity) {
            // Only the server-side readiness projection retains lifecycle/infrastructure facts:
            // AGENT_FORBIDDEN means binding changed, unexpected failures must not look like
            // an ordinary credential change. Native authentication still denies generically.
            if (projectIdentityFailure) throw invalidIdentity;
            throw denied();
        }
    }

    private AccountSecuritySnapshot account(String ownerJiacn) {
        var account = accounts.findUniqueByExactJiacn(ownerJiacn)
                .orElseThrow(AgentRuntimeAuthenticationService::denied);
        if (!ownerJiacn.equals(account.jiacn()) || !account.isAuthenticatable()) throw denied();
        return account;
    }
    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    static boolean exact(String value, int max) {
        return value != null && !value.isBlank() && value.equals(value.strip()) && value.length() <= max
                && value.chars().noneMatch(c -> Character.isISOControl(c) || Character.isSurrogate((char) c));
    }
    static boolean validAgentReference(String agent) {
        return agent != null && AGENT_REFERENCE.matcher(agent).matches();
    }
    static boolean validToken(String token) { return token != null && token.matches("[0-9a-f]{32}"); }
    static IllegalArgumentException denied() { return new IllegalArgumentException("AGENT_RUNTIME_UNAUTHENTICATED"); }
    private record Binding(String sessionId, AgentRuntimeAuthentication.Scope scope, String apiKeyId,
                           byte[] keyDigest, byte[] tokenDigest, long userId, long authEpoch, BooleanSupplier connected) { }
    public record Receipt(String scheme, String tenantId, String clientId, String ownerJiacn, String agentId,
                          String runtimeInstanceId, boolean contextPackEnabled) { }
}
