package cn.jia.chat.service;

import java.util.List;

/**
 * Read-only public boundary exposing only authoritative Juyiting conversation scope facts.
 * This service does not expose messages and does not grant runtime file access.
 */
public interface WorkspaceConversationAccessService {
    ConversationView requireAccessible(Scope scope, String conversationId);

    record Scope(String tenantId, String clientId, String ownerJiacn) { }

    record ConversationView(
            String conversationId,
            String scopeType,
            String scopeKey,
            String taskId,
            List<String> targetAgentIds,
            long lifecycleGeneration,
            long contextRevision) {
        public ConversationView {
            targetAgentIds = List.copyOf(targetAgentIds);
        }
    }
}
