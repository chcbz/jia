package cn.jia.chat.service;

import cn.jia.chat.handler.AgentWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class HallActionDispatcher {
    public static final String STATUS_DISPATCHED = "dispatched";
    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_FAILED = "failed";

    private final AgentWebSocketHandler agentWebSocketHandler;
    private final Map<String, List<HallAgentMailboxItem>> mailbox = new ConcurrentHashMap<>();

    public HallActionDispatchResult dispatch(HallActionIntent intent) {
        if (intent == null || !StringUtils.hasText(intent.getActorAgentId())) {
            return new HallActionDispatchResult(null, null, STATUS_FAILED, "actorAgentId is required");
        }
        String agentId = intent.getActorAgentId();
        Map<String, Object> payload = buildPayload(intent);
        if (!agentWebSocketHandler.isAgentConnected(agentId)) {
            queue(agentId, intent, payload);
            intent.setStatus(STATUS_QUEUED);
            return new HallActionDispatchResult(intent.getIntentId(), agentId, STATUS_QUEUED, "agent is offline");
        }
        boolean delivered = agentWebSocketHandler.sendDirectMessageToAgent(agentId, payload);
        if (!delivered) {
            queue(agentId, intent, payload);
            intent.setStatus(STATUS_QUEUED);
            return new HallActionDispatchResult(intent.getIntentId(), agentId, STATUS_QUEUED, "agent delivery failed");
        }
        intent.setStatus(STATUS_DISPATCHED);
        return new HallActionDispatchResult(intent.getIntentId(), agentId, STATUS_DISPATCHED, "dispatched");
    }

    public List<HallAgentMailboxItem> mailbox(String agentId) {
        return mailbox.getOrDefault(agentId, List.of());
    }

    private Map<String, Object> buildPayload(HallActionIntent intent) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "agent.action");
        payload.put("requestId", intent.getIntentId());
        payload.put("conversationId", intent.getConversationId());
        payload.put("conversationType", "juyiting");
        payload.put("agentId", intent.getActorAgentId());
        payload.put("actionType", intent.getActionType());
        payload.put("content", intent.getInstruction());

        Map<String, Object> metadata = new HashMap<>();
        putIfPresent(metadata, "triggerEventId", intent.getTriggerEventId());
        putIfPresent(metadata, "taskId", intent.getTaskId());
        putIfPresent(metadata, "targetAgentIds", intent.getTargetAgentIds());
        putIfPresent(metadata, "reason", intent.getReason());
        putIfPresent(metadata, "autonomyLevel", intent.getAutonomyLevel());
        putIfPresent(metadata, "requiresApproval", intent.getRequiresApproval());
        if (intent.getContext() != null) {
            metadata.put("context", intent.getContext());
        }
        metadata.put("autonomy", true);
        payload.put("metadata", metadata);
        return payload;
    }

    private void queue(String agentId, HallActionIntent intent, Map<String, ?> payload) {
        HallAgentMailboxItem item = new HallAgentMailboxItem();
        item.setIntentId(intent.getIntentId());
        item.setAgentId(agentId);
        item.setPayload(payload);
        item.setStatus("pending");
        item.setCreateTime(System.currentTimeMillis());
        item.setUpdateTime(item.getCreateTime());
        mailbox.computeIfAbsent(agentId, key -> new ArrayList<>()).add(item);
    }

    private void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
