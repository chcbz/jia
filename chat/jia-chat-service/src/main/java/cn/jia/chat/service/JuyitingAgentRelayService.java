package cn.jia.chat.service;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.service.AgentService;
import cn.jia.chat.entity.ChatConversationEntity;
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
import java.util.UUID;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class JuyitingAgentRelayService {
    private final AgentWebSocketHandler agentWebSocketHandler;
    private final ChatConversationEventBroker chatConversationEventBroker;
    private final BuiltinHallAgentSupport builtinHallAgentSupport;
    private final ChatConversationService chatConversationService;
    private final AgentService agentService;
    private final JuyitingConversationScopeService scopeService;

    public JuyitingAgentRelayResult relay(ChatMessageDTO chatMessage, String conversationId, Supplier<Flux<String>> builtinAgentStream) {
        JuyitingConversationScope scope = scopeService.resolve(chatMessage);
        if (!JuyitingConversationScopeService.CONVERSATION_TYPE_JUYITING.equals(scopeService.resolveConversationType(chatMessage))
                || scope.targetAgentIds().isEmpty()) {
            return new JuyitingAgentRelayResult(false, false, Flux.empty());
        }

        String ownerJiacn = requireIdentityPart(EsContextHolder.getContext().getJiacn());
        String ownerClientId = requireIdentityPart(EsContextHolder.getContext().getClientId());
        ChatConversationEntity conversation = chatConversationService.getOwned(
                ownerJiacn, ownerClientId, conversationId);
        if (!conversationMatchesScope(conversation, scope)) {
            return new JuyitingAgentRelayResult(true, true, Flux.just(JsonUtil.toSafeJson(Map.of(
                    "error", "conversation scope mismatch",
                    "conversationId", conversationId,
                    "conversationType", JuyitingConversationScopeService.CONVERSATION_TYPE_JUYITING
            ))));
        }

        Optional<String> forbiddenTarget = forbiddenOwnedRosterTarget(
                scope, ownerJiacn, ownerClientId);
        if (forbiddenTarget.isPresent()) {
            return new JuyitingAgentRelayResult(true, true, Flux.just(JsonUtil.toSafeJson(Map.of(
                    "error", "target outside owned roster",
                    "agentId", forbiddenTarget.get(),
                    "conversationId", conversationId,
                    "conversationType", JuyitingConversationScopeService.CONVERSATION_TYPE_JUYITING,
                    "conversationScopeType", scope.scopeType(),
                    "conversationScopeKey", scope.scopeKey()
            ))));
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

        saveDirectUserMessage(
                chatMessage, conversationId, scope, ownerJiacn, ownerClientId);

        if (scope.targetAgentIds().size() > 1) {
            return relayMultiTargetAgentMessage(
                    chatMessage, conversationId, scope, ownerJiacn, ownerClientId);
        }

        String selectedAgentId = scope.targetAgentIds().getFirst();
        if (builtinHallAgentSupport.isBuiltinAgent(selectedAgentId)) {
            return new JuyitingAgentRelayResult(true, true, builtinAgentStream.get());
        }

        Map<String, Object> payload = buildDirectAgentPayload(chatMessage, conversationId, selectedAgentId, scope);
        String tenantId = ownerJiacn;
        String clientId = ownerClientId;
        boolean delivered = agentWebSocketHandler.isAgentConnected(
                ownerJiacn, ownerClientId, selectedAgentId);
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

                    boolean sent;
                    try {
                        ChatConversationEntity live = chatConversationService.getOwned(
                                tenantId, clientId, conversationId);
                        if (!conversationMatchesScope(live, scope)) {
                            throw new IllegalStateException("Conversation is no longer available");
                        }
                        agentService.requireHostingNewWork(tenantId, clientId, selectedAgentId);
                        sent = agentWebSocketHandler.sendDirectMessageToAgent(
                                tenantId, clientId, selectedAgentId, payload);
                    } catch (RuntimeException denied) {
                        disposable.dispose();
                        emitter.error(denied);
                        return;
                    }
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

    private Optional<String> forbiddenOwnedRosterTarget(
            JuyitingConversationScope scope, String ownerJiacn, String ownerClientId) {
        for (String agentId : scope.targetAgentIds()) {
            if (builtinHallAgentSupport.isBuiltinAgent(agentId)) {
                continue;
            }
            try {
                agentService.get(agentId);
                agentService.requireHostingNewWork(ownerJiacn, ownerClientId, agentId);
            } catch (RuntimeException error) {
                return Optional.of(agentId);
            }
        }
        return Optional.empty();
    }

    private JuyitingAgentRelayResult relayMultiTargetAgentMessage(
            ChatMessageDTO chatMessage, String conversationId, JuyitingConversationScope scope,
            String ownerJiacn, String ownerClientId) {
        List<String> events = new ArrayList<>();
        boolean anyDelivered = false;
        for (String agentId : scope.targetAgentIds()) {
            ChatConversationEntity live = chatConversationService.getOwned(
                    ownerJiacn, ownerClientId, conversationId);
            if (!conversationMatchesScope(live, scope)) {
                throw new IllegalStateException("Conversation is no longer available");
            }
            agentService.requireHostingNewWork(ownerJiacn, ownerClientId, agentId);
            Map<String, Object> payload = buildDirectAgentPayload(
                    chatMessage, conversationId, agentId, scope);
            boolean delivered = agentWebSocketHandler.isAgentConnected(
                    ownerJiacn, ownerClientId, agentId)
                    && agentWebSocketHandler.sendDirectMessageToAgent(
                    ownerJiacn, ownerClientId, agentId, payload);
            anyDelivered = anyDelivered || delivered;
            events.add(buildAgentDeliveryEventJson(conversationId, agentId, delivered));
        }
        return new JuyitingAgentRelayResult(true, anyDelivered, Flux.fromIterable(events));
    }

    private Map<String, Object> buildDirectAgentPayload(ChatMessageDTO chatMessage, String conversationId, String agentId, JuyitingConversationScope scope) {
        long sentAt = System.currentTimeMillis();
        String messageId = UUID.randomUUID().toString();
        Map<String, Object> payload = new HashMap<>();
        payload.put("schemaVersion", AgentProtocolConstants.VERSION_1);
        payload.put("messageId", messageId);
        payload.put("messageType", AgentProtocolConstants.TYPE_CHAT_MESSAGE);
        payload.put("correlationId", conversationId);
        payload.put("targetAgentId", agentId);
        payload.put("conversationId", conversationId);
        payload.put("conversationType", JuyitingConversationScopeService.CONVERSATION_TYPE_JUYITING);
        payload.put("conversationScopeType", scope.scopeType());
        payload.put("conversationScopeKey", scope.scopeKey());
        payload.put("agentId", agentId);
        payload.put("content", chatMessage.getContent());
        payload.put("senderType", Optional.ofNullable(chatMessage.getSenderType()).orElse("user"));
        payload.put("senderName", Optional.ofNullable(chatMessage.getSenderName()).orElse("用户"));
        payload.put("metadata", trustedConversationMetadata(
                chatMessage, conversationId, scope));
        payload.put("sentAt", sentAt);
        payload.put("timestamp", sentAt);

        Map<String, Object> protocolPayload = new HashMap<>();
        protocolPayload.put("content", chatMessage.getContent());
        protocolPayload.put("senderType", Optional.ofNullable(chatMessage.getSenderType()).orElse("user"));
        protocolPayload.put("senderName", Optional.ofNullable(chatMessage.getSenderName()).orElse("用户"));
        protocolPayload.put("conversationScopeType", scope.scopeType());
        protocolPayload.put("conversationScopeKey", scope.scopeKey());
        putIfPresent(protocolPayload, "taskContextId", scope.taskId());
        protocolPayload.put("metadata", trustedConversationMetadata(
                chatMessage, conversationId, scope));
        payload.put("payload", protocolPayload);
        return payload;
    }

    private void saveDirectUserMessage(
            ChatMessageDTO chatMessage, String conversationId, JuyitingConversationScope scope,
            String ownerJiacn, String ownerClientId) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setJiacn(ownerJiacn);
        entity.setClientId(ownerClientId);
        entity.setConversationId(conversationId);
        entity.setMessageType("USER");
        entity.setContent(chatMessage.getContent());
        entity.setSyncStatus("PENDING");
        entity.setConversationType(JuyitingConversationScopeService.CONVERSATION_TYPE_JUYITING);
        entity.setSenderType(Optional.ofNullable(chatMessage.getSenderType()).orElse("user"));
        entity.setSenderName(Optional.ofNullable(chatMessage.getSenderName()).orElse("用户"));

        entity.setMetadata(JsonUtil.toJson(
                trustedConversationMetadata(chatMessage, conversationId, scope)));
        chatConversationService.appendOwnedMessage(ownerJiacn, ownerClientId, entity);
    }

    private Map<String, Object> trustedConversationMetadata(
            ChatMessageDTO chatMessage, String conversationId, JuyitingConversationScope scope) {
        Map<String, Object> metadata = new HashMap<>(
                ConversationMetadataPolicy.copyAllowed(chatMessage.getMetadata()));
        // Trusted conversation scope always wins over caller-provided metadata aliases.
        metadata.put("conversationId", conversationId);
        metadata.put("conversationType", JuyitingConversationScopeService.CONVERSATION_TYPE_JUYITING);
        metadata.put("selectedAgentId", scope.targetAgentId());
        metadata.put("mode", scope.scopeType());
        metadata.put("scopeKey", scope.scopeKey());
        metadata.put("conversationScopeType", scope.scopeType());
        metadata.put("conversationScopeKey", scope.scopeKey());
        metadata.put("targetAgentIds", scope.targetAgentIds());
        metadata.put("selectedTaskId", scope.taskId());
        return metadata;
    }

    private boolean conversationMatchesScope(
            ChatConversationEntity conversation, JuyitingConversationScope requested) {
        if (conversation == null
                || !JuyitingConversationScopeService.CONVERSATION_TYPE_JUYITING.equals(
                conversation.getConversationType())
                || !java.util.Objects.equals(conversation.getConversationScopeType(), requested.scopeType())
                || !java.util.Objects.equals(conversation.getConversationScopeKey(), requested.scopeKey())
                || !java.util.Objects.equals(conversation.getTaskId(), requested.taskId())) {
            return false;
        }
        String trustedTarget = conversation.getTargetAgentId();
        return trustedTarget == null || trustedTarget.isBlank()
                || requested.targetAgentIds().stream().allMatch(trustedTarget::equals);
    }

    private String requireIdentityPart(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalStateException("Conversation identity is unavailable");
        }
        return value;
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

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
