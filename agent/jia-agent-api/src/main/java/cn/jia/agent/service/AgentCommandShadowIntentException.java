package cn.jia.agent.service;

/** A capture-only shadow intent is immutable and must never become dispatchable. */
public final class AgentCommandShadowIntentException extends RuntimeException {
    public AgentCommandShadowIntentException(String message) {
        super(message);
    }
}
