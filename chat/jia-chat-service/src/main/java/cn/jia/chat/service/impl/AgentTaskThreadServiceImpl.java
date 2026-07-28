package cn.jia.chat.service.impl;

import cn.jia.agent.access.AgentTaskAccessLevel;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.AgentTaskCollaborationAccessService;
import cn.jia.chat.dao.AgentTaskThreadDao;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.entity.AgentTaskThreadConstants;
import cn.jia.chat.entity.AgentTaskThreadDTO;
import cn.jia.chat.entity.AgentTaskThreadEntity;
import cn.jia.chat.entity.AgentTaskThreadMessageCreateDTO;
import cn.jia.chat.entity.AgentTaskThreadMessageDTO;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.exception.AgentTaskThreadException;
import cn.jia.chat.exception.AgentTaskThreadException.Reason;
import cn.jia.chat.service.AgentTaskThreadService;
import cn.jia.core.util.JsonUtil;
import cn.jia.core.util.StringUtil;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.dao.DuplicateKeyException;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Named
public class AgentTaskThreadServiceImpl implements AgentTaskThreadService {
    private static final int MAX_SCOPE_LENGTH = 50;
    private static final int MAX_TASK_ID_LENGTH = 100;
    private static final int MAX_AGENT_ID_LENGTH = 100;
    private static final int MAX_TITLE_LENGTH = 500;
    private static final int MAX_SENDER_NAME_LENGTH = 100;
    private static final int MAX_TEXT_UTF8_BYTES = 65_535;
    private static final int MAX_MESSAGES = 500;

    private final AgentTaskThreadDao taskThreadDao;
    private final ChatConversationDao conversationDao;
    private final ChatMessageDao messageDao;
    private final AgentService agentService;
    private final AgentTaskCollaborationAccessService accessService;
    private final AgentTaskThreadCreationTransaction creationTransaction;

    @Inject
    public AgentTaskThreadServiceImpl(
            AgentTaskThreadDao taskThreadDao,
            ChatConversationDao conversationDao,
            ChatMessageDao messageDao,
            AgentService agentService,
            AgentTaskCollaborationAccessService accessService,
            AgentTaskThreadCreationTransaction creationTransaction) {
        this.taskThreadDao = Objects.requireNonNull(taskThreadDao, "taskThreadDao");
        this.conversationDao = Objects.requireNonNull(conversationDao, "conversationDao");
        this.messageDao = Objects.requireNonNull(messageDao, "messageDao");
        this.agentService = Objects.requireNonNull(agentService, "agentService");
        this.accessService = Objects.requireNonNull(accessService, "accessService");
        this.creationTransaction = Objects.requireNonNull(creationTransaction, "creationTransaction");
    }

    @Override
    public AgentTaskThreadDTO getOrCreateTeamThread(
            String tenantId, String clientId, String taskId, String actorAgentId, String title) {
        Scope scope = requireScope(tenantId, clientId, taskId, actorAgentId);
        requireWriteAccess(scope);
        return getOrCreateTeamThread(scope, title);
    }

    private AgentTaskThreadDTO getOrCreateTeamThread(Scope scope, String title) {
        AgentTaskThreadEntity existing = findTeam(scope);
        if (existing != null) {
            return toThreadDto(requireUsableThread(scope, existing));
        }

        String safeTitle = optionalText(title, "title", MAX_TITLE_LENGTH);
        if (safeTitle == null) {
            safeTitle = "Task " + scope.taskId() + " team thread";
        }
        String scopeKey = "task-thread:" + scope.taskId();
        try {
            AgentTaskThreadEntity created = creationTransaction.createTeamThread(
                    scope.tenantId(), scope.clientId(), scope.taskId(), scope.actorAgentId(),
                    safeTitle, scopeKey);
            return toThreadDto(requireUsableThread(scope, created));
        } catch (RuntimeException exception) {
            if (!isDuplicateBinding(exception)) {
                throw persistenceFailure(exception);
            }
            AgentTaskThreadEntity winner = awaitConcurrentWinner(scope);
            if (winner == null) {
                throw new AgentTaskThreadException(
                        Reason.CONFLICT, "Task thread creation conflicted; retry the request");
            }
            return toThreadDto(requireUsableThread(scope, winner));
        }
    }

    @Override
    public AgentTaskThreadDTO getTeamThread(
            String tenantId, String clientId, String taskId, String actorAgentId) {
        Scope scope = requireScope(tenantId, clientId, taskId, actorAgentId);
        requireReadAccess(scope);
        AgentTaskThreadEntity thread = findTeam(scope);
        if (thread == null) {
            throw unavailable();
        }
        return toThreadDto(requireUsableThread(scope, thread));
    }

    @Override
    public AgentTaskThreadMessageDTO appendTeamMessage(
            String tenantId, String clientId, String taskId,
            AgentTaskThreadMessageCreateDTO request) {
        if (request == null) {
            throw invalid("message request is required");
        }
        Scope scope = requireScope(tenantId, clientId, taskId, request.getActorAgentId());
        String content = requiredTextUtf8(request.getContent(), "content", MAX_TEXT_UTF8_BYTES);
        String senderName = optionalExactText(
                request.getSenderName(), "senderName", MAX_SENDER_NAME_LENGTH);
        requireWriteAccess(scope);
        // Ensure a stable binding exists; the actual append repeats ownership/member checks under row locks.
        getOrCreateTeamThread(scope, null);

        Map<String, Object> metadata = new LinkedHashMap<>();
        if (request.getMetadata() != null) {
            metadata.putAll(request.getMetadata());
        }
        metadata.put("taskId", scope.taskId());
        metadata.put("threadType", AgentTaskThreadConstants.THREAD_TYPE_TEAM);
        metadata.put("actorAgentId", scope.actorAgentId());
        String metadataJson;
        try {
            metadataJson = JsonUtil.toJson(metadata);
        } catch (RuntimeException exception) {
            throw invalid("metadata is invalid");
        }
        requireUtf8Bytes(metadataJson, "metadata", MAX_TEXT_UTF8_BYTES);

        ChatMessageEntity message = new ChatMessageEntity();
        message.setMessageType("ASSISTANT");
        message.setContent(content);
        message.setMetadata(metadataJson);
        message.setJiacn(scope.tenantId());
        message.setSyncStatus(AgentTaskThreadConstants.MEMORY_SYNC_EXCLUDED);
        message.setConversationType(AgentTaskThreadConstants.CONVERSATION_TYPE);
        message.setSenderType("agent");
        try {
            return toMessageDto(creationTransaction.appendTeamMessage(
                    scope.tenantId(), scope.clientId(), scope.taskId(), scope.actorAgentId(),
                    senderName, message));
        } catch (RuntimeException exception) {
            throw persistenceFailure(exception);
        }
    }

    @Override
    public List<AgentTaskThreadMessageDTO> listTeamMessages(
            String tenantId, String clientId, String taskId, String actorAgentId, int limit) {
        Scope scope = requireScope(tenantId, clientId, taskId, actorAgentId);
        if (limit < 1 || limit > MAX_MESSAGES) {
            throw invalid("limit must be between 1 and " + MAX_MESSAGES);
        }
        requireReadAccess(scope);
        AgentTaskThreadEntity thread = findTeam(scope);
        if (thread == null) {
            throw unavailable();
        }
        requireUsableThread(scope, thread);
        try {
            return messageDao.findByConversationIdScoped(
                            scope.tenantId(), scope.clientId(), thread.getConversationId(), limit)
                    .stream().map(this::toMessageDto).toList();
        } catch (RuntimeException exception) {
            throw persistenceFailure(exception);
        }
    }

    private AgentTaskThreadEntity findTeam(Scope scope) {
        return taskThreadDao.findByTaskThread(
                scope.tenantId(), scope.clientId(), scope.taskId(),
                AgentTaskThreadConstants.THREAD_TYPE_TEAM,
                AgentTaskThreadConstants.THREAD_KEY_TEAM);
    }

    private AgentTaskThreadEntity awaitConcurrentWinner(Scope scope) {
        for (int attempt = 0; attempt < 5; attempt++) {
            AgentTaskThreadEntity winner = findTeam(scope);
            if (winner != null) {
                return winner;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private AgentTaskThreadEntity requireUsableThread(
            Scope scope, AgentTaskThreadEntity thread) {
        if (thread == null
                || !scope.tenantId().equals(thread.getTenantId())
                || !scope.clientId().equals(thread.getClientId())
                || !scope.taskId().equals(thread.getTaskId())
                || !AgentTaskThreadConstants.THREAD_TYPE_TEAM.equals(thread.getThreadType())
                || !AgentTaskThreadConstants.THREAD_KEY_TEAM.equals(thread.getThreadKey())
                || !AgentTaskThreadConstants.THREAD_STATUS_ACTIVE.equals(thread.getStatus())
                || !canonicalId(thread.getConversationId())
                || !canonicalId(thread.getCreatedByAgentId())) {
            throw unavailable();
        }
        ChatConversationEntity conversation = conversationDao.findScopedById(
                scope.tenantId(), scope.clientId(), thread.getConversationId());
        if (conversation == null
                || conversation.getId() == null
                || !thread.getConversationId().equals(String.valueOf(conversation.getId()))
                || !scope.tenantId().equals(conversation.getTenantId())
                || !scope.clientId().equals(conversation.getClientId())
                || !scope.tenantId().equals(conversation.getJiacn())
                || !AgentTaskThreadConstants.CONVERSATION_TYPE.equals(conversation.getConversationType())
                || !AgentTaskThreadConstants.CONVERSATION_SCOPE_TYPE.equals(conversation.getConversationScopeType())
                || !("task-thread:" + scope.taskId()).equals(conversation.getConversationScopeKey())
                || !scope.taskId().equals(conversation.getTaskId())
                || !Integer.valueOf(0).equals(conversation.getStatus())) {
            throw unavailable();
        }
        return thread;
    }

    private void requireReadAccess(Scope scope) {
        requireOwnedActor(scope);
        AgentTaskAccessLevel access = accessService.resolveMemberAccess(
                scope.tenantId(), scope.clientId(), scope.taskId(), scope.actorAgentId());
        if (!access.canRead()) {
            throw unavailable();
        }
    }

    private void requireWriteAccess(Scope scope) {
        requireOwnedActor(scope);
        AgentTaskAccessLevel access = accessService.resolveMemberAccess(
                scope.tenantId(), scope.clientId(), scope.taskId(), scope.actorAgentId());
        if (!access.canWrite()) {
            throw unavailable();
        }
    }

    private void requireOwnedActor(Scope scope) {
        try {
            // HTTP callers nominate an actor, but authenticated tenant/client scope is authoritative.
            // Reuse the same active persona-binding ownership check as the API-key WebSocket handshake.
            agentService.requireApiKeyOwnedAgent(
                    scope.clientId(), scope.tenantId(), scope.actorAgentId());
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private Scope requireScope(
            String tenantId, String clientId, String taskId, String actorAgentId) {
        return new Scope(
                requiredId(tenantId, "tenantId", MAX_SCOPE_LENGTH),
                requiredId(clientId, "clientId", MAX_SCOPE_LENGTH),
                requiredId(taskId, "taskId", MAX_TASK_ID_LENGTH),
                requiredId(actorAgentId, "actorAgentId", MAX_AGENT_ID_LENGTH));
    }

    private String requiredId(String value, String field, int maxLength) {
        if (StringUtil.isBlank(value) || !value.equals(value.strip())
                || value.length() > maxLength
                || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid(field + " is invalid");
        }
        return value;
    }

    private String optionalExactText(String value, String field, int maxLength) {
        if (value == null) {
            return null;
        }
        if (StringUtil.isBlank(value) || !value.equals(value.strip())
                || value.length() > maxLength
                || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid(field + " is invalid");
        }
        return value;
    }

    private boolean canonicalId(String value) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.chars().noneMatch(Character::isISOControl);
    }

    private String optionalText(String value, String field, int maxLength) {
        if (StringUtil.isBlank(value)) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength || normalized.chars().anyMatch(Character::isISOControl)) {
            throw invalid(field + " is invalid");
        }
        return normalized;
    }

    private String requiredTextUtf8(String value, String field, int maxBytes) {
        if (StringUtil.isBlank(value)) {
            throw invalid(field + " is required");
        }
        requireUtf8Bytes(value, field, maxBytes);
        return value;
    }

    private void requireUtf8Bytes(String value, String field, int maxBytes) {
        if (value != null && value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw invalid(field + " is too large");
        }
    }

    private boolean isDuplicateBinding(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof DuplicateKeyException) {
                return true;
            }
            if (current instanceof SQLException sqlException) {
                String state = sqlException.getSQLState();
                String message = String.valueOf(sqlException.getMessage()).toLowerCase();
                if ("23505".equals(state)
                        || ("23000".equals(state)
                        && (message.contains("uk_task_thread_scope")
                        || message.contains("uk_task_thread_conversation")))) {
                    return true;
                }
            }
        }
        return false;
    }

    private AgentTaskThreadException persistenceFailure(Throwable cause) {
        if (cause instanceof AgentTaskThreadException exception) {
            return exception;
        }
        return new AgentTaskThreadException(
                Reason.PERSISTENCE_ERROR, "Task thread operation failed", cause);
    }

    private AgentTaskThreadException invalid(String message) {
        return new AgentTaskThreadException(Reason.INVALID_REQUEST, message);
    }

    private AgentTaskThreadException unavailable() {
        return new AgentTaskThreadException(
                Reason.NOT_FOUND_OR_FORBIDDEN,
                "Task thread is not available in the requested scope");
    }

    private AgentTaskThreadDTO toThreadDto(AgentTaskThreadEntity entity) {
        return new AgentTaskThreadDTO()
                .setTaskId(entity.getTaskId())
                .setThreadType(entity.getThreadType())
                .setThreadKey(entity.getThreadKey())
                .setConversationId(entity.getConversationId())
                .setCreatedByAgentId(entity.getCreatedByAgentId())
                .setStatus(entity.getStatus())
                .setCreatedAt(entity.getCreateTime());
    }

    private AgentTaskThreadMessageDTO toMessageDto(ChatMessageEntity entity) {
        return new AgentTaskThreadMessageDTO()
                .setMessageId(entity.getId())
                .setConversationId(entity.getConversationId())
                .setMessageType(entity.getMessageType())
                .setContent(entity.getContent())
                .setMetadata(entity.getMetadata())
                .setSenderType(entity.getSenderType())
                .setSenderName(entity.getSenderName())
                .setCreatedAt(entity.getCreateTime());
    }

    private record Scope(String tenantId, String clientId, String taskId, String actorAgentId) {
    }
}
