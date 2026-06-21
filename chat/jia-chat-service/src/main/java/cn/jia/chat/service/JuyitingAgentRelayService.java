package cn.jia.chat.service;

import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.handler.AgentWebSocketHandler;
import cn.jia.chat.handler.dto.ChatMessageDTO;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.util.JsonUtil;
import cn.jia.core.util.StringUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class JuyitingAgentRelayService {
    private final AgentWebSocketHandler agentWebSocketHandler;
    private final ChatConversationEventBroker chatConversationEventBroker;
    private final BuiltinHallAgentSupport builtinHallAgentSupport;
    private final ChatMessageDao chatMessageDao;
    private final JuyitingConversationScopeService scopeService;

    public JuyitingAgentRelayResult relay(ChatMessageDTO chatMessage, String conversationId, Supplier<Flux<String>> builtinAgentStream) {
        JuyitingConversationScope scope = scopeService.resolve(chatMessage);
        if (!JuyitingConversationScopeService.CONVERSATION_TYPE_JUYITING.equals(scopeService.resolveConversationType(chatMessage))
                || scope.targetAgentIds().isEmpty()) {
            return new JuyitingAgentRelayResult(false, false, Flux.empty());
        }

        Optional<String> invalidBountyTarget = scopeService.invalidBountyTarget(chatMessage, scope);
        if (invalidBountyTarget.isPresent()) {
            return new JuyitingAgentRelayResult(true, true, Flux.just(JsonUtil.toSafeJson(Map.of(
                    "error", "target outside bounty participants",
                    "agentId", invalidBountyTarget.get(),
                    "conversationId", conversationId,
                    "conversationType", JuyitingConversationScopeService.CONVERSATION_TYPE_JUYITING,
                    "conversationScopeType", scope.scopeType(),
                    "conversationScopeKey", scope.scopeKey()
            ))));
        }

        saveDirectUserMessage(chatMessage, conversationId, scope);

        if (scope.targetAgentIds().size() > 1) {
            return relayMultiTargetAgentMessage(chatMessage, conversationId, scope);
        }

        String selectedAgentId = scope.targetAgentIds().getFirst();
        if (builtinHallAgentSupport.isBuiltinAgent(selectedAgentId)) {
            return new JuyitingAgentRelayResult(true, true, builtinAgentStream.get());
        }

        Map<String, Object> payload = buildDirectAgentPayload(chatMessage, conversationId, selectedAgentId, scope);
        boolean delivered = agentWebSocketHandler.isAgentConnected(selectedAgentId);
        Flux<String> stream = delivered
                ? Flux.create(emitter -> {
                    final Disposable[] subscriptionRef = new Disposable[1];
                    Disposable disposable = chatConversationEventBroker.stream(conversationId)
                            .filter(this::isDirectAgentEvent)
                            .subscribe(eventJson -> {
                                emitter.next(eventJson);
                                if (isDirectAgentFinalEvent(eventJson)) {
                                    Disposable current = subscriptionRef[0];
                                    if (current != null && !current.isDisposed()) {
                                        current.dispose();
                                    }
                                    emitter.complete();
                                }
                            }, emitter::error);
                    subscriptionRef[0] = disposable;

                    boolean sent = agentWebSocketHandler.sendDirectMessageToAgent(selectedAgentId, payload);
                    emitter.next(buildAgentDeliveryEventJson(conversationId, selectedAgentId, sent));

                    if (!sent) {
                        disposable.dispose();
                        emitter.complete();
                    }

                    emitter.onDispose(disposable);
                }, FluxSink.OverflowStrategy.BUFFER)
                : Flux.just(buildAgentDeliveryEventJson(conversationId, selectedAgentId, false));
        return new JuyitingAgentRelayResult(true, delivered, stream);
    }

    public String selectedAgentId(ChatMessageDTO chatMessage) {
        JuyitingConversationScope scope = scopeService.resolve(chatMessage);
        return scope.targetAgentId();
    }

    private JuyitingAgentRelayResult relayMultiTargetAgentMessage(ChatMessageDTO chatMessage, String conversationId, JuyitingConversationScope scope) {
        List<String> events = new ArrayList<>();
        boolean anyDelivered = false;
        for (String agentId : scope.targetAgentIds()) {
            Map<String, Object> payload = buildDirectAgentPayload(chatMessage, conversationId, agentId, scope);
            boolean delivered = agentWebSocketHandler.isAgentConnected(agentId)
                    && agentWebSocketHandler.sendDirectMessageToAgent(agentId, payload);
            anyDelivered = anyDelivered || delivered;
            events.add(buildAgentDeliveryEventJson(conversationId, agentId, delivered));
        }
        return new JuyitingAgentRelayResult(true, anyDelivered, Flux.fromIterable(events));
    }

    private Map<String, Object> buildDirectAgentPayload(ChatMessageDTO chatMessage, String conversationId, String agentId, JuyitingConversationScope scope) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("conversationId", conversationId);
        payload.put("conversationType", JuyitingConversationScopeService.CONVERSATION_TYPE_JUYITING);
        payload.put("conversationScopeType", scope.scopeType());
        payload.put("conversationScopeKey", scope.scopeKey());
        payload.put("agentId", agentId);
        payload.put("content", chatMessage.getContent());
        payload.put("senderType", Optional.ofNullable(chatMessage.getSenderType()).orElse("user"));
        payload.put("senderName", Optional.ofNullable(chatMessage.getSenderName()).orElse("用户"));
        payload.put("metadata", Optional.ofNullable(chatMessage.getMetadata()).orElse(Map.of()));
        payload.put("timestamp", System.currentTimeMillis());
        return payload;
    }

    private void saveDirectUserMessage(ChatMessageDTO chatMessage, String conversationId, JuyitingConversationScope scope) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.init4Creation();
        entity.setJiacn(Optional.ofNullable(EsContextHolder.getContext().getJiacn()).orElse("Anonymous"));
        entity.setClientId(Optional.ofNullable(EsContextHolder.getContext().getClientId()).orElse("jia_client"));
        entity.setConversationId(conversationId);
        entity.setMessageType("USER");
        entity.setContent(chatMessage.getContent());
        entity.setSyncStatus("PENDING");
        entity.setConversationType(JuyitingConversationScopeService.CONVERSATION_TYPE_JUYITING);
        entity.setSenderType(Optional.ofNullable(chatMessage.getSenderType()).orElse("user"));
        entity.setSenderName(Optional.ofNullable(chatMessage.getSenderName()).orElse("用户"));

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("conversationId", conversationId);
        metadata.put("conversationType", JuyitingConversationScopeService.CONVERSATION_TYPE_JUYITING);
        metadata.put("selectedAgentId", scope.targetAgentId());
        metadata.put("mode", scope.scopeType());
        metadata.put("scopeKey", scope.scopeKey());
        metadata.put("conversationScopeType", scope.scopeType());
        metadata.put("conversationScopeKey", scope.scopeKey());
        metadata.put("targetAgentIds", scope.targetAgentIds());
        metadata.put("selectedTaskId", scope.taskId());
        if (chatMessage.getMetadata() != null) {
            metadata.putAll(chatMessage.getMetadata());
        }
        entity.setMetadata(JsonUtil.toJson(metadata));
        chatMessageDao.insert(entity);
    }

    private boolean isDirectAgentEvent(String eventJson) {
        if (StringUtil.isBlank(eventJson)) {
            return false;
        }
        return eventJson.contains("\"type\":\"agent_message_delta\"")
                || eventJson.contains("\"type\":\"agent_message\"");
    }

    private boolean isDirectAgentFinalEvent(String eventJson) {
        return StringUtil.isNotBlank(eventJson) && eventJson.contains("\"type\":\"agent_message\"");
    }

    private String buildAgentDeliveryEventJson(String conversationId, String agentId, boolean delivered) {
        return "{\"agentDelivery\":{\"agentId\":\"" + escapeJson(agentId)
                + "\",\"delivered\":" + delivered + "},\"conversationId\":\""
                + escapeJson(conversationId) + "\",\"conversationType\":\""
                + JuyitingConversationScopeService.CONVERSATION_TYPE_JUYITING + "\"}";
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
