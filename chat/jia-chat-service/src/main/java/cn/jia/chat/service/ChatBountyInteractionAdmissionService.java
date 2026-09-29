package cn.jia.chat.service;

import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.deliberation.ChatBountyBindingStore;
import cn.jia.chat.deliberation.ChatConversationEventEntity;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatInteractionStepStore;
import cn.jia.chat.deliberation.ChatRequestEntity;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.core.util.JsonUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Authenticated follow-up admission for the stable bounty discussion. This is NOT an execution
 * dispatcher: the link has no execution ID, and a later coordinator must re-check the grant and
 * actual paid-provider authorization before native START. Disabled at the HTTP boundary for now.
 */
@Service
public class ChatBountyInteractionAdmissionService {
    private static final Set<String> OPERATIONS = Set.of("INSPECT_INPUTS", "GENERATE_IMAGE",
            "EDIT_IMAGE", "GENERATE_AUDIO", "EDIT_AUDIO");
    private final AgentTaskExecutionGrantService grants;
    private final ChatBountyBindingStore bindings;
    private final ChatConversationDao conversations;
    private final ChatMessageDao messages;
    private final ChatDeliberationDao deliberation;
    private final ChatInteractionStepStore steps;
    private final JuyitingConversationScopeService scopeService;
    private final ChatConversationEventBroker broker;

    public ChatBountyInteractionAdmissionService(AgentTaskExecutionGrantService grants,
            ChatBountyBindingStore bindings, ChatConversationDao conversations,
            ChatMessageDao messages, ChatDeliberationDao deliberation,
            ChatInteractionStepStore steps, JuyitingConversationScopeService scopeService,
            ChatConversationEventBroker broker) {
        this.grants = Objects.requireNonNull(grants);
        this.bindings = Objects.requireNonNull(bindings);
        this.conversations = Objects.requireNonNull(conversations);
        this.messages = Objects.requireNonNull(messages);
        this.deliberation = Objects.requireNonNull(deliberation);
        this.steps = Objects.requireNonNull(steps);
        this.scopeService = Objects.requireNonNull(scopeService);
        this.broker = Objects.requireNonNull(broker);
    }

    public record Intent(String taskId, long expectedAssignmentRevision, String content,
            String operation, List<?> inputRefs, Object replyTo, Object continuationOf) { }
    public record Admission(String requestId, String userMessageId, String stepId,
            String state, long stateVersion, long eventCursor, boolean replay) { }

    @Transactional(rollbackFor = Exception.class)
    public Admission admit(String tenantId, ServerResolvedSender sender, String conversationId,
            String idempotencyKey, Intent intent) {
        if (!"0".equals(tenantId) || sender == null || !"user".equals(sender.type())
                || !exact(sender.jiacn(), 50) || !exact(sender.clientId(), 50)
                || !exact(conversationId, 100) || !exact(idempotencyKey, 100)
                || intent == null || !exact(intent.taskId(), 100)
                || intent.expectedAssignmentRevision() < 0
                || intent.content() == null || intent.content().isBlank()
                || !OPERATIONS.contains(intent.operation())) {
            throw invalid("Bounty interaction request is invalid");
        }
        // Resolver for newly selected files, prior media parts and clarification links is not
        // available yet. Never persist unchecked browser references as executable inputs.
        if (intent.inputRefs() != null && !intent.inputRefs().isEmpty()
                || intent.replyTo() != null || intent.continuationOf() != null) {
            throw invalid("Referenced inputs require a verified content resolver");
        }
        String owner = sender.jiacn();
        String client = sender.clientId();
        var scope = new AgentTaskExecutionGrantService.Scope(tenantId, client, owner);
        String requestId = stable("mmd-interaction-request", tenantId, client, owner, idempotencyKey);
        String stepId = stable("mmd-interaction-step", requestId);
        String executionIntentId = stable("mmd-interaction-execution", stepId);
        String digest = sha(CanonicalContextJson.write(Map.of(
                "schemaVersion", 2, "taskId", intent.taskId(),
                "assignmentRevision", intent.expectedAssignmentRevision(),
                "content", intent.content(), "operation", intent.operation(),
                "conversationId", conversationId)));
        // Read-only owner check before any grant/task mutation. Replays return the same request
        // even if the grant was revoked AFTER initial admission; they never create a new intent.
        ChatConversationEntity observed = conversations.findScopedById(owner, client, conversationId);
        requireDiscussion(observed, tenantId, owner, client, intent.taskId(), conversationId);
        ChatRequestEntity prior = deliberation.findRequest(tenantId, owner, client, requestId);
        if (prior != null) return replay(prior, digest, requestId, stepId, executionIntentId,
                tenantId, owner, client, conversationId, observed.getLifecycleGeneration(), intent);
        List<String> targets = scopeService.parsePersistedTargetAgentIds(observed.getTargetAgentIds());
        if (targets.size() != 1) throw unavailable();
        String targetId = targets.getFirst();
        // The Agent task root/grant lock must precede the binding and conversation locks. This
        // matches bootstrap assign -> grant -> binding -> conversation, avoiding lock inversion.
        var authorized = grants.resolveAndAdmit(scope, intent.taskId(),
                intent.expectedAssignmentRevision(), targetId, intent.operation(), false);
        if (authorized == null || authorized.assignmentRevision() != intent.expectedAssignmentRevision()
                || !targetId.equals(authorized.targetAgentId()) || authorized.grantVersion() < 1) {
            throw unavailable();
        }
        ChatBountyBindingStore.Binding binding = bindings.lock(
                new ChatBountyBindingStore.Scope(tenantId, owner, client), intent.taskId());
        if (binding.conversationId() == null || !conversationId.equals(Long.toString(binding.conversationId()))
                || binding.assignmentRevision() != intent.expectedAssignmentRevision()) {
            throw conflict("Bounty discussion assignment changed");
        }
        ChatConversationEntity locked = conversations.lockScopedById(owner, client, conversationId);
        requireDiscussion(locked, tenantId, owner, client, intent.taskId(), conversationId);
        if (!Objects.equals(observed.getLifecycleGeneration(), locked.getLifecycleGeneration())
                || !List.of(targetId).equals(scopeService.parsePersistedTargetAgentIds(
                        locked.getTargetAgentIds()))) throw conflict("Bounty discussion changed");
        // The binding and conversation row lock serialize the same-key insert. The second
        // request must reconcile instead of inserting a second billable intent.
        prior = deliberation.findRequest(tenantId, owner, client, requestId);
        if (prior != null) return replay(prior, digest, requestId, stepId, executionIntentId,
                tenantId, owner, client, conversationId, locked.getLifecycleGeneration(), intent);
        long now = System.currentTimeMillis();
        String inputDigest = sha(CanonicalContextJson.write(Map.of(
                "taskId", intent.taskId(), "assignmentRevision", intent.expectedAssignmentRevision(),
                "grantId", authorized.grantId(), "grantVersion", authorized.grantVersion(),
                "targetAgentId", targetId, "operation", intent.operation(), "content", intent.content())));
        ChatMessageEntity message = new ChatMessageEntity().setConversationId(conversationId)
                .setMessageType("USER").setContent(intent.content())
                .setMetadata(JsonUtil.toJson(Map.of("schemaVersion", 2, "requestId", requestId,
                        "taskId", intent.taskId(), "targetAgentId", targetId,
                        "permittedOperation", intent.operation(),
                        "interactionMode", "INSPECT_INPUTS".equals(intent.operation()) ? "INSPECT" : "EXECUTE")))
                .setJiacn(owner).setSyncStatus("PENDING").setConversationType("juyiting")
                .setSenderType(sender.type()).setSenderName(sender.displayName());
        message.setTenantId(tenantId);
        message.setClientId(client);
        message.init4Creation();
        if (messages.insertScoped(tenantId, client, message) != 1 || message.getId() == null)
            throw unavailable();
        ChatRequestEntity request = new ChatRequestEntity().setTenantId(tenantId)
                .setOwnerJiacn(owner).setClientId(client).setRequestId(requestId)
                .setRequestRevision(1L).setRequestDigest(digest).setConversationId(conversationId)
                .setConversationGeneration(locked.getLifecycleGeneration()).setUserMessageId(message.getId())
                .setAggregateState("PLANNING").setStateVersion(0L).setCreatedAt(now).setUpdatedAt(now);
        if (deliberation.insertRequest(request) != 1 || request.getId() == null) throw unavailable();
        String kind = "INSPECT_INPUTS".equals(intent.operation()) ? "INSPECT" : "EXECUTE";
        var step = new ChatInteractionStepStore.Step(stepId, tenantId, owner, client, requestId, 1L, 1L,
                conversationId, locked.getLifecycleGeneration(), intent.taskId(),
                intent.expectedAssignmentRevision(), authorized.grantId(), authorized.grantVersion(),
                targetId, kind, "ADMITTED", 0L, inputDigest, now, now);
        if (steps.insertStep(step) != 1) throw unavailable();
        if ("EXECUTE".equals(kind) && steps.insertLink(new ChatInteractionStepStore.ExecutionLink(
                executionIntentId, tenantId, owner, client, stepId, null, "WAITING_ADMISSION",
                0L, now, now)) != 1) throw unavailable();
        ChatConversationEventEntity event = new ChatConversationEventEntity()
                .setEventId(stable("mmd-interaction-event", requestId))
                .setTenantId(tenantId).setOwnerJiacn(owner).setClientId(client)
                .setConversationId(conversationId).setConversationGeneration(locked.getLifecycleGeneration())
                .setRequestId(requestId).setEventType("interaction.state_changed")
                .setEventVersion(0L).setOccurredAt(now)
                .setPayloadJson(JsonUtil.toJson(Map.of("requestId", requestId, "state", "PLANNING",
                        "userMessageId", Long.toString(message.getId()), "stepId", stepId)));
        if (deliberation.insertEvent(event) != 1 || event.getEventSequence() == null
                || deliberation.assignEventVersion(event.getEventSequence()) != 1) throw unavailable();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            long generation = locked.getLifecycleGeneration();
            Map<String, Object> frame = Map.of("type", "interaction.state_changed",
                    "eventId", event.getEventId(), "eventVersion", event.getEventSequence(),
                    "requestId", requestId, "userMessageId", Long.toString(message.getId()),
                    "state", "PLANNING");
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    broker.publishIfSubscribed(conversationId, generation, () -> {
                        var live = conversations.findScopedById(owner, client, conversationId);
                        return live != null && live.getDeletedAt() == null
                                && tenantId.equals(live.getTenantId())
                                && Objects.equals(generation, live.getLifecycleGeneration());
                    }, frame);
                }
            });
        }
        return new Admission(requestId, Long.toString(message.getId()), stepId,
                "PLANNING", 0L, event.getEventSequence(), false);
    }

    private Admission replay(ChatRequestEntity prior, String digest, String requestId,
            String stepId, String executionIntentId, String tenantId, String owner, String client,
            String conversationId, long generation, Intent intent) {
        if (!digest.equals(prior.getRequestDigest()) || !conversationId.equals(prior.getConversationId())
                || !Objects.equals(generation, prior.getConversationGeneration())
                || !Objects.equals(prior.getRequestRevision(), 1L)) {
            throw conflict("Idempotency key was already used for another interaction");
        }
        var recorded = steps.findStep(tenantId, owner, client, requestId, 1, 1);
        String kind = "INSPECT_INPUTS".equals(intent.operation()) ? "INSPECT" : "EXECUTE";
        if (recorded == null || !stepId.equals(recorded.stepId())
                || !kind.equals(recorded.kind()) || !intent.taskId().equals(recorded.taskId())
                || recorded.assignmentRevision() != intent.expectedAssignmentRevision()
                || !conversationId.equals(recorded.conversationId())
                || generation != recorded.conversationGeneration()
                || !tenantId.equals(recorded.tenantId()) || !owner.equals(recorded.ownerJiacn())
                || !client.equals(recorded.clientId()) || prior.getUserMessageId() == null
                || prior.getStateVersion() == null || prior.getAggregateState() == null) throw unavailable();
        if ("EXECUTE".equals(kind)) {
            var link = steps.findLink(tenantId, owner, client, stepId);
            if (link == null || !executionIntentId.equals(link.executionIntentId())
                    || !stepId.equals(link.stepId()) || !tenantId.equals(link.tenantId())
                    || !owner.equals(link.ownerJiacn()) || !client.equals(link.clientId())) throw unavailable();
        }
        return new Admission(requestId, Long.toString(prior.getUserMessageId()), stepId,
                prior.getAggregateState(), prior.getStateVersion(),
                deliberation.eventHighWatermark(tenantId, owner, client, conversationId, generation), true);
    }

    private static void requireDiscussion(ChatConversationEntity conversation, String tenantId,
            String owner, String client, String taskId, String conversationId) {
        if (conversation == null || conversation.getId() == null
                || !conversationId.equals(Long.toString(conversation.getId()))
                || !tenantId.equals(conversation.getTenantId()) || !owner.equals(conversation.getJiacn())
                || !client.equals(conversation.getClientId()) || !taskId.equals(conversation.getTaskId())
                || !"juyiting".equals(conversation.getConversationType())
                || !"bounty".equals(conversation.getConversationScopeType())
                || !("task:" + taskId).equals(conversation.getConversationScopeKey())
                || conversation.getDeletedAt() != null || conversation.getLifecycleGeneration() == null
                || conversation.getLifecycleGeneration() < 1) {
            throw new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,
                    "Bounty discussion is unavailable");
        }
    }
    private static boolean exact(String value, int max) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.codePointCount(0, value.length()) <= max
                && value.chars().noneMatch(Character::isISOControl);
    }
    private static String stable(String prefix, String... fields) {
        StringBuilder input = new StringBuilder(prefix);
        for (String field : fields) input.append('\n').append(field.length()).append(':').append(field);
        return sha(input.toString());
    }
    private static String sha(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static ChatDeliberationException invalid(String message) {
        return new ChatDeliberationException(ChatDeliberationException.Reason.INVALID_REQUEST, message);
    }
    private static ChatDeliberationException conflict(String message) {
        return new ChatDeliberationException(ChatDeliberationException.Reason.CONFLICT, message);
    }
    private static ChatDeliberationException unavailable() {
        return new ChatDeliberationException(ChatDeliberationException.Reason.PERSISTENCE_ERROR,
                "Bounty interaction could not be persisted");
    }
}
