package cn.jia.chat.service;

/** Public mailbox projection. Transport payload, hashes, leases and replay metadata are absent. */
public record HallDurableMailboxItem(
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
