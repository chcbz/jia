package cn.jia.chat.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Type-bounded allowlist for metadata persisted or relayed across asynchronous boundaries. */
public final class ConversationMetadataPolicy {
    private static final int MAX_SCALAR_LENGTH = 512;
    private static final int MAX_LIST_ITEMS = 100;
    private static final Set<String> SAFE_KEYS = Set.of(
            "schemaVersion", "messageId", "commandId", "correlationId", "causationId",
            "requestId", "conversationId", "conversationType", "conversationScopeType",
            "conversationScopeKey", "scopeKey", "mode", "taskId", "selectedTaskId",
            "workItemId", "sourceAgentId", "targetAgentId", "targetAgentIds",
            "participantAgentIds", "agentId", "selectedAgentId", "senderType", "senderName",
            "phase", "chunkIndex", "chunkCount");

    private ConversationMetadataPolicy() {
    }

    public static Map<String, Object> copyAllowed(Map<?, ?> source) {
        Map<String, Object> safe = new HashMap<>();
        if (source == null) {
            return safe;
        }
        source.forEach((key, value) -> {
            if (!(key instanceof String text) || !SAFE_KEYS.contains(text)) {
                return;
            }
            Object safeValue = copySafeValue(value);
            if (safeValue != null) {
                safe.put(text, safeValue);
            }
        });
        return safe;
    }

    private static Object copySafeValue(Object value) {
        if (value instanceof String text) {
            return isSafeText(text) ? text : null;
        }
        if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return value;
        }
        if (value instanceof Collection<?> values && values.size() <= MAX_LIST_ITEMS) {
            ArrayList<String> safe = new ArrayList<>(values.size());
            for (Object item : values) {
                if (!(item instanceof String text) || !isSafeText(text)) {
                    return null;
                }
                safe.add(text);
            }
            return List.copyOf(safe);
        }
        return null;
    }

    private static boolean isSafeText(String value) {
        return value.length() <= MAX_SCALAR_LENGTH
                && value.chars().noneMatch(Character::isISOControl);
    }
}
