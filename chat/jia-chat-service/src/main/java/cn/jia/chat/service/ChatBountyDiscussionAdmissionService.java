package cn.jia.chat.service;

import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.ChatBountyBindingStore;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.InteractionRoute;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.handler.dto.ChatMessageDTO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Same bounded fast CHAT profile for an authenticated bounty follow-up. No execute hint,
 * materialized file, tool or browser-supplied target is accepted on this branch. Only the
 * already-authorized task root and its stable user-facing bounty discussion select the Agent.
 */
@Service
public class ChatBountyDiscussionAdmissionService {
    private final AgentTaskMutationTransaction taskMutations;
    private final ChatBountyBindingStore bindings;
    private final ChatConversationDao conversations;
    private final JuyitingConversationScopeService scopeService;
    private final ChatDeliberationService deliberation;
    private final ChatDeliberationDao events;

    public ChatBountyDiscussionAdmissionService(AgentTaskMutationTransaction taskMutations,
            ChatBountyBindingStore bindings, ChatConversationDao conversations,
            JuyitingConversationScopeService scopeService, ChatDeliberationService deliberation,
            ChatDeliberationDao events) {
        this.taskMutations = Objects.requireNonNull(taskMutations);
        this.bindings = Objects.requireNonNull(bindings);
        this.conversations = Objects.requireNonNull(conversations);
        this.scopeService = Objects.requireNonNull(scopeService);
        this.deliberation = Objects.requireNonNull(deliberation);
        this.events = Objects.requireNonNull(events);
    }

    public record Discussion(String requestId, String userMessageId, List<String> turnIds,
            String state, String stateVersion, long eventCursor, boolean replay) { }

    @Transactional(rollbackFor = Exception.class)
    public Discussion admit(String tenantId, ServerResolvedSender sender, String conversationId,
            String idempotencyKey, String taskId, long expectedAssignmentRevision, String content) {
        if (!"0".equals(tenantId) || sender == null || !ServerResolvedSender.USER_TYPE.equals(sender.type())
                || !exact(sender.jiacn(), 50) || !exact(sender.clientId(), 50)
                || !exact(conversationId, 100) || !exact(idempotencyKey, 100)
                || !exact(taskId, 100) || expectedAssignmentRevision < 0
                || content == null || content.isBlank()) throw invalid();
        String owner = sender.jiacn(), client = sender.clientId();
        String requestId = stable("mmd-interaction-request", tenantId, client, owner, idempotencyKey);
        // The task root must precede the binding and conversation row locks, matching assign
        // -> grant -> binding -> conversation. Reassignment cannot race this CHAT admission.
        return taskMutations.executeWithLockedTaskRootInOwnerScope(tenantId, client, owner,
                taskId, root -> {
                    ChatBountyBindingStore.Binding binding = bindings.lock(
                            new ChatBountyBindingStore.Scope(tenantId, owner, client), taskId);
                    // Task version advances on unrelated task events; the binding carries the
                    // assignment revision, and the locked root carries the current target.
                    if (binding == null || binding.conversationId() == null
                            || !conversationId.equals(Long.toString(binding.conversationId()))
                            || binding.assignmentRevision() != expectedAssignmentRevision
                            || root == null || root.getTaskVersion() == null
                            || root.getTaskVersion() < expectedAssignmentRevision)
                        throw conflict();
                    ChatConversationEntity conversation = conversations.lockScopedById(owner, client, conversationId);
                    if (conversation == null || conversation.getId() == null
                            || !conversationId.equals(Long.toString(conversation.getId()))
                            || !tenantId.equals(conversation.getTenantId())
                            || !client.equals(conversation.getClientId())
                            || !owner.equals(conversation.getJiacn())
                            || !taskId.equals(conversation.getTaskId())
                            || !"juyiting".equals(conversation.getConversationType())
                            || !"bounty".equals(conversation.getConversationScopeType())
                            || !("task:" + taskId).equals(conversation.getConversationScopeKey())
                            || conversation.getDeletedAt() != null
                            || conversation.getLifecycleGeneration() == null
                            || conversation.getLifecycleGeneration() < 1) throw unavailable();
                    List<String> targets = scopeService.parsePersistedTargetAgentIds(
                            conversation.getTargetAgentIds());
                    if (targets.size() != 1 || !targets.getFirst().equals(root.getAssignedAgentId()))
                        throw conflict();
                    var scope = new JuyitingConversationScope("bounty", "task:" + taskId, taskId,
                            targets.getFirst(), List.copyOf(targets), List.copyOf(targets));
                    ChatMessageDTO input = new ChatMessageDTO();
                    input.setConversationId(conversationId);
                    input.setConversationType("juyiting");
                    input.setConversationScopeType("bounty");
                    input.setConversationScopeKey("task:" + taskId);
                    input.setTaskId(taskId);
                    input.setContent(content);
                    input.setRequestId(requestId);
                    input.setRequestRevision(1L);
                    input.setTargetAgentId(targets.getFirst());
                    input.setTargetAgentIds(List.copyOf(targets));
                    // No attachments or client hint. CHAT does not borrow the grant's tool
                    // capability or the EXECUTE thread; unavailable materials stay unmaterialized.
                    var admitted = deliberation.admit(tenantId, sender, conversationId,
                            conversation.getLifecycleGeneration(), scope, InteractionRoute.CHAT, input, null);
                    List<String> turns = admitted.dispatches().stream().map(
                            ChatDeliberationService.Dispatch::turnId).toList();
                    if (turns.size() != 1) throw unavailable();
                    var status = deliberation.getRequest(tenantId, owner, client, requestId);
                    return new Discussion(requestId, admitted.userMessageId(), turns, status.state(),
                            status.stateVersion(), events.eventHighWatermark(tenantId, owner,
                                    client, conversationId, conversation.getLifecycleGeneration()), admitted.replay());
                });
    }

    private static String stable(String prefix, String... parts) {
        StringBuilder text = new StringBuilder(prefix);
        for (String part : parts) text.append('\n').append(part.length()).append(':').append(part);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static boolean exact(String value, int max) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.codePointCount(0, value.length()) <= max
                && value.chars().noneMatch(Character::isISOControl);
    }
    private static ChatDeliberationException invalid() {
        return new ChatDeliberationException(ChatDeliberationException.Reason.INVALID_REQUEST,
                "Bounty discussion message is invalid");
    }
    private static ChatDeliberationException conflict() {
        return new ChatDeliberationException(ChatDeliberationException.Reason.CONFLICT,
                "Bounty discussion assignment changed");
    }
    private static ChatDeliberationException unavailable() {
        return new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,
                "Bounty discussion is unavailable");
    }
}
