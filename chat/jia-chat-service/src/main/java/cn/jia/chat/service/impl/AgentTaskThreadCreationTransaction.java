package cn.jia.chat.service.impl;

import cn.jia.chat.dao.AgentTaskThreadDao;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.entity.AgentTaskThreadConstants;
import cn.jia.chat.entity.AgentTaskThreadEntity;
import cn.jia.chat.entity.ChatConversationEntity;
import jakarta.inject.Inject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Isolated transaction boundary so a duplicate-key race rolls back its newly-created conversation.
 */
@Service
public class AgentTaskThreadCreationTransaction {
    private final AgentTaskThreadDao taskThreadDao;
    private final ChatConversationDao conversationDao;

    @Inject
    public AgentTaskThreadCreationTransaction(
            AgentTaskThreadDao taskThreadDao, ChatConversationDao conversationDao) {
        this.taskThreadDao = Objects.requireNonNull(taskThreadDao, "taskThreadDao");
        this.conversationDao = Objects.requireNonNull(conversationDao, "conversationDao");
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentTaskThreadEntity createTeamThread(
            String tenantId, String clientId, String taskId,
            String actorAgentId, String title, String conversationScopeKey) {
        ChatConversationEntity conversation = new ChatConversationEntity();
        conversation.setTitle(title);
        conversation.setJiacn(tenantId);
        conversation.setTenantId(tenantId);
        conversation.setClientId(clientId);
        conversation.setConversationType(AgentTaskThreadConstants.CONVERSATION_TYPE);
        conversation.setConversationScopeType(AgentTaskThreadConstants.CONVERSATION_SCOPE_TYPE);
        conversation.setConversationScopeKey(conversationScopeKey);
        conversation.setStatus(0);
        conversationDao.insert(conversation);
        if (conversation.getId() == null) {
            throw new IllegalStateException("Conversation insert did not return an id");
        }

        AgentTaskThreadEntity thread = new AgentTaskThreadEntity()
                .setTaskId(taskId)
                .setThreadType(AgentTaskThreadConstants.THREAD_TYPE_TEAM)
                .setThreadKey(AgentTaskThreadConstants.THREAD_KEY_TEAM)
                .setConversationId(String.valueOf(conversation.getId()))
                .setCreatedByAgentId(actorAgentId)
                .setStatus(AgentTaskThreadConstants.THREAD_STATUS_ACTIVE);
        taskThreadDao.insert(tenantId, clientId, thread);
        return thread;
    }
}
