package cn.jia.agent.access;

/**
 * Effective task collaboration access for an Agent in one exact tenant/client/task scope.
 */
public enum AgentTaskAccessLevel {
    NONE,
    READ_ONLY,
    READ_WRITE;

    public boolean canRead() {
        return this == READ_ONLY || this == READ_WRITE;
    }

    public boolean canWrite() {
        return this == READ_WRITE;
    }
}
