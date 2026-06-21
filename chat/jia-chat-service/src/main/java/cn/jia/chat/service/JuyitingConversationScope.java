package cn.jia.chat.service;

import java.util.List;

public record JuyitingConversationScope(
        String scopeType,
        String scopeKey,
        String taskId,
        String targetAgentId,
        List<String> targetAgentIds
) {
}
