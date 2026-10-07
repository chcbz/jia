package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskBountyBootstrapClaimDTO;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskRequirementSnapshotService;
import cn.jia.chat.api.ChatTypedDeliberationWire;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.deliberation.ChatConversationEventEntity;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatInteractionStepStore;
import cn.jia.chat.deliberation.ChatRequestEntity;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.core.util.JsonUtil;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Exactly one initial user request for a trusted assignment action. DELIBERATE admits a durable
 * typed CHAT turn; explicit execution intents retain their separately authorized step admission.
 * This transaction never calls a Provider or grants executable/paid authority.
 */
@Service
public class ChatBountyBootstrapAdmissionService {
    private static final Set<String> OPERATIONS = Set.of("DELIBERATE", "INSPECT_INPUTS", "GENERATE_IMAGE",
            "EDIT_IMAGE", "GENERATE_AUDIO", "EDIT_AUDIO");
    private final AgentTaskRequirementSnapshotService requirements;
    private final ChatBountyConversationService discussions;
    private final ChatConversationDao conversations;
    private final ChatMessageDao messages;
    private final ChatDeliberationDao deliberation;
    private final ChatInteractionStepStore steps;
    private final ChatConversationEventBroker broker;
    private final ObjectMapper json;
    private final ChatTypedDiscussionAdmissionService typedDiscussion;

    public ChatBountyBootstrapAdmissionService(AgentTaskRequirementSnapshotService requirements,
            ChatBountyConversationService discussions, ChatConversationDao conversations,
            ChatMessageDao messages, ChatDeliberationDao deliberation,
            ChatInteractionStepStore steps, ChatConversationEventBroker broker, ObjectMapper json,
            ChatTypedDiscussionAdmissionService typedDiscussion) {
        this.requirements = Objects.requireNonNull(requirements);
        this.discussions = Objects.requireNonNull(discussions);
        this.conversations = Objects.requireNonNull(conversations);
        this.messages = Objects.requireNonNull(messages);
        this.deliberation = Objects.requireNonNull(deliberation);
        this.steps = Objects.requireNonNull(steps);
        this.broker = Objects.requireNonNull(broker);
        this.json = Objects.requireNonNull(json);
        this.typedDiscussion = Objects.requireNonNull(typedDiscussion);
    }

    public record Admission(String conversationId, long conversationGeneration,
            String requestId, String userMessageId, String stepId, boolean replay) { }

    /**
     * Claim must come from the internal outbox claim, never from HTTP/Agent input. The immutable
     * requirement and the active grant are re-verified before Chat writes. The discussion's row
     * lock serializes this request with other bootstrap/legacy discussion creation.
     */
    @Transactional(rollbackFor = Exception.class)
    public Admission admit(AgentTaskBountyBootstrapClaimDTO claim) {
        if (claim == null || !"0".equals(claim.tenantId())
                || !exact(claim.ownerJiacn(), 50) || !exact(claim.clientId(), 50)
                || !exact(claim.taskId(), 100) || !exact(claim.sourceBusinessActionId(), 160)
                || !exact(claim.bootstrapId(), 100) || !exact(claim.targetAgentId(), 100)
                || !exact(claim.grantId(), 100) || !OPERATIONS.contains(claim.permittedOperation())
                || !"TASK_REQUIREMENT_REVISION_V1".equals(claim.requirementAnchor())
                || claim.requirementRevision() < 1 || claim.assignmentRevision() < 0
                || claim.grantVersion() < 1 || !hex(claim.referenceSummarySha256())
                || claim.references() == null || claim.references().size() > 32) {
            throw new IllegalArgumentException("Invalid trusted bounty bootstrap intent");
        }
        AgentTaskExecutionGrantService.Scope scope = new AgentTaskExecutionGrantService.Scope(
                claim.tenantId(), claim.clientId(), claim.ownerJiacn());
        AgentTaskRequirementSnapshotService.Snapshot requirement =
                requirements.read(scope, claim.taskId(), claim.requirementRevision());
        if (requirement == null || !claim.tenantId().equals(requirement.tenantId())
                || !claim.clientId().equals(requirement.clientId())
                || !claim.ownerJiacn().equals(requirement.ownerJiacn())
                || !claim.taskId().equals(requirement.taskId())
                || claim.requirementRevision() != requirement.revision()
                || !hex(requirement.sha256()) || requirement.title() == null
                || requirement.title().isBlank()) {
            throw new IllegalStateException("Exact owner-scoped requirement revision is unavailable");
        }
        // Only immutable source text goes into the initial request. Task-plan descriptions can
        // be truncated and must not silently replace the confirmed revision.
        // Quick intake may store the same exact demand in both fields; do not repeat it.
        // Never deduplicate inside either field or normalize the immutable snapshot.
        String content = requirement.title() +
                (requirement.description() == null || requirement.description().isEmpty()
                        || requirement.description().equals(requirement.title())
                        ? "" : "\n\n" + requirement.description());
        List<Map<String, Object>> references = claim.references().stream().map(reference -> {
            if (reference == null || !exact(reference.fileId(), 100)
                    || !Set.of("INPUT", "REFERENCE").contains(reference.purpose())
                    || !exact(reference.contentMimeType(), 127) || !hex(reference.contentHash())
                    || reference.version() < 1 || reference.byteLength() < 0) {
                throw new IllegalArgumentException("Bootstrap reference summary is invalid");
            }
            return Map.<String, Object>of("fileId", reference.fileId(), "version", reference.version(),
                    "purpose", reference.purpose(), "mimeType", reference.contentMimeType(),
                    "byteLength", reference.byteLength(), "contentHash", reference.contentHash());
        }).toList();
        // Claim was verified by the Agent outbox reader; this extra check detects a mutated DTO
        // before a material catalogue is admitted. Reading bytes remains separately ACL-bound.
        String referenceHash;
        try { referenceHash = hash(json.writeValueAsString(claim.references())); }
        catch (Exception invalid) { throw new IllegalStateException("Bootstrap references could not be verified", invalid); }
        if (!referenceHash.equals(claim.referenceSummarySha256())) {
            throw new IllegalStateException("Bootstrap reference summary was changed after claim");
        }
        ChatBountyConversationService.Discussion discussion = discussions.ensure(scope,
                claim.taskId(), claim.grantId(), claim.grantVersion(), claim.assignmentRevision(),
                claim.targetAgentId(), claim.permittedOperation(), requirement.title());
        if ("DELIBERATE".equals(claim.permittedOperation())) {
            var selectors = claim.references().stream().map(reference ->
                    new ChatTypedDeliberationWire.SourceSelector("TASK_LINKED_WORKSPACE_VERSION",
                            reference.fileId(), Integer.toString(reference.version()), reference.purpose(),
                            null, null)).toList();
            var command = new ChatTypedDeliberationWire.DiscussionCommand("DISCUSSION", claim.taskId(),
                    claim.assignmentRevision(), content, null, null, null, null, selectors);
            // Stable per original action. The typed admission persists the source catalogue and
            // one dispatch outbox atomically; replay returns its receipt without a second turn.
            // ensure() acquired this same task root/binding before the conversation lock.
            String key = stable("mmd-initial-discuss", claim.tenantId(), claim.clientId(),
                    claim.ownerJiacn(), claim.taskId(), claim.sourceBusinessActionId());
            var admitted = typedDiscussion.admit(claim.tenantId(), new ServerResolvedSender(
                    ServerResolvedSender.USER_TYPE, claim.ownerJiacn(), claim.ownerJiacn(),
                    claim.clientId(), DisplayNameSource.JIACN), discussion.conversationId(), key, command);
            if (admitted == null || !"ADMITTED".equals(admitted.state())
                    || !exact(admitted.requestId(), 100) || !exact(admitted.userMessageId(), 100)
                    || admitted.turnIds().size() != 1) {
                throw new IllegalStateException("Initial typed discussion was not durably admitted");
            }
            // CHAT has no execution step. Never manufacture a step or execution identifier.
            return new Admission(discussion.conversationId(), discussion.generation(),
                    admitted.requestId(), admitted.userMessageId(), null, admitted.replay());
        }
        String requestId = stable("mmd-initial-request", claim.tenantId(), claim.clientId(),
                claim.ownerJiacn(), claim.taskId(), claim.sourceBusinessActionId());
        String stepId = stable("mmd-initial-step", requestId);
        String executionIntentId = stable("mmd-execution-intent", stepId);
        String digest = hash(CanonicalContextJson.write(Map.of(
                "taskId", claim.taskId(), "actionId", claim.sourceBusinessActionId(),
                "requirementRevision", claim.requirementRevision(), "requirementHash", requirement.sha256(),
                "assignmentRevision", claim.assignmentRevision(), "agentId", claim.targetAgentId(),
                "operation", claim.permittedOperation(), "grantId", claim.grantId(),
                "grantVersion", claim.grantVersion(), "referencesHash", referenceHash)));
        // ensure() locked the scoped conversation in this transaction. Validate and keep that
        // lock for the idempotent read/insert; two competing workers cannot insert two messages.
        ChatConversationEntity bound = conversations.lockScopedById(claim.ownerJiacn(), claim.clientId(),
                discussion.conversationId());
        if (bound == null || !claim.tenantId().equals(bound.getTenantId())
                || !claim.clientId().equals(bound.getClientId())
                || !claim.ownerJiacn().equals(bound.getJiacn())
                || !claim.taskId().equals(bound.getTaskId())
                || !"juyiting".equals(bound.getConversationType())
                || !"bounty".equals(bound.getConversationScopeType())
                || bound.getDeletedAt() != null || !Objects.equals(bound.getLifecycleGeneration(),
                        discussion.generation())) {
            throw new IllegalStateException("Bound bounty discussion is unavailable");
        }
        ChatRequestEntity previous = deliberation.findRequest(claim.tenantId(), claim.ownerJiacn(),
                claim.clientId(), requestId);
        if (previous != null) {
            ChatInteractionStepStore.Step priorStep = steps.findStep(claim.tenantId(),
                    claim.ownerJiacn(), claim.clientId(), requestId, 1, 1);
            if (!digest.equals(previous.getRequestDigest()) || previous.getRequestRevision() == null
                    || previous.getRequestRevision() != 1 || !discussion.conversationId().equals(previous.getConversationId())
                    || !Objects.equals(discussion.generation(), previous.getConversationGeneration())
                    || priorStep == null || !stepId.equals(priorStep.stepId())
                    || !claim.taskId().equals(priorStep.taskId())
                    || !claim.grantId().equals(priorStep.grantId())
                    || claim.grantVersion() != priorStep.grantVersion()
                    || claim.assignmentRevision() != priorStep.assignmentRevision()
                    || !claim.targetAgentId().equals(priorStep.targetAgentId())
                    || !hash(CanonicalContextJson.write(Map.of(
                            "requirementHash", requirement.sha256(), "referenceHash", referenceHash,
                            "taskId", claim.taskId(), "assignmentRevision", claim.assignmentRevision(),
                            "grantId", claim.grantId(), "grantVersion", claim.grantVersion(),
                            "targetAgentId", claim.targetAgentId(), "operation", claim.permittedOperation())))
                    .equals(priorStep.inputSnapshotDigest())) {
                throw new IllegalStateException("Bootstrap idempotency key conflicts with existing request");
            }
            if (!"INSPECT_INPUTS".equals(claim.permittedOperation())) {
                var priorLink = steps.findLink(claim.tenantId(), claim.ownerJiacn(),
                        claim.clientId(), stepId);
                if (priorLink == null || !executionIntentId.equals(priorLink.executionIntentId())
                        || !stepId.equals(priorLink.stepId()))
                    throw new IllegalStateException("Bootstrap execution intent is incomplete");
            }
            return new Admission(discussion.conversationId(), discussion.generation(), requestId,
                    Long.toString(previous.getUserMessageId()), stepId, true);
        }
        long now = System.currentTimeMillis();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schemaVersion", 2);
        metadata.put("requestId", requestId);
        metadata.put("taskId", claim.taskId());
        metadata.put("sourceBusinessActionId", claim.sourceBusinessActionId());
        metadata.put("requirementRevision", claim.requirementRevision());
        metadata.put("requirementHash", requirement.sha256());
        metadata.put("referenceSummaries", references);
        metadata.put("referenceSummarySha256", referenceHash);
        metadata.put("targetAgentId", claim.targetAgentId());
        // Persist the server-authorized operation for later exact intent reconstruction.
        // The request/step digest still binds grant, revision and reference summaries.
        metadata.put("permittedOperation", claim.permittedOperation());
        metadata.put("interactionMode", "INSPECT_INPUTS".equals(claim.permittedOperation())
                ? "INSPECT" : "EXECUTE");
        ChatMessageEntity message = new ChatMessageEntity().setConversationId(discussion.conversationId())
                .setMessageType("USER").setContent(content).setMetadata(JsonUtil.toJson(metadata))
                .setJiacn(claim.ownerJiacn()).setSyncStatus("PENDING")
                .setConversationType("juyiting").setSenderType("user").setSenderName(claim.ownerJiacn());
        message.setTenantId(claim.tenantId());
        message.setClientId(claim.clientId());
        message.init4Creation();
        if (messages.insertScoped(claim.tenantId(), claim.clientId(), message) != 1
                || message.getId() == null) throw new IllegalStateException("Initial requirement message was not saved");
        ChatRequestEntity request = new ChatRequestEntity().setTenantId(claim.tenantId())
                .setOwnerJiacn(claim.ownerJiacn()).setClientId(claim.clientId())
                .setRequestId(requestId).setRequestRevision(1L).setRequestDigest(digest)
                .setConversationId(discussion.conversationId())
                .setConversationGeneration(discussion.generation()).setUserMessageId(message.getId())
                .setAggregateState("PLANNING").setStateVersion(0L).setCreatedAt(now).setUpdatedAt(now);
        if (deliberation.insertRequest(request) != 1 || request.getId() == null)
            throw new IllegalStateException("Initial bounty request was not saved");
        String kind = "INSPECT_INPUTS".equals(claim.permittedOperation()) ? "INSPECT" : "EXECUTE";
        String inputDigest = hash(CanonicalContextJson.write(Map.of(
                "requirementHash", requirement.sha256(), "referenceHash", referenceHash,
                "taskId", claim.taskId(), "assignmentRevision", claim.assignmentRevision(),
                "grantId", claim.grantId(), "grantVersion", claim.grantVersion(),
                "targetAgentId", claim.targetAgentId(), "operation", claim.permittedOperation())));
        ChatInteractionStepStore.Step step = new ChatInteractionStepStore.Step(stepId,
                claim.tenantId(), claim.ownerJiacn(), claim.clientId(), requestId, 1L, 1L,
                discussion.conversationId(), discussion.generation(), claim.taskId(),
                claim.assignmentRevision(), claim.grantId(), claim.grantVersion(),
                claim.targetAgentId(), kind, "ADMITTED", 0L, inputDigest, now, now);
        if (steps.insertStep(step) != 1) throw new IllegalStateException("Initial bounty step was not saved");
        if ("EXECUTE".equals(kind)) {
            var link = new ChatInteractionStepStore.ExecutionLink(executionIntentId, claim.tenantId(),
                    claim.ownerJiacn(), claim.clientId(), stepId, null, "WAITING_ADMISSION", 0L, now, now);
            if (steps.insertLink(link) != 1)
                throw new IllegalStateException("Initial execution intent was not saved");
        }
        ChatConversationEventEntity event = new ChatConversationEventEntity()
                .setEventId(stable("mmd-initial-event", requestId))
                .setTenantId(claim.tenantId()).setOwnerJiacn(claim.ownerJiacn())
                .setClientId(claim.clientId()).setConversationId(discussion.conversationId())
                .setConversationGeneration(discussion.generation()).setRequestId(requestId)
                .setEventType("interaction.state_changed").setEventVersion(0L)
                .setPayloadJson(JsonUtil.toJson(Map.of("requestId", requestId, "state", "PLANNING",
                        "userMessageId", Long.toString(message.getId()), "stepId", stepId)))
                .setOccurredAt(now);
        if (deliberation.insertEvent(event) != 1 || event.getEventSequence() == null
                || deliberation.assignEventVersion(event.getEventSequence()) != 1)
            throw new IllegalStateException("Initial bounty event was not committed");
        // DB event is authoritative. Live notification is merely a post-commit optimization.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            Map<String, Object> frame = Map.of("type", "interaction.state_changed",
                    "eventId", event.getEventId(), "eventVersion", event.getEventSequence(),
                    "requestId", requestId, "userMessageId", Long.toString(message.getId()),
                    "state", "PLANNING");
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    broker.publishIfSubscribed(discussion.conversationId(), discussion.generation(),
                            () -> {
                                var live = conversations.findScopedById(claim.ownerJiacn(),
                                        claim.clientId(), discussion.conversationId());
                                return live != null && live.getDeletedAt() == null
                                        && claim.tenantId().equals(live.getTenantId())
                                        && Objects.equals(discussion.generation(), live.getLifecycleGeneration());
                            }, frame);
                }
            });
        }
        return new Admission(discussion.conversationId(), discussion.generation(), requestId,
                Long.toString(message.getId()), stepId, false);
    }

    private static boolean exact(String text, int max) {
        return text != null && !text.isBlank() && text.equals(text.strip())
                && text.codePointCount(0, text.length()) <= max
                && text.chars().noneMatch(Character::isISOControl);
    }
    private static boolean hex(String text) { return text != null && text.matches("[0-9a-f]{64}"); }
    private static String stable(String prefix, String... values) {
        StringBuilder input = new StringBuilder(prefix);
        for (String value : values) input.append('\n').append(value.length()).append(':').append(value);
        return hash(input.toString());
    }
    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }
}
