package cn.jia.chat.service.impl;

import cn.jia.agent.access.AgentTaskAccessLevel;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.AgentTaskCollaborationAccessService;
import cn.jia.chat.dao.AgentTaskThreadDao;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.entity.AgentTaskThreadConstants;
import cn.jia.chat.entity.AgentTaskThreadEntity;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.exception.AgentTaskThreadException;
import jakarta.inject.Inject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** Transactional write boundary for task-thread creation and append ACL revalidation. */
@Service
public class AgentTaskThreadCreationTransaction {
    private final AgentTaskThreadDao taskThreadDao;
    private final ChatConversationDao conversationDao;
    private final ChatMessageDao messageDao;
    private final AgentService agentService;
    private final AgentTaskCollaborationAccessService accessService;

    @Inject
    public AgentTaskThreadCreationTransaction(
            AgentTaskThreadDao taskThreadDao,
            ChatConversationDao conversationDao,
            ChatMessageDao messageDao,
            AgentService agentService,
            AgentTaskCollaborationAccessService accessService) {
        this.taskThreadDao = Objects.requireNonNull(taskThreadDao, "taskThreadDao");
        this.conversationDao = Objects.requireNonNull(conversationDao, "conversationDao");
        this.messageDao = Objects.requireNonNull(messageDao, "messageDao");
        this.agentService = Objects.requireNonNull(agentService, "agentService");
        this.accessService = Objects.requireNonNull(accessService, "accessService");
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentTaskThreadEntity createTeamThread(
            String tenantId, String clientId, String taskId,
            String actorAgentId, String title, String conversationScopeKey) {
        requireLockedWriter(tenantId, clientId, taskId, actorAgentId);
        AgentTaskThreadEntity existing = taskThreadDao.findByTaskThreadForUpdate(
                tenantId, clientId, taskId,
                AgentTaskThreadConstants.THREAD_TYPE_TEAM,
                AgentTaskThreadConstants.THREAD_KEY_TEAM);
        if (existing != null) {
            return requireCanonicalThread(tenantId, clientId, taskId, existing);
        }
        return createBinding(tenantId, clientId, taskId, actorAgentId, title, conversationScopeKey);
    }

    @Transactional(rollbackFor = Exception.class)
    public ChatMessageEntity appendTeamMessage(
            String tenantId, String clientId, String taskId, String actorAgentId,
            String requestedSenderName, ChatMessageEntity message) {
        AgentRuntimeDTO runtime = requireLockedWriter(tenantId, clientId, taskId, actorAgentId);
        AgentTaskThreadEntity thread = taskThreadDao.findByTaskThreadForUpdate(
                tenantId, clientId, taskId,
                AgentTaskThreadConstants.THREAD_TYPE_TEAM,
                AgentTaskThreadConstants.THREAD_KEY_TEAM);
        thread = requireCanonicalThread(tenantId, clientId, taskId, thread);
        ChatConversationEntity conversation = conversationDao.findScopedById(
                tenantId, clientId, thread.getConversationId());
        requireCanonicalConversation(tenantId, clientId, taskId, thread, conversation);

        String senderName = trustedSenderName(runtime, actorAgentId);
        if (requestedSenderName != null && !requestedSenderName.equals(senderName)) {
            throw new AgentTaskThreadException(
                    AgentTaskThreadException.Reason.INVALID_REQUEST,
                    "senderName does not match the authenticated agent");
        }
        message.setConversationId(thread.getConversationId());
        message.setSenderName(senderName);
        int inserted = messageDao.insertScoped(tenantId, clientId, message);
        if (inserted != 1 || message.getId() == null) {
            throw new IllegalStateException("Task thread message insert did not affect one row");
        }
        return message;
    }

    private AgentTaskThreadEntity createBinding(
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
        conversation.setTaskId(taskId);
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
        if (taskThreadDao.insert(tenantId, clientId, thread) != 1) {
            throw new IllegalStateException("Task thread binding insert did not affect one row");
        }
        return thread;
    }

    private AgentRuntimeDTO requireLockedWriter(
            String tenantId, String clientId, String taskId, String actorAgentId) {
        try {
            AgentRuntimeDTO runtime = agentService.requireApiKeyOwnedAgentForUpdate(
                    clientId, tenantId, actorAgentId);
            AgentTaskAccessLevel access = accessService.resolveMemberAccessForUpdate(
                    tenantId, clientId, taskId, actorAgentId);
            if (runtime == null || !actorAgentId.equals(runtime.getAgentId()) || !access.canWrite()) {
                throw unavailable();
            }
            return runtime;
        } catch (AgentTaskThreadException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private AgentTaskThreadEntity requireCanonicalThread(
            String tenantId, String clientId, String taskId, AgentTaskThreadEntity thread) {
        if (thread == null
                || !tenantId.equals(thread.getTenantId())
                || !clientId.equals(thread.getClientId())
                || !taskId.equals(thread.getTaskId())
                || !AgentTaskThreadConstants.THREAD_TYPE_TEAM.equals(thread.getThreadType())
                || !AgentTaskThreadConstants.THREAD_KEY_TEAM.equals(thread.getThreadKey())
                || !AgentTaskThreadConstants.THREAD_STATUS_ACTIVE.equals(thread.getStatus())
                || !canonicalId(thread.getConversationId())
                || !canonicalId(thread.getCreatedByAgentId())) {
            throw unavailable();
        }
        return thread;
    }

    private void requireCanonicalConversation(
            String tenantId, String clientId, String taskId,
            AgentTaskThreadEntity thread, ChatConversationEntity conversation) {
        if (conversation == null
                || conversation.getId() == null
                || !thread.getConversationId().equals(String.valueOf(conversation.getId()))
                || !tenantId.equals(conversation.getTenantId())
                || !clientId.equals(conversation.getClientId())
                || !tenantId.equals(conversation.getJiacn())
                || !AgentTaskThreadConstants.CONVERSATION_TYPE.equals(conversation.getConversationType())
                || !AgentTaskThreadConstants.CONVERSATION_SCOPE_TYPE.equals(conversation.getConversationScopeType())
                || !("task-thread:" + taskId).equals(conversation.getConversationScopeKey())
                || !taskId.equals(conversation.getTaskId())
                || !Integer.valueOf(0).equals(conversation.getStatus())) {
            throw unavailable();
        }
    }

    private String trustedSenderName(AgentRuntimeDTO runtime, String actorAgentId) {
        String candidate = runtime.getName();
        if (!canonicalDisplayName(candidate)) {
            candidate = runtime.getPersonaName();
        }
        return canonicalDisplayName(candidate) ? candidate : actorAgentId;
    }

    private boolean canonicalDisplayName(String value) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.length() <= 100
                && value.chars().noneMatch(Character::isISOControl);
    }

    private boolean canonicalId(String value) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.chars().noneMatch(Character::isISOControl);
    }

    private AgentTaskThreadException unavailable() {
        return new AgentTaskThreadException(
                AgentTaskThreadException.Reason.NOT_FOUND_OR_FORBIDDEN,
                "Task thread is not available in the requested scope");
    }
}
