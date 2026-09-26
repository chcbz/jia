package cn.jia.chat.service;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.PersonalWorkspaceTaskLinkService;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.handler.AgentWebSocketHandler;
import cn.jia.chat.handler.dto.ChatMessageDTO;
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
    private final PersonalWorkspaceTaskLinkService taskLinkService;

    private static final String MATERIALS_AVAILABLE = "AVAILABLE";
    private static final String MATERIALS_UNAVAILABLE = "UNAVAILABLE";

    public JuyitingAgentRelayResult relay(
            ChatMessageDTO chatMessage, String conversationId, ServerResolvedSender sender,
            Supplier<Flux<String>> builtinAgentStream) {
        JuyitingConversationScope requestedScope = scopeService.resolve(chatMessage);
        if (!JuyitingConversationScopeService.CONVERSATION_TYPE_JUYITING.equals(scopeService.resolveConversationType(chatMessage))
                || requestedScope.scopeType() == null) {
            return new JuyitingAgentRelayResult(false, Mono.just(false), Flux.empty());
        }

        ServerResolvedSender trustedSender = requireTrustedSender(sender);
        String ownerJiacn = trustedSender.jiacn();
        String ownerClientId = trustedSender.clientId();
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

        Map<String, Object> taskMaterials = trustedTaskMaterials(scope, trustedSender);
        saveDirectUserMessage(
                chatMessage, conversationId, scope, trustedSender, generation, taskMaterials);

        if (scope.targetAgentIds().size() > 1) {
            return relayMultiTargetAgentMessage(
                    chatMessage, conversationId, scope, trustedSender, generation, taskMaterials);
        }

        String selectedAgentId = scope.targetAgentIds().getFirst();
        if (builtinHallAgentSupport.isBuiltinAgent(selectedAgentId)) {
            return new JuyitingAgentRelayResult(
                    true, Mono.just(true), builtinAgentStream.get().takeUntilOther(
                    chatConversationEventBroker.deletionSignal(
                            conversationId, generation,
                            () -> chatConversationService.isLiveGeneration(
                                    ownerJiacn, ownerClientId, conversationId, generation))));
        }

        Map<String, Object> payload = buildDirectAgentPayload(
                chatMessage, conversationId, selectedAgentId, scope, trustedSender, taskMaterials);
        String tenantId = ownerJiacn;
        String clientId = ownerClientId;
        Sinks.One<Boolean> deliveryOutcome = Sinks.one();
        Flux<String> stream = Flux.<String>create(emitter -> {
            final Disposable[] subscriptionRef = new Disposable[1];
            Disposable disposable = chatConversationEventBroker.stream(
                            conversationId, generation,
                            () -> chatConversationService.isLiveGeneration(
                                    tenantId, clientId, conversationId, generation))
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
                            tenantId, clientId, conversationId, generation),
                    () -> {
                        agentService.requireHostingNewWork(
                                tenantId, clientId, selectedAgentId);
                        sent.set(agentWebSocketHandler.sendDirectMessageToAgent(
                                tenantId, clientId, selectedAgentId, payload));
                    });
            deliveryOutcome.tryEmitValue(live && sent.get());
            if (!live) {
                disposable.dispose();
                emitter.complete();
                return;
            }
            emitter.next(buildAgentDeliveryEventJson(
                    conversationId, selectedAgentId, sent.get()));

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
            ServerResolvedSender sender, long generation, Map<String, Object> taskMaterials) {
        String ownerJiacn = sender.jiacn();
        String ownerClientId = sender.clientId();
        Sinks.One<Boolean> deliveryOutcome = Sinks.one();
        Flux<String> events = Flux.defer(() -> {
            List<String> deliveryEvents = new ArrayList<>();
            AtomicBoolean anyDelivered = new AtomicBoolean();
            for (String agentId : scope.targetAgentIds()) {
                AtomicBoolean delivered = new AtomicBoolean();
                boolean live = chatConversationEventBroker.runIfLive(
                        conversationId, generation,
                        () -> chatConversationService.isLiveGeneration(
                                ownerJiacn, ownerClientId, conversationId, generation),
                        () -> {
                            agentService.requireHostingNewWork(
                                    ownerJiacn, ownerClientId, agentId);
                            delivered.set(agentWebSocketHandler.sendDirectMessageToAgent(
                                    ownerJiacn, ownerClientId, agentId,
                                    buildDirectAgentPayload(
                                            chatMessage, conversationId, agentId, scope, sender,
                                            taskMaterials)));
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
                                ownerJiacn, ownerClientId, conversationId, generation)))
                .doFinally(ignored -> deliveryOutcome.tryEmitValue(false));
        return new JuyitingAgentRelayResult(
                true, deliveryOutcome.asMono().defaultIfEmpty(false), events);
    }

    private Map<String, Object> buildDirectAgentPayload(
            ChatMessageDTO chatMessage, String conversationId, String agentId,
            JuyitingConversationScope scope, ServerResolvedSender sender,
            Map<String, Object> taskMaterials) {
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
        payload.put("senderType", sender.type());
        payload.put("senderName", sender.displayName());
        Map<String, Object> trustedMetadata = trustedConversationMetadata(
                chatMessage, conversationId, scope, sender, taskMaterials);
        payload.put("metadata", trustedMetadata);
        payload.put("sentAt", sentAt);
        payload.put("timestamp", sentAt);

        Map<String, Object> protocolPayload = new HashMap<>();
        protocolPayload.put("content", chatMessage.getContent());
        protocolPayload.put("senderType", sender.type());
        protocolPayload.put("senderName", sender.displayName());
        protocolPayload.put("conversationScopeType", scope.scopeType());
        protocolPayload.put("conversationScopeKey", scope.scopeKey());
        putIfPresent(protocolPayload, "taskContextId", scope.taskId());
        protocolPayload.put("metadata", trustedMetadata);
        payload.put("payload", protocolPayload);
        return payload;
    }

    private void saveDirectUserMessage(
            ChatMessageDTO chatMessage, String conversationId, JuyitingConversationScope scope,
            ServerResolvedSender sender, long generation, Map<String, Object> taskMaterials) {
        String ownerJiacn = sender.jiacn();
        String ownerClientId = sender.clientId();
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setJiacn(ownerJiacn);
        entity.setClientId(ownerClientId);
        entity.setConversationId(conversationId);
        entity.setMessageType("USER");
        entity.setContent(chatMessage.getContent());
        entity.setSyncStatus("PENDING");
        entity.setConversationType(JuyitingConversationScopeService.CONVERSATION_TYPE_JUYITING);
        entity.setSenderType(sender.type());
        entity.setSenderName(sender.displayName());

        entity.setMetadata(JsonUtil.toJson(
                trustedConversationMetadata(
                        chatMessage, conversationId, scope, sender, taskMaterials)));
        chatConversationService.appendOwnedMessage(
                ownerJiacn, ownerClientId, entity, generation);
    }

    private Map<String, Object> trustedConversationMetadata(
            ChatMessageDTO chatMessage, String conversationId, JuyitingConversationScope scope,
            ServerResolvedSender sender, Map<String, Object> taskMaterials) {
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
        if (taskMaterials != null) {
            metadata.put("taskMaterials", taskMaterials);
        }
        // Authenticated sender fields are written last and override every request alias.
        metadata.put("senderType", sender.type());
        metadata.put("senderName", sender.displayName());
        metadata.put("jiacn", sender.jiacn());
        return metadata;
    }

    private Map<String, Object> trustedTaskMaterials(
            JuyitingConversationScope scope, ServerResolvedSender sender) {
        if (scope.taskId() == null) {
            return null;
        }
        try {
            PersonalWorkspaceTaskLinkService.LinkListView page = taskLinkService.list(
                    new PersonalWorkspaceTaskLinkService.Scope(
                            "0", sender.clientId(), sender.jiacn()),
                    scope.taskId(), null);
            if (page == null || page.items() == null) {
                return unavailableTaskMaterials();
            }
            List<Map<String, Object>> items = page.items().stream()
                    .filter(link -> isActiveTaskMaterial(link, scope.taskId()))
                    .map(link -> Map.<String, Object>of(
                            "fileId", link.fileId(),
                            "version", link.version(),
                            "role", link.role()))
                    .toList();
            return Map.of(
                    "status", MATERIALS_AVAILABLE,
                    "complete", page.nextCursor() == null,
                    "items", items);
        } catch (RuntimeException unavailable) {
            return unavailableTaskMaterials();
        }
    }

    private boolean isActiveTaskMaterial(
            PersonalWorkspaceTaskLinkService.LinkView link, String authorizedTaskId) {
        return link != null
                && authorizedTaskId.equals(link.taskId())
                && "ACTIVE".equals(link.state())
                && ("INPUT".equals(link.role()) || "REFERENCE".equals(link.role()))
                && validMaterialFileId(link.fileId())
                && link.version() > 0;
    }

    private boolean validMaterialFileId(String fileId) {
        return fileId != null
                && !fileId.isBlank()
                && fileId.equals(fileId.strip())
                && fileId.codePointCount(0, fileId.length()) <= 100
                && fileId.chars().noneMatch(Character::isISOControl);
    }

    private Map<String, Object> unavailableTaskMaterials() {
        return Map.of(
                "status", MATERIALS_UNAVAILABLE,
                "complete", false,
                "items", List.of());
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

    private ServerResolvedSender requireTrustedSender(ServerResolvedSender sender) {
        if (sender == null || !ServerResolvedSender.USER_TYPE.equals(sender.type())) {
            throw new IllegalStateException("Authenticated sender identity is unavailable");
        }
        requireIdentityPart(sender.jiacn());
        requireIdentityPart(sender.clientId());
        if (sender.displayName() == null || sender.displayName().isBlank()
                || !sender.displayName().equals(sender.displayName().strip())
                || sender.displayName().codePointCount(0, sender.displayName().length()) > 100
                || sender.displayName().codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalStateException("Authenticated sender identity is unavailable");
        }
        return sender;
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
