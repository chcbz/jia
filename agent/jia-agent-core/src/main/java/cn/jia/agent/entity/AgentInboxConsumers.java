package cn.jia.agent.entity;

/** Stable logical Inbox consumer identities. Never derive these from a host, PID, session, or broker tag. */
public final class AgentInboxConsumers {
    public static final String AGENT_COMMAND_DISPATCH_V1 = "agent-command-dispatch-v1";

    private AgentInboxConsumers() {
    }
}
