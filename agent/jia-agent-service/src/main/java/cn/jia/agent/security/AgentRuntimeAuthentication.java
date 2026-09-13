package cn.jia.agent.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.List;

/** Not an OAuth identity. Only the dedicated runtime filter may construct this principal. */
public final class AgentRuntimeAuthentication extends AbstractAuthenticationToken {
    private final Scope scope;

    AgentRuntimeAuthentication(Scope scope) {
        super(List.of(new SimpleGrantedAuthority("AGENT_RUNTIME_NARROW")));
        this.scope = scope;
        super.setAuthenticated(true);
    }

    @Override public Scope getPrincipal() { return scope; }
    @Override public Object getCredentials() { return null; }
    @Override public String getName() { return scope.agentId(); }
    @Override public void setAuthenticated(boolean value) {
        if (value) throw new IllegalArgumentException("Runtime authentication cannot be promoted");
        super.setAuthenticated(false);
    }

    public record Scope(String tenantId, String clientId, String agentId, String runtimeInstanceId) { }
}
