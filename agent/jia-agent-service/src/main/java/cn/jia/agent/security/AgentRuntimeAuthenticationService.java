package cn.jia.agent.security;

import cn.jia.agent.config.AgentTaskEventsGate;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.oauth.service.ApiKeyService;
import cn.jia.user.security.AccountSecurityService;
import cn.jia.user.security.AccountSecuritySnapshot;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Reuses the existing registration token, NEVER issues JWTs. A process-local live WebSocket
 * binding is also required: restart/another API replica fails closed (HTTP must reach the
 * WebSocket-owning replica). Every request rechecks persistent token, identity, key and account.
 * No secret is retained in the registry or principal. No client-selected scope is accepted.
 */
@Service
public final class AgentRuntimeAuthenticationService {
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
    public Receipt bind(String sessionId, String tenant, String client, String agent, String runtime,
            String apiKeyId, String token, BooleanSupplier connected) {
        if (!exact(sessionId, 128) || !exact(tenant, 50) || !exact(client, 50)
                || agent == null || !agent.matches("agt_[0-9a-f]{32}")
                || !exact(runtime, 100) || runtime.equals(agent) || !exact(apiKeyId, 128)
                || connected == null || !validToken(token)) throw denied();
        var scope = new AgentRuntimeAuthentication.Scope(tenant, client, agent, runtime);
        var account = account(tenant);
        var key = keys.get(apiKeyId);
        if (key == null || key.getApiKey() == null || key.getApiKey().isBlank()) throw denied();
        Binding binding = new Binding(sessionId, scope, apiKeyId, digest(key.getApiKey()),
                digest(token), account.userId(), account.authEpoch(), connected);
        validate(binding, token);
        bindings.put(agent, binding); // exact canonical identity: newer registration supersedes older
        return new Receipt("native-runtime-v1", tenant, client, agent, runtime,
                gate.allows(tenant, client));
    }

    public void disconnect(String sessionId) {
        bindings.entrySet().removeIf(entry -> entry.getValue().sessionId.equals(sessionId));
    }

    public AgentRuntimeAuthentication authenticate(String agent, String runtime, String token) {
        if (agent == null || runtime == null || !validToken(token)) throw denied();
        Binding binding = bindings.get(agent);
        if (binding == null || !binding.scope.runtimeInstanceId().equals(runtime)) throw denied();
        validate(binding, token);
        if (bindings.get(agent) != binding || !binding.connected.getAsBoolean()) throw denied();
        return new AgentRuntimeAuthentication(binding.scope);
    }

    private void validate(Binding binding, String token) {
        var scope = binding.scope;
        if (!binding.connected.getAsBoolean()
                || !account(scope.tenantId()).matches(binding.userId, scope.tenantId(), binding.authEpoch)
                || !MessageDigest.isEqual(binding.tokenDigest, digest(token))) throw denied();
        var key = keys.get(binding.apiKeyId);
        if (key == null || !binding.apiKeyId.equals(key.getId()) || !Integer.valueOf(1).equals(key.getStatus())
                || !scope.tenantId().equals(key.getJiacn())
                || !scope.clientId().equals(key.getClientId()) || key.getApiKey() == null
                || !MessageDigest.isEqual(binding.keyDigest, digest(key.getApiKey()))
                || key.getExpireTime() != null && key.getExpireTime() <= System.currentTimeMillis()) throw denied();
        var row = runtimes.findByAgentId(scope.agentId());
        if (row == null || !scope.agentId().equals(row.getAgentId())
                || !scope.tenantId().equals(row.getTenantId()) || !scope.tenantId().equals(row.getOwnerJiacn())
                || !scope.clientId().equals(row.getClientId()) || row.getBindingId() == null
                || !Set.of("online", "busy").contains(row.getStatus()) || !validToken(row.getTokenHash())
                || !MessageDigest.isEqual(row.getTokenHash().getBytes(StandardCharsets.UTF_8),
                        token.getBytes(StandardCharsets.UTF_8))) throw denied();
        var identity = identities.requireActiveIdentityForBinding(scope.tenantId(), scope.clientId(),
                scope.tenantId(), row.getBindingId(), scope.agentId());
        if (identity == null || !scope.agentId().equals(identity.getCanonicalAgentId())) throw denied();
        if (identities.requireActiveBinding(identity, null) == null) throw denied();
    }

    private AccountSecuritySnapshot account(String tenant) {
        var account = accounts.findUniqueByExactJiacn(tenant).orElseThrow(AgentRuntimeAuthenticationService::denied);
        if (!tenant.equals(account.jiacn()) || !account.isAuthenticatable()) throw denied();
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
    static boolean validToken(String token) { return token != null && token.matches("[0-9a-f]{32}"); }
    static IllegalArgumentException denied() { return new IllegalArgumentException("AGENT_RUNTIME_UNAUTHENTICATED"); }
    private record Binding(String sessionId, AgentRuntimeAuthentication.Scope scope, String apiKeyId,
                           byte[] keyDigest, byte[] tokenDigest, long userId, long authEpoch, BooleanSupplier connected) { }
    public record Receipt(String scheme, String tenantId, String clientId, String agentId,
                          String runtimeInstanceId, boolean contextPackEnabled) { }
}
