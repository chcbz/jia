package cn.jia.chat.service;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.service.AgentService;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.handler.AgentWebSocketHandler;
import cn.jia.chat.handler.dto.ChatMessageDTO;
import cn.jia.core.tenant.UserAdmissionScope;
import cn.jia.core.util.JsonUtil;
import cn.jia.core.util.StringUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
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

    public JuyitingAgentRelayResult relay(UserAdmissionScope admission, ChatMessageDTO chatMessage, String conversationId, Supplier<Flux<String>> builtinAgentStream) {
        JuyitingConversationScope requestedScope = scopeService.resolve(chatMessage);
        if (!JuyitingConversationScopeService.CONVERSATION_TYPE_JUYITING.equals(scopeService.resolveConversationType(chatMessage))
                || requestedScope.scopeType() == null) {
            return new JuyitingAgentRelayResult(false, Mono.just(false), Flux.empty());
        }

        String ownerJiacn = requireIdentityPart(admission.businessScope().ownerJiacn());
        String ownerClientId = requireIdentityPart(admission.businessScope().callerClientId());
        JuyitingConversationScope scope;
        try {
            scope = scopeService.authorize(
                    chatMessage, requestedScope, admission);
        } catch (RuntimeException denied) {
            return deniedScope(conversationId, requestedScope);
        }
        ChatConversationEntity conversation = chatConversationService.get(admission, conversationId);
        if (!conversationMatchesScope(conversation, scope)) {
            return new JuyitingAgentRelayResult(true, Mono.just(true), Flux.just(JsonUtil.toSafeJson(Map.of(
                    "error", "conversation scope mismatch",
                    "conversationId", conversationId,
                    "conversationType", JuyitingConversationScopeService.CONVERSATION_TYPE_JUYITING
            ))));
        }
        long generation = lifecycleGeneration(conversation);

        Optional<String> forbiddenTarget = forbiddenOwnedRosterTarget(
                admission, scope, ownerJiacn, ownerClientId);
        if (forbiddenTarget.isPresent()) {
            return new JuyitingAgentRelayResult(true, Mono.just(true), Flux.just(JsonUtil.toSafeJson(Map.of(
                    "error", "target outside owned roster",
                    "agentId", forbiddenTarget.get(),
                    "conversationId", conversationId,
                    "conversationType", JuyitingConversationScopeService.CONVERSATION_TYPE_JUYITING,
                    "conversationScopeType", scope.scopeType(),
                    "conversationScopeKey", scope.scopeKey()
            ))));
        }

        saveDirectUserMessage(
                admission, chatMessage, conversationId, scope, ownerJiacn, ownerClientId, generation);

        if (scope.targetAgentIds().size() > 1) {
            return relayMultiTargetAgentMessage(
                    admission, chatMessage, conversationId, scope, ownerJiacn, ownerClientId, generation);
        }

        String selectedAgentId = scope.targetAgentIds().getFirst();
        if (builtinHallAgentSupport.isBuiltinAgent(selectedAgentId)) {
            return new JuyitingAgentRelayResult(
                    true, Mono.just(true), builtinAgentStream.get().takeUntilOther(
                    chatConversationEventBroker.deletionSignal(
                            conversationId, generation,
                            () -> chatConversationService.isLiveGeneration(
                                    admission, conversationId, generation))));
        }

        Map<String, Object> payload = buildDirectAgentPayload(chatMessage, conversationId, selectedAgentId, scope);
        Sinks.One<Boolean> deliveryOutcome = Sinks.one();
        Flux<String> stream = Flux.defer(() -> {
            Sinks.Many<String> bufferedAgentEvents =
                    Sinks.many().unicast().onBackpressureBuffer(
                            new ArrayBlockingQueue<>(ChatStreamPolicy.OUTBOUND_BUFFER_LIMIT));
            final Disposable[] subscriptionRef = new Disposable[1];
            Disposable disposable = chatConversationEventBroker.stream(
                            conversationId, generation,
                            () -> chatConversationService.isLiveGeneration(
                                    admission, conversationId, generation))
                    .filter(this::isDirectAgentEvent)
                    .subscribe(eventJson -> {
                        Sinks.EmitResult emitResult = bufferedAgentEvents.tryEmitNext(eventJson);
                        if (emitResult == Sinks.EmitResult.FAIL_OVERFLOW) {
                            bufferedAgentEvents.tryEmitError(new IllegalStateException(
                                    "Agent event buffer overflow"));
                        }
                        if (isDirectAgentFinalEvent(eventJson)) {
                            bufferedAgentEvents.tryEmitComplete();
                            Disposable current = subscriptionRef[0];
                            if (current != null && !current.isDisposed()) {
                                current.dispose();
                            }
                        }
                    }, bufferedAgentEvents::tryEmitError);
            subscriptionRef[0] = disposable;

            AtomicBoolean sent = new AtomicBoolean();
            boolean live = chatConversationEventBroker.runIfLive(
                    conversationId, generation,
                    () -> chatConversationService.isLiveGeneration(
                                    admission, conversationId, generation),
                    () -> {
                        agentService.requireHostingNewWork(
                                ownerJiacn, ownerClientId, selectedAgentId);
                        sent.set(agentWebSocketHandler.sendDirectMessageToAgent(
                                ownerJiacn, ownerClientId, selectedAgentId, payload));
                    });
            deliveryOutcome.tryEmitValue(live && sent.get());
            if (!live) {
                disposable.dispose();
                bufferedAgentEvents.tryEmitComplete();
                return Flux.<String>empty();
            }

            Flux<String> deliveryEvent = Flux.just(buildAgentDeliveryEventJson(
                    conversationId, selectedAgentId, sent.get()));
            if (!sent.get()) {
                disposable.dispose();
                bufferedAgentEvents.tryEmitComplete();
                return deliveryEvent;
            }

            return deliveryEvent
                    .concatWith(bufferedAgentEvents.asFlux())
                    .doFinally(ignored -> disposable.dispose());
        })
                .transform(ChatStreamPolicy::bounded)
                .takeUntilOther(chatConversationEventBroker.deletionSignal(
                        conversationId, generation,
                        () -> chatConversationService.isLiveGeneration(
                                    admission, conversationId, generation)))
                .doFinally(ignored -> deliveryOutcome.tryEmitValue(false));
        return new JuyitingAgentRelayResult(
                true, deliveryOutcome.asMono().defaultIfEmpty(false), stream);
    }

    public String selectedAgentId(ChatMessageDTO chatMessage) {
        JuyitingConversationScope scope = scopeService.resolve(chatMessage);
        return scope.targetAgentId();
    }

    private Optional<String> forbiddenOwnedRosterTarget(
            UserAdmissionScope admission, JuyitingConversationScope scope, String ownerJiacn, String ownerClientId) {
        for (String agentId : scope.targetAgentIds()) {
            if (builtinHallAgentSupport.isBuiltinAgent(agentId)) {
                continue;
            }
            if (!"0".equals(admission.businessScope().tenantId().value())) {
                return Optional.of(agentId);
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
            UserAdmissionScope admission, ChatMessageDTO chatMessage, String conversationId, JuyitingConversationScope scope,
            String ownerJiacn, String ownerClientId, long generation) {
        Sinks.One<Boolean> deliveryOutcome = Sinks.one();
        Flux<String> events = Flux.defer(() -> {
            List<String> deliveryEvents = new ArrayList<>();
            AtomicBoolean anyDelivered = new AtomicBoolean();
            for (String agentId : scope.targetAgentIds()) {
                AtomicBoolean delivered = new AtomicBoolean();
                boolean live = chatConversationEventBroker.runIfLive(
                        conversationId, generation,
                        () -> chatConversationService.isLiveGeneration(
                                    admission, conversationId, generation),
                        () -> {
                            agentService.requireHostingNewWork(
                                    ownerJiacn, ownerClientId, agentId);
                            delivered.set(agentWebSocketHandler.sendDirectMessageToAgent(
                                    ownerJiacn, ownerClientId, agentId,
                                    buildDirectAgentPayload(
                                            chatMessage, conversationId, agentId, scope)));
                            if (delivered.get()) {
                                anyDelivered.set(true);
                            }
                        });
                if (!live) {
                    break;
                }
                deliveryEvents.add(buildAgentDeliveryEventJson(
                        conversationId, agentId, delivered.get()));
            }
            deliveryOutcome.tryEmitValue(anyDelivered.get());
            return Flux.fromIterable(deliveryEvents);
        }).takeUntilOther(chatConversationEventBroker.deletionSignal(
                        conversationId, generation,
                        () -> chatConversationService.isLiveGeneration(
                                    admission, conversationId, generation)))
                .doFinally(ignored -> deliveryOutcome.tryEmitValue(false));
        return new JuyitingAgentRelayResult(
                true, deliveryOutcome.asMono().defaultIfEmpty(false), events);
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
            UserAdmissionScope admission, ChatMessageDTO chatMessage, String conversationId, JuyitingConversationScope scope,
            String ownerJiacn, String ownerClientId, long generation) {
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
        chatConversationService.appendOwnedMessage(
                admission, entity, generation);
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
        if (!scope.authoritativeAgentIds().isEmpty()) {
            metadata.put("participantAgentIds", scope.authoritativeAgentIds());
        }
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
        if (trustedTarget != null && !trustedTarget.isBlank()
                && requested.targetAgentIds().stream().anyMatch(
                agentId -> !trustedTarget.equals(agentId))) {
            return false;
        }
        List<String> persistedTargets;
        try {
            persistedTargets = scopeService.parsePersistedTargetAgentIds(
                    conversation.getTargetAgentIds());
        } catch (RuntimeException invalidPersistedScope) {
            return false;
        }
        return !persistedTargets.isEmpty()
                && persistedTargets.containsAll(requested.targetAgentIds());
    }

    private JuyitingAgentRelayResult deniedScope(
            String conversationId, JuyitingConversationScope scope) {
        Map<String, Object> event = new HashMap<>();
        event.put("error", "task conversation scope unavailable");
        event.put("conversationId", conversationId);
        event.put("conversationType", JuyitingConversationScopeService.CONVERSATION_TYPE_JUYITING);
        if (scope != null) {
            event.put("conversationScopeType", scope.scopeType());
            event.put("conversationScopeKey", scope.scopeKey());
        }
        return new JuyitingAgentRelayResult(
                true, Mono.just(true), Flux.just(JsonUtil.toSafeJson(event)));
    }

    private long lifecycleGeneration(ChatConversationEntity conversation) {
        Long generation = conversation == null ? null : conversation.getLifecycleGeneration();
        if (generation == null) {
            return 1L;
        }
        if (generation < 1) {
            throw new IllegalStateException("Conversation generation is unavailable");
        }
        return generation;
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
