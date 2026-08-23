package cn.jia.chat.service.impl;

import cn.jia.agent.access.AgentTaskAccessLevel;
import cn.jia.agent.common.TaskEventPayload;
import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.entity.AgentTaskEventWriteCommand;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.AgentTaskCollaborationAccessService;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
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
    private static final String EVENT_ID_PREFIX = "evt_";

    private final AgentTaskThreadDao taskThreadDao;
    private final ChatConversationDao conversationDao;
    private final ChatMessageDao messageDao;
    private final AgentService agentService;
    private final AgentTaskCollaborationAccessService accessService;
    private final AgentTaskMutationTransaction mutationTransaction;
    private final AgentTaskEventWriter eventWriter;

    @Inject
    public AgentTaskThreadCreationTransaction(
            AgentTaskThreadDao taskThreadDao,
            ChatConversationDao conversationDao,
            ChatMessageDao messageDao,
            AgentService agentService,
            AgentTaskCollaborationAccessService accessService,
            AgentTaskMutationTransaction mutationTransaction,
            AgentTaskEventWriter eventWriter) {
        this.taskThreadDao = Objects.requireNonNull(taskThreadDao, "taskThreadDao");
        this.conversationDao = Objects.requireNonNull(conversationDao, "conversationDao");
        this.messageDao = Objects.requireNonNull(messageDao, "messageDao");
        this.agentService = Objects.requireNonNull(agentService, "agentService");
        this.accessService = Objects.requireNonNull(accessService, "accessService");
        this.mutationTransaction = Objects.requireNonNull(mutationTransaction, "mutationTransaction");
        this.eventWriter = Objects.requireNonNull(eventWriter, "eventWriter");
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentTaskThreadEntity createTeamThread(
            String tenantId, String clientId, String taskId,
            String actorAgentId, String title, String conversationScopeKey) {
        return mutationTransaction.executeWithLockedTaskRoot(
                tenantId, clientId, taskId, taskRoot -> createTeamThreadLocked(
                        tenantId, clientId, taskId, actorAgentId, title,
                        conversationScopeKey, taskRoot));
    }

    private AgentTaskThreadEntity createTeamThreadLocked(
            String tenantId, String clientId, String taskId,
            String actorAgentId, String title, String conversationScopeKey,
            AgentTaskMetaEntity taskRoot) {
        requireLockedWriter(tenantId, clientId, taskId, actorAgentId);
        AgentTaskThreadEntity existing = taskThreadDao.findByTaskThreadForUpdate(
                tenantId, clientId, taskId,
                AgentTaskThreadConstants.THREAD_TYPE_TEAM,
                AgentTaskThreadConstants.THREAD_KEY_TEAM);
        if (existing != null) {
            return requireCanonicalThread(tenantId, clientId, taskId, existing);
        }
        AgentTaskThreadEntity created = createBinding(
                tenantId, clientId, taskId, actorAgentId, title, conversationScopeKey);
        appendThreadCreated(tenantId, clientId, taskId, actorAgentId, taskRoot, created);
        return created;
    }

    @Transactional(rollbackFor = Exception.class)
    public ChatMessageEntity appendTeamMessage(
            String tenantId, String clientId, String taskId, String actorAgentId,
            String requestedSenderName, ChatMessageEntity message) {
        return mutationTransaction.executeWithLockedTaskRoot(
                tenantId, clientId, taskId, taskRoot -> appendTeamMessageLocked(
                        tenantId, clientId, taskId, actorAgentId,
                        requestedSenderName, message, taskRoot));
    }

    private ChatMessageEntity appendTeamMessageLocked(
            String tenantId, String clientId, String taskId, String actorAgentId,
            String requestedSenderName, ChatMessageEntity message,
            AgentTaskMetaEntity taskRoot) {
        AgentRuntimeDTO runtime = requireLockedWriter(tenantId, clientId, taskId, actorAgentId);
        AgentTaskThreadEntity thread = taskThreadDao.findByTaskThreadForUpdate(
                tenantId, clientId, taskId,
                AgentTaskThreadConstants.THREAD_TYPE_TEAM,
                AgentTaskThreadConstants.THREAD_KEY_TEAM);
        if (thread == null) {
            thread = createBinding(
                    tenantId, clientId, taskId, actorAgentId,
                    "Task " + taskId + " team thread", "task-thread:" + taskId);
            appendThreadCreated(tenantId, clientId, taskId, actorAgentId, taskRoot, thread);
        } else {
            thread = requireCanonicalThread(tenantId, clientId, taskId, thread);
        }
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
        appendMessagePosted(tenantId, clientId, taskId, actorAgentId, taskRoot, message);
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
        if (conversationDao.insert(conversation) != 1 || conversation.getId() == null) {
            throw new IllegalStateException("Conversation insert did not affect one row");
        }

        AgentTaskThreadEntity thread = new AgentTaskThreadEntity()
                .setTaskId(taskId)
                .setThreadType(AgentTaskThreadConstants.THREAD_TYPE_TEAM)
                .setThreadKey(AgentTaskThreadConstants.THREAD_KEY_TEAM)
                .setConversationId(String.valueOf(conversation.getId()))
                .setCreatedByAgentId(actorAgentId)
                .setStatus(AgentTaskThreadConstants.THREAD_STATUS_ACTIVE);
        if (taskThreadDao.insert(tenantId, clientId, thread) != 1 || thread.getId() == null) {
            throw new IllegalStateException("Task thread binding insert did not affect one row");
        }
        return thread;
    }

    private void appendThreadCreated(
            String tenantId, String clientId, String taskId, String actorAgentId,
            AgentTaskMetaEntity taskRoot, AgentTaskThreadEntity thread) {
        long occurredAt = positiveTime(thread.getCreateTime());
        TaskEventPayload.Builder payload = TaskEventPayload.builder()
                .put(TaskEventPayload.Key.THREAD_ID, String.valueOf(thread.getId()))
                .put(TaskEventPayload.Key.THREAD_TYPE, thread.getThreadType())
                .put(TaskEventPayload.Key.CONVERSATION_ID, thread.getConversationId())
                .put(TaskEventPayload.Key.CREATED_AT, occurredAt);
        eventWriter.append(command(tenantId, clientId, taskId,
                TaskEventType.THREAD_CREATED, TaskEventType.ActorType.AGENT, actorAgentId,
                TaskEventType.Aggregate.THREAD, String.valueOf(thread.getId()), payload,
                occurredAt, taskRoot.getTaskVersion()));
    }

    private void appendMessagePosted(
            String tenantId, String clientId, String taskId, String actorAgentId,
            AgentTaskMetaEntity taskRoot, ChatMessageEntity message) {
        long occurredAt = positiveTime(message.getCreateTime());
        TaskEventPayload.Builder payload = TaskEventPayload.builder()
                .put(TaskEventPayload.Key.MESSAGE_ID, String.valueOf(message.getId()))
                .put(TaskEventPayload.Key.MESSAGE_TYPE, requiredMessageType(message.getMessageType()))
                .put(TaskEventPayload.Key.CONVERSATION_ID, message.getConversationId())
                .put(TaskEventPayload.Key.SENDER_AGENT_ID, actorAgentId)
                .putContentDigest(TaskEventPayload.ContentDigest.fromUtf8(message.getContent()))
                .put(TaskEventPayload.Key.CREATED_AT, occurredAt);
        eventWriter.append(command(tenantId, clientId, taskId,
                TaskEventType.MESSAGE_POSTED, TaskEventType.ActorType.AGENT, actorAgentId,
                TaskEventType.Aggregate.MESSAGE, String.valueOf(message.getId()), payload,
                occurredAt, taskRoot.getTaskVersion()));
    }

    private AgentTaskEventWriteCommand command(
            String tenantId, String clientId, String taskId,
            String eventType, String actorType, String actorId,
            String aggregateType, String aggregateId,
            TaskEventPayload.Builder payload, long occurredAt, long resultVersion) {
        String seed = tenantId + '\u0000' + clientId + '\u0000' + taskId + '\u0000'
                + eventType + '\u0000' + aggregateType + '\u0000' + aggregateId + '\u0000'
                + resultVersion;
        return new AgentTaskEventWriteCommand()
                .setTenantId(tenantId)
                .setClientId(clientId)
                .setTaskId(taskId)
                .setEventId(EVENT_ID_PREFIX
                        + TaskEventPayload.ContentDigest.fromUtf8(seed).sha256())
                .setEventType(eventType)
                .setActorType(actorType)
                .setActorId(actorId)
                .setAggregateType(aggregateType)
                .setAggregateId(aggregateId)
                .setEventJson(payload.toJson())
                .setOccurredAt(occurredAt);
    }

    private String requiredMessageType(String messageType) {
        if (messageType == null || messageType.isBlank()) {
            throw new IllegalArgumentException("Task thread messageType is required");
        }
        return messageType;
    }

    private long positiveTime(Long value) {
        long result = value == null ? System.currentTimeMillis() : value;
        if (result <= 0) {
            throw new IllegalStateException("Task thread event timestamp must be positive");
        }
        return result;
    }

    private AgentRuntimeDTO requireLockedWriter(
            String tenantId, String clientId, String taskId, String actorAgentId) {
        try {
            // Root lock is acquired by AgentTaskMutationTransaction before this member lock.
            // Preserve B07's remaining order: member -> identity/runtime -> thread/message.
            AgentTaskAccessLevel access = accessService.resolveMemberAccessForUpdate(
                    tenantId, clientId, taskId, actorAgentId);
            AgentRuntimeDTO runtime = access.canWrite()
                    ? agentService.requireApiKeyOwnedAgentForUpdate(clientId, tenantId, actorAgentId)
                    : null;
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
