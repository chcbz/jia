package cn.jia.chat.service.impl;

import cn.jia.chat.dao.AgentTaskThreadDao;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.entity.AgentTaskThreadConstants;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.service.WorkspaceConversationAccessService;
import cn.jia.core.util.JsonUtil;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Authoritative chat-side implementation for workspace conversation ACL checks. */
@Named
public class WorkspaceConversationAccessServiceImpl implements WorkspaceConversationAccessService {
    private static final String DEFAULT_TENANT = "0";
    private static final String CONVERSATION_TYPE_JUYITING = "juyiting";
    private static final Set<String> SUPPORTED_SCOPE_TYPES =
            Set.of("public", "bounty", "private");
    private static final int MAX_IDENTITY_LENGTH = 50;
    private static final int MAX_SCOPE_ID_LENGTH = 100;
    private static final int MAX_SCOPE_KEY_LENGTH = 120;
    private static final int MAX_TARGET_JSON_LENGTH = 2000;
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final ChatConversationDao conversationDao;
    private final AgentTaskThreadDao taskThreadDao;

    @Inject
    public WorkspaceConversationAccessServiceImpl(
            ChatConversationDao conversationDao, AgentTaskThreadDao taskThreadDao) {
        this.conversationDao = Objects.requireNonNull(conversationDao, "conversationDao");
        this.taskThreadDao = Objects.requireNonNull(taskThreadDao, "taskThreadDao");
    }

    @Override
    @Transactional(readOnly = true)
    public ConversationView requireAccessible(Scope scope, String conversationId) {
        validateScope(scope);
        String canonicalConversationId = canonicalConversationId(conversationId);

        ChatConversationEntity conversation;
        try {
            conversation = conversationDao.findScopedById(
                    scope.ownerJiacn(), scope.clientId(), canonicalConversationId);
        } catch (RuntimeException queryFailure) {
            throw denied();
        }
        requireExactOwnedJuyitingConversation(scope, canonicalConversationId, conversation);
        rejectTaskThreadBinding(canonicalConversationId);

        String scopeType = conversation.getConversationScopeType();
        String scopeKey = exactText(
                conversation.getConversationScopeKey(), MAX_SCOPE_KEY_LENGTH);
        String taskId = optionalExactText(conversation.getTaskId(), MAX_SCOPE_ID_LENGTH);
        List<String> targetAgentIds = authoritativeTargets(conversation);
        validateConversationScope(scopeType, scopeKey, taskId, targetAgentIds,
                conversation.getTargetAgentId());

        return new ConversationView(
                canonicalConversationId,
                scopeType,
                scopeKey,
                taskId,
                targetAgentIds,
                positiveRevision(conversation.getLifecycleGeneration()),
                positiveRevision(conversation.getUpdateTime()));
    }

    private void requireExactOwnedJuyitingConversation(
            Scope scope, String conversationId, ChatConversationEntity conversation) {
        if (conversation == null
                || conversation.getId() == null
                || !conversationId.equals(Long.toString(conversation.getId()))
                || !DEFAULT_TENANT.equals(conversation.getTenantId())
                || !scope.clientId().equals(conversation.getClientId())
                || !scope.ownerJiacn().equals(conversation.getJiacn())
                || conversation.getDeletedAt() != null
                || !CONVERSATION_TYPE_JUYITING.equals(conversation.getConversationType())
                || AgentTaskThreadConstants.hasTaskThreadMarkerEvidence(conversation)
                || !SUPPORTED_SCOPE_TYPES.contains(conversation.getConversationScopeType())) {
            throw denied();
        }
    }

    private void rejectTaskThreadBinding(String conversationId) {
        boolean taskThreadBound;
        try {
            taskThreadBound = taskThreadDao.findAnyByConversationId(conversationId) != null;
        } catch (RuntimeException queryFailure) {
            throw denied();
        }
        if (taskThreadBound) {
            throw denied();
        }
    }

    private List<String> authoritativeTargets(ChatConversationEntity conversation) {
        String json = conversation.getTargetAgentIds();
        if (json == null || json.isBlank() || !json.equals(json.strip())
                || json.length() > MAX_TARGET_JSON_LENGTH) {
            throw denied();
        }
        List<String> parsed;
        try {
            parsed = JsonUtil.getMapper().readValue(json, STRING_LIST);
        } catch (Exception malformed) {
            throw denied();
        }
        if (parsed == null || parsed.isEmpty()) {
            throw denied();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String targetAgentId : parsed) {
            String canonical = exactText(targetAgentId, MAX_SCOPE_ID_LENGTH);
            if (!unique.add(canonical)) {
                throw denied();
            }
        }
        return List.copyOf(new ArrayList<>(unique));
    }

    private void validateConversationScope(
            String scopeType,
            String scopeKey,
            String taskId,
            List<String> targetAgentIds,
            String persistedPrivateTarget) {
        if ("private".equals(scopeType)) {
            if (targetAgentIds.size() != 1) {
                throw denied();
            }
            String targetAgentId = targetAgentIds.getFirst();
            if (!targetAgentId.equals(exactText(persistedPrivateTarget, MAX_SCOPE_ID_LENGTH))) {
                throw denied();
            }
            String expectedScopeKey = taskId == null
                    ? "agent:" + targetAgentId
                    : "task:" + taskId + ":agent:" + targetAgentId;
            if (!expectedScopeKey.equals(scopeKey)) {
                throw denied();
            }
            return;
        }

        if (persistedPrivateTarget != null) {
            throw denied();
        }
        if ("bounty".equals(scopeType) && taskId == null) {
            throw denied();
        }
        String expectedScopeKey = taskId == null ? "public" : "task:" + taskId;
        if (!expectedScopeKey.equals(scopeKey)) {
            throw denied();
        }
    }

    private void validateScope(Scope scope) {
        if (scope == null
                || !DEFAULT_TENANT.equals(scope.tenantId())
                || !isExactText(scope.clientId(), MAX_IDENTITY_LENGTH)
                || !isExactText(scope.ownerJiacn(), MAX_IDENTITY_LENGTH)
                || DEFAULT_TENANT.equals(scope.ownerJiacn())) {
            throw denied();
        }
    }

    private String canonicalConversationId(String conversationId) {
        if (!isExactText(conversationId, MAX_IDENTITY_LENGTH)) {
            throw denied();
        }
        try {
            long id = Long.parseLong(conversationId);
            if (id < 1 || !conversationId.equals(Long.toString(id))) {
                throw denied();
            }
            return conversationId;
        } catch (NumberFormatException malformed) {
            throw denied();
        }
    }

    private String optionalExactText(String value, int maximumLength) {
        return value == null ? null : exactText(value, maximumLength);
    }

    private String exactText(String value, int maximumLength) {
        if (!isExactText(value, maximumLength)) {
            throw denied();
        }
        return value;
    }

    private boolean isExactText(String value, int maximumLength) {
        return value != null
                && !value.isBlank()
                && value.length() <= maximumLength
                && value.equals(value.strip())
                && value.chars().noneMatch(Character::isISOControl);
    }

    private long positiveRevision(Long revision) {
        if (revision == null || revision < 1) {
            throw denied();
        }
        return revision;
    }

    private IllegalStateException denied() {
        return new IllegalStateException("Workspace conversation is unavailable");
    }
}
