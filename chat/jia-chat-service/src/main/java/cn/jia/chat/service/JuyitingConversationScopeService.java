package cn.jia.chat.service;

import cn.jia.agent.service.AgentService;
import cn.jia.chat.handler.dto.ChatMessageDTO;
import cn.jia.core.util.JsonUtil;
import cn.jia.core.util.StringUtil;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Resolves display metadata separately from authoritative conversation/task ACL. */
@Service
public class JuyitingConversationScopeService {
    public static final String CONVERSATION_TYPE_JUYITING = "juyiting";
    public static final String SCOPE_PUBLIC = "public";
    public static final String SCOPE_BOUNTY = "bounty";
    public static final String SCOPE_PRIVATE = "private";
    private static final int MAX_SCOPE_ID_LENGTH = 100;
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final BuiltinHallAgentSupport builtinHallAgentSupport;
    private final AgentService agentService;

    public JuyitingConversationScopeService(
            BuiltinHallAgentSupport builtinHallAgentSupport, AgentService agentService) {
        this.builtinHallAgentSupport = builtinHallAgentSupport;
        this.agentService = agentService;
    }

    public JuyitingConversationScope resolve(ChatMessageDTO chatMessage) {
        String scopeType = normalizeScopeType(resolveConversationScopeType(chatMessage));
        String taskId = normalizeOptionalScopeId(resolveTaskId(chatMessage));
        List<String> targetAgentIds = resolveTargetAgentIds(chatMessage, scopeType, taskId);
        String targetAgentId = targetAgentIds.isEmpty() ? null : targetAgentIds.getFirst();
        String scopeKey = resolveConversationScopeKey(
                chatMessage, scopeType, taskId, targetAgentId);
        if (SCOPE_PRIVATE.equals(scopeType) && targetAgentIds.isEmpty()) {
            String targetFromScope = normalizeOptionalScopeId(targetAgentIdFromScopeKey(scopeKey));
            if (StringUtil.isNotBlank(targetFromScope)) {
                targetAgentIds = List.of(targetFromScope);
                targetAgentId = targetFromScope;
            }
        }
        return new JuyitingConversationScope(
                scopeType, scopeKey, taskId, targetAgentId, targetAgentIds, List.of());
    }

    /**
     * Resolves task membership from the authenticated tenant/client and returns an ACL-frozen scope.
     * Caller participant metadata is intentionally ignored for authorization.
     */
    public JuyitingConversationScope authorize(
            ChatMessageDTO chatMessage,
            JuyitingConversationScope requested,
            String tenantId,
            String clientId) {
        if (requested == null || !isCanonicalIdentity(tenantId) || !isCanonicalIdentity(clientId)
                || !isSupportedScope(requested.scopeType())) {
            throw denied();
        }

        boolean taskScoped = SCOPE_BOUNTY.equals(requested.scopeType())
                || StringUtil.isNotBlank(requested.taskId())
                || (StringUtil.isNotBlank(requested.scopeKey())
                && requested.scopeKey().startsWith("task:"));
        if (!taskScoped) {
            List<String> targets = normalizeAgentIds(requested.targetAgentIds());
            if (SCOPE_PRIVATE.equals(requested.scopeType()) && targets.size() != 1) {
                throw denied();
            }
            if (SCOPE_PUBLIC.equals(requested.scopeType()) && targets.isEmpty()) {
                targets = List.of(requireScopeId(builtinHallAgentSupport.defaultAgentId()));
            }
            return withTargets(requested, targets, targets);
        }

        String taskId = requireScopeId(requested.taskId());
        List<String> members;
        try {
            members = normalizeAuthoritativeAgentIds(
                    agentService.listTaskMemberAgentIds(tenantId, clientId, taskId));
        } catch (RuntimeException queryFailure) {
            throw denied();
        }
        if (members.isEmpty()) {
            throw denied();
        }
        requireExpectedTaskScope(requested, taskId);

        List<String> targets = normalizeAgentIds(requested.targetAgentIds());
        if (targets.isEmpty()) {
            targets = members;
        }
        if (SCOPE_PRIVATE.equals(requested.scopeType()) && targets.size() != 1) {
            throw denied();
        }
        Set<String> authoritative = new LinkedHashSet<>(members);
        if (targets.stream().anyMatch(agentId -> !authoritative.contains(agentId))) {
            throw denied();
        }
        return new JuyitingConversationScope(
                requested.scopeType(), requested.scopeKey(), taskId,
                targets.getFirst(), List.copyOf(targets), List.copyOf(members));
    }

    public String serializeTargetAgentIds(List<String> targetAgentIds) {
        List<String> normalized = normalizeAgentIds(targetAgentIds);
        String json = JsonUtil.toJson(normalized);
        if (json == null) {
            throw denied();
        }
        return json;
    }

    public List<String> parsePersistedTargetAgentIds(String json) {
        if (StringUtil.isBlank(json)) {
            return List.of();
        }
        try {
            List<String> parsed = JsonUtil.getMapper().readValue(json, STRING_LIST);
            List<String> normalized = normalizeAgentIds(parsed);
            if (parsed == null || normalized.size() != parsed.size()) {
                throw denied();
            }
            return normalized;
        } catch (RuntimeException failure) {
            throw denied();
        } catch (Exception failure) {
            throw denied();
        }
    }

    public String resolveConversationType(ChatMessageDTO chatMessage) {
        if (chatMessage == null || StringUtil.isBlank(chatMessage.getConversationType())) {
            return "normal";
        }
        return chatMessage.getConversationType();
    }

    public String targetAgentIdFromScopeKey(String scopeKey) {
        if (StringUtil.isBlank(scopeKey)) {
            return null;
        }
        String marker = ":agent:";
        int nestedIndex = scopeKey.lastIndexOf(marker);
        if (nestedIndex >= 0) {
            return scopeKey.substring(nestedIndex + marker.length());
        }
        String prefix = "agent:";
        return scopeKey.startsWith(prefix) ? scopeKey.substring(prefix.length()) : null;
    }

    public List<String> normalizeAgentIds(List<?> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            normalized.add(requireScopeId(String.valueOf(value)));
        }
        return new ArrayList<>(normalized);
    }

    private List<String> normalizeAuthoritativeAgentIds(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                throw denied();
            }
            normalized.add(requireScopeId(value));
        }
        return List.copyOf(normalized);
    }

    public List<?> metadataList(ChatMessageDTO chatMessage, String key) {
        Object value = metadataValue(chatMessage, key);
        if (value instanceof List<?> list) {
            return list;
        }
        if (value instanceof String text && StringUtil.isNotBlank(text)) {
            return List.of(text.split(","));
        }
        return List.of();
    }

    public Object metadataValue(ChatMessageDTO chatMessage, String key) {
        if (chatMessage == null || chatMessage.getMetadata() == null) {
            return null;
        }
        return chatMessage.getMetadata().get(key);
    }

    private JuyitingConversationScope withTargets(
            JuyitingConversationScope requested,
            List<String> targets,
            List<String> authoritative) {
        String selected = targets.isEmpty() ? null : targets.getFirst();
        return new JuyitingConversationScope(
                requested.scopeType(), requested.scopeKey(), requested.taskId(), selected,
                List.copyOf(targets), List.copyOf(authoritative));
    }

    private void requireExpectedTaskScope(JuyitingConversationScope requested, String taskId) {
        String expected;
        if (SCOPE_PRIVATE.equals(requested.scopeType())) {
            String target = requireScopeId(requested.targetAgentId());
            expected = "task:" + taskId + ":agent:" + target;
        } else {
            expected = "task:" + taskId;
        }
        if (!expected.equals(requested.scopeKey())) {
            throw denied();
        }
    }

    private String resolveConversationScopeType(ChatMessageDTO chatMessage) {
        if (chatMessage == null || !CONVERSATION_TYPE_JUYITING.equals(resolveConversationType(chatMessage))) {
            return null;
        }
        if (StringUtil.isNotBlank(chatMessage.getConversationScopeType())) {
            return chatMessage.getConversationScopeType();
        }
        Object mode = metadataValue(chatMessage, "mode");
        if (mode != null && StringUtil.isNotBlank(String.valueOf(mode))) {
            return String.valueOf(mode);
        }
        return SCOPE_PUBLIC;
    }

    private String resolveConversationScopeKey(
            ChatMessageDTO chatMessage, String scopeType, String taskId, String targetAgentId) {
        if (chatMessage == null || !CONVERSATION_TYPE_JUYITING.equals(resolveConversationType(chatMessage))) {
            return null;
        }
        if (StringUtil.isNotBlank(chatMessage.getConversationScopeKey())) {
            return chatMessage.getConversationScopeKey().strip();
        }
        Object scopeKey = metadataValue(chatMessage, "scopeKey");
        if (scopeKey != null && StringUtil.isNotBlank(String.valueOf(scopeKey))) {
            return String.valueOf(scopeKey).strip();
        }
        if (StringUtil.isNotBlank(taskId) && !SCOPE_PRIVATE.equals(scopeType)) {
            return "task:" + taskId;
        }
        if (SCOPE_PRIVATE.equals(scopeType)) {
            if (StringUtil.isNotBlank(taskId) && StringUtil.isNotBlank(targetAgentId)) {
                return "task:" + taskId + ":agent:" + targetAgentId;
            }
            if (StringUtil.isNotBlank(targetAgentId)) {
                return "agent:" + targetAgentId;
            }
        }
        return SCOPE_PUBLIC;
    }

    private String resolveTaskId(ChatMessageDTO chatMessage) {
        if (chatMessage == null) {
            return null;
        }
        if (StringUtil.isNotBlank(chatMessage.getTaskId())) {
            return chatMessage.getTaskId();
        }
        Object selectedTaskId = metadataValue(chatMessage, "selectedTaskId");
        return selectedTaskId == null || StringUtil.isBlank(String.valueOf(selectedTaskId))
                ? null : String.valueOf(selectedTaskId);
    }

    private List<String> resolveTargetAgentIds(
            ChatMessageDTO chatMessage, String scopeType, String taskId) {
        if (chatMessage == null || !CONVERSATION_TYPE_JUYITING.equals(resolveConversationType(chatMessage))) {
            return List.of();
        }
        List<String> explicitTargets = normalizeAgentIds(chatMessage.getTargetAgentIds());
        if (explicitTargets.isEmpty() && StringUtil.isNotBlank(chatMessage.getTargetAgentId())) {
            explicitTargets = normalizeAgentIds(List.of(chatMessage.getTargetAgentId()));
        }
        if (explicitTargets.isEmpty()) {
            explicitTargets = normalizeAgentIds(metadataList(chatMessage, "targetAgentIds"));
        }
        if (explicitTargets.isEmpty()) {
            explicitTargets = normalizeAgentIds(metadataList(chatMessage, "mentionAgentIds"));
        }
        if (explicitTargets.isEmpty()) {
            Object selectedAgentId = metadataValue(chatMessage, "selectedAgentId");
            if (selectedAgentId != null && StringUtil.isNotBlank(String.valueOf(selectedAgentId))) {
                explicitTargets = normalizeAgentIds(List.of(String.valueOf(selectedAgentId)));
            }
        }
        if (!explicitTargets.isEmpty()) {
            return explicitTargets;
        }
        if (SCOPE_PRIVATE.equals(scopeType)) {
            String targetFromScope = targetAgentIdFromScopeKey(
                    resolveConversationScopeKey(chatMessage, scopeType, taskId, null));
            return StringUtil.isBlank(targetFromScope)
                    ? List.of() : List.of(requireScopeId(targetFromScope));
        }
        // Task-scoped public/bounty requests default to the authoritative member set in authorize().
        if (StringUtil.isNotBlank(taskId) || SCOPE_BOUNTY.equals(scopeType)) {
            return List.of();
        }
        return List.of(requireScopeId(builtinHallAgentSupport.defaultAgentId()));
    }

    private String normalizeScopeType(String value) {
        return value == null ? null : value.strip().toLowerCase(java.util.Locale.ROOT);
    }

    private String normalizeOptionalScopeId(String value) {
        return StringUtil.isBlank(value) ? null : requireScopeId(value);
    }

    private String requireScopeId(String value) {
        if (value == null) {
            throw denied();
        }
        String normalized = value.strip();
        if (normalized.isBlank() || normalized.length() > MAX_SCOPE_ID_LENGTH
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw denied();
        }
        return normalized;
    }

    private boolean isCanonicalIdentity(String value) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.length() <= 50 && value.chars().noneMatch(Character::isISOControl);
    }

    private boolean isSupportedScope(String scopeType) {
        return SCOPE_PUBLIC.equals(scopeType)
                || SCOPE_BOUNTY.equals(scopeType)
                || SCOPE_PRIVATE.equals(scopeType);
    }

    private IllegalStateException denied() {
        return new IllegalStateException("Juyiting conversation scope is unavailable");
    }
}
