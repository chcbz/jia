package cn.jia.chat.service;

import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.deliberation.ChatConversationEventEntity;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatInteractionStepStore;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.core.util.JsonUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

/** Reconcile committed, owner-fenced output bytes into durable message/part/event facts.
 * A read or replay never creates assets, and this consumer never invokes a Provider.
 */
@Service
@ConditionalOnProperty(prefix = "chat.bounty-asset", name = "enabled", havingValue = "true")
public class ChatBountyAssetProjector {
    private static final Set<String> IMAGES = Set.of("image/png", "image/jpeg", "image/webp", "image/gif");
    private static final Set<String> AUDIO = Set.of("audio/mpeg", "audio/ogg", "audio/wav", "audio/mp4", "audio/webm");
    private final JdbcTemplate jdbc;
    private final ChatDeliberationService requests;
    private final ChatDeliberationDao events;
    private final ChatConversationDao conversations;
    private final ChatMessageDao messages;
    private final PersonalWorkspaceExecutionService executions;
    private final ChatConversationEventBroker broker;

    public ChatBountyAssetProjector(JdbcTemplate jdbc, ChatDeliberationService requests,
            ChatDeliberationDao events, ChatConversationDao conversations, ChatMessageDao messages,
            PersonalWorkspaceExecutionService executions, ChatConversationEventBroker broker) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.requests = Objects.requireNonNull(requests);
        this.events = Objects.requireNonNull(events);
        this.conversations = Objects.requireNonNull(conversations);
        this.messages = Objects.requireNonNull(messages);
        this.executions = Objects.requireNonNull(executions);
        this.broker = Objects.requireNonNull(broker);
    }

    public record Candidate(String tenantId, String ownerJiacn, String clientId, String requestId,
            String stepId, String executionId) { }
    public record Asset(String assetId, String tenantId, String ownerJiacn, String clientId,
            String conversationId, long generation, String requestId, String stepId,
            String executionId, String runId, String outputId, String messageId,
            String partId, String kind, String mime, String sha256, long byteLength, long revision) { }

    /** Read-only scan; no task/Chat locks are held before the grant's task-root lock. */
    public List<Candidate> pending(String afterStepId, int limit) {
        if (limit < 1 || limit > 128) throw new IllegalArgumentException("Invalid scan limit");
        return jdbc.query("""
                SELECT s.tenant_id,s.owner_jiacn,s.client_id,s.request_id,s.step_id,l.execution_id
                FROM chat_interaction_step s
                JOIN chat_step_execution_link l ON l.step_id=s.step_id
                  AND l.tenant_id=s.tenant_id AND l.owner_jiacn=s.owner_jiacn AND l.client_id=s.client_id
                JOIN agent_personal_workspace_execution e ON e.execution_id=l.execution_id
                  AND e.tenant_id=s.tenant_id AND e.client_id=s.client_id AND e.owner_jiacn=s.owner_jiacn
                  AND e.execution_state='OUTPUT_COMMITTED' AND e.execution_mode='CONVERSATION'
                WHERE s.kind='EXECUTE' AND s.state='RUNNING' AND l.state='RUNNING'
                  AND (? IS NULL OR s.step_id > ?)
                ORDER BY s.step_id LIMIT ?
                """, (rs, n) -> new Candidate(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6)), afterStepId, afterStepId, limit);
    }

    /** Task-root/grant and committed byte check precede the conversation lock and event insert.
     * Per-output message, asset relation and journal frames commit atomically.
     */
    @Transactional(rollbackFor = Exception.class)
    public int project(Candidate candidate) {
        if (candidate == null) throw new IllegalArgumentException("Missing candidate");
        var request = requests.getRequest(candidate.tenantId(), candidate.ownerJiacn(),
                candidate.clientId(), candidate.requestId());
        if (request == null || request.steps() == null) return 0;
        var step = request.steps().stream().filter(value -> candidate.stepId().equals(value.stepId()))
                .findFirst().orElse(null);
        if (request == null || !candidate.requestId().equals(request.requestId())
                || !"RUNNING".equals(request.state())
                || step == null || !"EXECUTE".equals(step.kind()) || !"RUNNING".equals(step.state())
                || !candidate.executionId().equals(step.executionId()) || step.taskId() == null
                || step.targetAgentId() == null) return 0;
        var scope = new PersonalWorkspaceExecutionService.OwnerScope(candidate.tenantId(),
                candidate.clientId(), candidate.ownerJiacn());
        var execution = executions.get(scope, candidate.executionId());
        if (execution == null || !"CONVERSATION".equals(execution.executionMode())
                || !"OUTPUT_COMMITTED".equals(execution.state())
                || !candidate.executionId().equals(execution.executionId())
                || !step.taskId().equals(execution.taskId())
                || !request.conversationId().equals(execution.conversationId())
                || !step.targetAgentId().equals(execution.targetAgentId())
                || execution.runId() == null) return 0;
        List<PersonalWorkspaceExecutionService.ConversationOutputInfo> outputs = executions.listConversationOutputs(
                scope, step.taskId(), execution.runId());
        if (outputs == null || outputs.isEmpty() || outputs.size() > 128)
            throw new IllegalStateException("Committed output catalogue is unavailable");
        // Runtime grant/root locks are acquired above. Other writers follow root -> conversation.
        ChatConversationEntity live = conversations.lockScopedById(candidate.ownerJiacn(),
                candidate.clientId(), request.conversationId());
        if (live == null || live.getDeletedAt() != null || live.getLifecycleGeneration() == null
                || !Objects.equals(request.conversationGeneration(), Long.toString(live.getLifecycleGeneration()))
                || !candidate.tenantId().equals(live.getTenantId())
                || !candidate.ownerJiacn().equals(live.getJiacn())
                || !candidate.clientId().equals(live.getClientId())
                || !step.taskId().equals(live.getTaskId())
                || !"bounty".equals(live.getConversationScopeType())
                || !Objects.equals(live.getConversationScopeKey(), "task:" + step.taskId())) return 0;
        int count = 0;
        for (var output : outputs) {
            if (output == null || !candidate.executionId().equals(output.executionId())
                    || !safe(output.outputId()) || !safeMime(output.contentMimeType())
                    || output.sha256() == null || !output.sha256().matches("[0-9a-f]{64}")
                    || output.byteLength() < 0) throw new IllegalStateException("Untrusted output catalogue");
            String suffix = outputSuffix(scope, candidate.stepId(), output.outputId());
            String assetId = "ast_" + suffix;
            Asset prior = findCurrent(scope, request.conversationId(), assetId, true);
            if (prior != null) {
                if (!candidate.stepId().equals(prior.stepId())
                        || !output.outputId().equals(prior.outputId())
                        || !candidate.executionId().equals(prior.executionId())
                        || !candidate.requestId().equals(prior.requestId())
                        || !execution.runId().equals(prior.runId())
                        || live.getLifecycleGeneration() != prior.generation()
                        || !output.sha256().equals(prior.sha256())
                        || output.byteLength() != prior.byteLength()
                        || !output.contentMimeType().equals(prior.mime()))
                    throw new IllegalStateException("Previously published output differs from committed bytes");
                continue;
            }
            long now = System.currentTimeMillis();
            String messageText = "已生成成果；可预览或下载。";
            ChatMessageEntity message = new ChatMessageEntity().setConversationId(request.conversationId())
                    .setMessageType("ASSISTANT").setContent(messageText)
                    .setMetadata(JsonUtil.toJson(Map.of("requestId", candidate.requestId(),
                            "stepId", candidate.stepId(), "agentId", step.targetAgentId())))
                    .setJiacn(candidate.ownerJiacn()).setSyncStatus("PENDING")
                    .setConversationType("juyiting").setSenderType("agent")
                    .setSenderName("好汉");
            message.init4Creation();
            if (messages.insertScoped(candidate.tenantId(), candidate.clientId(), message) != 1
                    || message.getId() == null) throw new IllegalStateException("Unable to persist result message");
            String kind = IMAGES.contains(output.contentMimeType()) ? "image"
                    : AUDIO.contains(output.contentMimeType()) ? "audio" : "file";
            String partId = "part_" + suffix;
            int inserted = jdbc.update("""
                    INSERT INTO chat_conversation_asset (asset_id,tenant_id,owner_jiacn,client_id,
                      conversation_id,conversation_generation,request_id,step_id,execution_id,run_id,
                      output_id,message_id,part_id,kind,content_mime_type,sha256,byte_length,revision,created_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,?)
                    """, assetId, candidate.tenantId(), candidate.ownerJiacn(), candidate.clientId(),
                    request.conversationId(), live.getLifecycleGeneration(), candidate.requestId(), candidate.stepId(),
                    candidate.executionId(), execution.runId(), output.outputId(), message.getId(), partId,
                    kind, output.contentMimeType(), output.sha256(), output.byteLength(), now);
            if (inserted != 1) throw new IllegalStateException("Unable to persist result asset");
            Map<String, Object> part = Map.of("partId", partId, "revision", "1", "kind", kind,
                    "state", "ready", "assetId", assetId, "mime", output.contentMimeType(),
                    "contentHash", output.sha256(), "byteLength", output.byteLength());
            Map<String, Object> frame = Map.of("type", "agent_message", "conversationId", request.conversationId(),
                    "requestId", candidate.requestId(), "messageId", Long.toString(message.getId()),
                    "senderType", "agent", "senderName", "好汉", "agentId", step.targetAgentId(),
                    "content", messageText, "parts", List.of(part));
            var event = new ChatConversationEventEntity().setEventId("mmd-result-" + suffix)
                    .setTenantId(candidate.tenantId()).setOwnerJiacn(candidate.ownerJiacn())
                    .setClientId(candidate.clientId()).setConversationId(request.conversationId())
                    .setConversationGeneration(live.getLifecycleGeneration()).setRequestId(candidate.requestId())
                    .setEventType("agent_message").setEventVersion(0L)
                    .setPayloadJson(JsonUtil.toJson(frame)).setOccurredAt(now);
            if (events.insertEvent(event) != 1 || event.getEventSequence() == null
                    || events.assignEventVersion(event.getEventSequence()) != 1)
                throw new IllegalStateException("Unable to publish result event");
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                long generation = live.getLifecycleGeneration();
                var signal = new java.util.LinkedHashMap<String, Object>(frame);
                signal.put("eventId", event.getEventId());
                signal.put("eventSequence", Long.toString(event.getEventSequence()));
                signal.put("eventVersion", Long.toString(event.getEventSequence()));
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() {
                        broker.publishIfSubscribed(request.conversationId(), generation, () -> {
                            var current = conversations.findScopedById(candidate.ownerJiacn(), candidate.clientId(),
                                    request.conversationId());
                            return current != null && current.getDeletedAt() == null
                                    && Objects.equals(current.getLifecycleGeneration(), generation)
                                    && candidate.tenantId().equals(current.getTenantId());
                        }, signal);
                    }
                });
            }
            count++;
        }
        if (count > 0) {
            // The source catalogue was checked against committed bytes and an immutable intent.
            // A later GET or SSE replay only reads this projection; it never starts execution.
            var stored = jdbc.update("""
                    UPDATE chat_interaction_step SET state='OUTPUT_COMMITTED',state_version=state_version+1,
                      updated_at=? WHERE step_id=? AND tenant_id=? AND owner_jiacn=? AND client_id=?
                      AND state='RUNNING' AND state_version=?
                    """, System.currentTimeMillis(), candidate.stepId(), candidate.tenantId(),
                    candidate.ownerJiacn(), candidate.clientId(), Long.parseLong(step.stateVersion()));
            if (stored != 1) throw new IllegalStateException("Unable to finalize step projection");
            var requestRow = events.findRequest(candidate.tenantId(), candidate.ownerJiacn(),
                    candidate.clientId(), candidate.requestId());
            if (requestRow == null || !"RUNNING".equals(requestRow.getAggregateState())
                    || !candidate.tenantId().equals(requestRow.getTenantId())
                    || !candidate.ownerJiacn().equals(requestRow.getOwnerJiacn())
                    || !candidate.clientId().equals(requestRow.getClientId())
                    || !candidate.requestId().equals(requestRow.getRequestId())
                    || !request.conversationId().equals(requestRow.getConversationId())
                    || events.updateRequestState(requestRow, "OUTPUT_COMMITTED", System.currentTimeMillis()) != 1)
                throw new IllegalStateException("Unable to finalize request projection");
        }
        return count;
    }

    /** History is a projection of durable, live-generation source links, not model metadata. */
    @Transactional(readOnly = true)
    public Map<String, List<Part>> partsFor(PersonalWorkspaceExecutionService.OwnerScope owner,
            String conversationId) {
        if (owner == null || !safe(conversationId)) return Map.of();
        var rows = jdbc.query("""
                SELECT CAST(a.message_id AS CHAR),a.part_id,a.kind,a.asset_id,
                  a.content_mime_type,a.sha256,a.byte_length,a.revision
                FROM chat_conversation_asset a
                JOIN chat_conversation c ON c.id=CAST(a.conversation_id AS UNSIGNED)
                  AND BINARY CAST(c.id AS CHAR)=BINARY a.conversation_id
                  AND c.deleted_at IS NULL AND c.lifecycle_generation=a.conversation_generation
                  AND BINARY c.tenant_id=BINARY a.tenant_id AND BINARY c.client_id=BINARY a.client_id
                  AND BINARY c.jiacn=BINARY a.owner_jiacn
                JOIN chat_message m ON m.id=a.message_id
                  AND BINARY m.tenant_id=BINARY a.tenant_id AND BINARY m.client_id=BINARY a.client_id
                  AND BINARY m.jiacn=BINARY a.owner_jiacn AND BINARY m.conversation_id=BINARY a.conversation_id
                WHERE BINARY a.tenant_id=BINARY ? AND BINARY a.owner_jiacn=BINARY ?
                  AND BINARY a.client_id=BINARY ? AND BINARY a.conversation_id=BINARY ?
                ORDER BY a.message_id,a.created_at,a.part_id
                """, (rs, n) -> Map.entry(rs.getString(1), new Part(rs.getString(2), rs.getString(3),
                "ready", rs.getString(4), rs.getString(5), "", Long.toString(rs.getLong(8)),
                rs.getString(6), rs.getLong(7))), owner.tenantId(), owner.ownerJiacn(),
                owner.clientId(), conversationId);
        Map<String, List<Part>> collected = new java.util.LinkedHashMap<>();
        for (var row : rows) collected.computeIfAbsent(row.getKey(), ignored -> new java.util.ArrayList<>())
                .add(row.getValue());
        var result = new java.util.LinkedHashMap<String, List<Part>>();
        collected.forEach((key, values) -> result.put(key, List.copyOf(values)));
        return Map.copyOf(result);
    }

    public record Part(String partId, String kind, String state, String assetId,
            String mime, String filename, String revision, String contentHash, long byteLength) { }

    /** Read only an exact owner/tenant/generation relation; caller still rechecks source bytes. */
    @Transactional(readOnly = true)
    public Asset find(PersonalWorkspaceExecutionService.OwnerScope owner, String conversationId, String assetId) {
        if (owner == null || !safe(conversationId) || !safe(assetId)) return null;
        return findCurrent(owner, conversationId, assetId, false);
    }

    /** Owner-scoped, read-only output -> asset mapping. Absent projection stays absent. */
    @Transactional(readOnly = true)
    public Asset findOutput(PersonalWorkspaceExecutionService.OwnerScope owner, String conversationId,
            String requestId, String stepId, String executionId, String runId, String outputId) {
        if (owner == null || !safe(conversationId) || !safe(requestId) || !safe(stepId)
                || !safe(executionId) || !safe(runId) || !safe(outputId)) return null;
        var asset = findCurrent(owner, conversationId, "ast_" + outputSuffix(owner, stepId, outputId), false);
        if (asset == null || !owner.tenantId().equals(asset.tenantId())
                || !owner.ownerJiacn().equals(asset.ownerJiacn()) || !owner.clientId().equals(asset.clientId())
                || !conversationId.equals(asset.conversationId()) || !requestId.equals(asset.requestId())
                || !stepId.equals(asset.stepId()) || !executionId.equals(asset.executionId())
                || !runId.equals(asset.runId()) || !outputId.equals(asset.outputId())) return null;
        return asset;
    }

    private static String outputSuffix(PersonalWorkspaceExecutionService.OwnerScope owner,
            String stepId, String outputId) {
        return digest(owner.tenantId() + "\n" + owner.ownerJiacn() + "\n" + owner.clientId()
                + "\n" + stepId + "\n" + outputId).substring(0, 32);
    }

    // After locking the conversation, use a current locking read rather than an
    // earlier repeatable-read snapshot. Concurrent relays must observe the first
    // committed projection instead of attempting another message/asset insert.
    private Asset findCurrent(PersonalWorkspaceExecutionService.OwnerScope owner,
            String conversationId, String assetId, boolean lock) {
        List<Asset> found = jdbc.query("""
                SELECT a.asset_id,a.tenant_id,a.owner_jiacn,a.client_id,a.conversation_id,
                  a.conversation_generation,a.request_id,a.step_id,a.execution_id,a.run_id,a.output_id,
                  a.message_id,a.part_id,a.kind,a.content_mime_type,a.sha256,a.byte_length,a.revision
                FROM chat_conversation_asset a
                JOIN chat_conversation c ON c.id=CAST(a.conversation_id AS UNSIGNED)
                  AND BINARY CAST(c.id AS CHAR)=BINARY a.conversation_id
                  AND c.deleted_at IS NULL AND c.lifecycle_generation=a.conversation_generation
                  AND BINARY c.tenant_id=BINARY a.tenant_id AND BINARY c.client_id=BINARY a.client_id
                  AND BINARY c.jiacn=BINARY a.owner_jiacn
                JOIN chat_message m ON m.id=a.message_id
                  AND BINARY m.tenant_id=BINARY a.tenant_id AND BINARY m.client_id=BINARY a.client_id
                  AND BINARY m.jiacn=BINARY a.owner_jiacn AND BINARY m.conversation_id=BINARY a.conversation_id
                WHERE a.asset_id=? AND BINARY a.asset_id=BINARY ?
                  AND BINARY a.tenant_id=BINARY ? AND BINARY a.owner_jiacn=BINARY ?
                  AND BINARY a.client_id=BINARY ? AND BINARY a.conversation_id=BINARY ?
                """ + (lock ? " FOR UPDATE" : ""), (rs,n) -> new Asset(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
                rs.getString(5),rs.getLong(6),rs.getString(7),rs.getString(8),rs.getString(9),
                rs.getString(10),rs.getString(11),Long.toString(rs.getLong(12)),rs.getString(13),
                rs.getString(14),rs.getString(15),rs.getString(16),rs.getLong(17),rs.getLong(18)),
                assetId,assetId,owner.tenantId(),owner.ownerJiacn(),owner.clientId(),conversationId);
        return found.size() == 1 ? found.getFirst() : null;
    }

    private static boolean safe(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}");
    }
    private static boolean safeMime(String value) {
        return value != null && value.matches("[a-z0-9.+-]+/[a-z0-9.+-]+");
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
