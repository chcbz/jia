package cn.jia.agent.service;

/** Internal lifecycle callback, after authenticated registration and local transport readiness.
 * Implementations persist wakeups only; never perform model or socket I/O here.
 * Throwing fails registration, so reconnect can retry rather than silently lose recovery work. */
public interface AgentSessionReadyListener {
    void sessionReady(String tenantId, String clientId, String canonicalAgentId);
}
