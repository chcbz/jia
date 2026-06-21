package cn.jia.chat.service;

import cn.jia.chat.handler.dto.ChatMessageDTO;
import cn.jia.core.util.StringUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class JuyitingConversationScopeService {
    public static final String CONVERSATION_TYPE_JUYITING = "juyiting";
    public static final String SCOPE_PUBLIC = "public";
    public static final String SCOPE_BOUNTY = "bounty";
    public static final String SCOPE_PRIVATE = "private";

    private final BuiltinHallAgentSupport builtinHallAgentSupport;

    public JuyitingConversationScope resolve(ChatMessageDTO chatMessage) {
        String scopeType = resolveConversationScopeType(chatMessage);
        String taskId = resolveTaskId(chatMessage);
        List<String> targetAgentIds = resolveTargetAgentIds(chatMessage, scopeType);
        String targetAgentId = targetAgentIds.isEmpty() ? null : targetAgentIds.getFirst();
        String scopeKey = resolveConversationScopeKey(chatMessage, scopeType, taskId, targetAgentId);
        if (SCOPE_PRIVATE.equals(scopeType) && targetAgentIds.isEmpty()) {
            String targetFromScope = targetAgentIdFromScopeKey(scopeKey);
            if (StringUtil.isNotBlank(targetFromScope)) {
                targetAgentIds = List.of(targetFromScope);
                targetAgentId = targetFromScope;
            }
        }
        return new JuyitingConversationScope(scopeType, scopeKey, taskId, targetAgentId, targetAgentIds);
    }

    public String resolveConversationType(ChatMessageDTO chatMessage) {
        if (chatMessage == null || StringUtil.isBlank(chatMessage.getConversationType())) {
            return "normal";
        }
        return chatMessage.getConversationType();
    }

    public Optional<String> invalidBountyTarget(ChatMessageDTO chatMessage, JuyitingConversationScope scope) {
        if (scope == null || !SCOPE_BOUNTY.equals(scope.scopeType())) {
            return Optional.empty();
        }
        List<String> participantAgentIds = normalizeAgentIds(metadataList(chatMessage, "participantAgentIds"));
        if (participantAgentIds.isEmpty()) {
            return Optional.empty();
        }
        Set<String> participants = new LinkedHashSet<>(participantAgentIds);
        return scope.targetAgentIds().stream()
                .filter(agentId -> !participants.contains(agentId))
                .findFirst();
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
            String text = String.valueOf(value).trim();
            if (StringUtil.isNotBlank(text)) {
                normalized.add(text);
            }
        }
        return new ArrayList<>(normalized);
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

    private String resolveConversationScopeKey(ChatMessageDTO chatMessage, String scopeType, String taskId, String targetAgentId) {
        if (chatMessage == null || !CONVERSATION_TYPE_JUYITING.equals(resolveConversationType(chatMessage))) {
            return null;
        }
        if (StringUtil.isNotBlank(chatMessage.getConversationScopeKey())) {
            return chatMessage.getConversationScopeKey();
        }
        Object scopeKey = metadataValue(chatMessage, "scopeKey");
        if (scopeKey != null && StringUtil.isNotBlank(String.valueOf(scopeKey))) {
            return String.valueOf(scopeKey);
        }
        if (SCOPE_BOUNTY.equals(scopeType) && StringUtil.isNotBlank(taskId)) {
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
                ? null
                : String.valueOf(selectedTaskId);
    }

    private List<String> resolveTargetAgentIds(ChatMessageDTO chatMessage, String scopeType) {
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
        if (SCOPE_BOUNTY.equals(scopeType)) {
            return normalizeAgentIds(metadataList(chatMessage, "participantAgentIds"));
        }
        if (SCOPE_PRIVATE.equals(scopeType)) {
            String targetFromScope = targetAgentIdFromScopeKey(resolveConversationScopeKey(chatMessage, scopeType, resolveTaskId(chatMessage), null));
            return StringUtil.isBlank(targetFromScope) ? List.of() : List.of(targetFromScope);
        }
        return List.of(builtinHallAgentSupport.defaultAgentId());
    }
}
