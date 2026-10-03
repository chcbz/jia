package cn.jia.chat.service;

import cn.jia.agent.security.AgentRuntimeAuthentication;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.service.PersonalWorkspaceStorage;
import cn.jia.chat.archive.conversation.ChatConversationArchiveStore;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.ChatBountyBindingStore;
import cn.jia.chat.deliberation.ChatContextSnapshotEntity;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatDeliberationStates;
import cn.jia.chat.deliberation.ChatRequestEntity;
import cn.jia.chat.deliberation.ChatTurnEntity;
import cn.jia.chat.deliberation.ChatTypedDeliberationStore;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.handler.TypedInspectionSessionRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Linearizable current-authority boundary for INSPECT bytes and late finals. */
@Service
public class ChatInspectionAuthorityService {
    private static final Set<String> ACTIVE_TURN_STATES = Set.of(
            ChatDeliberationStates.RECEIVED, ChatDeliberationStates.QUEUED,
            ChatDeliberationStates.DISPATCHED, ChatDeliberationStates.STREAMING,
            ChatDeliberationStates.UNKNOWN, ChatDeliberationStates.RECOVERY_REQUIRED);

    public record Content(String filename, String mimeType, byte[] bytes) {
        public Content {
            if (filename == null || filename.isBlank() || mimeType == null || mimeType.isBlank()
                    || bytes == null) throw new IllegalArgumentException("Invalid inspection content");
            bytes = bytes.clone();
        }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    private final AgentTaskMutationTransaction tasks;
    private final ChatBountyBindingStore bindings;
    private final ChatConversationDao conversations;
    private final ChatDeliberationDao deliberation;
    private final ChatTypedDeliberationStore typed;
    private final TypedInspectionSessionRegistry sessions;
    private final JuyitingConversationScopeService scopes;
    private final JdbcTemplate jdbc;
    private final PersonalWorkspaceStorage storage;
    private final PersonalWorkspaceExecutionService executions;
    private final ChatConversationArchiveStore archive;

    public ChatInspectionAuthorityService(AgentTaskMutationTransaction tasks,
            ChatBountyBindingStore bindings, ChatConversationDao conversations,
            ChatDeliberationDao deliberation, ChatTypedDeliberationStore typed,
            TypedInspectionSessionRegistry sessions, JuyitingConversationScopeService scopes,
            JdbcTemplate jdbc, PersonalWorkspaceStorage storage,
            PersonalWorkspaceExecutionService executions, ChatConversationArchiveStore archive) {
        this.tasks = Objects.requireNonNull(tasks);
        this.bindings = Objects.requireNonNull(bindings);
        this.conversations = Objects.requireNonNull(conversations);
        this.deliberation = Objects.requireNonNull(deliberation);
        this.typed = Objects.requireNonNull(typed);
        this.sessions = Objects.requireNonNull(sessions);
        this.scopes = Objects.requireNonNull(scopes);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.storage = Objects.requireNonNull(storage);
        this.executions = Objects.requireNonNull(executions);
        this.archive = Objects.requireNonNull(archive);
    }

    @Transactional(rollbackFor = Exception.class)
    public Content read(AgentRuntimeAuthentication.Scope runtime, String requestId, String turnId,
            String sourceRefId, String suppliedManifestDigest) {
        if (runtime == null || !exact(runtime.tenantId(), 50) || !exact(runtime.ownerJiacn(), 50)
                || !exact(runtime.clientId(), 50) || !exact(runtime.agentId(), 100)
                || !exact(runtime.runtimeInstanceId(), 100) || !exact(requestId, 100)
                || !exact(turnId, 100) || !exact(sourceRefId, 100)
                || suppliedManifestDigest == null
                || !suppliedManifestDigest.matches("sha256:[0-9a-f]{64}")) throw unavailable();

        ChatRequestEntity request = deliberation.findRequest(
                runtime.tenantId(), runtime.ownerJiacn(), runtime.clientId(), requestId);
        ChatTurnEntity locator = deliberation.findTurn(
                runtime.tenantId(), runtime.ownerJiacn(), runtime.clientId(), turnId);
        if (request == null || locator == null || !requestId.equals(locator.getRequestId())
                || !Objects.equals(request.getRequestRevision(), locator.getRequestRevision())
                || !Objects.equals(request.getConversationId(), locator.getConversationId())
                || !Objects.equals(request.getConversationGeneration(), locator.getConversationGeneration())
                || !runtime.agentId().equals(locator.getTargetAgentId())
                || !"INSPECT".equals(locator.getRoute())) throw unavailable();

        ChatTypedDeliberationStore.Scope scope = new ChatTypedDeliberationStore.Scope(
                runtime.tenantId(), runtime.ownerJiacn(), runtime.clientId(),
                request.getConversationId(), request.getConversationGeneration());
        ChatTypedDeliberationStore.Admission admission = typed.findAdmissionByRequest(scope, requestId);
        if (admission == null) throw unavailable();
        Map<String, Object> inspection = ChatTypedInspectionContextService.inspection(
                admission.sourceCatalogJson());
        String taskId = admission.taskId();
        return tasks.executeWithLockedTaskRootInOwnerScope(runtime.tenantId(), runtime.clientId(),
                runtime.ownerJiacn(), taskId, root -> {
                    Locked locked = lockAndVerify(scope, admission, locator, inspection,
                            root == null ? null : root.getAssignedAgentId(),
                            root == null ? null : root.getTaskVersion(), runtime.agentId(),
                            suppliedManifestDigest, false);
                    Map<String, Object> source = source(locked.sources(), sourceRefId);
                    Map<String, Object> selector = object(source.get("selector"));
                    Content content = "TASK_LINKED_WORKSPACE_VERSION".equals(selector.get("kind"))
                            ? workspaceContent(scope, taskId, selector, source)
                            : assetContent(scope, taskId, locked.turn().getTargetAgentId(),
                                    selector, source);
                    verifyBytes(content, source);
                    return content;
                });
    }

    /** Called before the conversation lock for an INSPECT final. */
    public <T> T withFinalAuthority(ChatTurnEntity locator, Supplier<T> callback) {
        if (locator == null || !"INSPECT".equals(locator.getRoute()) || callback == null) {
            throw unavailable();
        }
        ChatTypedDeliberationStore.Scope scope = new ChatTypedDeliberationStore.Scope(
                locator.getTenantId(), locator.getOwnerJiacn(), locator.getClientId(),
                locator.getConversationId(), locator.getConversationGeneration());
        ChatTypedDeliberationStore.Admission admission = typed.findAdmissionByRequest(
                scope, locator.getRequestId());
        if (admission == null) throw unavailable();
        Map<String, Object> inspection = ChatTypedInspectionContextService.inspection(
                admission.sourceCatalogJson());
        return tasks.executeWithLockedTaskRootInOwnerScope(scope.tenantId(), scope.clientId(),
                scope.ownerJiacn(), admission.taskId(), root -> {
                    Locked locked = lockAndVerify(scope, admission, locator, inspection,
                            root == null ? null : root.getAssignedAgentId(),
                            root == null ? null : root.getTaskVersion(), locator.getTargetAgentId(),
                            string(inspection.get("manifestDigest")), true);
                    for (Map<String, Object> source : locked.sources()) {
                        verifySourceCurrent(scope, admission.taskId(), locked.turn().getTargetAgentId(),
                                source);
                    }
                    return callback.get();
                });
    }

    @SuppressWarnings("unchecked")
    private Locked lockAndVerify(ChatTypedDeliberationStore.Scope scope,
            ChatTypedDeliberationStore.Admission admission, ChatTurnEntity locator,
            Map<String, Object> inspection, String rootAgent, Long taskVersion,
            String expectedAgent, String manifestDigest, boolean allowCommittedFinal) {
        ChatBountyBindingStore.Binding binding;
        try {
            binding = bindings.lock(new ChatBountyBindingStore.Scope(
                    scope.tenantId(), scope.ownerJiacn(), scope.clientId()), admission.taskId());
        } catch (IllegalStateException missing) {
            throw conflict("Inspection assignment changed");
        } catch (RuntimeException unavailable) {
            throw serviceUnavailable("Inspection assignment service is unavailable");
        }
        if (binding == null || binding.conversationId() == null
                || !scope.conversationId().equals(Long.toString(binding.conversationId()))
                || binding.assignmentRevision() != admission.assignmentRevision()
                || taskVersion == null || taskVersion < admission.assignmentRevision()
                || !expectedAgent.equals(rootAgent)) throw conflict("Inspection assignment changed");

        ChatConversationEntity conversation = conversations.lockScopedById(
                scope.ownerJiacn(), scope.clientId(), scope.conversationId());
        if (conversation == null || conversation.getDeletedAt() != null
                || !scope.tenantId().equals(conversation.getTenantId())
                || !scope.ownerJiacn().equals(conversation.getJiacn())
                || !scope.clientId().equals(conversation.getClientId())
                || !scope.conversationId().equals(Long.toString(conversation.getId()))
                || conversation.getLifecycleGeneration() == null
                || conversation.getLifecycleGeneration() != scope.conversationGeneration()
                || !admission.taskId().equals(conversation.getTaskId())
                || !"juyiting".equals(conversation.getConversationType())
                || !"bounty".equals(conversation.getConversationScopeType())
                || !("task:" + admission.taskId()).equals(conversation.getConversationScopeKey())
                || !List.of(expectedAgent).equals(
                        scopes.parsePersistedTargetAgentIds(conversation.getTargetAgentIds()))) {
            throw conflict("Inspection conversation changed");
        }

        ChatRequestEntity request = deliberation.lockRequest(scope.tenantId(), scope.ownerJiacn(),
                scope.clientId(), admission.requestId(), admission.requestRevision());
        ChatTypedDeliberationStore.Admission lockedAdmission = typed.findAdmissionByKey(
                scope, admission.idempotencyKey(), true);
        ChatTurnEntity turn = deliberation.lockTurn(scope.tenantId(), scope.ownerJiacn(),
                scope.clientId(), locator.getTurnId());
        if (lockedAdmission == null
                || !lockedAdmission.admissionId().equals(admission.admissionId())
                || !lockedAdmission.requestDigest().equals(admission.requestDigest())
                || !lockedAdmission.bodyDigest().equals(admission.bodyDigest())
                || !lockedAdmission.requestId().equals(admission.requestId())) {
            throw conflict("Inspection admission changed");
        }
        if (request == null || turn == null
                || !admission.requestId().equals(request.getRequestId())
                || request.getRequestRevision() == null
                || request.getRequestRevision() != admission.requestRevision()
                || !scope.conversationId().equals(request.getConversationId())
                || request.getConversationGeneration() == null
                || request.getConversationGeneration() != scope.conversationGeneration()
                || !admission.requestId().equals(turn.getRequestId())
                || turn.getRequestRevision() == null
                || turn.getRequestRevision() != admission.requestRevision()
                || !scope.conversationId().equals(turn.getConversationId())
                || turn.getConversationGeneration() == null
                || turn.getConversationGeneration() != scope.conversationGeneration()
                || !expectedAgent.equals(turn.getTargetAgentId())
                || !"INSPECT".equals(turn.getRoute())
                || !acceptableState(request, turn, allowCommittedFinal)) {
            throw conflict("Inspection request is no longer active");
        }

        ChatContextSnapshotEntity snapshot = deliberation.findSnapshot(scope.tenantId(),
                scope.ownerJiacn(), scope.clientId(), turn.getSnapshotId());
        if (snapshot == null || !turn.getContextDigest().equals(snapshot.getContextDigest())
                || !turn.getRequestId().equals(snapshot.getRequestId())
                || !Objects.equals(turn.getRequestRevision(), snapshot.getRequestRevision())
                || !turn.getConversationId().equals(snapshot.getConversationId())
                || !Objects.equals(turn.getConversationGeneration(), snapshot.getConversationGeneration())
                || !turn.getTargetAgentId().equals(snapshot.getTargetAgentId())
                || !turn.getRoute().equals(snapshot.getRoute())) {
            throw conflict("Inspection snapshot changed");
        }
        Map<String, Object> snapshotFacts = parseMap(snapshot.getFactsManifestJson());
        if (snapshotFacts.containsKey("typedDeliberation")) {
            throw conflict("Inspection snapshot is ambiguous");
        }
        Map<String, Object> snapshotInspection = object(snapshotFacts.get("typedInspection"));
        if (!CanonicalContextJson.write(inspection).equals(
                CanonicalContextJson.write(snapshotInspection))
                || !Objects.equals(manifestDigest, inspection.get("manifestDigest"))) {
            throw conflict("Inspection manifest changed");
        }

        Map<String, Object> manifest = object(inspection.get("manifest"));
        Map<String, Object> manifestScope = object(manifest.get("scope"));
        if (!scope.tenantId().equals(manifestScope.get("tenantId"))
                || !scope.ownerJiacn().equals(manifestScope.get("ownerJiacn"))
                || !scope.clientId().equals(manifestScope.get("clientId"))
                || !scope.conversationId().equals(manifestScope.get("conversationId"))
                || !Long.toString(scope.conversationGeneration()).equals(
                        manifestScope.get("conversationGeneration"))
                || !admission.taskId().equals(manifestScope.get("taskId"))
                || !Long.toString(admission.assignmentRevision()).equals(
                        manifestScope.get("assignmentRevision"))
                || !admission.requestId().equals(manifestScope.get("requestId"))
                || !Long.toString(admission.requestRevision()).equals(
                        manifestScope.get("requestRevision"))
                || !expectedAgent.equals(manifestScope.get("targetAgentId"))) {
            throw conflict("Inspection manifest scope changed");
        }
        Map<String, Object> profile = object(manifest.get("profile"));
        try {
            TypedInspectionSessionRegistry.Ready ready = sessions.requireSingleReady(
                    new TypedInspectionSessionRegistry.Scope(
                            scope.tenantId(), scope.ownerJiacn(), scope.clientId()), expectedAgent);
            if (!CanonicalContextJson.write(profile).equals(
                    CanonicalContextJson.write(ready.manifestProfile()))) {
                throw conflict("Inspection profile changed");
            }
        } catch (IllegalStateException unavailable) {
            throw serviceUnavailable("Inspection profile is unavailable");
        }
        Object rawSources = manifest.get("sources");
        if (!(rawSources instanceof List<?> list) || list.isEmpty()) {
            throw conflict("Inspection manifest is invalid");
        }
        return new Locked(turn, (List<Map<String, Object>>) (List<?>) list);
    }


    private static boolean acceptableState(ChatRequestEntity request, ChatTurnEntity turn,
            boolean allowCommittedFinal) {
        if (ACTIVE_TURN_STATES.contains(turn.getState())) {
            return turn.getFinalDigest() == null && turn.getFinalMessageId() == null
                    && ChatDeliberationStates.RUNNING.equals(request.getAggregateState());
        }
        return allowCommittedFinal
                && Set.of(ChatDeliberationStates.FINAL_PERSISTED, ChatDeliberationStates.PUBLISHED)
                        .contains(turn.getState())
                && turn.getFinalDigest() != null && turn.getFinalMessageId() != null
                && ChatDeliberationStates.COMPLETED.equals(request.getAggregateState());
    }

    private void verifySourceCurrent(ChatTypedDeliberationStore.Scope scope, String taskId,
            String agentId, Map<String, Object> source) {
        Map<String, Object> selector = object(source.get("selector"));
        if ("TASK_LINKED_WORKSPACE_VERSION".equals(selector.get("kind"))) {
            Map<String, Object> row = workspaceRow(scope, taskId, selector);
            if (!sameSource(text(row, "content_mime_type"), text(row, "content_hash"),
                    number(row, "byte_length"), source)) {
                throw conflict("Inspection workspace source changed");
            }
            return;
        }
        if ("CURRENT_CONVERSATION_ASSET".equals(selector.get("kind"))) {
            ChatConversationArchiveStore.Source actual = archive.findAuthorizedSourceForUpdate(
                    new ChatConversationArchiveStore.Scope(
                            scope.tenantId(), scope.ownerJiacn(), scope.clientId()),
                    scope.conversationId(), string(selector.get("assetId")),
                    Long.parseLong(string(selector.get("assetRevision"))), agentId);
            if (actual == null || actual.conversationGeneration() != scope.conversationGeneration()
                    || !taskId.equals(actual.taskId())
                    || !sameSource(actual.contentMimeType(), actual.sha256(),
                            actual.byteLength(), source)) {
                throw conflict("Inspection asset changed");
            }
            return;
        }
        throw invalid("Unsupported inspection selector");
    }

    private Content workspaceContent(ChatTypedDeliberationStore.Scope scope, String taskId,
            Map<String, Object> selector, Map<String, Object> source) {
        Map<String, Object> row = workspaceRow(scope, taskId, selector);
        if (!sameSource(text(row, "content_mime_type"), text(row, "content_hash"),
                number(row, "byte_length"), source)) {
            throw conflict("Inspection workspace source changed");
        }
        try {
            PersonalWorkspaceStorage.StoredContent stored = storage.read(
                    new PersonalWorkspaceStorage.Scope(
                            scope.tenantId(), scope.clientId(), scope.ownerJiacn()),
                    text(row, "storage_uri"), string(source.get("sha256")),
                    Long.parseLong(string(source.get("byteLength"))),
                    string(source.get("mimeType")));
            return new Content(text(row, "original_filename"), stored.mimeType(), stored.content());
        } catch (ChatDeliberationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw serviceUnavailable("Inspection storage is unavailable");
        }
    }

    private Content assetContent(ChatTypedDeliberationStore.Scope scope, String taskId,
            String agentId, Map<String, Object> selector, Map<String, Object> source) {
        ChatConversationArchiveStore.Source actual = archive.findAuthorizedSourceForUpdate(
                new ChatConversationArchiveStore.Scope(
                        scope.tenantId(), scope.ownerJiacn(), scope.clientId()),
                scope.conversationId(), string(selector.get("assetId")),
                Long.parseLong(string(selector.get("assetRevision"))), agentId);
        if (actual == null || actual.conversationGeneration() != scope.conversationGeneration()
                || !taskId.equals(actual.taskId())
                || !sameSource(actual.contentMimeType(), actual.sha256(), actual.byteLength(), source)) {
            throw conflict("Inspection asset changed");
        }
        try {
            PersonalWorkspaceExecutionService.ConversationOutput value = executions.readConversationOutput(
                    new PersonalWorkspaceExecutionService.OwnerScope(
                            scope.tenantId(), scope.clientId(), scope.ownerJiacn()),
                    taskId, actual.runId(), actual.outputId());
            return new Content(value.originalFilename(), value.contentMimeType(), value.bytes());
        } catch (RuntimeException failure) {
            throw serviceUnavailable("Inspection asset storage is unavailable");
        }
    }

    private Map<String, Object> workspaceRow(ChatTypedDeliberationStore.Scope scope, String taskId,
            Map<String, Object> selector) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT v.original_filename,v.content_mime_type,v.content_hash,v.byte_length,v.storage_uri
                FROM agent_personal_workspace_task_file_link l
                JOIN agent_personal_workspace_file f ON f.file_id=l.file_id
                  AND BINARY f.tenant_id=BINARY l.tenant_id
                  AND BINARY f.client_id=BINARY l.client_id
                  AND BINARY f.owner_jiacn=BINARY l.owner_jiacn AND f.state='ACTIVE'
                JOIN agent_personal_workspace_file_version v ON v.file_id=l.file_id AND v.version=l.file_version
                  AND BINARY v.tenant_id=BINARY l.tenant_id
                  AND BINARY v.client_id=BINARY l.client_id
                  AND BINARY v.owner_jiacn=BINARY l.owner_jiacn
                WHERE BINARY l.tenant_id=BINARY ? AND BINARY l.owner_jiacn=BINARY ?
                  AND BINARY l.client_id=BINARY ? AND BINARY l.task_id=BINARY ?
                  AND BINARY l.file_id=BINARY ? AND l.file_version=?
                  AND l.link_role=? AND BINARY l.link_role=BINARY ? AND l.link_state='ACTIVE' FOR UPDATE
                """, scope.tenantId(), scope.ownerJiacn(), scope.clientId(), taskId,
                string(selector.get("fileId")),
                Integer.parseInt(string(selector.get("version"))),
                string(selector.get("purpose")), string(selector.get("purpose")));
        if (rows.size() != 1) throw conflict("Inspection workspace link changed");
        return rows.getFirst();
    }

    private static void verifyBytes(Content content, Map<String, Object> source) {
        byte[] bytes = content.bytes();
        if (!Objects.equals(content.mimeType(), source.get("mimeType"))
                || bytes.length != Long.parseLong(string(source.get("byteLength")))
                || !sha256(bytes).equals(source.get("sha256"))) {
            throw conflict("Inspection content differs from manifest");
        }
    }

    private static boolean sameSource(String mimeType, String sha256, long byteLength,
            Map<String, Object> source) {
        return Objects.equals(mimeType, source.get("mimeType"))
                && Objects.equals(sha256, source.get("sha256"))
                && Long.toString(byteLength).equals(source.get("byteLength"));
    }

    private static Map<String, Object> source(List<Map<String, Object>> sources, String id) {
        List<Map<String, Object>> matches = sources.stream()
                .filter(value -> id.equals(value.get("sourceRefId"))).toList();
        if (matches.size() != 1) throw unavailable();
        return matches.getFirst();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseMap(String json) {
        try {
            Object value = cn.jia.core.util.JsonUtil.getMapper().readValue(json, Map.class);
            if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        } catch (Exception ignored) {
            // Mapped below to a non-sensitive conflict.
        }
        throw conflict("Inspection snapshot is invalid");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        throw conflict("Inspection object is invalid");
    }

    private static String string(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw conflict("Inspection source metadata is invalid");
        }
        return text;
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) value = row.get(key.toUpperCase());
        if (!(value instanceof String text) || text.isBlank()) {
            throw serviceUnavailable("Inspection source metadata is unavailable");
        }
        return text;
    }

    private static long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) value = row.get(key.toUpperCase());
        if (!(value instanceof Number number) || number.longValue() < 0) {
            throw serviceUnavailable("Inspection source metadata is unavailable");
        }
        return number.longValue();
    }

    private static boolean exact(String value, int max) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.codePointCount(0, value.length()) <= max
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private record Locked(ChatTurnEntity turn, List<Map<String, Object>> sources) { }

    private static ChatDeliberationException invalid(String message) {
        return new ChatDeliberationException(ChatDeliberationException.Reason.INVALID_REQUEST, message);
    }

    private static ChatDeliberationException unavailable() {
        return new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,
                "Inspection input is unavailable");
    }

    private static ChatDeliberationException conflict(String message) {
        return new ChatDeliberationException(ChatDeliberationException.Reason.CONFLICT, message);
    }

    private static ChatDeliberationException serviceUnavailable(String message) {
        return new ChatDeliberationException(ChatDeliberationException.Reason.PERSISTENCE_ERROR, message);
    }
}
