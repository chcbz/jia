package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.security.AgentRuntimeAuthentication;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.service.PersonalWorkspaceStorage;
import cn.jia.chat.api.ChatTypedInspectionWire;
import cn.jia.chat.archive.conversation.ChatConversationArchiveStore;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.deliberation.ChatBountyBindingStore;
import cn.jia.chat.deliberation.ChatContextSnapshotEntity;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatDeliberationStates;
import cn.jia.chat.deliberation.ChatInteractionStepStore;
import cn.jia.chat.deliberation.ChatRequestEntity;
import cn.jia.chat.deliberation.ChatTurnEntity;
import cn.jia.chat.deliberation.ChatTypedDeliberationStore;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.handler.TypedInspectionSessionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.when;

/** Actual class proxies prove INSPECT transactions are not bypassed by final classes or direct calls. */
class ChatTypedInspectionSpringTransactionTest {
    @Test
    void admissionLateFailureRollsBackWorkPerformedByCollaborators() {
        DriverManagerDataSource source = database("inspection_admission");
        JdbcTemplate evidence = evidence(source);
        AgentTaskMutationTransaction tasks = mock(AgentTaskMutationTransaction.class);
        ChatBountyBindingStore bindings = mock(ChatBountyBindingStore.class);
        ChatConversationDao conversations = mock(ChatConversationDao.class);
        JuyitingConversationScopeService scopes = mock(JuyitingConversationScopeService.class);
        ChatDeliberationService deliberation = mock(ChatDeliberationService.class);
        ChatDeliberationDao events = mock(ChatDeliberationDao.class);
        ChatTypedInspectionContextService contexts = mock(ChatTypedInspectionContextService.class);
        ChatTypedDeliberationService typed = mock(ChatTypedDeliberationService.class);
        ChatTypedDeliberationStore store = mock(ChatTypedDeliberationStore.class);
        when(typed.store()).thenReturn(store);
        when(tasks.executeWithLockedTaskRootInOwnerScope(
                eq("0"), eq("client"), eq("owner"), eq("task"), any()))
                .thenAnswer(invocation -> {
                    active(false);
                    AgentTaskMutationTransaction.LockedTaskMutation<?> callback = invocation.getArgument(4);
                    return callback.apply(new AgentTaskMetaEntity().setTaskVersion(4L)
                            .setAssignedAgentId("agent"));
                });
        when(bindings.lock(new ChatBountyBindingStore.Scope("0", "owner", "client"), "task"))
                .thenReturn(new ChatBountyBindingStore.Binding(3, 42L));
        ChatConversationEntity conversation = conversation();
        when(conversations.lockScopedById("owner", "client", "42")).thenReturn(conversation);
        when(scopes.parsePersistedTargetAgentIds("[\"agent\"]")).thenReturn(List.of("agent"));
        when(contexts.resolve(any(), any())).thenReturn(new ChatTypedInspectionContextService.Context(
                Map.of("schemaVersion", 1), "{}", selectors(), Map.of()));
        when(deliberation.admitInspection(eq("0"), any(), eq("42"), eq(1L), any(), any(), isNull(),
                any(), any())).thenAnswer(invocation -> {
                    active(false);
                    evidence.update("INSERT INTO tx_evidence VALUES ('request')");
                    cn.jia.chat.handler.dto.ChatMessageDTO input = invocation.getArgument(5);
                    return admitted(input.getRequestId());
                });
        when(events.eventHighWatermark("0", "owner", "client", "42", 1)).thenReturn(3L);
        when(store.insertAdmission(any())).thenReturn(0);

        try (AnnotationConfigApplicationContext context = context(source)) {
            context.registerBean(ChatTypedInspectionAdmissionService.class,
                    () -> new ChatTypedInspectionAdmissionService(tasks, bindings, conversations, scopes,
                            deliberation, events, contexts, typed));
            context.refresh();
            ChatTypedInspectionAdmissionService service =
                    context.getBean(ChatTypedInspectionAdmissionService.class);
            assertTrue(AopUtils.isCglibProxy(service));
            assertThrows(ChatDeliberationException.class, () -> service.admit("0", sender(), "42", "key",
                    new ChatTypedInspectionWire.Command("DISCUSSION", "task", 3, "inspect",
                            null, null, null, null, selectors())));
        }
        assertEquals(0, evidence.queryForObject("SELECT COUNT(*) FROM tx_evidence", Integer.class));
    }

    @Test
    void authorityReadFailureRollsBackInsideActualProxyTransaction() {
        DriverManagerDataSource source = database("inspection_authority");
        JdbcTemplate evidence = evidence(source);
        AgentTaskMutationTransaction tasks = mock(AgentTaskMutationTransaction.class);
        ChatDeliberationDao deliberation = mock(ChatDeliberationDao.class);
        ChatTypedDeliberationStore typed = mock(ChatTypedDeliberationStore.class);
        ChatRequestEntity request = new ChatRequestEntity().setRequestId("request")
                .setRequestRevision(1L).setConversationId("42").setConversationGeneration(1L);
        ChatTurnEntity turn = new ChatTurnEntity().setTurnId("turn").setRequestId("request")
                .setRequestRevision(1L).setConversationId("42").setConversationGeneration(1L)
                .setTargetAgentId("agent").setRoute("INSPECT");
        when(deliberation.findRequest("0", "owner", "client", "request")).thenReturn(request);
        when(deliberation.findTurn("0", "owner", "client", "turn")).thenReturn(turn);
        ChatTypedDeliberationStore.Scope storeScope =
                new ChatTypedDeliberationStore.Scope("0", "owner", "client", "42", 1);
        when(typed.findAdmissionByRequest(storeScope, "request")).thenReturn(
                new ChatTypedDeliberationStore.Admission("admission", storeScope, "key", digest('a'),
                        digest('b'), "DISCUSSION", "task", 3, null, null, "request", 1, 7,
                        "[\"turn\"]", authorityEnvelope(), "ADMITTED", 0, 1, 1));
        when(tasks.executeWithLockedTaskRootInOwnerScope(
                eq("0"), eq("client"), eq("owner"), eq("task"), any()))
                .thenAnswer(invocation -> {
                    active(false);
                    evidence.update("INSERT INTO tx_evidence VALUES ('authority')");
                    throw new ChatDeliberationException(
                            ChatDeliberationException.Reason.CONFLICT, "revoked");
                });

        try (AnnotationConfigApplicationContext context = context(source)) {
            context.registerBean(ChatInspectionAuthorityService.class, () -> new ChatInspectionAuthorityService(
                    tasks, mock(ChatBountyBindingStore.class), mock(ChatConversationDao.class), deliberation,
                    typed, mock(TypedInspectionSessionRegistry.class),
                    mock(JuyitingConversationScopeService.class), mock(JdbcTemplate.class),
                    mock(PersonalWorkspaceStorage.class), mock(PersonalWorkspaceExecutionService.class),
                    mock(ChatConversationArchiveStore.class)));
            context.refresh();
            ChatInspectionAuthorityService service = context.getBean(ChatInspectionAuthorityService.class);
            assertTrue(AopUtils.isCglibProxy(service));
            assertThrows(ChatDeliberationException.class, () -> service.read(
                    new AgentRuntimeAuthentication.Scope("0", "client", "owner", "agent", "runtime"),
                    "request", "turn", "source_" + "1".repeat(40), digest('c')));
        }
        assertEquals(0, evidence.queryForObject("SELECT COUNT(*) FROM tx_evidence", Integer.class));
    }

    @Test
    void v2FinalLateTurnFailureRollsBackMessageAndTypedOutcomeInOneActualTransaction() {
        DriverManagerDataSource source = database("inspection_final");
        JdbcTemplate evidence = evidence(source);
        ChatDeliberationDao dao = mock(ChatDeliberationDao.class);
        ChatConversationDao conversations = mock(ChatConversationDao.class);
        ChatMessageDao messages = mock(ChatMessageDao.class);
        AgentService agents = mock(AgentService.class);
        ChatInspectionAuthorityService authority = mock(ChatInspectionAuthorityService.class);
        ChatTypedDeliberationStore store = mock(ChatTypedDeliberationStore.class);
        ChatConversationArchiveStore archive = mock(ChatConversationArchiveStore.class);
        TypedInspectionSessionRegistry sessions = new TypedInspectionSessionRegistry();
        sessions.register("session", "0", "owner", "client", "agent", declaration(), () -> true);
        ChatTypedInspectionService typedService = new ChatTypedInspectionService(store, sessions, archive);

        ChatConversationEntity conversation = conversation();
        when(conversations.findScopedById("owner", "client", "42")).thenReturn(conversation);
        when(conversations.lockScopedById("owner", "client", "42")).thenReturn(conversation);
        ChatTurnEntity turn = new ChatTurnEntity().setTenantId("0").setOwnerJiacn("owner")
                .setClientId("client").setConversationId("42").setConversationGeneration(1L)
                .setRequestId("request").setRequestRevision(1L).setTurnId("turn")
                .setDispatchId("dispatch").setSnapshotId("snapshot").setContextDigest(digest('9'))
                .setTargetAgentId("agent").setRoute("INSPECT").setState(ChatDeliberationStates.RECEIVED)
                .setStateVersion(0L).setLastDeltaSeq(0L).setCreatedAt(1L).setUpdatedAt(1L);
        ChatRequestEntity request = new ChatRequestEntity().setTenantId("0").setOwnerJiacn("owner")
                .setClientId("client").setRequestId("request").setRequestRevision(1L)
                .setConversationId("42").setConversationGeneration(1L).setUserMessageId(7L)
                .setAggregateState(ChatDeliberationStates.RUNNING).setStateVersion(0L);
        String envelope = authorityEnvelope();
        Map<String, Object> typedMarker = ChatTypedInspectionContextService.inspection(envelope);
        ChatContextSnapshotEntity snapshot = new ChatContextSnapshotEntity().setSnapshotId("snapshot")
                .setTenantId("0").setOwnerJiacn("owner").setClientId("client")
                .setConversationId("42").setConversationGeneration(1L).setRequestId("request")
                .setRequestRevision(1L).setTargetAgentId("agent").setRoute("INSPECT")
                .setContextDigest(digest('9')).setFactsManifestJson(CanonicalContextJson.write(
                        Map.of("task", Map.of("id", "task"), "typedInspection", typedMarker)));
        when(dao.findTurn("0", "owner", "client", "turn")).thenReturn(turn);
        when(dao.lockTurn("0", "owner", "client", "turn")).thenReturn(turn);
        when(dao.findSnapshot("0", "owner", "client", "snapshot")).thenReturn(snapshot);
        when(dao.findRequest("0", "owner", "client", "request")).thenReturn(request);
        ChatTypedDeliberationStore.Scope storeScope =
                new ChatTypedDeliberationStore.Scope("0", "owner", "client", "42", 1);
        when(store.findAdmissionByRequest(storeScope, "request")).thenReturn(
                new ChatTypedDeliberationStore.Admission("admission", storeScope, "key", digest('a'),
                        digest('b'), "DISCUSSION", "task", 3, null, null, "request", 1, 7,
                        "[\"turn\"]", envelope, "ADMITTED", 0, 1, 1));
        when(store.findOutcomeByTurn(storeScope, "turn", true)).thenReturn(null);
        when(messages.insertScoped(eq("0"), eq("client"), any(ChatMessageEntity.class)))
                .thenAnswer(invocation -> {
                    active(false);
                    evidence.update("INSERT INTO tx_evidence VALUES ('message')");
                    ChatMessageEntity message = invocation.getArgument(2);
                    message.setId(9L);
                    return 1;
                });
        when(store.insertOutcome(any())).thenAnswer(invocation -> {
            active(false);
            evidence.update("INSERT INTO tx_evidence VALUES ('outcome')");
            return 1;
        });
        when(dao.persistFinal(same(turn), anyString(), eq(9L), anyLong())).thenAnswer(invocation -> {
            active(false);
            evidence.update("INSERT INTO tx_evidence VALUES ('turn')");
            return 0;
        });
        when(authority.withFinalAuthority(eq(turn), any())).thenAnswer(invocation -> {
            active(false);
            java.util.function.Supplier<?> callback = invocation.getArgument(1);
            return callback.get();
        });

        try (AnnotationConfigApplicationContext context = context(source)) {
            context.registerBean(ChatInteractionStepStore.class,
                    () -> mock(ChatInteractionStepStore.class));
            context.registerBean(ChatDeliberationService.class, () -> {
                ChatDeliberationService service = new ChatDeliberationService(
                        dao, conversations, messages, agents);
                service.setTypedInspection(typedService);
                service.setInspectionAuthority(authority);
                return service;
            });
            context.refresh();
            ChatDeliberationService service = context.getBean(ChatDeliberationService.class);
            assertTrue(AopUtils.isCglibProxy(service));
            assertThrows(ChatDeliberationException.class, () -> service.persistFinal(
                    "0", "owner", "client", "42", 1, "agent", "request", "turn", "dispatch",
                    "snapshot", digest('9'), "Inspected.", 2, outcomeJson(), receiptJson(envelope),
                    new ServerResolvedAgentSender("agent", "Agent", "owner", "client", "agent")));
        }
        assertEquals(0, evidence.queryForObject("SELECT COUNT(*) FROM tx_evidence", Integer.class));
    }

    @Test
    void typedOutcomeReadRunsThroughReadOnlyClassProxy() {
        DriverManagerDataSource source = database("inspection_projection");
        ChatTypedDeliberationStore store = mock(ChatTypedDeliberationStore.class);
        when(store.findAdmissionByRequest(any(), eq("request"))).thenAnswer(invocation -> {
            active(true);
            throw new ChatDeliberationException(
                    ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN, "missing");
        });
        try (AnnotationConfigApplicationContext context = context(source)) {
            context.registerBean(ChatTypedInspectionService.class,
                    () -> new ChatTypedInspectionService(store,
                            mock(TypedInspectionSessionRegistry.class),
                            mock(ChatConversationArchiveStore.class)));
            context.refresh();
            ChatTypedInspectionService service = context.getBean(ChatTypedInspectionService.class);
            assertTrue(AopUtils.isCglibProxy(service));
            assertThrows(ChatDeliberationException.class, () -> service.read(
                    new ChatTypedDeliberationStore.Scope("0", "owner", "client", "42", 1),
                    "request", "turn", 1));
        }
    }

    private static AnnotationConfigApplicationContext context(DataSource source) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfig.class);
        context.registerBean(DataSource.class, () -> source);
        context.registerBean(DataSourceTransactionManager.class,
                () -> new DataSourceTransactionManager(source));
        return context;
    }

    private static DriverManagerDataSource database(String prefix) {
        return new DriverManagerDataSource("jdbc:h2:mem:" + prefix + "_" + UUID.randomUUID()
                + ";MODE=MYSQL;DB_CLOSE_DELAY=-1", "sa", "");
    }

    private static JdbcTemplate evidence(DataSource source) {
        JdbcTemplate value = new JdbcTemplate(source);
        value.execute("CREATE TABLE tx_evidence(label VARCHAR(40) PRIMARY KEY)");
        return value;
    }

    private static void active(boolean readOnly) {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(readOnly, TransactionSynchronizationManager.isCurrentTransactionReadOnly());
    }

    private static ServerResolvedSender sender() {
        return new ServerResolvedSender("user", "Human", "owner", "client", DisplayNameSource.JIACN);
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

    private static List<ChatTypedInspectionWire.SourceSelector> selectors() {
        return List.of(new ChatTypedInspectionWire.SourceSelector(
                "TASK_LINKED_WORKSPACE_VERSION", "file", "1", "REFERENCE", null, null));
    }

    private static ChatDeliberationService.Admission admitted(String requestId) {
        return new ChatDeliberationService.Admission(requestId, 1, "7", "42", 1,
                cn.jia.chat.deliberation.InteractionRoute.INSPECT,
                List.of(new ChatDeliberationService.Dispatch(requestId, "turn", "dispatch", "event", "agent",
                        "INSPECT", "RECEIVED", "snapshot", digest('d'), Map.of(), Map.of())), false);
    }

    private static String authorityEnvelope() {
        Map<String, Object> selector = ChatTypedInspectionWire.selectorMap(selectors().getFirst());
        Map<String, Object> source = new java.util.LinkedHashMap<>();
        source.put("sourceRefId", "source_" + "1".repeat(40));
        source.put("selector", selector);
        source.put("mediaKind", "text");
        source.put("mimeType", "text/plain");
        source.put("byteLength", "4");
        source.put("sha256", "a".repeat(64));
        source.put("carrier", "DIRECT_TEXT");
        source.put("carrierContractDigest", digest('e'));
        Map<String, Object> manifest = new java.util.LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        manifest.put("purpose", "INSPECT");
        Map<String, Object> manifestScope = new java.util.LinkedHashMap<>();
        manifestScope.put("tenantId", "0");
        manifestScope.put("ownerJiacn", "owner");
        manifestScope.put("clientId", "client");
        manifestScope.put("conversationId", "42");
        manifestScope.put("conversationGeneration", "1");
        manifestScope.put("taskId", "task");
        manifestScope.put("assignmentRevision", "3");
        manifestScope.put("requestId", "request");
        manifestScope.put("requestRevision", "1");
        manifestScope.put("targetAgentId", "agent");
        manifest.put("scope", manifestScope);
        manifest.put("profile", Map.of("profileId", "profile", "engineContractId", "engine",
                "enginePolicyDigest", digest('f'), "toolPolicyDigest", digest('1'),
                "inputPolicyDigest", digest('2')));
        manifest.put("sources", List.of(source));
        String manifestDigest = ChatDeliberationService.digest(manifest);
        String authorization = "inspection_" + sha256("request\0" + manifestDigest).substring(0, 40);
        Map<String, Object> inspection = new java.util.LinkedHashMap<>();
        inspection.put("schemaVersion", 1);
        inspection.put("contract", ChatTypedInspectionWire.CONTRACT);
        inspection.put("purpose", "INSPECT");
        inspection.put("discussionFacts", Map.of("schemaVersion", 1, "referenceMode", "AVAILABLE",
                "supportedOperations", List.of("GENERATE_IMAGE", "EDIT_IMAGE"),
                "availableSources", List.of(Map.of("sourceRefId", source.get("sourceRefId"),
                        "kind", "TASK_WORKSPACE_FILE", "mediaType", "text"))));
        inspection.put("manifest", manifest);
        inspection.put("manifestDigest", manifestDigest);
        inspection.put("authorizationId", authorization);
        Map<String, Object> envelope = Map.of("schemaVersion", 1,
                "contract", ChatTypedInspectionWire.CONTRACT, "purpose", "INSPECT",
                "typedInspection", inspection);
        String json = CanonicalContextJson.write(envelope);
        ChatTypedInspectionContextService.parseEnvelope(json);
        return json;
    }

    private static Map<String, Object> declaration() {
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("schemaVersion", 1);
        value.put("contract", ChatTypedInspectionWire.CONTRACT);
        value.put("enabled", true);
        value.put("profileId", "profile");
        value.put("engineContractId", "engine");
        value.put("enginePolicyDigest", digest('f'));
        value.put("toolPolicyDigest", digest('1'));
        value.put("inputPolicyDigest", digest('2'));
        value.put("toolPolicy", "STRICT_NO_TOOLS");
        value.put("recovery", "durable-inbox-turn-readback-v1");
        value.put("supportedInputs", List.of(Map.of("mediaKind", "text", "mimeType", "text/plain",
                "carrier", "DIRECT_TEXT", "carrierContractDigest", digest('e'))));
        return value;
    }

    private static String outcomeJson() {
        return "{\"schemaVersion\":2,\"kind\":\"ANSWER\",\"text\":\"Inspected.\","
                + "\"clarification\":null,\"proposal\":null}";
    }

    private static String receiptJson(String envelope) {
        Map<String, Object> inspection = ChatTypedInspectionContextService.inspection(envelope);
        Map<String, Object> manifest = (Map<String, Object>) inspection.get("manifest");
        Map<String, Object> source = (Map<String, Object>) ((List<?>) manifest.get("sources")).getFirst();
        Map<String, Object> receiptSource = new java.util.LinkedHashMap<>();
        receiptSource.put("sourceRefId", source.get("sourceRefId"));
        receiptSource.put("sha256", source.get("sha256"));
        receiptSource.put("byteLength", source.get("byteLength"));
        receiptSource.put("carrier", source.get("carrier"));
        receiptSource.put("contributionDigest", digest('3'));
        Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("schemaVersion", 1);
        input.put("authorizationId", inspection.get("authorizationId"));
        input.put("manifestDigest", inspection.get("manifestDigest"));
        input.put("sources", List.of(receiptSource));
        Map<String, Object> receipt = new java.util.LinkedHashMap<>(input);
        receipt.put("inputDigest", "sha256:" + sha256(CanonicalContextJson.write(input)));
        receipt.put("engineThreadId", "thread");
        receipt.put("engineTurnId", "engine-turn");
        return CanonicalContextJson.write(receipt);
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

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfig { }
}
