package cn.jia.chat.service.impl;

import cn.jia.agent.service.AgentTaskContextPackConversationSource;
import cn.jia.chat.entity.AgentTaskThreadDTO;
import cn.jia.chat.entity.AgentTaskThreadMessageDTO;
import cn.jia.chat.exception.AgentTaskThreadException;
import cn.jia.chat.service.AgentTaskThreadService;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.util.List;
import java.util.Objects;

/** Metadata-only task-thread reference. It never calls an AI or summary provider. */
@Named
public class AgentTaskContextPackConversationSourceImpl
        implements AgentTaskContextPackConversationSource {
    static final int RECENT_MESSAGE_LIMIT = 100;

    private final AgentTaskThreadService threadService;

    @Inject
    public AgentTaskContextPackConversationSourceImpl(AgentTaskThreadService threadService) {
        this.threadService = Objects.requireNonNull(threadService, "threadService");
    }

    @Override
    public ConversationReference findReference(
            String tenantId, String clientId, String taskId, String actorAgentId) {
        try {
            AgentTaskThreadDTO thread = threadService.getTeamThread(
                    tenantId, clientId, taskId, actorAgentId);
            List<AgentTaskThreadMessageDTO> rows = threadService.listTeamMessages(
                    tenantId, clientId, taskId, actorAgentId, RECENT_MESSAGE_LIMIT + 1);
            if (thread == null || thread.getConversationId() == null || rows == null) {
                return ConversationReference.unavailable("CONVERSATION_REFERENCE_INVALID");
            }
            boolean truncated = rows.size() > RECENT_MESSAGE_LIMIT;
            int from = Math.max(0, rows.size() - RECENT_MESSAGE_LIMIT);
            Long latestAt = null;
            for (AgentTaskThreadMessageDTO row : rows.subList(from, rows.size())) {
                if (row == null || !thread.getConversationId().equals(row.getConversationId())
                        || row.getCreatedAt() == null || row.getCreatedAt() < 0) {
                    return ConversationReference.unavailable(
                            "CONVERSATION_REFERENCE_INVALID");
                }
                if (latestAt == null || row.getCreatedAt() > latestAt) {
                    latestAt = row.getCreatedAt();
                }
            }
            return ConversationReference.available(thread.getConversationId(),
                    Math.min(rows.size(), RECENT_MESSAGE_LIMIT), latestAt, truncated);
        } catch (AgentTaskThreadException exception) {
            return ConversationReference.unavailable("CONVERSATION_REFERENCE_UNAVAILABLE");
        } catch (RuntimeException exception) {
            return ConversationReference.unavailable("CONVERSATION_SOURCE_UNAVAILABLE");
        }
    }
}
