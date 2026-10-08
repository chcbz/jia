package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.chat.api.ChatTypedInspectionWire;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.ChatBountyBindingStore;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatTypedDeliberationStore;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.handler.dto.ChatMessageDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatTypedInspectionAdmissionServiceTest {
    private final AgentTaskMutationTransaction tasks = mock(AgentTaskMutationTransaction.class);
    private final ChatBountyBindingStore bindings = mock(ChatBountyBindingStore.class);
    private final ChatConversationDao conversations = mock(ChatConversationDao.class);
    private final JuyitingConversationScopeService scopes = mock(JuyitingConversationScopeService.class);
    private final ChatDeliberationService deliberation = mock(ChatDeliberationService.class);
    private final ChatDeliberationDao events = mock(ChatDeliberationDao.class);
    private final ChatTypedInspectionContextService contexts = mock(ChatTypedInspectionContextService.class);
    private final ChatTypedDeliberationService typed = mock(ChatTypedDeliberationService.class);
    private final ChatTypedDeliberationStore store = mock(ChatTypedDeliberationStore.class);
    private final ChatTypedInspectionAdmissionService service = new ChatTypedInspectionAdmissionService(
            tasks, bindings, conversations, scopes, deliberation, events, contexts, typed);
    private final ServerResolvedSender sender = new ServerResolvedSender(
            "user", "Human", "owner", "client", DisplayNameSource.JIACN);
    private final ChatTypedDeliberationStore.Scope scope =
            new ChatTypedDeliberationStore.Scope("0", "owner", "client", "42", 1);

    @BeforeEach
    void ready() {
        when(typed.store()).thenReturn(store);
        when(tasks.executeWithLockedTaskRootInOwnerScope(
                eq("0"), eq("client"), eq("owner"), eq("task"), any()))
                .thenAnswer(invocation -> {
                    AgentTaskMutationTransaction.LockedTaskMutation<?> callback = invocation.getArgument(4);
                    return callback.apply(new AgentTaskMetaEntity().setTaskVersion(4L)
                            .setAssignedAgentId("agent"));
                });
        when(bindings.lock(new ChatBountyBindingStore.Scope("0", "owner", "client"), "task"))
                .thenReturn(new ChatBountyBindingStore.Binding(3, 42L));
        ChatConversationEntity conversation = conversation();
        when(conversations.lockScopedById("owner", "client", "42")).thenReturn(conversation);
        when(conversations.findScopedById("owner", "client", "42")).thenReturn(conversation);
        when(scopes.parsePersistedTargetAgentIds("[\"agent\"]")).thenReturn(List.of("agent"));
    }

    @Test
    void freshAdmissionUsesTrustedInspectionPathAndPersistsOneAtomicReceipt() {
        ChatTypedInspectionContextService.Context context = context();
        when(contexts.resolve(any(), eq(selectors()))).thenReturn(context);
        when(deliberation.admitInspection(eq("0"), eq(sender), eq("42"), eq(1L), any(), any(),
                isNull(), eq(context.typedInspection()), any())).thenAnswer(invocation -> {
                    ChatMessageDTO input = invocation.getArgument(5);
                    return admitted(input.getRequestId());
                });
        when(events.eventHighWatermark("0", "owner", "client", "42", 1)).thenReturn(9L);
        when(store.insertAdmission(any())).thenReturn(1);

        ChatTypedInspectionWire.Accepted accepted = service.admit("0", sender, "42", "key", command());
        assertFalse(accepted.replay());
        assertEquals(List.of("turn-1"), accepted.turnIds());
        assertTrue(accepted.typedOutcomeUrl().endsWith("/inspection-outcome"));
        ArgumentCaptor<ChatTypedDeliberationStore.Admission> row =
                ArgumentCaptor.forClass(ChatTypedDeliberationStore.Admission.class);
        verify(store).insertAdmission(row.capture());
        assertEquals(context.admissionEnvelopeJson(), row.getValue().sourceCatalogJson());
        assertFalse(row.getValue().bodyDigest().equals(row.getValue().requestDigest()));
    }

    @Test
    void crossContractSameKeyConflictsBeforeContextOrDispatch() {
        ChatTypedDeliberationStore.Admission chat = admission("[]", bodyDigest(command()));
        when(store.findAdmissionByKey(scope, "key", true)).thenReturn(chat);
        ChatDeliberationException failure = assertThrows(ChatDeliberationException.class,
                () -> service.admit("0", sender, "42", "key", command()));
        assertEquals(ChatDeliberationException.Reason.CONFLICT, failure.reason());
        verifyNoInteractions(contexts, deliberation);
    }

    @Test
    void recoveryIsReadOnlyAndRejectsCrossContractKey() {
        ChatTypedInspectionContextService.Context context = context();
        when(store.findAdmissionByKey(scope, "key", false))
                .thenReturn(admission(context.admissionEnvelopeJson(), bodyDigest(command())));
        ChatTypedInspectionWire.Accepted recovered = service.recover("0", sender, "42", "key");
        assertTrue(recovered.replay());
        verify(tasks, never()).executeWithLockedTaskRootInOwnerScope(
                anyString(), anyString(), anyString(), anyString(), any());
        verify(store, never()).insertAdmission(any());
        verifyNoInteractions(contexts, deliberation, events);

        when(store.findAdmissionByKey(scope, "key", false))
                .thenReturn(admission("[]", bodyDigest(command())));
        ChatDeliberationException conflict = assertThrows(ChatDeliberationException.class,
                () -> service.recover("0", sender, "42", "key"));
        assertEquals(ChatDeliberationException.Reason.CONFLICT, conflict.reason());
    }

    @Test
    void sameKeyChangedBodyConflictsWithoutSecondDispatch() {
        ChatTypedInspectionContextService.Context context = context();
        when(store.findAdmissionByKey(scope, "key", true))
                .thenReturn(admission(context.admissionEnvelopeJson(), bodyDigest(command())));
        ChatTypedInspectionWire.Command changed = new ChatTypedInspectionWire.Command("DISCUSSION", "task", 3,
                "changed", null, null, null, null, selectors());
        ChatDeliberationException failure = assertThrows(ChatDeliberationException.class,
                () -> service.admit("0", sender, "42", "key", changed));
        assertEquals(ChatDeliberationException.Reason.CONFLICT, failure.reason());
        verifyNoInteractions(contexts, deliberation);
    }

    private static ChatConversationEntity conversation() {
        ChatConversationEntity value = new ChatConversationEntity().setId(42L).setJiacn("owner")
                .setConversationType("juyiting").setConversationScopeType("bounty")
                .setConversationScopeKey("task:task").setTaskId("task")
                .setTargetAgentIds("[\"agent\"]").setLifecycleGeneration(1L);
        value.setTenantId("0");
        value.setClientId("client");
        return value;
    }

    private static ChatTypedInspectionWire.Command command() {
        return new ChatTypedInspectionWire.Command("DISCUSSION", "task", 3, "inspect",
                null, null, null, null, selectors());
    }

    private static List<ChatTypedInspectionWire.SourceSelector> selectors() {
        return List.of(new ChatTypedInspectionWire.SourceSelector(
                "TASK_LINKED_WORKSPACE_VERSION", "file", "2", "REFERENCE", null, null));
    }

    private static ChatTypedInspectionContextService.Context context() {
        Map<String, Object> selector = ChatTypedInspectionWire.selectorMap(selectors().getFirst());
        Map<String, Object> source = mapOf("sourceRefId", "source_" + "1".repeat(40),
                "selector", selector, "mediaKind", "text", "mimeType", "text/plain",
                "byteLength", "4", "sha256", "a".repeat(64), "carrier", "DIRECT_TEXT",
                "carrierContractDigest", digest('b'));
        Map<String, Object> manifest = mapOf("schemaVersion", 1, "purpose", "INSPECT",
                "scope", mapOf("tenantId", "0", "ownerJiacn", "owner", "clientId", "client",
                        "conversationId", "42", "conversationGeneration", "1", "taskId", "task",
                        "assignmentRevision", "3", "requestId",
                        "mmd-inspection-request_4eb2be5b8db7b02aa99019b1cf501552de83f583",
                        "requestRevision", "1", "targetAgentId", "agent"),
                "profile", mapOf("profileId", "profile", "engineContractId", "engine",
                        "enginePolicyDigest", digest('c'), "toolPolicyDigest", digest('d'),
                        "inputPolicyDigest", digest('e')),
                "sources", List.of(source));
        String manifestDigest = ChatDeliberationService.digest(manifest);
        String requestId = (String) map(manifest.get("scope")).get("requestId");
        String authorizationId = "inspection_" + sha256(requestId + "\0" + manifestDigest).substring(0, 40);
        Map<String, Object> typedInspection = mapOf("schemaVersion", 1,
                "contract", ChatTypedInspectionWire.CONTRACT, "purpose", "INSPECT",
                "discussionFacts", mapOf("schemaVersion", 1, "referenceMode", "AVAILABLE",
                        "supportedOperations", List.of("GENERATE_IMAGE", "EDIT_IMAGE"),
                        "availableSources", List.of(mapOf("sourceRefId", source.get("sourceRefId"),
                                "kind", "TASK_WORKSPACE_FILE", "mediaType", "text"))),
                "manifest", manifest, "manifestDigest", manifestDigest,
                "authorizationId", authorizationId);
        Map<String, Object> envelope = mapOf("schemaVersion", 1,
                "contract", ChatTypedInspectionWire.CONTRACT, "purpose", "INSPECT",
                "typedInspection", typedInspection);
        String json = CanonicalContextJson.write(envelope);
        ChatTypedInspectionContextService.parseEnvelope(json);
        return new ChatTypedInspectionContextService.Context(typedInspection, json, selectors(), Map.of());
    }

    private static ChatTypedDeliberationStore.Admission admission(String envelope, String bodyDigest) {
        return new ChatTypedDeliberationStore.Admission("admission", scopeStatic(), "key", digest('f'),
                bodyDigest, "DISCUSSION", "task", 3, null, null, "request-old", 1, 7,
                "[\"turn-old\"]", envelope, "ADMITTED", 0, 9, 1);
    }

    private static ChatTypedDeliberationStore.Scope scopeStatic() {
        return new ChatTypedDeliberationStore.Scope("0", "owner", "client", "42", 1);
    }

    private static ChatDeliberationService.Admission admitted(String requestId) {
        return new ChatDeliberationService.Admission(requestId, 1, "7", "42", 1,
                cn.jia.chat.deliberation.InteractionRoute.INSPECT,
                List.of(new ChatDeliberationService.Dispatch(requestId, "turn-1", "dispatch-1", "event-1",
                        "agent", "INSPECT", "RECEIVED", "snapshot-1", digest('a'), Map.of(), Map.of())), false);
    }

    private static String bodyDigest(ChatTypedInspectionWire.Command command) {
        Map<String, Object> value = commandMap(command);
        value.put("contract", ChatTypedInspectionWire.CONTRACT);
        value.put("purpose", "INSPECT");
        return ChatDeliberationService.digest(value);
    }

    private static Map<String, Object> commandMap(ChatTypedInspectionWire.Command command) {
        return mapOf("schemaVersion", 1, "intent", command.intent(), "taskId", command.taskId(),
                "expectedAssignmentRevision", Long.toString(command.expectedAssignmentRevision()),
                "content", command.content(), "parentOutcomeId", command.parentOutcomeId(),
                "expectedParentStateVersion", null, "pendingQuestionId", null,
                "expectedPendingQuestionStateVersion", null,
                "sourceSelectors", command.sourceSelectors().stream()
                        .map(ChatTypedInspectionWire::selectorMap).toList());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> mapOf(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }

    private static String digest(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
