package cn.jia.agent.security;

import cn.jia.agent.config.AgentTaskEventsGate;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.service.AgentIdentityService;
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
        bindings.put(agent, binding); // exact canonical identity: newer registration supersedes older
        return new Receipt("native-runtime-v1", SINGLE_TENANT_ID, client, ownerJiacn, agent, runtime,
                gate.allows(SINGLE_TENANT_ID, client));
    }

    public void disconnect(String sessionId) {
        bindings.entrySet().removeIf(entry -> entry.getValue().sessionId.equals(sessionId));
    }

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
        if (!validToken(token) || !MessageDigest.isEqual(binding.tokenDigest, digest(token))) throw denied();
        validateCurrent(binding);
    }

    private void validateCurrent(Binding binding) {
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
            if (identity == null || !scope.agentId().equals(identity.getCanonicalAgentId())) throw denied();
            if (identities.requireActiveBinding(identity, null) == null) throw denied();
        } catch (DataAccessException unavailable) {
            throw unavailable;
        } catch (RuntimeException invalidIdentity) {
            // Identity lifecycle/binding mismatch is an authentication denial. Persistence
            // failures remain distinguishable above so capability reads can return 503.
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
