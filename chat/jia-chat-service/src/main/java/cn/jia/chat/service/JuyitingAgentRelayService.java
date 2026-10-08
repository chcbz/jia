package cn.jia.chat.service;

import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.PersonalWorkspaceTaskLinkService;
import cn.jia.chat.deliberation.InteractionRoute;
import cn.jia.chat.handler.AgentWebSocketHandler;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.handler.dto.ChatMessageDTO;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
    private final ChatDeliberationService deliberationService;

    private static final String MATERIALS_AVAILABLE = "AVAILABLE";
    private static final String MATERIALS_UNAVAILABLE = "UNAVAILABLE";

    public JuyitingAgentRelayResult relay(
            ChatMessageDTO chatMessage, String conversationId,
            ServerResolvedSender sender, InteractionRoute route) {
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

        Map<String, Object> taskMaterials = trustedTaskMaterials(scope, trustedSender, tenantId);
        ChatDeliberationService.Admission admission = deliberationService.admit(
                tenantId, trustedSender, conversationId, generation, scope, route,
                chatMessage, taskMaterials);
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

    private Map<String, Object> trustedTaskMaterials(
            JuyitingConversationScope scope, ServerResolvedSender sender, String tenantId) {
        if (scope.taskId() == null) {
            return null;
        }
        try {
            PersonalWorkspaceTaskLinkService.LinkListView page = taskLinkService.list(
                    new PersonalWorkspaceTaskLinkService.Scope(
                            tenantId, sender.clientId(), sender.jiacn()),
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

}
