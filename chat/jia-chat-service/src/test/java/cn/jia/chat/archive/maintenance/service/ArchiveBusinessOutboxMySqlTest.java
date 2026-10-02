package cn.jia.chat.archive.maintenance.service;

import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.AgentTaskArtifactStorage;
import cn.jia.chat.archive.config.ArchiveSchemaInitializer;
import cn.jia.chat.archive.maintenance.config.ArchiveMaintenanceProperties;
import cn.jia.chat.archive.maintenance.config.ArchiveMaintenanceSchemaInitializer;
import cn.jia.chat.archive.maintenance.dto.ArchiveWithdrawRequest;
import cn.jia.chat.archive.maintenance.dto.ArchiveWithdrawalDTO;
import cn.jia.chat.archive.content.ArchiveEtags;
import cn.jia.chat.archive.maintenance.model.ArchiveActorScope;
import cn.jia.chat.archive.maintenance.model.ArchiveBusinessOutboxRecord;
import cn.jia.chat.archive.maintenance.model.ArchiveConfirmedRequestRecord;
import cn.jia.chat.archive.maintenance.model.ArchiveMaintenanceJobRecord;
import cn.jia.chat.archive.maintenance.store.ArchiveMaintenanceStore;
import cn.jia.chat.archive.maintenance.store.JdbcArchiveMaintenanceStore;
import cn.jia.chat.archive.service.ArchiveMySqlTestGuard;
import cn.jia.chat.archive.service.ArchiveTransactions;
import cn.jia.chat.archive.service.SpringArchiveTransactions;
import cn.jia.chat.archive.store.JdbcArchiveContentStore;
import cn.jia.chat.dao.AgentTaskThreadDao;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.dao.impl.ChatConversationDaoImpl;
import cn.jia.chat.dao.impl.ChatMessageDaoImpl;
import cn.jia.chat.mapper.ChatConversationMapper;
import cn.jia.chat.mapper.ChatMessageMapper;
import cn.jia.chat.service.BuiltinHallAgentSupport;
import cn.jia.chat.service.ChatConversationEventBroker;
import cn.jia.chat.service.ChatConversationService;
import cn.jia.chat.service.JuyitingConversationScopeService;
import cn.jia.chat.service.impl.ChatConversationServiceImpl;
import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.core.util.JsonUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.Field;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/**
 * Opt-in MySQL evidence for the real shared chat persistence boundary. The confirmation and
 * transport identities are fixture prerequisites; message persistence, archive claims, source
 * acknowledgements, transaction rollback, and fencing use production JDBC/MyBatis code.
 */
class ArchiveBusinessOutboxMySqlTest {
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final String COLLECTION = "platform-classics";
    private static final String OWNER = "owner-a";
    private static final String CLIENT = "client-a";
    private static final String SHA = "a".repeat(64);

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private ArchiveTransactions transactions;
    private ChatConversationDao conversationDao;
    private ChatConversationService conversationService;
    private ChatConversationEventBroker broker;
    private JuyitingConversationScopeService scopes;
    private BuiltinHallAgentSupport builtin;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue("true".equals(System.getenv("CYF_H02_MYSQL_ISOLATED")),
                "requires explicit isolated H02 MySQL acknowledgement");
        ArchiveMySqlTestGuard.Target target = ArchiveMySqlTestGuard.requireDisposable(System.getenv());
        dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(target.url());
        dataSource.setUsername(System.getenv().getOrDefault("CYF_H02_MYSQL_USER", "root"));
        dataSource.setPassword(System.getenv().getOrDefault("CYF_H02_MYSQL_PASSWORD", ""));
        jdbc = new JdbcTemplate(dataSource);
        clean();
        new ArchiveSchemaInitializer(jdbc).initialize();
        new ArchiveMaintenanceSchemaInitializer(jdbc, new JdbcArchiveMaintenanceStore(jdbc),
                new ArchiveMaintenanceProperties()).initialize();
        createChatTables();

        SqlSessionFactory factory = createSqlSessionFactory();
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        ChatConversationDaoImpl conversationDaoImpl = new ChatConversationDaoImpl();
        setBaseMapper(conversationDaoImpl, template.getMapper(ChatConversationMapper.class));
        conversationDao = conversationDaoImpl;
        ChatMessageDaoImpl messageDaoImpl = new ChatMessageDaoImpl();
        setBaseMapper(messageDaoImpl, template.getMapper(ChatMessageMapper.class));
        ChatMessageDao messageDao = messageDaoImpl;

        broker = new ChatConversationEventBroker();
        conversationService = new ChatConversationServiceImpl(conversationDao, messageDao,
                mock(AgentTaskThreadDao.class), broker);
        builtin = new BuiltinHallAgentSupport(mock(AgentService.class));
        scopes = new JuyitingConversationScopeService(builtin, mock(AgentService.class));
        transactions = new SpringArchiveTransactions(new DataSourceTransactionManager(dataSource));
    }

    @AfterEach
    void tearDown() {
        if (jdbc != null) clean();
    }

    @Test
    void lateAcknowledgementFailureRollsBackMessageAndReplaysToOneDurableProjection() {
        seedBoundEvent("job-rollback", "intent-rollback", 41, 4, "JOB_CREATED");
        FailingCompleteStore failing = new FailingCompleteStore(jdbc);
        ArchiveBusinessOutboxDispatcher first = dispatcher(failing, NOW);

        assertEquals(0, first.dispatchOnce());
        assertEquals(0, count("chat_message"));
        assertEquals("WAITING_RETRY:PENDING", jdbc.queryForObject("""
                SELECT CONCAT(o.state,':',e.outbox_state)
                FROM archive_business_outbox o
                JOIN archive_event e ON e.job_id=o.job_id AND e.sequence=o.event_sequence
                WHERE o.projection_key='EVENT:job-rollback:1'
                """, String.class));

        ArchiveBusinessOutboxDispatcher recovery = dispatcher(
                new JdbcArchiveMaintenanceStore(jdbc), NOW.plusSeconds(2));
        assertEquals(1, recovery.dispatchOnce());
        assertEquals(1, count("chat_message"));
        assertEquals("DELIVERED:DELIVERED:1", jdbc.queryForObject("""
                SELECT CONCAT(o.state,':',e.outbox_state,':',o.projected_message_id IS NOT NULL)
                FROM archive_business_outbox o
                JOIN archive_event e ON e.job_id=o.job_id AND e.sequence=o.event_sequence
                WHERE o.projection_key='EVENT:job-rollback:1'
                """, String.class));
        assertEquals(0, recovery.dispatchOnce());
        assertEquals(1, count("chat_message"));
        String content = jdbc.queryForObject("SELECT content FROM chat_message", String.class);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> parsed = JsonUtil.fromJson(content, java.util.Map.class);
        assertEquals("archive_maintenance_receipt", parsed.get("type"));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> compatibility =
                (java.util.Map<String, Object>) parsed.get("archiveMaintenance");
        assertEquals("job-rollback", compatibility.get("jobId"),
                "the persisted payload must satisfy the frozen Web v1 receipt parser");
        assertTrue(content.contains("JOB_CREATED"));
        assertTrue(!content.contains("secret-payload"));
    }

    @Test
    void competingDispatchersUseTheRealClaimCasAndPersistOnlyOneMessage() throws Exception {
        seedBoundEvent("job-race", "intent-race", 42, 2, "JOB_CREATED");
        CountDownLatch candidatesRead = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ArchiveBusinessOutboxDispatcher first = dispatcher(
                new BarrierCandidateStore(jdbc, candidatesRead, release), NOW);
        ArchiveBusinessOutboxDispatcher second = dispatcher(
                new BarrierCandidateStore(jdbc, candidatesRead, release), NOW);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit((java.util.concurrent.Callable<Integer>) first::dispatchOnce);
            Future<Integer> b = pool.submit((java.util.concurrent.Callable<Integer>) second::dispatchOnce);
            assertTrue(candidatesRead.await(10, TimeUnit.SECONDS),
                    "both dispatchers must observe the same candidate before claim");
            release.countDown();
            assertEquals(1, a.get(15, TimeUnit.SECONDS) + b.get(15, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
        assertEquals(1, count("chat_message"));
        assertEquals("DELIVERED:1", jdbc.queryForObject("""
                SELECT CONCAT(state,':',attempt_count) FROM archive_business_outbox
                WHERE projection_key='EVENT:job-race:1'
                """, String.class));
    }

    @Test
    void crossManagerWithdrawalLocksJobRootBeforeDispatcherPublicationProjection() throws Exception {
        seedBoundPublishedEdition("job-lock-order", "intent-lock-order", 47, 2);
        jdbc.update("INSERT INTO archive_collection_manager(collection_id,tenant_id,client_id,owner_jiacn,"
                + "permissions,state,revision) VALUES (?,'0','withdraw-client','withdraw-owner',"
                + "'edition.withdraw','ACTIVE',7)", COLLECTION);

        CountDownLatch withdrawalHasJobRoot = new CountDownLatch(1);
        CountDownLatch releaseWithdrawal = new CountDownLatch(1);
        CountDownLatch dispatcherAttemptedJobRoot = new CountDownLatch(1);
        ArchiveMaintenanceServiceImpl maintenance = maintenanceService(new BlockingJobRootStore(
                jdbc, "job-lock-order", withdrawalHasJobRoot, releaseWithdrawal));
        ArchiveBusinessOutboxDispatcher projection = dispatcher(new AttemptingJobRootStore(
                jdbc, "job-lock-order", dispatcherAttemptedJobRoot), NOW);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ArchiveWithdrawalDTO> withdrawal = pool.submit(() -> maintenance.withdraw(
                    new ArchiveActorScope("0", "withdraw-client", "withdraw-owner"),
                    "work-readback", "edition-readback", "cross-manager-withdraw", 1,
                    new ArchiveWithdrawRequest("cross-manager correction", null)));
            assertTrue(withdrawalHasJobRoot.await(10, TimeUnit.SECONDS),
                    "withdrawal must hold the publication job root before work/publication roots");

            Future<Integer> firstProjection = pool.submit(
                    (java.util.concurrent.Callable<Integer>) projection::dispatchOnce);
            assertTrue(dispatcherAttemptedJobRoot.await(10, TimeUnit.SECONDS),
                    "dispatcher must attempt the same job root through a distinct manager grant");
            assertTrue(!firstProjection.isDone(),
                    "dispatcher must wait on the ordered job root instead of forming a publication lock cycle");
            assertEquals(0, count("chat_message"));

            releaseWithdrawal.countDown();
            ArchiveWithdrawalDTO receipt = withdrawal.get(15, TimeUnit.SECONDS);
            assertEquals("PENDING", receipt.outboxState());
            assertEquals(1, firstProjection.get(15, TimeUnit.SECONDS));
            assertEquals(1, projection.dispatchOnce(),
                    "the committed withdrawal must remain as one subsequent durable notification");
        } finally {
            releaseWithdrawal.countDown();
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertEquals("WITHDRAWN:1:2", jdbc.queryForObject("""
                SELECT CONCAT(p.state,':',w.active_edition_id IS NULL,':',cw.revision)
                FROM archive_publication p
                JOIN archive_work w ON w.work_id=p.work_id
                JOIN archive_collection_work cw ON cw.collection_id=p.collection_id AND cw.work_id=p.work_id
                WHERE p.publication_id='publication-readback'
                """, String.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_business_outbox WHERE state='DELIVERED'", Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_event WHERE outbox_state='DELIVERED'", Integer.class)
                + jdbc.queryForObject(
                        "SELECT COUNT(*) FROM archive_edition_withdrawal WHERE outbox_state='DELIVERED'",
                        Integer.class));
        assertEquals(2, count("chat_message"));
        assertEquals(1, count("archive_publication"));
        assertEquals(1, count("archive_edition_withdrawal"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM archive_collection_manager "
                + "WHERE collection_id=?", Integer.class, COLLECTION),
                "the concurrency proof must use two legitimate manager grant rows");
        assertEquals("edition.withdraw", jdbc.queryForObject("SELECT permissions FROM archive_collection_manager "
                + "WHERE collection_id=? AND client_id='withdraw-client' AND owner_jiacn='withdraw-owner'",
                String.class, COLLECTION));
    }

    @Test
    void boundedStateQueueScansUseDueIndexesAndIgnoreDeepFutureLeaseBacklog() {
        seedCandidateScanJob();
        for (int sequence = 1; sequence <= 256; sequence++) {
            insertCandidateEvent(sequence, "LEASED", NOW.minusSeconds(3600),
                    NOW.plusSeconds(3600L + sequence), null);
        }
        insertCandidateEvent(1001, "READY", NOW.minusSeconds(5), null, null);
        insertCandidateEvent(1002, "WAITING_RETRY", NOW.minusSeconds(4), null, "RETRYABLE");
        insertCandidateEvent(1003, "LEASED", NOW.minusSeconds(3600), NOW.minusSeconds(3), null);

        JdbcArchiveMaintenanceStore store = new JdbcArchiveMaintenanceStore(jdbc);
        List<ArchiveBusinessOutboxRecord> first = store.findBusinessOutboxCandidates(
                NOW, null, null, 2);
        assertEquals(List.of("EVENT:job-scan:1001", "EVENT:job-scan:1002"),
                first.stream().map(ArchiveBusinessOutboxRecord::projectionKey).toList());
        List<ArchiveBusinessOutboxRecord> next = store.findBusinessOutboxCandidates(
                NOW, NOW.minusSeconds(4), "EVENT:job-scan:1002", 2);
        assertEquals(List.of("EVENT:job-scan:1003"),
                next.stream().map(ArchiveBusinessOutboxRecord::projectionKey).toList());

        assertDueQueuePlan("READY", "available_at", "idx_archive_business_available");
        assertDueQueuePlan("WAITING_RETRY", "available_at", "idx_archive_business_available");
        assertDueQueuePlan("LEASED", "lease_until", "idx_archive_business_lease");
        assertEquals(256, jdbc.queryForObject("SELECT COUNT(*) FROM archive_business_outbox "
                + "WHERE state='LEASED' AND lease_until>?", Integer.class, Timestamp.from(NOW)),
                "the plan proof must retain a deep future-lease backlog outside the due range");
    }

    @Test
    void withdrawalProjectionPersistsCurrentSanitizedFactsAndAcknowledgesItsExactSource() {
        seedBoundWithdrawal("job-withdrawal", "intent-withdrawal", 45, 2);

        assertEquals(1, dispatcher(new JdbcArchiveMaintenanceStore(jdbc), NOW).dispatchOnce());
        assertEquals(1, count("chat_message"));
        assertEquals("DELIVERED:DELIVERED:1", jdbc.queryForObject("""
                SELECT CONCAT(o.state,':',w.outbox_state,':',o.projected_message_id IS NOT NULL)
                FROM archive_business_outbox o
                JOIN archive_edition_withdrawal w ON w.withdrawal_id=o.withdrawal_id
                WHERE o.projection_key='WITHDRAWAL:withdrawal-a'
                """, String.class));
        assertEquals(1, count("archive_publication"),
                "notification recovery must never create a second publication");
        assertEquals(1, count("archive_edition_withdrawal"));

        String content = jdbc.queryForObject("SELECT content FROM chat_message", String.class);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> parsed = JsonUtil.fromJson(content, java.util.Map.class);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> compatibility =
                (java.util.Map<String, Object>) parsed.get("archiveMaintenance");
        assertEquals("job-withdrawal", compatibility.get("jobId"));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> publication =
                (java.util.Map<String, Object>) parsed.get("publication");
        assertEquals("WITHDRAWN", publication.get("state"));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> withdrawal =
                (java.util.Map<String, Object>) publication.get("withdrawal");
        assertEquals("withdrawal-a", withdrawal.get("withdrawalId"));
        assertEquals("2", withdrawal.get("resultingWorkRevision"));
        assertTrue(!content.contains("withdrawal reason must stay private"));
    }

    @Test
    void committedPublicationProjectionConvergesAfterFailedThenPassedReadbackWithoutRepublishing() {
        seedBoundPublishedEdition("job-readback", "intent-readback", 46, 2);
        JdbcArchiveMaintenanceStore store = new JdbcArchiveMaintenanceStore(jdbc);

        assertEquals(1, dispatcher(store, NOW).dispatchOnce());
        assertEquals("PENDING", latestVerification().get("state"));
        assertEquals(1, count("chat_message"));

        ArchiveMaintenanceServiceImpl maintenance = maintenanceService(store);
        ArchiveActorScope actor = new ArchiveActorScope("0", CLIENT, OWNER);
        maintenance.edition(actor, "work-readback", "edition-readback");
        makeReadyNow();
        assertEquals("FAILED:2", jdbc.queryForObject(
                "SELECT CONCAT(state,':',revision) FROM archive_publication_readback "
                        + "WHERE publication_id='publication-readback'", String.class));
        assertEquals(1, eventCount("PUBLICATION_READBACK_COMPLETED"));
        maintenance.edition(actor, "work-readback", "edition-readback");
        assertEquals(1, eventCount("PUBLICATION_READBACK_COMPLETED"),
                "the same failed facts must not advance readback or duplicate the outbox fact");

        assertEquals(0, dispatcher(new FailingCompleteStore(jdbc), NOW).dispatchOnce());
        assertEquals(1, count("chat_message"),
                "the message and acknowledgement must roll back together");
        assertEquals(1, dispatcher(store, NOW.plusSeconds(2)).dispatchOnce());
        assertEquals(2, count("chat_message"));
        assertCurrentReadbackMatchesLatestCard("FAILED");

        String paragraphSha = ArchiveEtags.sha256("x".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, jdbc.update("UPDATE archive_paragraph SET sha256=? "
                + "WHERE paragraph_id='edition-readback-c001-p0001'", paragraphSha));
        maintenance.edition(actor, "work-readback", "edition-readback");
        makeReadyNow();
        assertEquals("PASSED:3", jdbc.queryForObject(
                "SELECT CONCAT(state,':',revision) FROM archive_publication_readback "
                        + "WHERE publication_id='publication-readback'", String.class));
        assertEquals(2, eventCount("PUBLICATION_READBACK_COMPLETED"));
        assertEquals(1, dispatcher(store, NOW.plusSeconds(3)).dispatchOnce());
        assertEquals(3, count("chat_message"));
        assertCurrentReadbackMatchesLatestCard("PASSED");

        maintenance.edition(actor, "work-readback", "edition-readback");
        assertEquals(2, eventCount("PUBLICATION_READBACK_COMPLETED"),
                "an unchanged terminal readback must not emit a duplicate business fact");
        assertEquals(0, dispatcher(store, NOW.plusSeconds(4)).dispatchOnce());
        assertEquals(1, count("archive_publication"),
                "readback notification recovery must not create a second publication");
        assertEquals("PUBLISHED:2:publication-readback", jdbc.queryForObject(
                "SELECT CONCAT(state,':',revision,':',publication_id) FROM archive_maintenance_job "
                        + "WHERE job_id='job-readback'", String.class));
    }

    @Test
    void generationRotationAndManagerRevocationTerminateWithoutCrossOwnerProjection() {
        seedBoundEvent("job-rotated", "intent-rotated", 43, 3, "JOB_CREATED");
        jdbc.update("UPDATE chat_conversation SET lifecycle_generation=4 WHERE id=43");
        assertEquals(1, dispatcher(new JdbcArchiveMaintenanceStore(jdbc), NOW).dispatchOnce());
        assertEquals("NO_TARGET:NO_TARGET:CONVERSATION_GENERATION_FENCED",
                state("EVENT:job-rotated:1"));
        assertEquals(0, count("chat_message"));

        cleanMaintenanceAndChatRows();
        seedBoundEvent("job-revoked", "intent-revoked", 44, 1, "JOB_CREATED");
        jdbc.update("UPDATE archive_collection_manager SET state='REVOKED',revision=2 "
                + "WHERE collection_id=? AND owner_jiacn=? AND client_id=?",
                COLLECTION, OWNER, CLIENT);
        assertEquals(1, dispatcher(new JdbcArchiveMaintenanceStore(jdbc), NOW).dispatchOnce());
        assertEquals("NO_TARGET:NO_TARGET:MANAGER_AUTHORIZATION_REVOKED",
                state("EVENT:job-revoked:1"));
        assertEquals(0, count("chat_message"));
    }

    private ArchiveBusinessOutboxDispatcher dispatcher(ArchiveMaintenanceStore store, Instant now) {
        return new ArchiveBusinessOutboxDispatcher(store, transactions, conversationDao,
                conversationService, broker, scopes, builtin, Clock.fixed(now, ZoneOffset.UTC));
    }

    private void seedCandidateScanJob() {
        jdbc.update("INSERT INTO archive_collection_manager(collection_id,tenant_id,client_id,owner_jiacn,"
                + "permissions,state,revision) VALUES (?,'0',?,?,?,'ACTIVE',1)", COLLECTION,
                CLIENT, OWNER, "job.manage");
        jdbc.update("INSERT INTO archive_maintenance_job(job_id,collection_id,tenant_id,client_id,owner_jiacn,"
                + "manager_authorization_revision,publication_mode,operation_code,state,wait_reason,revision,"
                + "request_intent_id,request_sha256) VALUES ('job-scan',?,'0',?,?,1,'MANUAL','ADD_WORK',"
                + "'WAITING_INPUT','SOURCE_AND_WORK',1,'intent-scan',?)",
                COLLECTION, CLIENT, OWNER, SHA);
    }

    private void insertCandidateEvent(long sequence, String state, Instant availableAt,
            Instant leaseUntil, String lastErrorCode) {
        jdbc.update("INSERT INTO archive_event(job_id,sequence,schema_version,event_type,job_revision,data_json,"
                + "outbox_state) VALUES ('job-scan',?,1,'SCAN_CANDIDATE',1,'{}','PENDING')", sequence);
        jdbc.update("INSERT INTO archive_business_outbox(projection_key,source_type,job_id,event_sequence,"
                + "withdrawal_id,state,attempt_count,fencing_token,available_at,lease_until,last_error_code,"
                + "projected_message_id) VALUES (?, 'JOB_EVENT','job-scan',?,NULL,?,0,0,?,?,?,NULL)",
                "EVENT:job-scan:" + sequence, sequence, state, Timestamp.from(availableAt),
                leaseUntil == null ? null : Timestamp.from(leaseUntil), lastErrorCode);
    }

    private void assertDueQueuePlan(String state, String timeColumn, String expectedIndex) {
        Map<String, Object> plan = jdbc.queryForMap("EXPLAIN SELECT o.* FROM archive_business_outbox o "
                + "FORCE INDEX (" + expectedIndex + ") WHERE o.state=? AND o." + timeColumn
                + "<=? ORDER BY o." + timeColumn + ",o.projection_key LIMIT 3",
                state, Timestamp.from(NOW));
        Map<String, Object> safePlan = new java.util.LinkedHashMap<>();
        safePlan.put("state", state);
        safePlan.put("key", explainValue(plan, "key"));
        safePlan.put("type", explainValue(plan, "type"));
        safePlan.put("Extra", explainValue(plan, "Extra"));
        safePlan.put("rows", explainValue(plan, "rows"));
        System.out.println("ARCHIVE_BUSINESS_OUTBOX_EXPLAIN " + safePlan);
        assertEquals(expectedIndex, explainValue(plan, "key"));
        assertEquals("range", explainValue(plan, "type"));
        String extra = explainValue(plan, "Extra");
        assertTrue(extra == null || !extra.toLowerCase(java.util.Locale.ROOT).contains("filesort"),
                "the due queue must be returned in index order without sorting the future backlog: " + plan);
    }

    private static String explainValue(Map<String, Object> plan, String name) {
        for (Map.Entry<String, Object> entry : plan.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue() == null ? null : entry.getValue().toString();
            }
        }
        return null;
    }

    private void seedBoundEvent(String jobId, String intentId, long conversationId,
            long generation, String eventType) {
        jdbc.update("INSERT INTO archive_collection_manager(collection_id,tenant_id,client_id,owner_jiacn,"
                + "permissions,state,revision) VALUES (?,'0',?,?,?,'ACTIVE',3)", COLLECTION,
                CLIENT, OWNER, "job.manage");
        jdbc.update("INSERT INTO archive_maintenance_job(job_id,collection_id,tenant_id,client_id,owner_jiacn,"
                + "manager_authorization_revision,publication_mode,operation_code,state,wait_reason,revision,"
                + "request_intent_id,request_sha256) VALUES (?,?,'0',?,?,3,'MANUAL','ADD_WORK',"
                + "'WAITING_INPUT','SOURCE_AND_WORK',1,?,?)",
                jobId, COLLECTION, CLIENT, OWNER, intentId, SHA);
        jdbc.update("INSERT INTO chat_conversation(id,title,jiacn,status,conversation_type,"
                + "conversation_scope_type,conversation_scope_key,target_agent_ids,deleted_at,"
                + "lifecycle_generation,create_time,update_time,client_id,tenant_id) VALUES "
                + "(?,? ,?,0,'juyiting','public','public',?,NULL,?,1,1,?,'0')",
                conversationId, jobId, OWNER, "[\"" + builtin.defaultAgentId() + "\"]",
                generation, CLIENT);
        JdbcArchiveMaintenanceStore store = new JdbcArchiveMaintenanceStore(jdbc);
        insertAndBindConfirmation(store, jobId, intentId, conversationId, generation);
        transactions.required(() -> {
            store.appendJobEvent(jobId, 1, eventType, "{\"secret-payload\":true}");
            return null;
        });
        makeReadyNow();
    }

    private void seedBoundWithdrawal(String jobId, String intentId, long conversationId,
            long generation) {
        jdbc.update("INSERT INTO archive_collection_manager(collection_id,tenant_id,client_id,owner_jiacn,"
                + "permissions,state,revision) VALUES (?,'0',?,?,?,'ACTIVE',3)", COLLECTION,
                CLIENT, OWNER, "job.manage");
        jdbc.update("INSERT INTO archive_work(work_id,title,active_edition_id) "
                + "VALUES ('work-withdrawal','title',NULL)");
        jdbc.update("INSERT INTO archive_collection_work(collection_id,work_id,canonical_key,revision) "
                + "VALUES (?,'work-withdrawal','work-withdrawal',2)", COLLECTION);
        jdbc.update("INSERT INTO archive_edition(edition_id,work_id,import_state,source_sha256,manifest_sha256,"
                + "manifest_file_sha256,source_utf8_byte_length,chapter_count,preface_paragraph_count,"
                + "chapter_paragraph_count,reader_paragraph_count,preface_utf8_byte_length,"
                + "chapter_utf8_byte_length,reader_utf8_byte_length) VALUES ('edition-withdrawal',"
                + "'work-withdrawal','READY',?,?,?,1,1,0,1,1,0,1,1)", SHA, SHA, SHA);
        jdbc.update("INSERT INTO archive_chapter(edition_id,block_id,block_type,reader_ordinal,chapter_number,"
                + "title,paragraph_count,utf8_byte_length,block_content_sha256) VALUES "
                + "('edition-withdrawal','edition-withdrawal-c001','CHAPTER',1,1,'chapter',1,1,?)", SHA);
        jdbc.update("INSERT INTO archive_paragraph(edition_id,block_id,paragraph_id,ordinal,text,"
                + "utf8_byte_length,sha256) VALUES ('edition-withdrawal','edition-withdrawal-c001',"
                + "'edition-withdrawal-c001-p0001',1,'x',1,?)", SHA);
        jdbc.update("INSERT INTO archive_appointment_slot(collection_id,role_code,current_appointment_id,revision) "
                + "VALUES (?,'ARCHIVE_EDITOR',NULL,1)", COLLECTION);
        jdbc.update("INSERT INTO archive_appointment(appointment_id,collection_id,role_code,tenant_id,client_id,"
                + "owner_jiacn,agent_id,binding_version,work_scope_mode,work_ids,permission_profile,"
                + "required_skill_key,required_skill_version,required_skill_sha256,status,revision) VALUES "
                + "('appointment-withdrawal',?,'ARCHIVE_EDITOR','0',?,?,'agent-a','7','COLLECTION','',"
                + "'PUBLISH_VALIDATED','archive-maintainer','1.0.0',?,'ACTIVE',1)",
                COLLECTION, CLIENT, OWNER, SHA);
        jdbc.update("UPDATE archive_appointment_slot SET current_appointment_id='appointment-withdrawal' "
                + "WHERE collection_id=? AND role_code='ARCHIVE_EDITOR'", COLLECTION);
        jdbc.update("INSERT INTO archive_source_snapshot(source_id,collection_id,tenant_id,client_id,owner_jiacn,"
                + "storage_uri,raw_sha256,raw_byte_length,source_name,source_version,rights_basis,"
                + "normalization_rule,state) VALUES ('source-withdrawal',?,'0',?,?,'cyf-artifact://source',"
                + "?,1,'source','v1','authorized','UTF8_EXACT_V1','READY')",
                COLLECTION, CLIENT, OWNER, SHA);
        jdbc.update("INSERT INTO archive_maintenance_job(job_id,run_id,collection_id,tenant_id,client_id,owner_jiacn,"
                + "appointment_id,appointment_revision,agent_id,binding_version,permission_profile,"
                + "manager_authorization_revision,publication_mode,operation_code,work_id,canonical_key,title,"
                + "source_id,source_sha256,source_summary,rights_basis,state,wait_reason,revision,draft_id,"
                + "publication_id,request_intent_id,request_sha256) VALUES (?, 'run-withdrawal',?,'0',?,?,"
                + "'appointment-withdrawal',1,'agent-a','7','PUBLISH_VALIDATED',3,'MANUAL','REVISE_WORK',"
                + "'work-withdrawal','work-withdrawal','title','source-withdrawal',?,'source / v1',"
                + "'authorized','PUBLISHED',NULL,4,'draft-withdrawal','publication-a',?,?)",
                jobId, COLLECTION, CLIENT, OWNER, SHA, intentId, SHA);
        jdbc.update("INSERT INTO archive_publication(publication_id,job_id,collection_id,work_id,edition_id,"
                + "draft_revision,manifest_sha256,source_sha256,state,actor_type,actor_id,authorization_revision) "
                + "VALUES ('publication-a',?,?, 'work-withdrawal','edition-withdrawal',1,?,?,'WITHDRAWN',"
                + "'HUMAN',?,3)", jobId, COLLECTION, SHA, SHA, OWNER);
        jdbc.update("INSERT INTO archive_publication_readback(publication_id,state,revision,verification_digest,"
                + "findings_json,checked_at) VALUES ('publication-a','PASSED',2,?,'[]',CURRENT_TIMESTAMP(6))", SHA);
        jdbc.update("INSERT INTO chat_conversation(id,title,jiacn,status,conversation_type,"
                + "conversation_scope_type,conversation_scope_key,target_agent_ids,deleted_at,"
                + "lifecycle_generation,create_time,update_time,client_id,tenant_id) VALUES "
                + "(?,? ,?,0,'juyiting','public','public',?,NULL,?,1,1,?,'0')",
                conversationId, jobId, OWNER, "[\"" + builtin.defaultAgentId() + "\"]",
                generation, CLIENT);
        JdbcArchiveMaintenanceStore store = new JdbcArchiveMaintenanceStore(jdbc);
        insertAndBindConfirmation(store, jobId, intentId, conversationId, generation);
        transactions.required(() -> {
            store.insertWithdrawal(new cn.jia.chat.archive.maintenance.model.ArchiveWithdrawalRecord(
                    "withdrawal-a", "publication-a", COLLECTION, "work-withdrawal",
                    "edition-withdrawal", "withdrawal reason must stay private", "0", CLIENT,
                    OWNER, "HUMAN", OWNER, 3, null, null, 2,
                    "withdrawal-key", NOW, "PENDING"));
            return null;
        });
    }

    private void seedBoundPublishedEdition(String jobId, String intentId, long conversationId,
            long generation) {
        String paragraphSha = ArchiveEtags.sha256("x".getBytes(StandardCharsets.UTF_8));
        String blockId = "edition-readback-c001";
        String blockContentSha = ArchiveEtags.sha256(
                (SHA + ":" + blockId + ":[" + paragraphSha + "]")
                        .getBytes(StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO archive_collection_manager(collection_id,tenant_id,client_id,owner_jiacn,"
                + "permissions,state,revision) VALUES (?,'0',?,?,?,'ACTIVE',3)", COLLECTION,
                CLIENT, OWNER, "job.manage,publish");
        jdbc.update("INSERT INTO archive_work(work_id,title,active_edition_id) "
                + "VALUES ('work-readback','title',NULL)");
        jdbc.update("INSERT INTO archive_collection_work(collection_id,work_id,canonical_key,revision) "
                + "VALUES (?,'work-readback','work-readback',1)", COLLECTION);
        jdbc.update("INSERT INTO archive_edition(edition_id,work_id,import_state,source_sha256,manifest_sha256,"
                + "manifest_file_sha256,source_utf8_byte_length,chapter_count,preface_paragraph_count,"
                + "chapter_paragraph_count,reader_paragraph_count,preface_utf8_byte_length,"
                + "chapter_utf8_byte_length,reader_utf8_byte_length) VALUES ('edition-readback',"
                + "'work-readback','READY',?,?,?,1,1,0,1,1,0,1,1)", SHA, SHA, SHA);
        jdbc.update("INSERT INTO archive_chapter(edition_id,block_id,block_type,reader_ordinal,chapter_number,"
                + "title,paragraph_count,utf8_byte_length,block_content_sha256) VALUES "
                + "('edition-readback',?,'CHAPTER',1,1,'chapter',1,1,?)", blockId, blockContentSha);
        jdbc.update("INSERT INTO archive_paragraph(edition_id,block_id,paragraph_id,ordinal,text,"
                + "utf8_byte_length,sha256) VALUES ('edition-readback',?,"
                + "'edition-readback-c001-p0001',1,'x',1,?)", blockId, "b".repeat(64));
        jdbc.update("UPDATE archive_work SET active_edition_id='edition-readback' "
                + "WHERE work_id='work-readback'");
        jdbc.update("INSERT INTO archive_appointment_slot(collection_id,role_code,current_appointment_id,revision) "
                + "VALUES (?,'ARCHIVE_EDITOR',NULL,1)", COLLECTION);
        jdbc.update("INSERT INTO archive_appointment(appointment_id,collection_id,role_code,tenant_id,client_id,"
                + "owner_jiacn,agent_id,binding_version,work_scope_mode,work_ids,permission_profile,"
                + "required_skill_key,required_skill_version,required_skill_sha256,status,revision) VALUES "
                + "('appointment-readback',?,'ARCHIVE_EDITOR','0',?,?,'agent-a','7','COLLECTION','',"
                + "'PUBLISH_VALIDATED','archive-maintainer','1.0.0',?,'ACTIVE',1)",
                COLLECTION, CLIENT, OWNER, SHA);
        jdbc.update("UPDATE archive_appointment_slot SET current_appointment_id='appointment-readback' "
                + "WHERE collection_id=? AND role_code='ARCHIVE_EDITOR'", COLLECTION);
        jdbc.update("INSERT INTO archive_source_snapshot(source_id,collection_id,tenant_id,client_id,owner_jiacn,"
                + "storage_uri,raw_sha256,raw_byte_length,source_name,source_version,rights_basis,"
                + "normalization_rule,state) VALUES ('source-readback',?,'0',?,?,'cyf-artifact://source',"
                + "?,1,'source','v1','authorized','UTF8_EXACT_V1','READY')",
                COLLECTION, CLIENT, OWNER, SHA);
        jdbc.update("INSERT INTO archive_maintenance_job(job_id,run_id,collection_id,tenant_id,client_id,owner_jiacn,"
                + "appointment_id,appointment_revision,agent_id,binding_version,permission_profile,"
                + "manager_authorization_revision,publication_mode,operation_code,work_id,canonical_key,title,"
                + "source_id,source_sha256,source_summary,rights_basis,state,wait_reason,revision,draft_id,"
                + "publication_id,request_intent_id,request_sha256) VALUES (?, 'run-readback',?,'0',?,? ,"
                + "'appointment-readback',1,'agent-a','7','PUBLISH_VALIDATED',3,'MANUAL','REVISE_WORK',"
                + "'work-readback','work-readback','title','source-readback',?,'source / v1',"
                + "'authorized','PUBLISHED',NULL,2,'draft-readback','publication-readback',?,?)",
                jobId, COLLECTION, CLIENT, OWNER, SHA, intentId, SHA);
        jdbc.update("INSERT INTO archive_publication(publication_id,job_id,collection_id,work_id,edition_id,"
                + "draft_revision,manifest_sha256,source_sha256,state,actor_type,actor_id,authorization_revision) "
                + "VALUES ('publication-readback',?,?, 'work-readback','edition-readback',1,?,?,'PUBLISHED',"
                + "'HUMAN',?,3)", jobId, COLLECTION, SHA, SHA, OWNER);
        jdbc.update("INSERT INTO archive_publication_readback(publication_id,state,revision,verification_digest,"
                + "findings_json,checked_at) VALUES ('publication-readback','PENDING',1,NULL,'[]',NULL)");
        jdbc.update("INSERT INTO chat_conversation(id,title,jiacn,status,conversation_type,"
                + "conversation_scope_type,conversation_scope_key,target_agent_ids,deleted_at,"
                + "lifecycle_generation,create_time,update_time,client_id,tenant_id) VALUES "
                + "(?,? ,?,0,'juyiting','public','public',?,NULL,?,1,1,?,'0')",
                conversationId, jobId, OWNER, "[\"" + builtin.defaultAgentId() + "\"]",
                generation, CLIENT);
        JdbcArchiveMaintenanceStore store = new JdbcArchiveMaintenanceStore(jdbc);
        insertAndBindConfirmation(store, jobId, intentId, conversationId, generation);
        transactions.required(() -> {
            store.appendJobEvent(jobId, 2, "PUBLICATION_COMMITTED",
                    "{\"publicationId\":\"publication-readback\"}");
            return null;
        });
        makeReadyNow();
    }

    private void insertAndBindConfirmation(JdbcArchiveMaintenanceStore store, String jobId,
            String intentId, long conversationId, long generation) {
        String confirmationRef = "confirm-" + jobId;
        store.insertConfirmedRequest(new ArchiveConfirmedRequestRecord(
                confirmationRef, intentId, "0", CLIENT, OWNER, COLLECTION, "{}", SHA,
                "MANUAL", null, null, null, null, null, null, 1));
        assertEquals(1, store.bindConfirmedRequest(
                new ArchiveActorScope("0", CLIENT, OWNER), confirmationRef, 1,
                Long.toString(conversationId), "100", generation, SHA, "SONGJIANG", null));
    }

    private void makeReadyNow() {
        jdbc.update("UPDATE archive_business_outbox SET available_at=? WHERE state='READY'",
                Timestamp.from(NOW));
    }

    private ArchiveMaintenanceServiceImpl maintenanceService(JdbcArchiveMaintenanceStore store) {
        return new ArchiveMaintenanceServiceImpl(store, new JdbcArchiveContentStore(jdbc),
                transactions, mock(AgentIdentityService.class), new ObjectMapper(),
                mock(AgentTaskArtifactStorage.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> latestVerification() {
        String content = jdbc.queryForObject(
                "SELECT content FROM chat_message ORDER BY id DESC LIMIT 1", String.class);
        Map<String, Object> root = JsonUtil.fromJson(content, Map.class);
        Map<String, Object> publication = (Map<String, Object>) root.get("publication");
        return (Map<String, Object>) publication.get("verification");
    }

    private void assertCurrentReadbackMatchesLatestCard(String expectedState) {
        Map<String, Object> verification = latestVerification();
        String currentState = jdbc.queryForObject(
                "SELECT state FROM archive_publication_readback "
                        + "WHERE publication_id='publication-readback'", String.class);
        String currentRevision = jdbc.queryForObject(
                "SELECT CAST(revision AS CHAR) FROM archive_publication_readback "
                        + "WHERE publication_id='publication-readback'", String.class);
        String currentDigest = jdbc.queryForObject(
                "SELECT verification_digest FROM archive_publication_readback "
                        + "WHERE publication_id='publication-readback'", String.class);
        assertEquals(expectedState, verification.get("state"));
        assertEquals(currentState, verification.get("state"));
        assertEquals(currentRevision, verification.get("revision"));
        assertEquals(currentDigest, verification.get("verificationDigest"));
    }

    private int eventCount(String eventType) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM archive_event WHERE event_type=?",
                Integer.class, eventType);
    }

    private String state(String key) {
        return jdbc.queryForObject("""
                SELECT CONCAT(o.state,':',e.outbox_state,':',o.last_error_code)
                FROM archive_business_outbox o
                JOIN archive_event e ON e.job_id=o.job_id AND e.sequence=o.event_sequence
                WHERE o.projection_key=?
                """, String.class, key);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void createChatTables() {
        jdbc.execute("""
                CREATE TABLE chat_conversation (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    title VARCHAR(500) NULL,
                    jiacn VARCHAR(50) NULL,
                    status INT NULL,
                    conversation_type VARCHAR(20) NULL,
                    conversation_scope_type VARCHAR(20) NULL,
                    conversation_scope_key VARCHAR(120) NULL,
                    task_id VARCHAR(64) NULL,
                    target_agent_id VARCHAR(100) NULL,
                    target_agent_ids VARCHAR(2000) NULL,
                    deleted_at BIGINT NULL,
                    lifecycle_generation BIGINT NOT NULL DEFAULT 1,
                    create_time BIGINT NULL,
                    update_time BIGINT NULL,
                    client_id VARCHAR(50) NULL,
                    tenant_id VARCHAR(50) NULL,
                    PRIMARY KEY (id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        jdbc.execute("""
                CREATE TABLE chat_message (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    conversation_id VARCHAR(100) NOT NULL,
                    message_type VARCHAR(20) NULL,
                    content TEXT NULL,
                    metadata TEXT NULL,
                    create_time BIGINT NULL,
                    update_time BIGINT NULL,
                    client_id VARCHAR(50) NULL,
                    tenant_id VARCHAR(50) NULL,
                    jiacn VARCHAR(50) NULL,
                    sync_status VARCHAR(20) NULL,
                    conversation_type VARCHAR(20) NULL,
                    sender_type VARCHAR(20) NULL,
                    sender_name VARCHAR(100) NULL,
                    PRIMARY KEY (id),
                    KEY idx_chat_message_conversation (conversation_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
    }

    private SqlSessionFactory createSqlSessionFactory() throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ChatConversationMapper.class);
        configuration.addMapper(ChatMessageMapper.class);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        factoryBean.setGlobalConfig(globalConfig);
        return factoryBean.getObject();
    }

    private void setBaseMapper(Object dao, Object mapper) throws Exception {
        Field field = BaseDaoImpl.class.getDeclaredField("baseMapper");
        field.setAccessible(true);
        field.set(dao, mapper);
    }

    private void cleanMaintenanceAndChatRows() {
        jdbc.update("DELETE FROM chat_message");
        jdbc.update("DELETE FROM chat_conversation");
        jdbc.update("DELETE FROM archive_business_outbox");
        jdbc.update("DELETE FROM archive_event");
        jdbc.update("DELETE FROM archive_confirmed_request");
        jdbc.update("DELETE FROM archive_maintenance_job");
        jdbc.update("DELETE FROM archive_collection_manager");
    }

    private void clean() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS=0");
                try {
                    for (String table : new String[]{"chat_message", "chat_conversation",
                            "archive_idempotency", "archive_note", "archive_bookmark",
                            "archive_reader_progress", "archive_admin_operation_receipt", "archive_operation",
                            "archive_business_outbox", "archive_event", "archive_edition_withdrawal",
                            "archive_publication_readback", "archive_publication", "archive_validation",
                            "archive_draft", "archive_execution_grant", "archive_job_run",
                            "archive_maintenance_job", "archive_confirmed_request",
                            "archive_source_snapshot", "archive_appointment", "archive_appointment_slot",
                            "archive_collection_work", "archive_collection_manager", "archive_collection",
                            "archive_paragraph", "archive_chapter", "archive_edition", "archive_work"}) {
                        statement.execute("DROP TABLE IF EXISTS " + table);
                    }
                } finally {
                    statement.execute("SET FOREIGN_KEY_CHECKS=1");
                }
            }
            return null;
        });
    }

    private static final class FailingCompleteStore extends JdbcArchiveMaintenanceStore {
        private final AtomicBoolean failOnce = new AtomicBoolean(true);

        private FailingCompleteStore(JdbcTemplate jdbc) {
            super(jdbc);
        }

        @Override
        public int completeBusinessOutbox(String projectionKey, long fencingToken,
                String terminalState, Long projectedMessageId, String terminalCode) {
            int updated = super.completeBusinessOutbox(projectionKey, fencingToken,
                    terminalState, projectedMessageId, terminalCode);
            if (updated == 1 && failOnce.compareAndSet(true, false)) {
                throw new IllegalStateException("injected late acknowledgement failure");
            }
            return updated;
        }
    }

    private static final class BlockingJobRootStore extends JdbcArchiveMaintenanceStore {
        private final String jobId;
        private final CountDownLatch locked;
        private final CountDownLatch release;
        private final AtomicBoolean blockOnce = new AtomicBoolean(true);

        private BlockingJobRootStore(JdbcTemplate jdbc, String jobId, CountDownLatch locked,
                CountDownLatch release) {
            super(jdbc);
            this.jobId = jobId;
            this.locked = locked;
            this.release = release;
        }

        @Override
        public ArchiveMaintenanceJobRecord findJob(String id, boolean lock) {
            ArchiveMaintenanceJobRecord job = super.findJob(id, lock);
            if (lock && jobId.equals(id) && blockOnce.compareAndSet(true, false)) {
                locked.countDown();
                awaitLatch(release, "withdrawal job-root release timed out");
            }
            return job;
        }
    }

    private static final class AttemptingJobRootStore extends JdbcArchiveMaintenanceStore {
        private final String jobId;
        private final CountDownLatch attempted;
        private final AtomicBoolean signalOnce = new AtomicBoolean(true);

        private AttemptingJobRootStore(JdbcTemplate jdbc, String jobId, CountDownLatch attempted) {
            super(jdbc);
            this.jobId = jobId;
            this.attempted = attempted;
        }

        @Override
        public ArchiveMaintenanceJobRecord findJob(String id, boolean lock) {
            if (lock && jobId.equals(id) && signalOnce.compareAndSet(true, false)) attempted.countDown();
            return super.findJob(id, lock);
        }
    }

    private static void awaitLatch(CountDownLatch latch, String message) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException(message);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(message, interrupted);
        }
    }

    private static final class BarrierCandidateStore extends JdbcArchiveMaintenanceStore {
        private final CountDownLatch candidatesRead;
        private final CountDownLatch release;

        private BarrierCandidateStore(JdbcTemplate jdbc, CountDownLatch candidatesRead,
                CountDownLatch release) {
            super(jdbc);
            this.candidatesRead = candidatesRead;
            this.release = release;
        }

        @Override
        public List<ArchiveBusinessOutboxRecord> findBusinessOutboxCandidates(Instant now,
                Instant afterAvailableAt, String afterProjectionKey, int limit) {
            List<ArchiveBusinessOutboxRecord> rows = super.findBusinessOutboxCandidates(
                    now, afterAvailableAt, afterProjectionKey, limit);
            candidatesRead.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("claim race release timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("claim race interrupted", interrupted);
            }
            return rows;
        }
    }
}
