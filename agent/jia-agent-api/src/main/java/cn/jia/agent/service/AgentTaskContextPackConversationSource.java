package cn.jia.agent.service;

/** Optional metadata-only conversation source. Implementations must not generate paid summaries. */
public interface AgentTaskContextPackConversationSource {
    ConversationReference findReference(
            String tenantId, String clientId, String taskId, String actorAgentId);

    record ConversationReference(
            boolean available, String reason, String conversationId,
            Integer recentMessageCount, Long latestMessageAt, boolean truncated) {

        public static ConversationReference unavailable(String reason) {
            return new ConversationReference(false, reason, null, null, null, false);
        }

        public static ConversationReference available(
                String conversationId, int recentMessageCount,
                Long latestMessageAt, boolean truncated) {
            return new ConversationReference(true, null, conversationId,
                    recentMessageCount, latestMessageAt, truncated);
        }
    }
}
