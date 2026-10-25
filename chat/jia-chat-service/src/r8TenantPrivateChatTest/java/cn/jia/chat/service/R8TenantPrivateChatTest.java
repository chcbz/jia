package cn.jia.chat.service;

import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.PersonalWorkspaceTaskLinkService;
import cn.jia.chat.dao.AgentTaskThreadDao;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.dao.impl.ChatConversationDaoImpl;
import cn.jia.chat.dao.impl.ChatMessageDaoImpl;
import cn.jia.chat.deliberation.ChatBountyBindingStore;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatDeliberationDaoImpl;
import cn.jia.chat.deliberation.ChatDeliberationMapper;
import cn.jia.chat.deliberation.ChatDispatchOutboxEntity;
import cn.jia.chat.deliberation.ChatTurnEntity;
import cn.jia.chat.deliberation.InteractionRoute;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.handler.AgentWebSocketHandler;
import cn.jia.chat.handler.dto.ChatMessageDTO;
import cn.jia.chat.mapper.ChatConversationMapper;
import cn.jia.chat.mapper.ChatMessageMapper;
import cn.jia.chat.service.impl.ChatConversationServiceImpl;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.util.JsonUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** R8/R9 ordinary private CHAT path with real production DAOs and real Spring JDBC transactions. */
class R8TenantPrivateChatTest {
    private static final String TENANT = "tenant-1";
    private static final String OWNER = "owner-1";
    private static final String CLIENT = "client-1";
    private static final String AGENT = "melonready";
    private static final String CONVERSATION = "1760458007485";
    private static final String REQUEST = "bde4cc7b-3fa5-4df4-8640-9f143b40ef65";

    private JdbcTemplate jdbc;
    private SqlSessionTemplate sql;
    private ChatConversationDao conversations;
    private ChatMessageDao messages;
    private ChatDeliberationDao deliberationDao;
    private ChatDispatchAuthorityService dispatchAuthority;
    private ChatDeliberationService deliberation;
    private ChatDeliberationOutboxService outbox;
    private DataSourceTransactionManager transactionManager;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:r8_" + UUID.randomUUID().toString().replace("-", "")
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        createSchema();

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ChatConversationMapper.class);
        configuration.addMapper(ChatMessageMapper.class);
        configuration.addMapper(ChatDeliberationMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        GlobalConfig global = new GlobalConfig();
        global.setIdentifierGenerator(new com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator());
        factory.setGlobalConfig(global);
        sql = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));

        ChatConversationDaoImpl realConversations = new ChatConversationDaoImpl();
        ReflectionTestUtils.setField(realConversations, "baseMapper", sql.getMapper(ChatConversationMapper.class));
        conversations = spy(realConversations);
        ChatMessageDaoImpl realMessages = new ChatMessageDaoImpl();
        ReflectionTestUtils.setField(realMessages, "baseMapper", sql.getMapper(ChatMessageMapper.class));
        messages = spy(realMessages);
        deliberationDao = spy(new ChatDeliberationDaoImpl(sql.getMapper(ChatDeliberationMapper.class)));

        // Any regression to a convenience overload is an immediate test failure, including tenant 0.
        doThrow(new AssertionError("legacy tenant-0 conversation read used"))
                .when(conversations).findScopedById(anyString(), anyString(), anyString());
        doThrow(new AssertionError("legacy tenant-0 conversation lock used"))
                .when(conversations).lockScopedById(anyString(), anyString(), anyString());
        doThrow(new AssertionError("legacy tenant-0 history read used"))
                .when(messages).findOwnedByConversationIdWithLimit(
                        anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt());

        doThrow(new AssertionError("divergent live-generation count probe used"))
                .when(conversations).isLiveGeneration(
                        anyString(), anyString(), anyString(), anyString(), anyLong());

        dispatchAuthority = mock(ChatDispatchAuthorityService.class);
        ChatDeliberationService rawDeliberation = new ChatDeliberationService(
                deliberationDao, conversations, messages, mock(AgentService.class));
        rawDeliberation.setDispatchAuthority(dispatchAuthority);
        ChatDeliberationOutboxService rawOutbox = new ChatDeliberationOutboxService(deliberationDao);
        rawOutbox.setDispatchAuthority(dispatchAuthority);
        transactionManager = new DataSourceTransactionManager(dataSource);
        deliberation = transactional(rawDeliberation, transactionManager);
        outbox = transactional(rawOutbox, transactionManager);
        assertTrue(AopUtils.isAopProxy(deliberation));
        assertTrue(AopUtils.isAopProxy(outbox));
    }

    @AfterEach
    void tearDown() {
        EsContextHolder.clearContext();
    }

    @Test
    void tenant1AdmissionHostedDispatchNativeFinalMessagesAndRequestGetStayInExactScope() {
        seedConversation(TENANT, OWNER, CLIENT, CONVERSATION, AGENT);
        ChatDeliberationService.Admission admission = admit(
                TENANT, OWNER, CLIENT, CONVERSATION, REQUEST, AGENT, InteractionRoute.CHAT);
        assertEquals(1, count("chat_message"));
        assertEquals(1, count("chat_request"));
        assertEquals(1, count("chat_turn"));
        assertEquals(1, count("chat_context_snapshot"));
        assertEquals(1, count("chat_dispatch_outbox"));

        ChatDeliberationService.Dispatch dispatch = admission.dispatches().getFirst();
        ChatDispatchOutboxEntity candidate = deliberationDao.findOutboxById(
                TENANT, OWNER, CLIENT, dispatch.dispatchEventId());
        assertNotNull(candidate);
        ChatDeliberationOutboxService.Claim claim = outbox.claim(candidate, "r8-worker",
                Math.max(System.currentTimeMillis(), candidate.getAvailableAt()) + 1, 120_000);
        assertNotNull(claim);
        ChatTurnEntity turn = deliberationDao.findTurn(TENANT, OWNER, CLIENT, dispatch.turnId());
        bindAuthority(TENANT, OWNER, CLIENT, AGENT, claim.row(), turn);

        ChatConversationService conversationService = mock(ChatConversationService.class);
        doThrow(new AssertionError("legacy tenant-0 live-generation read used"))
                .when(conversationService).isLiveGeneration(
                        anyString(), anyString(), anyString(), anyLong());
        when(conversationService.isLiveGeneration(
                TENANT, OWNER, CLIENT, CONVERSATION, 1L)).thenReturn(true);
        AgentWebSocketHandler sockets = mock(AgentWebSocketHandler.class);
        when(sockets.sendNegotiatedChatMessageToAgent(
                eq(TENANT), eq(OWNER), eq(CLIENT), eq(AGENT), eq(InteractionRoute.CHAT), any()))
                .thenReturn(new AgentWebSocketHandler.CapabilityDispatchResult(
                        AgentWebSocketHandler.CapabilityDispatchStatus.READY, true, Map.of()));
        BuiltinHallAgentSupport builtin = mock(BuiltinHallAgentSupport.class);
        when(builtin.isBuiltinAgent(AGENT)).thenReturn(false);
        ChatConversationEventBroker broker = new ChatConversationEventBroker();
        try (ChatDeliberationOutboxRelay relay = new ChatDeliberationOutboxRelay(
                outbox, deliberation, sockets, builtin, broker, conversationService, mock(ChatClient.class))) {
            relay.setDispatchAuthority(dispatchAuthority);
            relay.deliverClaim(claim);
        }
        assertEquals("AWAITING_ACK", jdbc.queryForObject(
                "SELECT status FROM chat_dispatch_outbox WHERE event_id=?", String.class,
                dispatch.dispatchEventId()));
        verify(conversationService).isLiveGeneration(TENANT, OWNER, CLIENT, CONVERSATION, 1L);
        verify(conversationService, never()).isLiveGeneration(OWNER, CLIENT, CONVERSATION, 1L);

        ChatDeliberationService.FinalResult result = deliberation.persistFinal(
                TENANT, OWNER, CLIENT, CONVERSATION, 1L, AGENT, REQUEST,
                dispatch.turnId(), dispatch.dispatchId(), dispatch.contextSnapshotId(), dispatch.contextHash(),
                "native tenant-1 reply", new ServerResolvedAgentSender(
                        ServerResolvedAgentSender.AGENT_TYPE, "Melon Ready", OWNER, CLIENT, AGENT));
        assertEquals(ChatDeliberationService.FinalStatus.PERSISTED, result.status());
        ChatDeliberationService.RequestView request = deliberation.getRequest(TENANT, OWNER, CLIENT, REQUEST);
        assertEquals("COMPLETED", request.state());
        assertEquals(2, count("chat_message"));
        assertEquals(2, count("chat_dispatch_outbox"));
        List<ChatMessageEntity> durable = messages.findOwnedByConversationIdWithLimit(
                TENANT, OWNER, CLIENT, CONVERSATION, 200);
        assertEquals(List.of("USER", "ASSISTANT"), durable.stream().map(ChatMessageEntity::getMessageType).toList());
        assertEquals(List.of(TENANT, TENANT), durable.stream().map(ChatMessageEntity::getTenantId).toList());
        assertEquals("native tenant-1 reply", durable.getLast().getContent());
        for (String table : List.of("chat_message", "chat_request", "chat_turn",
                "chat_context_snapshot", "chat_dispatch_outbox", "chat_conversation_event")) {
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE tenant_id='0'", Integer.class), table);
        }
        verifyNoLegacyDaoOverloads();
    }

    @Test
    void exactScopeAndCallbackNegativesFailClosedAndTransactionRollsBackPartialAdmission() {
        seedConversation(TENANT, OWNER, CLIENT, CONVERSATION, AGENT);

        // Force a failure after the USER insert; the real transaction must remove that partial row.
        doThrow(new IllegalStateException("forced request persistence outage")).doCallRealMethod()
                .when(deliberationDao).insertRequest(any());
        assertThrows(IllegalStateException.class, () -> admit(
                TENANT, OWNER, CLIENT, CONVERSATION, "rollback-request", AGENT, InteractionRoute.CHAT));
        assertNoAdmissionRows();

        assertThrows(RuntimeException.class, () -> admit(
                "tenant-foreign", OWNER, CLIENT, CONVERSATION, "foreign-tenant", AGENT, InteractionRoute.CHAT));
        assertThrows(RuntimeException.class, () -> admit(
                TENANT, "owner-foreign", CLIENT, CONVERSATION, "foreign-owner", AGENT, InteractionRoute.CHAT));
        assertThrows(RuntimeException.class, () -> admit(
                TENANT, OWNER, "client-foreign", CONVERSATION, "foreign-client", AGENT, InteractionRoute.CHAT));
        assertThrows(RuntimeException.class, () -> deliberation.admit(
                TENANT, human(OWNER, CLIENT), CONVERSATION, 2L, privateScope(AGENT),
                InteractionRoute.CHAT, input("wrong-generation"), null));
        assertThrows(RuntimeException.class, () -> admit(
                TENANT, OWNER, CLIENT, CONVERSATION, "execute-forbidden", AGENT, InteractionRoute.EXECUTE));
        assertNoAdmissionRows();

        ChatDeliberationService.Admission admission = admit(
                TENANT, OWNER, CLIENT, CONVERSATION, REQUEST, AGENT, InteractionRoute.CHAT);
        ChatDeliberationService.Dispatch dispatch = admission.dispatches().getFirst();
        ChatDispatchOutboxEntity row = deliberationDao.findOutboxById(
                TENANT, OWNER, CLIENT, dispatch.dispatchEventId());
        ChatTurnEntity turn = deliberationDao.findTurn(TENANT, OWNER, CLIENT, dispatch.turnId());
        bindAuthority(TENANT, OWNER, CLIENT, AGENT, row, turn);
        ServerResolvedAgentSender sender = new ServerResolvedAgentSender(
                ServerResolvedAgentSender.AGENT_TYPE, "Melon Ready", OWNER, CLIENT, AGENT);

        assertThrows(RuntimeException.class, () -> finalReply(
                "tenant-foreign", OWNER, CLIENT, 1L, AGENT, dispatch, sender));
        assertThrows(RuntimeException.class, () -> finalReply(
                TENANT, "owner-foreign", CLIENT, 1L, AGENT, dispatch, sender));
        assertThrows(RuntimeException.class, () -> finalReply(
                TENANT, OWNER, "client-foreign", 1L, AGENT, dispatch,
                new ServerResolvedAgentSender("agent", "Melon Ready", OWNER, "client-foreign", AGENT)));
        assertThrows(RuntimeException.class, () -> finalReply(
                TENANT, OWNER, CLIENT, 2L, AGENT, dispatch, sender));
        assertThrows(RuntimeException.class, () -> finalReply(
                TENANT, OWNER, CLIENT, 1L, "foreign-agent", dispatch,
                new ServerResolvedAgentSender("agent", "Foreign", OWNER, CLIENT, "foreign-agent")));
        assertEquals(1, count("chat_message"));
        assertEquals(1, count("chat_dispatch_outbox"));
        assertEquals("RUNNING", deliberation.getRequest(TENANT, OWNER, CLIENT, REQUEST).state());
        assertThrows(RuntimeException.class, () -> deliberation.getRequest("tenant-foreign", OWNER, CLIENT, REQUEST));
        assertThrows(RuntimeException.class, () -> deliberation.getRequest(TENANT, "owner-foreign", CLIENT, REQUEST));
        assertThrows(RuntimeException.class, () -> deliberation.getRequest(TENANT, OWNER, "client-foreign", REQUEST));
        verifyNoLegacyDaoOverloads();
    }

    @Test
    void liveGenerationUsesExactEntityReadWithoutAmbientContextAndKeepsTenant0Compatibility() throws Exception {
        seedConversation(TENANT, OWNER, CLIENT, CONVERSATION, AGENT);
        seedConversation("0", "legacy-owner", "legacy-client", "77", AGENT);
        ChatConversationService service = realConversationService(new ChatConversationEventBroker());

        EsContext spoof = new EsContext();
        spoof.setTenantId("spoof-tenant");
        spoof.setJiacn("spoof-owner");
        spoof.setClientId("spoof-client");
        EsContextHolder.setContext(spoof);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            List<Boolean> observed = executor.submit(() -> {
                EsContext workerBefore = EsContextHolder.getContext();
                assertEquals(null, workerBefore.getTenantId());
                List<Boolean> result = List.of(
                        service.isLiveGeneration(TENANT, OWNER, CLIENT, CONVERSATION, 1L),
                        service.isLiveGeneration("tenant-foreign", OWNER, CLIENT, CONVERSATION, 1L),
                        service.isLiveGeneration(TENANT, "owner-foreign", CLIENT, CONVERSATION, 1L),
                        service.isLiveGeneration(TENANT, OWNER, "client-foreign", CONVERSATION, 1L),
                        service.isLiveGeneration(TENANT, OWNER, CLIENT, CONVERSATION, 2L),
                        service.isLiveGeneration("0", "legacy-owner", "legacy-client", "77", 1L));
                assertEquals(null, EsContextHolder.getContext().getTenantId());
                return result;
            }).get(3, TimeUnit.SECONDS);
            assertEquals(List.of(true, false, false, false, false, true), observed);
            assertEquals(null, executor.submit(() -> EsContextHolder.getContext().getTenantId())
                    .get(3, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertEquals("spoof-tenant", EsContextHolder.getContext().getTenantId());
        verify(conversations, never()).isLiveGeneration(
                anyString(), anyString(), anyString(), anyString(), anyLong());

        ChatConversationEventBroker phaseBroker = new ChatConversationEventBroker();
        assertThrows(ChatConversationEventBroker.PersistentLiveCheckUnavailable.class,
                () -> phaseBroker.runIfLiveOrThrow(CONVERSATION, 1L,
                        () -> { throw new IllegalStateException("live check outage"); }, () -> { }));
        assertThrows(ChatConversationEventBroker.LivePublicationUnavailable.class,
                () -> phaseBroker.runIfLiveOrThrow(CONVERSATION, 1L, () -> true,
                        () -> { throw new IllegalStateException("publication outage"); }));

        doThrow(new IllegalStateException("exact conversation read outage"))
                .when(conversations).findScopedById(TENANT, OWNER, CLIENT, CONVERSATION);
        assertThrows(IllegalStateException.class,
                () -> service.isLiveGeneration(TENANT, OWNER, CLIENT, CONVERSATION, 1L));
    }

    @Test
    void asyncPublicationFailureIsRetriedAndNeverMisclassifiedAsStaleDead() throws Exception {
        seedConversation(TENANT, OWNER, CLIENT, CONVERSATION, AGENT);
        ChatDeliberationService.Admission admission = admit(
                TENANT, OWNER, CLIENT, CONVERSATION, REQUEST, AGENT, InteractionRoute.CHAT);
        ChatDeliberationService.Dispatch dispatch = admission.dispatches().getFirst();
        ChatTurnEntity turn = deliberationDao.findTurn(TENANT, OWNER, CLIENT, dispatch.turnId());
        bindOutgoingAuthority(TENANT, OWNER, CLIENT, AGENT, dispatch, turn);

        CountDownLatch attempted = new CountDownLatch(1);
        AgentWebSocketHandler sockets = mock(AgentWebSocketHandler.class);
        when(sockets.sendNegotiatedChatMessageToAgent(
                eq(TENANT), eq(OWNER), eq(CLIENT), eq(AGENT), eq(InteractionRoute.CHAT), any()))
                .thenAnswer(ignored -> {
                    attempted.countDown();
                    throw new IllegalStateException("socket publication outage");
                });
        BuiltinHallAgentSupport builtin = mock(BuiltinHallAgentSupport.class);
        when(builtin.isBuiltinAgent(AGENT)).thenReturn(false);
        ChatConversationEventBroker broker = new ChatConversationEventBroker();
        ChatDeliberationOutboxRelay relay = fixedTenantRelay(
                sockets, builtin, broker, realConversationService(broker));
        relay.setDispatchAuthority(dispatchAuthority);

        EsContext caller = new EsContext();
        caller.setTenantId("spoof-tenant");
        caller.setJiacn("spoof-owner");
        caller.setClientId("spoof-client");
        EsContextHolder.setContext(caller);
        try {
            relay.start();
            assertTrue(attempted.await(3, TimeUnit.SECONDS));
            assertEquals("RETRY", awaitOutboxStatus(dispatch.dispatchEventId(), "RETRY", 3_000));
        } finally {
            relay.stop();
        }
        assertEquals("LivePublicationUnavailable", jdbc.queryForObject(
                "SELECT last_error FROM chat_dispatch_outbox WHERE event_id=?", String.class,
                dispatch.dispatchEventId()));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_dispatch_outbox WHERE event_id=? AND status='DEAD'", Integer.class,
                dispatch.dispatchEventId()));
        assertEquals("spoof-tenant", EsContextHolder.getContext().getTenantId());
    }

    @Test
    void cleanGenerationMismatchStillDeadLettersWithoutAttemptingSocketPublication() {
        seedConversation(TENANT, OWNER, CLIENT, CONVERSATION, AGENT);
        ChatDeliberationService.Admission admission = admit(
                TENANT, OWNER, CLIENT, CONVERSATION, REQUEST, AGENT, InteractionRoute.CHAT);
        ChatDeliberationService.Dispatch dispatch = admission.dispatches().getFirst();
        ChatDispatchOutboxEntity candidate = deliberationDao.findOutboxById(
                TENANT, OWNER, CLIENT, dispatch.dispatchEventId());
        ChatDeliberationOutboxService.Claim claim = outbox.claim(candidate, "r9-stale-worker",
                Math.max(System.currentTimeMillis(), candidate.getAvailableAt()) + 1, 120_000);
        assertNotNull(claim);
        assertEquals(1, jdbc.update(
                "UPDATE chat_conversation SET lifecycle_generation=2 WHERE tenant_id=? AND jiacn=? AND client_id=? AND id=?",
                TENANT, OWNER, CLIENT, Long.parseLong(CONVERSATION)));
        ChatTurnEntity turn = deliberationDao.findTurn(TENANT, OWNER, CLIENT, dispatch.turnId());
        bindOutgoingAuthority(TENANT, OWNER, CLIENT, AGENT, dispatch, turn);

        AgentWebSocketHandler sockets = mock(AgentWebSocketHandler.class);
        BuiltinHallAgentSupport builtin = mock(BuiltinHallAgentSupport.class);
        when(builtin.isBuiltinAgent(AGENT)).thenReturn(false);
        ChatConversationEventBroker broker = new ChatConversationEventBroker();
        try (ChatDeliberationOutboxRelay relay = new ChatDeliberationOutboxRelay(
                outbox, deliberation, sockets, builtin, broker,
                realConversationService(broker), mock(ChatClient.class))) {
            relay.setDispatchAuthority(dispatchAuthority);
            relay.deliverClaim(claim);
        }
        assertEquals("DEAD", jdbc.queryForObject(
                "SELECT status FROM chat_dispatch_outbox WHERE event_id=?", String.class,
                dispatch.dispatchEventId()));
        assertEquals("CONVERSATION_DELETED_OR_GENERATION_STALE", jdbc.queryForObject(
                "SELECT last_error FROM chat_dispatch_outbox WHERE event_id=?", String.class,
                dispatch.dispatchEventId()));
        verify(sockets, never()).sendNegotiatedChatMessageToAgent(
                anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void exactDeadEventRecoveryResumesOriginalAdmissionWithoutAnotherPost() throws Exception {
        seedConversation(TENANT, OWNER, CLIENT, CONVERSATION, AGENT);
        ChatDeliberationService.Admission admission = admit(
                TENANT, OWNER, CLIENT, CONVERSATION, REQUEST, AGENT, InteractionRoute.CHAT);
        ChatDeliberationService.Dispatch dispatch = admission.dispatches().getFirst();
        ChatDispatchOutboxEntity candidate = deliberationDao.findOutboxById(
                TENANT, OWNER, CLIENT, dispatch.dispatchEventId());
        ChatDeliberationOutboxService.Claim failed = outbox.claim(candidate, "r9-original-worker",
                Math.max(System.currentTimeMillis(), candidate.getAvailableAt()) + 1, 120_000);
        assertNotNull(failed);
        assertTrue(outbox.dead(failed, "CONVERSATION_DELETED_OR_GENERATION_STALE", System.currentTimeMillis()));
        ChatDispatchOutboxEntity dead = deliberationDao.findOutboxById(
                TENANT, OWNER, CLIENT, dispatch.dispatchEventId());
        assertEquals("DEAD", dead.getStatus());
        assertEquals(2L, dead.getVersion());
        assertEquals(1, dead.getAttemptCount());
        assertEquals(1L, dead.getFencingToken());

        assertEquals(0, recoverExactDead("tenant-foreign", OWNER, CLIENT, REQUEST, dispatch, dead));
        assertEquals(0, recoverExactDead(TENANT, "owner-foreign", CLIENT, REQUEST, dispatch, dead));
        assertEquals(0, recoverExactDead(TENANT, OWNER, "client-foreign", REQUEST, dispatch, dead));
        assertEquals(0, recoverExactDead(TENANT, OWNER, CLIENT, "request-foreign", dispatch, dead));
        ChatDeliberationService.Dispatch foreignTurn = new ChatDeliberationService.Dispatch(
                REQUEST, "turn-foreign", dispatch.dispatchId(), dispatch.dispatchEventId(), AGENT,
                dispatch.route(), dispatch.state(), dispatch.contextSnapshotId(), dispatch.contextHash(),
                dispatch.sourceVector(), dispatch.factsManifest());
        assertEquals(0, recoverExactDead(TENANT, OWNER, CLIENT, REQUEST, foreignTurn, dead));
        assertEquals(1, recoverExactDead(TENANT, OWNER, CLIENT, REQUEST, dispatch, dead));
        assertEquals("RETRY", jdbc.queryForObject(
                "SELECT status FROM chat_dispatch_outbox WHERE event_id=?", String.class,
                dispatch.dispatchEventId()));
        assertEquals(3L, jdbc.queryForObject(
                "SELECT version FROM chat_dispatch_outbox WHERE event_id=?", Long.class,
                dispatch.dispatchEventId()));

        ChatTurnEntity turn = deliberationDao.findTurn(TENANT, OWNER, CLIENT, dispatch.turnId());
        bindOutgoingAuthority(TENANT, OWNER, CLIENT, AGENT, dispatch, turn);
        AgentWebSocketHandler sockets = mock(AgentWebSocketHandler.class);
        when(sockets.sendNegotiatedChatMessageToAgent(
                eq(TENANT), eq(OWNER), eq(CLIENT), eq(AGENT), eq(InteractionRoute.CHAT), any()))
                .thenReturn(new AgentWebSocketHandler.CapabilityDispatchResult(
                        AgentWebSocketHandler.CapabilityDispatchStatus.READY, true, Map.of()));
        BuiltinHallAgentSupport builtin = mock(BuiltinHallAgentSupport.class);
        when(builtin.isBuiltinAgent(AGENT)).thenReturn(false);
        ChatConversationEventBroker broker = new ChatConversationEventBroker();
        ChatDeliberationOutboxRelay relay = fixedTenantRelay(
                sockets, builtin, broker, realConversationService(broker));
        relay.setDispatchAuthority(dispatchAuthority);
        try {
            relay.start();
            assertEquals("AWAITING_ACK", awaitOutboxStatus(
                    dispatch.dispatchEventId(), "AWAITING_ACK", 3_000));
        } finally {
            relay.stop();
        }

        ChatDeliberationService.FinalResult result = deliberation.persistFinal(
                TENANT, OWNER, CLIENT, CONVERSATION, 1L, AGENT, REQUEST,
                dispatch.turnId(), dispatch.dispatchId(), dispatch.contextSnapshotId(), dispatch.contextHash(),
                "recovered original reply", new ServerResolvedAgentSender(
                        ServerResolvedAgentSender.AGENT_TYPE, "Melon Ready", OWNER, CLIENT, AGENT));
        assertEquals(ChatDeliberationService.FinalStatus.PERSISTED, result.status());
        ChatDeliberationService.RequestView request = deliberation.getRequest(TENANT, OWNER, CLIENT, REQUEST);
        assertEquals("COMPLETED", request.state());
        assertEquals(admission.userMessageId(), request.userMessageId());
        assertEquals(1, count("chat_request"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE message_type='USER'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE message_type='ASSISTANT' AND content='recovered original reply'",
                Integer.class));
    }

    @Test
    void tenant0PrivateChatRetainsLegacyApiBehaviorWhileUsingExplicitScope() {
        String tenant = "0";
        String owner = "legacy-owner";
        String client = "legacy-client";
        String conversation = "77";
        String requestId = "legacy-request";
        seedConversation(tenant, owner, client, conversation, AGENT);
        ChatDeliberationService.Admission admission = admit(
                tenant, owner, client, conversation, requestId, AGENT, InteractionRoute.CHAT);
        ChatDeliberationService.Dispatch dispatch = admission.dispatches().getFirst();
        ChatDispatchOutboxEntity row = deliberationDao.findOutboxById(
                tenant, owner, client, dispatch.dispatchEventId());
        ChatTurnEntity turn = deliberationDao.findTurn(tenant, owner, client, dispatch.turnId());
        bindAuthority(tenant, owner, client, AGENT, row, turn);
        ChatDeliberationService.FinalResult result = deliberation.persistFinal(
                tenant, owner, client, conversation, 1L, AGENT, requestId,
                dispatch.turnId(), dispatch.dispatchId(), dispatch.contextSnapshotId(), dispatch.contextHash(),
                "legacy reply", new ServerResolvedAgentSender("agent", "Legacy Agent", owner, client, AGENT));
        assertEquals(ChatDeliberationService.FinalStatus.PERSISTED, result.status());
        assertEquals("COMPLETED", deliberation.getRequest(tenant, owner, client, requestId).state());
        assertEquals(2, messages.findOwnedByConversationIdWithLimit(
                tenant, owner, client, conversation, 200).size());
        verifyNoLegacyDaoOverloads();
    }

    @Test
    void ownedRosterPreflightPassesTrustedTenantNotOwnerIntoHostingGate() {
        AgentWebSocketHandler sockets = mock(AgentWebSocketHandler.class);
        ChatConversationEventBroker broker = new ChatConversationEventBroker();
        BuiltinHallAgentSupport builtin = mock(BuiltinHallAgentSupport.class);
        ChatConversationService conversationService = mock(ChatConversationService.class);
        AgentService agents = mock(AgentService.class);
        PersonalWorkspaceTaskLinkService links = mock(PersonalWorkspaceTaskLinkService.class);
        ChatDeliberationService admissions = mock(ChatDeliberationService.class);
        JuyitingConversationScopeService scopes = new JuyitingConversationScopeService(builtin, agents);
        JuyitingAgentRelayService relay = new JuyitingAgentRelayService(
                sockets, broker, builtin, conversationService, agents, scopes, links, admissions);

        EsContext context = new EsContext();
        context.setTenantId(TENANT);
        context.setJiacn(OWNER);
        context.setClientId(CLIENT);
        EsContextHolder.setContext(context);
        ChatConversationEntity conversation = conversation(TENANT, OWNER, CLIENT, CONVERSATION, AGENT);
        when(conversationService.getOwned(TENANT, OWNER, CLIENT, CONVERSATION)).thenReturn(conversation);
        when(builtin.isBuiltinAgent(AGENT)).thenReturn(false);
        ChatDeliberationService.Dispatch dispatch = new ChatDeliberationService.Dispatch(
                REQUEST, "turn", "dispatch", "event", AGENT, "CHAT", "RECEIVED",
                "snapshot", "sha256:" + "a".repeat(64), Map.of(), Map.of());
        when(admissions.admit(eq(TENANT), any(ServerResolvedSender.class), eq(CONVERSATION), eq(1L),
                any(JuyitingConversationScope.class), eq(InteractionRoute.CHAT), any(ChatMessageDTO.class), any()))
                .thenReturn(new ChatDeliberationService.Admission(
                        REQUEST, 1L, "1", CONVERSATION, 1L, InteractionRoute.CHAT, List.of(dispatch), true));

        ChatMessageDTO input = input(REQUEST);
        input.setConversationType("juyiting");
        input.setConversationScopeType("private");
        input.setConversationScopeKey("agent:" + AGENT);
        input.setTargetAgentId(AGENT);
        input.setTargetAgentIds(List.of(AGENT));
        JuyitingAgentRelayResult result = relay.relay(
                input, CONVERSATION, human(OWNER, CLIENT), InteractionRoute.CHAT);
        assertTrue(result.attempted());
        assertEquals(1, result.stream().collectList().block().size());
        verify(agents).requireHostingNewWork(TENANT, CLIENT, AGENT);
        verify(agents, never()).requireHostingNewWork(OWNER, CLIENT, AGENT);
        verify(admissions).admit(eq(TENANT), any(ServerResolvedSender.class), eq(CONVERSATION), eq(1L),
                any(JuyitingConversationScope.class), eq(InteractionRoute.CHAT), eq(input), any());
    }

    private ChatConversationService realConversationService(ChatConversationEventBroker broker) {
        return new ChatConversationServiceImpl(conversations, messages, mock(AgentTaskThreadDao.class), broker,
                mock(ChatBountyBindingStore.class), mock(AgentService.class));
    }

    private ChatDeliberationOutboxRelay fixedTenantRelay(AgentWebSocketHandler sockets,
            BuiltinHallAgentSupport builtin, ChatConversationEventBroker broker,
            ChatConversationService conversationService) {
        return new ChatDeliberationOutboxRelay(outbox, deliberation, sockets, builtin, broker,
                conversationService, mock(ChatClient.class), 120_000, 10_000, 1,
                true, TENANT, CLIENT);
    }

    private String awaitOutboxStatus(String eventId, String expected, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        String current = null;
        while (System.nanoTime() < deadline) {
            current = jdbc.queryForObject(
                    "SELECT status FROM chat_dispatch_outbox WHERE event_id=?", String.class, eventId);
            if (expected.equals(current)) return current;
            Thread.sleep(10);
        }
        return current;
    }

    private void bindOutgoingAuthority(String tenant, String owner, String client, String agent,
            ChatDeliberationService.Dispatch dispatch, ChatTurnEntity turn) {
        ChatDeliberationException denied = new ChatDeliberationException(
                ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN, "test binding denied");
        doAnswer(invocation -> {
            ChatDispatchOutboxEntity row = invocation.getArgument(0);
            if (row != null && tenant.equals(row.getTenantId()) && owner.equals(row.getOwnerJiacn())
                    && client.equals(row.getClientId()) && dispatch.turnId().equals(row.getTurnId())
                    && dispatch.dispatchId().equals(row.getDispatchId())
                    && dispatch.dispatchEventId().equals(row.getEventId())) {
                return new ChatDispatchAuthorityService.Binding(row, turn, owner);
            }
            throw denied;
        }).when(dispatchAuthority).outgoing(any(ChatDispatchOutboxEntity.class));
        doReturn(new ChatDispatchAuthorityService.Binding(
                deliberationDao.findOutboxById(tenant, owner, client, dispatch.dispatchEventId()), turn, owner))
                .when(dispatchAuthority).incoming(tenant, client, owner, agent, dispatch.dispatchId());
    }

    private int recoverExactDead(String tenant, String owner, String client, String requestId,
            ChatDeliberationService.Dispatch dispatch, ChatDispatchOutboxEntity expectedDead) {
        Integer updated = new TransactionTemplate(transactionManager).execute(status -> {
            ChatDispatchOutboxEntity row = deliberationDao.lockOutboxById(
                    tenant, owner, client, dispatch.dispatchEventId());
            if (row == null || !"DISPATCH".equals(row.getEventType()) || !"DEAD".equals(row.getStatus())
                    || !"CONVERSATION_DELETED_OR_GENERATION_STALE".equals(row.getLastError())
                    || !dispatch.turnId().equals(row.getTurnId())
                    || !dispatch.dispatchId().equals(row.getDispatchId())
                    || !expectedDead.getVersion().equals(row.getVersion())
                    || !expectedDead.getAttemptCount().equals(row.getAttemptCount())
                    || !expectedDead.getFencingToken().equals(row.getFencingToken())
                    || row.getAvailableAt() != null || row.getLeaseOwner() != null
                    || row.getLeaseUntil() != null || row.getSentAt() != null) return 0;
            ChatTurnEntity turn = deliberationDao.findTurn(tenant, owner, client, dispatch.turnId());
            var request = deliberationDao.findRequest(tenant, owner, client, requestId);
            ChatConversationEntity conversation = conversations.findScopedById(
                    tenant, owner, client, CONVERSATION);
            if (turn == null || !requestId.equals(turn.getRequestId())
                    || !"RECEIVED".equals(turn.getState()) || turn.getFinalMessageId() != null
                    || !"CHAT".equals(turn.getRoute()) || !AGENT.equals(turn.getTargetAgentId())
                    || request == null || !"RUNNING".equals(request.getAggregateState())
                    || !CONVERSATION.equals(request.getConversationId())
                    || !Long.valueOf(1L).equals(request.getConversationGeneration())
                    || conversation == null || conversation.getDeletedAt() != null
                    || !Long.valueOf(1L).equals(conversation.getLifecycleGeneration())) return 0;
            long now = System.currentTimeMillis();
            return jdbc.update("""
                    UPDATE chat_dispatch_outbox
                    SET status='RETRY', available_at=?, lease_owner=NULL, lease_until=NULL,
                        last_error='R9_EXACT_AUTHORIZED_RECOVERY', sent_at=NULL,
                        version=version+1, updated_at=?
                    WHERE tenant_id=? AND owner_jiacn=? AND client_id=? AND event_id=?
                      AND turn_id=? AND dispatch_id=? AND event_type='DISPATCH'
                      AND status='DEAD' AND last_error='CONVERSATION_DELETED_OR_GENERATION_STALE'
                      AND version=? AND attempt_count=? AND fencing_token=?
                      AND available_at IS NULL AND lease_owner IS NULL AND lease_until IS NULL AND sent_at IS NULL
                    """, now - 1, now, tenant, owner, client, dispatch.dispatchEventId(),
                    dispatch.turnId(), dispatch.dispatchId(), expectedDead.getVersion(),
                    expectedDead.getAttemptCount(), expectedDead.getFencingToken());
        });
        return updated == null ? 0 : updated;
    }

    private ChatDeliberationService.Admission admit(String tenant, String owner, String client,
            String conversation, String requestId, String agent, InteractionRoute route) {
        return deliberation.admit(tenant, human(owner, client), conversation, 1L,
                privateScope(agent), route, input(requestId, conversation, agent), null);
    }

    private ChatDeliberationService.FinalResult finalReply(String tenant, String owner, String client,
            long generation, String agent, ChatDeliberationService.Dispatch dispatch,
            ServerResolvedAgentSender sender) {
        return deliberation.persistFinal(tenant, owner, client, CONVERSATION, generation, agent, REQUEST,
                dispatch.turnId(), dispatch.dispatchId(), dispatch.contextSnapshotId(), dispatch.contextHash(),
                "must not persist", sender);
    }

    private void bindAuthority(String tenant, String owner, String client, String agent,
            ChatDispatchOutboxEntity row, ChatTurnEntity turn) {
        ChatDeliberationException denied = new ChatDeliberationException(
                ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN, "test binding denied");
        doThrow(denied).when(dispatchAuthority).outgoing(any(ChatDispatchOutboxEntity.class));
        doThrow(denied).when(dispatchAuthority).incoming(
                anyString(), anyString(), anyString(), anyString(), anyString());
        ChatDispatchAuthorityService.Binding binding = new ChatDispatchAuthorityService.Binding(row, turn, owner);
        doReturn(binding).when(dispatchAuthority).outgoing(row);
        doReturn(binding).when(dispatchAuthority).incoming(
                tenant, client, owner, agent, turn.getDispatchId());
    }

    private ServerResolvedSender human(String owner, String client) {
        return new ServerResolvedSender(ServerResolvedSender.USER_TYPE, "Authenticated Owner",
                owner, client, DisplayNameSource.JIACN);
    }

    private JuyitingConversationScope privateScope(String agent) {
        return new JuyitingConversationScope("private", "agent:" + agent, null, agent,
                List.of(agent), List.of(agent));
    }

    private ChatMessageDTO input(String requestId) {
        return input(requestId, CONVERSATION, AGENT);
    }

    private ChatMessageDTO input(String requestId, String conversationId, String agent) {
        ChatMessageDTO input = new ChatMessageDTO();
        input.setContent("ordinary private chat");
        input.setConversationId(conversationId);
        input.setRequestId(requestId);
        input.setRequestRevision(1L);
        input.setConversationType("juyiting");
        input.setConversationScopeType("private");
        input.setConversationScopeKey("agent:" + agent);
        input.setTargetAgentId(agent);
        input.setTargetAgentIds(List.of(agent));
        return input;
    }

    private void seedConversation(String tenant, String owner, String client,
            String conversationId, String agent) {
        ChatConversationEntity conversation = conversation(tenant, owner, client, conversationId, agent);
        jdbc.update("""
                INSERT INTO chat_conversation
                  (id,title,jiacn,conversation_type,conversation_scope_type,conversation_scope_key,
                   task_id,target_agent_id,target_agent_ids,status,deleted_at,lifecycle_generation,
                   create_time,update_time,tenant_id,client_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, conversation.getId(), "private", owner, "juyiting", "private", "agent:" + agent,
                null, agent, conversation.getTargetAgentIds(), 1, null, 1L, 1L, 1L, tenant, client);
    }

    private ChatConversationEntity conversation(String tenant, String owner, String client,
            String conversationId, String agent) {
        ChatConversationEntity conversation = new ChatConversationEntity()
                .setId(Long.parseLong(conversationId)).setTitle("private").setJiacn(owner)
                .setConversationType("juyiting").setConversationScopeType("private")
                .setConversationScopeKey("agent:" + agent).setTargetAgentId(agent)
                .setTargetAgentIds(JsonUtil.toJson(List.of(agent))).setStatus(1)
                .setLifecycleGeneration(1L);
        conversation.setTenantId(tenant);
        conversation.setClientId(client);
        return conversation;
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void assertNoAdmissionRows() {
        for (String table : List.of("chat_message", "chat_request", "chat_turn",
                "chat_context_snapshot", "chat_dispatch_outbox", "chat_conversation_event")) {
            assertEquals(0, count(table), table);
        }
    }

    private void verifyNoLegacyDaoOverloads() {
        verify(conversations, never()).findScopedById(anyString(), anyString(), anyString());
        verify(conversations, never()).lockScopedById(anyString(), anyString(), anyString());
        verify(messages, never()).findOwnedByConversationIdWithLimit(
                anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @SuppressWarnings("unchecked")
    private static <T> T transactional(T target, DataSourceTransactionManager transactionManager) {
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        interceptor.afterPropertiesSet();
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        return (T) factory.getProxy();
    }

    private void createSchema() {
        jdbc.execute("""
                CREATE TABLE chat_conversation (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, title VARCHAR(255), jiacn VARCHAR(50),
                  conversation_type VARCHAR(32), conversation_scope_type VARCHAR(32),
                  conversation_scope_key VARCHAR(100), task_id VARCHAR(100), target_agent_id VARCHAR(100),
                  target_agent_ids TEXT, status INT, deleted_at BIGINT, lifecycle_generation BIGINT,
                  create_time BIGINT, update_time BIGINT, tenant_id VARCHAR(50), client_id VARCHAR(50))
                """);
        jdbc.execute("""
                CREATE TABLE chat_message (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, conversation_id VARCHAR(100), message_type VARCHAR(32),
                  content MEDIUMTEXT, metadata MEDIUMTEXT, jiacn VARCHAR(50), sync_status VARCHAR(20),
                  conversation_type VARCHAR(32), sender_type VARCHAR(32), sender_name VARCHAR(100),
                  create_time BIGINT, update_time BIGINT, tenant_id VARCHAR(50), client_id VARCHAR(50))
                """);
        jdbc.execute("""
                CREATE TABLE chat_context_snapshot (
                  snapshot_id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(50), owner_jiacn VARCHAR(50),
                  client_id VARCHAR(50), conversation_id VARCHAR(100), conversation_generation BIGINT,
                  request_id VARCHAR(100), request_revision BIGINT, target_agent_id VARCHAR(100),
                  route VARCHAR(20), source_vector_json TEXT, facts_manifest_json MEDIUMTEXT,
                  context_digest VARCHAR(100), created_at BIGINT)
                """);
        jdbc.execute("""
                CREATE TABLE chat_request (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(50), owner_jiacn VARCHAR(50),
                  client_id VARCHAR(50), request_id VARCHAR(100), request_revision BIGINT,
                  request_digest VARCHAR(100), conversation_id VARCHAR(100), conversation_generation BIGINT,
                  user_message_id BIGINT, aggregate_state VARCHAR(30), state_version BIGINT,
                  created_at BIGINT, updated_at BIGINT)
                """);
        jdbc.execute("""
                CREATE TABLE chat_turn (
                  turn_id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(50), owner_jiacn VARCHAR(50),
                  client_id VARCHAR(50), request_id VARCHAR(100), request_revision BIGINT,
                  conversation_id VARCHAR(100), conversation_generation BIGINT, target_agent_id VARCHAR(100),
                  snapshot_id VARCHAR(64), context_digest VARCHAR(100), dispatch_id VARCHAR(64),
                  route VARCHAR(20), state VARCHAR(30), state_version BIGINT, last_delta_seq BIGINT,
                  last_delta_digest VARCHAR(100), final_digest VARCHAR(100), final_message_id BIGINT,
                  terminal_reason VARCHAR(500), created_at BIGINT, updated_at BIGINT)
                """);
        jdbc.execute("""
                CREATE TABLE chat_dispatch_outbox (
                  event_id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(50), owner_jiacn VARCHAR(50),
                  client_id VARCHAR(50), turn_id VARCHAR(64), dispatch_id VARCHAR(64), event_type VARCHAR(30),
                  task_grant_id VARCHAR(100), task_grant_version BIGINT, task_assignment_revision BIGINT,
                  task_requirement_revision BIGINT, status VARCHAR(30), payload_json MEDIUMTEXT,
                  version BIGINT, available_at BIGINT, lease_owner VARCHAR(100), lease_until BIGINT,
                  attempt_count INT, fencing_token BIGINT, last_error VARCHAR(500), sent_at BIGINT,
                  created_at BIGINT, updated_at BIGINT)
                """);
        jdbc.execute("""
                CREATE TABLE chat_conversation_event (
                  event_sequence BIGINT AUTO_INCREMENT PRIMARY KEY, event_id VARCHAR(64), tenant_id VARCHAR(50),
                  owner_jiacn VARCHAR(50), client_id VARCHAR(50), conversation_id VARCHAR(100),
                  conversation_generation BIGINT, request_id VARCHAR(100), turn_id VARCHAR(64),
                  dispatch_id VARCHAR(64), event_type VARCHAR(40), event_version BIGINT,
                  payload_json MEDIUMTEXT, occurred_at BIGINT)
                """);
    }
}
