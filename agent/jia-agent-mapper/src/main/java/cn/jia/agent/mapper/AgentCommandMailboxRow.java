package cn.jia.agent.mapper;

/** Internal keyset row. The database id never crosses the service boundary. */
public record AgentCommandMailboxRow(
        long id,
        String commandId,
        String taskId,
        String workItemId,
        String targetAgentId,
        String commandType,
        String status,
        long expiresAt,
        long createTime,
        long updateTime) {
}
