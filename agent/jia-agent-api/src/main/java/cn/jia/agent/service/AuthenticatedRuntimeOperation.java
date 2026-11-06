package cn.jia.agent.service;

import java.security.Principal;
import java.util.function.Function;

/** Native HTTP entry boundary. Identity and session fencing stay in the existing authentication service. */
public interface AuthenticatedRuntimeOperation {
    record Scope(String tenantId, String clientId, String ownerJiacn, String agentId) { }
    <T> T withAuthenticatedRuntime(Principal principal, Function<Scope, T> operation);
}
