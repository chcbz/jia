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
            InteractionRoute route) {
        return relay(chatMessage, conversationId, route, ignored -> Flux.empty());
    }

    /** Compatibility overload; builtin work is intentionally not tied to this HTTP-supplied Flux. */
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
        String tenantId = requireIdentityPart(EsContextHolder.getContext().getTenantId());
        if (!tenantId.equals(requireIdentityPart(conversation.getTenantId()))) {
            return deniedScope(conversationId, scope);
        }

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

        // Admission commit is the only acceptance boundary. The SmartLifecycle durable relay
        // independently claims every child DISPATCH and routes builtin/hosted work per target.
        List<String> accepted = admission.dispatches().stream()
                .map(dispatch -> buildAgentDeliveryEventJson(
                        conversationId, dispatch.targetAgentId(), false, dispatch))
                .toList();
        Flux<String> durableEvents = chatConversationEventBroker.stream(
                        conversationId, generation,
                        () -> chatConversationService.isLiveGeneration(
                                ownerJiacn, ownerClientId, conversationId, generation))
                .filter(event -> event.contains("\"requestId\":\"" + admission.requestId() + "\""));
        return new JuyitingAgentRelayResult(true, Mono.just(true),
                Flux.fromIterable(accepted).concatWith(durableEvents));
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
                "eventVersion", "1",
                "occurredAt", Long.toString(System.currentTimeMillis())));
    }

    private JuyitingAgentRelayResult replayAdmission(ChatDeliberationService.Admission admission) {
        List<String> events = admission.dispatches().stream().map(dispatch -> JsonUtil.toSafeJson(Map.ofEntries(
                Map.entry("type", "chat_request_replay"),
                Map.entry("conversationId", admission.conversationId()),
                Map.entry("requestId", admission.requestId()),
                Map.entry("requestRevision", Long.toString(admission.requestRevision())),
                Map.entry("turnId", dispatch.turnId()),
                Map.entry("dispatchId", dispatch.dispatchId()),
                Map.entry("targetAgentId", dispatch.targetAgentId()),
                Map.entry("state", dispatch.state()),
                Map.entry("eventId", UUID.randomUUID().toString()),
                Map.entry("eventVersion", "1"),
                Map.entry("occurredAt", Long.toString(System.currentTimeMillis()))))).toList();
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
