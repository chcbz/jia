package cn.jia.chat.service;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.service.AgentService;
import cn.jia.chat.deliberation.InteractionRoute;
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
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
public class JuyitingAgentRelayService {
    private final AgentWebSocketHandler agentWebSocketHandler;
    private final ChatConversationEventBroker chatConversationEventBroker;
    private final BuiltinHallAgentSupport builtinHallAgentSupport;
    private final ChatConversationService chatConversationService;
    private final AgentService agentService;
    private final JuyitingConversationScopeService scopeService;
    private final ChatDeliberationService deliberationService;

    public JuyitingAgentRelayResult relay(ChatMessageDTO chatMessage, String conversationId,
            InteractionRoute route, Function<ChatDeliberationService.Dispatch, Flux<String>> builtinAgentStream) {
        JuyitingConversationScope requestedScope = scopeService.resolve(chatMessage);
        if (!JuyitingConversationScopeService.CONVERSATION_TYPE_JUYITING.equals(scopeService.resolveConversationType(chatMessage))
                || requestedScope.scopeType() == null) {
            return new JuyitingAgentRelayResult(false, Mono.just(false), Flux.empty());
        }

        String ownerJiacn = requireIdentityPart(EsContextHolder.getContext().getJiacn());
        String ownerClientId = requireIdentityPart(EsContextHolder.getContext().getClientId());
        JuyitingConversationScope scope;
        try {
            scope = scopeService.authorize(
                    chatMessage, requestedScope, ownerJiacn, ownerClientId);
        } catch (RuntimeException denied) {
            return deniedScope(conversationId, requestedScope);
        }
        ChatConversationEntity conversation = chatConversationService.getOwned(
                ownerJiacn, ownerClientId, conversationId);
        if (!conversationMatchesScope(conversation, scope)) {
            return new JuyitingAgentRelayResult(true, Mono.just(true), Flux.just(JsonUtil.toSafeJson(Map.of(
                    "error", "conversation scope mismatch",
                    "conversationId", conversationId,
                    "conversationType", JuyitingConversationScopeService.CONVERSATION_TYPE_JUYITING
            ))));
        }
        long generation = lifecycleGeneration(conversation);
        String tenantId = requireIdentityPart(conversation.getTenantId());

        Optional<String> forbiddenTarget = forbiddenOwnedRosterTarget(
                scope, ownerJiacn, ownerClientId);
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

        ChatDeliberationService.Admission admission = deliberationService.admit(
                tenantId, ownerJiacn, ownerClientId, conversationId, generation, scope, route, chatMessage);
        if (admission.replay()) {
            return replayAdmission(admission);
        }

        if (scope.targetAgentIds().size() > 1) {
            return relayMultiTargetAgentMessage(
                    chatMessage, conversationId, scope, tenantId, ownerJiacn, ownerClientId, generation, admission);
        }

        ChatDeliberationService.Dispatch dispatch = admission.dispatches().getFirst();
        String selectedAgentId = dispatch.targetAgentId();
        if (builtinHallAgentSupport.isBuiltinAgent(selectedAgentId)) {
            deliberationService.markDispatch(tenantId, ownerJiacn, ownerClientId, dispatch.turnId(), true);
            return new JuyitingAgentRelayResult(
                    true, Mono.just(true), builtinAgentStream.apply(dispatch).takeUntilOther(
                    chatConversationEventBroker.deletionSignal(
                            conversationId, generation,
                            () -> chatConversationService.isLiveGeneration(
                                    ownerJiacn, ownerClientId, conversationId, generation))));
        }

        Map<String, Object> payload = buildDirectAgentPayload(chatMessage, conversationId, selectedAgentId, scope, admission, dispatch);
        Sinks.One<Boolean> deliveryOutcome = Sinks.one();
        Flux<String> stream = Flux.<String>create(emitter -> {
            final Disposable[] subscriptionRef = new Disposable[1];
            Disposable disposable = chatConversationEventBroker.stream(
                            conversationId, generation,
                            () -> chatConversationService.isLiveGeneration(
                                    ownerJiacn, ownerClientId, conversationId, generation))
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
            emitter.onDispose(disposable);

            AtomicBoolean sent = new AtomicBoolean();
            boolean live = chatConversationEventBroker.runIfLive(
                    conversationId, generation,
                    () -> chatConversationService.isLiveGeneration(
                            ownerJiacn, ownerClientId, conversationId, generation),
                    () -> {
                        agentService.requireHostingNewWork(
                                ownerJiacn, ownerClientId, selectedAgentId);
                        requireNoActiveTransaction();
                        sent.set(agentWebSocketHandler.sendDirectMessageToAgent(
                                ownerJiacn, ownerClientId, selectedAgentId, payload));
                    });
            if (live) {
                deliberationService.markDispatch(tenantId, ownerJiacn, ownerClientId, dispatch.turnId(), sent.get());
            }
            // DB admission, not socket delivery, is the acceptance boundary. Offline turns remain queued.
            deliveryOutcome.tryEmitValue(live);
            if (!live) {
                disposable.dispose();
                emitter.complete();
                return;
            }
            emitter.next(buildAgentDeliveryEventJson(
                    conversationId, selectedAgentId, sent.get(), dispatch));

            if (!sent.get()) {
                disposable.dispose();
                emitter.complete();
            }

        }, FluxSink.OverflowStrategy.ERROR)
                .transform(ChatStreamPolicy::bounded)
                .takeUntilOther(chatConversationEventBroker.deletionSignal(
                        conversationId, generation,
                        () -> chatConversationService.isLiveGeneration(
                                ownerJiacn, ownerClientId, conversationId, generation)))
                .doFinally(ignored -> deliveryOutcome.tryEmitValue(false));
        return new JuyitingAgentRelayResult(
                true, deliveryOutcome.asMono().defaultIfEmpty(false), stream);
    }

    private void requireNoActiveTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Agent delivery attempted before transaction commit");
        }
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
            String tenantId, String ownerJiacn, String ownerClientId, long generation,
            ChatDeliberationService.Admission admission) {
        Sinks.One<Boolean> deliveryOutcome = Sinks.one();
        Flux<String> events = Flux.defer(() -> {
            List<String> deliveryEvents = new ArrayList<>();
            AtomicBoolean anyDelivered = new AtomicBoolean();
            for (ChatDeliberationService.Dispatch dispatch : admission.dispatches()) {
                String agentId = dispatch.targetAgentId();
                AtomicBoolean delivered = new AtomicBoolean();
                boolean live = chatConversationEventBroker.runIfLive(
                        conversationId, generation,
                        () -> chatConversationService.isLiveGeneration(
                                ownerJiacn, ownerClientId, conversationId, generation),
                        () -> {
                            agentService.requireHostingNewWork(
                                    ownerJiacn, ownerClientId, agentId);
                            requireNoActiveTransaction();
                            delivered.set(agentWebSocketHandler.sendDirectMessageToAgent(
                                    ownerJiacn, ownerClientId, agentId,
                                    buildDirectAgentPayload(
                                            chatMessage, conversationId, agentId, scope, admission, dispatch)));
                            deliberationService.markDispatch(
                                    tenantId, ownerJiacn, ownerClientId, dispatch.turnId(), delivered.get());
                            if (delivered.get()) {
                                anyDelivered.set(true);
                            }
                        });
                if (!live) {
                    break;
                }
                deliveryEvents.add(buildAgentDeliveryEventJson(
                        conversationId, agentId, delivered.get(), dispatch));
            }
            deliveryOutcome.tryEmitValue(true);
            return Flux.fromIterable(deliveryEvents);
        }).takeUntilOther(chatConversationEventBroker.deletionSignal(
                        conversationId, generation,
                        () -> chatConversationService.isLiveGeneration(
                                ownerJiacn, ownerClientId, conversationId, generation)))
                .doFinally(ignored -> deliveryOutcome.tryEmitValue(false));
        return new JuyitingAgentRelayResult(
                true, deliveryOutcome.asMono().defaultIfEmpty(false), events);
    }

    private Map<String, Object> buildDirectAgentPayload(ChatMessageDTO chatMessage, String conversationId,
            String agentId, JuyitingConversationScope scope,
            ChatDeliberationService.Admission admission, ChatDeliberationService.Dispatch dispatch) {
        long sentAt = System.currentTimeMillis();
        String messageId = UUID.randomUUID().toString();
        Map<String, Object> payload = new HashMap<>();
        payload.put("schemaVersion", AgentProtocolConstants.VERSION_1);
        payload.put("messageId", messageId);
        payload.put("messageType", AgentProtocolConstants.TYPE_CHAT_MESSAGE);
        payload.put("correlationId", conversationId);
        payload.put("requestId", admission.requestId());
        payload.put("requestRevision", admission.requestRevision());
        payload.put("turnId", dispatch.turnId());
        payload.put("dispatchId", dispatch.dispatchId());
        payload.put("route", dispatch.route());
        payload.put("contextSnapshotId", dispatch.contextSnapshotId());
        payload.put("contextHash", dispatch.contextHash());
        payload.put("targetAgentId", agentId);
        payload.put("conversationId", conversationId);
        payload.put("conversationGeneration", admission.conversationGeneration());
        payload.put("conversationType", JuyitingConversationScopeService.CONVERSATION_TYPE_JUYITING);
        payload.put("conversationScopeType", scope.scopeType());
        payload.put("conversationScopeKey", scope.scopeKey());
        payload.put("agentId", agentId);
        payload.put("content", chatMessage.getContent());
        payload.put("senderType", Optional.ofNullable(chatMessage.getSenderType()).orElse("user"));
        payload.put("senderName", Optional.ofNullable(chatMessage.getSenderName()).orElse("用户"));
        payload.put("metadata", trustedConversationMetadata(
                chatMessage, conversationId, scope));
        payload.put("contextSnapshot", Map.of(
                "schemaVersion", "1",
                "contextSnapshotId", dispatch.contextSnapshotId(),
                "contextHash", dispatch.contextHash(),
                "sourceVector", dispatch.sourceVector(),
                "facts", dispatch.factsManifest()));
        payload.put("sentAt", sentAt);
        payload.put("timestamp", sentAt);

        Map<String, Object> protocolPayload = new HashMap<>();
        protocolPayload.put("content", chatMessage.getContent());
        protocolPayload.put("conversationId", conversationId);
        protocolPayload.put("conversationGeneration", admission.conversationGeneration());
        protocolPayload.put("requestId", admission.requestId());
        protocolPayload.put("requestRevision", admission.requestRevision());
        protocolPayload.put("turnId", dispatch.turnId());
        protocolPayload.put("dispatchId", dispatch.dispatchId());
        protocolPayload.put("route", dispatch.route());
        protocolPayload.put("contextSnapshotId", dispatch.contextSnapshotId());
        protocolPayload.put("contextHash", dispatch.contextHash());
        protocolPayload.put("contextSnapshot", payload.get("contextSnapshot"));
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

    private String buildAgentDeliveryEventJson(String conversationId, String agentId, boolean delivered,
            ChatDeliberationService.Dispatch dispatch) {
        return JsonUtil.toSafeJson(Map.of(
                "agentDelivery", Map.of("agentId", agentId, "delivered", delivered,
                        "accepted", true, "state", delivered ? "DISPATCHED" : "QUEUED"),
                "conversationId", conversationId,
                "conversationType", JuyitingConversationScopeService.CONVERSATION_TYPE_JUYITING,
                "requestId", dispatch == null ? "" : dispatch.requestId(),
                "turnId", dispatch == null ? "" : dispatch.turnId(),
                "dispatchId", dispatch == null ? "" : dispatch.dispatchId(),
                "targetAgentId", agentId,
                "eventId", UUID.randomUUID().toString(),
                "eventVersion", 1,
                "occurredAt", System.currentTimeMillis()));
    }

    private JuyitingAgentRelayResult replayAdmission(ChatDeliberationService.Admission admission) {
        List<String> events = admission.dispatches().stream().map(dispatch -> JsonUtil.toSafeJson(Map.ofEntries(
                Map.entry("type", "chat_request_replay"),
                Map.entry("conversationId", admission.conversationId()),
                Map.entry("requestId", admission.requestId()),
                Map.entry("requestRevision", admission.requestRevision()),
                Map.entry("turnId", dispatch.turnId()),
                Map.entry("dispatchId", dispatch.dispatchId()),
                Map.entry("targetAgentId", dispatch.targetAgentId()),
                Map.entry("state", dispatch.state()),
                Map.entry("eventId", UUID.randomUUID().toString()),
                Map.entry("eventVersion", 1),
                Map.entry("occurredAt", System.currentTimeMillis())))).toList();
        return new JuyitingAgentRelayResult(true, Mono.just(true), Flux.fromIterable(events));
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
