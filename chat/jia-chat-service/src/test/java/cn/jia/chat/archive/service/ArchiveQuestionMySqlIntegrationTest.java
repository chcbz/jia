package cn.jia.chat.archive.service;

import cn.jia.chat.archive.config.ArchiveQuestionSchemaInitializer;
import cn.jia.chat.archive.config.ArchiveReaderDataSchemaInitializer;
import cn.jia.chat.archive.config.ArchiveSchemaInitializer;
import cn.jia.chat.archive.content.ArchiveEtags;
import cn.jia.chat.archive.content.ArchiveManifest;
import cn.jia.chat.archive.content.ArchiveManifestBundle;
import cn.jia.chat.archive.content.ArchiveManifestLoader;
import cn.jia.chat.archive.model.ArchiveOwnerScope;
import cn.jia.chat.archive.store.JdbcArchiveContentStore;
import cn.jia.chat.archive.store.JdbcArchivePersonalDataStore;
import cn.jia.chat.archive.store.JdbcArchiveQuestionStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Destructive H05A evidence; guarded for an explicit disposable loopback MySQL 8.0.21 fixture only. */
class ArchiveQuestionMySqlIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-22T12:00:00Z");
    private JdbcTemplate jdbc;
    private ArchiveQuestionSchemaInitializer questionSchema;
    private JdbcArchiveQuestionStore store;
    private ArchiveQuestionServiceImpl service;
    private ArchiveQuestionEventDelivery delivery;
    private SpringArchiveTransactions transactions;
    private ArchiveManifest manifest;
    private byte[] body;

    @BeforeEach
    void setUp() {
        assumeTrue("true".equals(System.getenv("CYF_H05A_MYSQL_ISOLATED")),
                "requires explicit isolated H05A MySQL acknowledgement");
        var target = ArchiveQuestionMySqlTestGuard.requireDisposable(System.getenv());
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(target.url());
        Properties connectionProperties = new Properties();
        connectionProperties.setProperty("useUnicode", "true");
        connectionProperties.setProperty("characterEncoding", "UTF-8");
        dataSource.setConnectionProperties(connectionProperties);
        dataSource.setUsername(System.getenv().getOrDefault("CYF_H05A_MYSQL_USER", "root"));
        dataSource.setPassword(System.getenv().getOrDefault("CYF_H05A_MYSQL_PASSWORD", ""));
        jdbc = new JdbcTemplate(dataSource);
        clean();
        new ArchiveSchemaInitializer(jdbc).initialize();
        new ArchiveReaderDataSchemaInitializer(jdbc).initialize();
        questionSchema = new ArchiveQuestionSchemaInitializer(jdbc);
        questionSchema.initialize();
        questionSchema.initialize();
        ArchiveManifestBundle bundle = new ArchiveManifestLoader().load();
        transactions = new SpringArchiveTransactions(new DataSourceTransactionManager(dataSource));
        new ArchiveContentImporter(new JdbcArchiveContentStore(jdbc), transactions, 100)
                .importAndActivate(bundle.manifest(), bundle.manifestFileSha256());
        manifest = bundle.manifest();
        body = questionBody(manifest.chapters().getFirst(), "何谓忠义？");
        store = new JdbcArchiveQuestionStore(jdbc);
        delivery = new ArchiveQuestionEventDelivery(store, new ArchiveQuestionEventBroker(), transactions);
        service = new ArchiveQuestionServiceImpl(store, new JdbcArchivePersonalDataStore(jdbc), transactions,
                new ArchiveClerkFallbackProvider(), delivery, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach void tearDown() {
        if (delivery != null) delivery.stop();
        if (jdbc != null) clean();
    }

    @Test
    void realMysqlFourScopesReplayConcurrencyFencingExhaustionZeroWriteAndDriftFailClosed() throws Exception {
        String id = "123e4567-e89b-42d3-a456-426614174000";
        List<ArchiveOwnerScope> owners = List.of(
                new ArchiveOwnerScope("owner-a", "client-a", "owner-a"),
                new ArchiveOwnerScope("owner-a", "client-b", "owner-a"),
                new ArchiveOwnerScope("owner-b", "client-a", "owner-b"),
                new ArchiveOwnerScope("owner-b", "client-b", "owner-b"));
        for (ArchiveOwnerScope owner : owners) {
            ArchiveMutationResult first = service.create(owner, id, path(id), "same-key", body);
            ArchiveMutationResult replay = service.create(owner, id, path(id), "same-key", body);
            assertArrayEquals(first.body(), replay.body());
            assertTrue(replay.replayed());
        }
        assertEquals(4, count("archive_question"));
        assertEquals(4, count("archive_question_event"));
        assertEquals(4, count("archive_outbox"));
        assertEquals(4, count("archive_question_mutation"));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(DISTINCT tenant_id,client_id,owner_jiacn) FROM archive_question", Integer.class));
        assertEquals(404, assertThrows(ArchivePersonalDataException.class,
                () -> service.get(new ArchiveOwnerScope("owner-a", "client-c", "owner-a"), id)).status());

        String responseLossId = "173e4567-e89b-42d3-a456-426614174000";
        ArchiveTransactions responseLoss = new ArchiveTransactions() {
            private boolean disconnect = true;
            @Override public <T> T required(java.util.function.Supplier<T> action) {
                T committed = transactions.required(action);
                if (disconnect) {
                    disconnect = false;
                    throw new IllegalStateException("simulated response loss after commit");
                }
                return committed;
            }
            @Override public void afterCommit(Runnable action) { transactions.afterCommit(action); }
        };
        ArchiveQuestionServiceImpl responseLossService = new ArchiveQuestionServiceImpl(store,
                new JdbcArchivePersonalDataStore(jdbc), responseLoss, new ArchiveClerkFallbackProvider(),
                delivery, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThrows(IllegalStateException.class, () -> responseLossService.create(owners.getFirst(),
                responseLossId, path(responseLossId), "response-loss", body));
        ArchiveMutationResult recoveredResponse = responseLossService.create(owners.getFirst(),
                responseLossId, path(responseLossId), "response-loss", body);
        assertTrue(recoveredResponse.replayed());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM archive_question_event WHERE tenant_id='owner-a' AND client_id='client-a' AND question_id=?", Integer.class, responseLossId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM archive_outbox WHERE tenant_id='owner-a' AND client_id='client-a' AND question_id=?", Integer.class, responseLossId));

        int beforeRouting = count("archive_question_mutation");
        byte[] routed = new String(body, StandardCharsets.UTF_8).replaceFirst("\\}$", ",\"targetAgentId\":\"wuyong\"}")
                .getBytes(StandardCharsets.UTF_8);
        ArchivePersonalDataException routing = assertThrows(ArchivePersonalDataException.class,
                () -> service.create(owners.getFirst(), "223e4567-e89b-42d3-a456-426614174000",
                        path("223e4567-e89b-42d3-a456-426614174000"), "routing", routed));
        assertEquals("ROUTING_NOT_SUPPORTED", routing.code());
        assertEquals(beforeRouting, count("archive_question_mutation"));

        String pendingId = "273e4567-e89b-42d3-a456-426614174000";
        ArchiveWriteJson writeJson = new ArchiveWriteJson();
        ArchiveWriteJson.Parsed pendingParsed = writeJson.parseQuestion(body,
                cn.jia.chat.archive.dto.ArchiveQuestionPutRequest.class);
        jdbc.update("""
                INSERT INTO archive_question_mutation
                (tenant_id,client_id,owner_jiacn,question_id,http_method,canonical_path,
                 idempotency_key,request_sha256,state,expires_at)
                VALUES (?,?,?,?,?,?,?,?,'PENDING',?)
                """, owners.getFirst().tenantId(), owners.getFirst().clientId(), owners.getFirst().ownerJiacn(),
                pendingId, "PUT", path(pendingId), "pending-key",
                writeJson.sha256("PUT", path(pendingId), pendingParsed.canonicalJson()),
                java.sql.Timestamp.from(NOW.plusSeconds(604800)));
        int beforePendingQuestions = count("archive_question");
        ArchivePersonalDataException pending = assertThrows(ArchivePersonalDataException.class,
                () -> service.create(owners.getFirst(), pendingId, path(pendingId), "pending-key", body));
        assertEquals(409, pending.status());
        assertEquals(beforePendingQuestions, count("archive_question"));

        String raceId = "323e4567-e89b-42d3-a456-426614174000";
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        List<Object> outcomes = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int index = 0; index < 2; index++) pool.submit(() -> {
            try {
                start.await();
                outcomes.add(service.create(owners.getFirst(), raceId, path(raceId), "race-key", body));
            } catch (Throwable failure) { outcomes.add(failure); }
        });
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
        assertEquals(2, outcomes.stream().filter(ArchiveMutationResult.class::isInstance).count());
        assertEquals(1, outcomes.stream().filter(value -> value instanceof ArchiveMutationResult result && result.replayed()).count());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM archive_question WHERE tenant_id='owner-a' AND client_id='client-a' AND question_id=?", Integer.class, raceId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM archive_question_event WHERE tenant_id='owner-a' AND client_id='client-a' AND question_id=?", Integer.class, raceId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM archive_outbox WHERE tenant_id='owner-a' AND client_id='client-a' AND question_id=?", Integer.class, raceId));

        String conflictRaceId = "373e4567-e89b-42d3-a456-426614174000";
        CountDownLatch conflictStart = new CountDownLatch(1);
        var conflictPool = Executors.newFixedThreadPool(2);
        List<Object> conflictOutcomes = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int index = 0; index < 2; index++) {
            String key = "different-key-" + index;
            conflictPool.submit(() -> {
                try {
                    conflictStart.await();
                    conflictOutcomes.add(service.create(owners.getFirst(), conflictRaceId,
                            path(conflictRaceId), key, body));
                } catch (Throwable failure) { conflictOutcomes.add(failure); }
            });
        }
        conflictStart.countDown();
        conflictPool.shutdown();
        assertTrue(conflictPool.awaitTermination(60, TimeUnit.SECONDS));
        assertEquals(1, conflictOutcomes.stream().filter(ArchiveMutationResult.class::isInstance).count());
        assertEquals(1, conflictOutcomes.stream().filter(value -> value instanceof ArchivePersonalDataException failure
                && failure.status() == 409).count());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM archive_question WHERE tenant_id='owner-a' AND client_id='client-a' AND question_id=?", Integer.class, conflictRaceId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM archive_question_event WHERE tenant_id='owner-a' AND client_id='client-a' AND question_id=?", Integer.class, conflictRaceId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM archive_outbox WHERE tenant_id='owner-a' AND client_id='client-a' AND question_id=?", Integer.class, conflictRaceId));

        ArchiveQuestionWorker worker = new ArchiveQuestionWorker(store, transactions,
                new ArchiveClerkFallbackProvider(), delivery, enabledPolicy(), Clock.fixed(NOW, ZoneOffset.UTC));
        for (int index = 0; index < 10 && worker.runOnce(); index++) { }
        for (ArchiveOwnerScope owner : owners) assertEquals("SUCCEEDED", service.get(owner, id).status());
        assertEquals("SUCCEEDED", service.get(owners.getFirst(), raceId).status());
        assertEquals("SUCCEEDED", service.get(owners.getFirst(), conflictRaceId).status());
        assertEquals("SUCCEEDED", service.get(owners.getFirst(), responseLossId).status());

        String staleId = "423e4567-e89b-42d3-a456-426614174000";
        service.create(owners.getFirst(), staleId, path(staleId), "stale-create", body);
        jdbc.update("UPDATE archive_question SET status='RUNNING',version=2,current_sequence=2 WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND question_id=?",
                owners.getFirst().tenantId(), owners.getFirst().clientId(), owners.getFirst().ownerJiacn(), staleId);
        jdbc.update("INSERT INTO archive_question_event (tenant_id,client_id,owner_jiacn,question_id,sequence,event_type,payload_json,occurred_at) VALUES (?,?,?,?,2,'QUESTION_RUNNING','{}',?)",
                owners.getFirst().tenantId(), owners.getFirst().clientId(), owners.getFirst().ownerJiacn(), staleId,
                java.sql.Timestamp.from(NOW));
        jdbc.update("UPDATE archive_outbox SET state='LEASED',attempt_count=1,fencing_token=2,lease_until=?,published_sequence=1 WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND question_id=?",
                java.sql.Timestamp.from(NOW.plusSeconds(30)), owners.getFirst().tenantId(), owners.getFirst().clientId(),
                owners.getFirst().ownerJiacn(), staleId);
        var staleQuestion = store.findQuestion(owners.getFirst(), staleId, false);
        assertFalse(worker.persistAnswerAndComplete(new ArchiveQuestionWorker.Claimed(owners.getFirst(), staleId, 1, 1,
                staleQuestion.questionText(), staleQuestion.selectedText()), "must-not-persist"));
        assertEquals("", service.get(owners.getFirst(), staleId).answer());

        String exhaustedId = "523e4567-e89b-42d3-a456-426614174000";
        service.create(owners.getFirst(), exhaustedId, path(exhaustedId), "exhaust-create", body);
        jdbc.update("UPDATE archive_question SET status='FAILED_RETRYABLE',last_error_code='X',version=? WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND question_id=?",
                Long.MAX_VALUE, owners.getFirst().tenantId(), owners.getFirst().clientId(), owners.getFirst().ownerJiacn(), exhaustedId);
        jdbc.update("UPDATE archive_outbox SET state='WAITING_RETRY',lease_until=NULL,last_error_code='X' WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND question_id=?",
                owners.getFirst().tenantId(), owners.getFirst().clientId(), owners.getFirst().ownerJiacn(), exhaustedId);
        int beforeExhaustMutation = count("archive_question_mutation");
        ArchivePersonalDataException exhausted = assertThrows(ArchivePersonalDataException.class,
                () -> service.retry(owners.getFirst(), exhaustedId, path(exhaustedId) + "/retry", "exhaust-retry",
                        ("{\"expectedVersion\":\"" + Long.MAX_VALUE + "\"}").getBytes(StandardCharsets.UTF_8)));
        assertEquals("VERSION_EXHAUSTED", exhausted.code());
        assertEquals(beforeExhaustMutation, count("archive_question_mutation"));
        assertEquals(Long.MAX_VALUE, store.findQuestion(owners.getFirst(), exhaustedId, false).version());

        String sequenceId = "573e4567-e89b-42d3-a456-426614174000";
        service.create(owners.getFirst(), sequenceId, path(sequenceId), "sequence-create", body);
        jdbc.update("UPDATE archive_question SET status='FAILED_RETRYABLE',last_error_code='X',version=10,current_sequence=? WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND question_id=?",
                Long.MAX_VALUE, owners.getFirst().tenantId(), owners.getFirst().clientId(),
                owners.getFirst().ownerJiacn(), sequenceId);
        jdbc.update("UPDATE archive_outbox SET state='WAITING_RETRY',lease_until=NULL,last_error_code='X' WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND question_id=?",
                owners.getFirst().tenantId(), owners.getFirst().clientId(), owners.getFirst().ownerJiacn(), sequenceId);
        int beforeSequenceMutations = count("archive_question_mutation");
        int beforeSequenceEvents = jdbc.queryForObject("SELECT COUNT(*) FROM archive_question_event WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND question_id=?",
                Integer.class, owners.getFirst().tenantId(), owners.getFirst().clientId(),
                owners.getFirst().ownerJiacn(), sequenceId);
        ArchivePersonalDataException sequence = assertThrows(ArchivePersonalDataException.class,
                () -> service.retry(owners.getFirst(), sequenceId, path(sequenceId) + "/retry", "sequence-retry",
                        "{\"expectedVersion\":\"10\"}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("VERSION_EXHAUSTED", sequence.code());
        assertEquals(beforeSequenceMutations, count("archive_question_mutation"));
        assertEquals(beforeSequenceEvents, jdbc.queryForObject("SELECT COUNT(*) FROM archive_question_event WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND question_id=?",
                Integer.class, owners.getFirst().tenantId(), owners.getFirst().clientId(),
                owners.getFirst().ownerJiacn(), sequenceId));

        int h02Rows = count("archive_paragraph");
        int h03Rows = count("archive_reader_progress");
        ArchiveQuestionProvider unavailable = new ArchiveQuestionProvider() {
            @Override public boolean available() { return false; }
            @Override public Answer answer(Request request) { throw new AssertionError(); }
        };
        ArchiveQuestionServiceImpl unavailableService = new ArchiveQuestionServiceImpl(store,
                new JdbcArchivePersonalDataStore(jdbc), transactions, unavailable, delivery, Clock.fixed(NOW, ZoneOffset.UTC));
        String unavailableId = "623e4567-e89b-42d3-a456-426614174000";
        assertEquals(503, assertThrows(ArchivePersonalDataException.class,
                () -> unavailableService.create(owners.getFirst(), unavailableId, path(unavailableId),
                        "unavailable", body)).status());
        assertEquals(h02Rows, count("archive_paragraph"));
        assertEquals(h03Rows, count("archive_reader_progress"));

        jdbc.execute("ALTER TABLE archive_question_event MODIFY payload_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL");
        assertThrows(IllegalStateException.class, questionSchema::initialize);
    }

    private byte[] questionBody(ArchiveManifest.Block block, String question) {
        ArchiveManifest.Paragraph first = block.paragraphs().getFirst();
        ArchiveManifest.Paragraph second = block.paragraphs().get(1);
        String selected = first.text() + "\n\n" + second.text();
        String anchor = "{\"editionManifestSha256\":\"" + manifestHash() + "\",\"blockType\":\"CHAPTER\","
                + "\"blockId\":\"" + block.blockId() + "\",\"segments\":[{\"paragraphId\":\""
                + first.paragraphId() + "\",\"startByte\":0,\"endByte\":" + first.utf8ByteLength()
                + ",\"paragraphSha256\":\"" + first.sha256() + "\"},{\"paragraphId\":\""
                + second.paragraphId() + "\",\"startByte\":0,\"endByte\":" + second.utf8ByteLength()
                + ",\"paragraphSha256\":\"" + second.sha256() + "\"}],\"selectionSha256\":\""
                + ArchiveEtags.sha256(selected.getBytes(StandardCharsets.UTF_8)) + "\"}";
        return ("{\"question\":\"" + question + "\",\"anchor\":" + anchor + "}")
                .getBytes(StandardCharsets.UTF_8);
    }
    private String manifestHash() {
        return new ArchiveManifestLoader().load().manifest().manifestSha256();
    }
    private cn.jia.chat.archive.config.ArchiveQuestionAccessPolicy enabledPolicy() {
        cn.jia.chat.archive.config.ArchiveReaderProperties properties = new cn.jia.chat.archive.config.ArchiveReaderProperties();
        properties.setEnabled(true);
        List<cn.jia.chat.archive.config.ArchiveReaderProperties.AllowedScope> scopes = new ArrayList<>();
        for (String owner : List.of("owner-a", "owner-b")) for (String client : List.of("client-a", "client-b")) {
            var scope = new cn.jia.chat.archive.config.ArchiveReaderProperties.AllowedScope();
            scope.setTenantId(owner); scope.setClientId(client); scopes.add(scope);
        }
        properties.setAllowedScopes(scopes);
        cn.jia.chat.archive.config.ArchiveQuestionProperties question =
                new cn.jia.chat.archive.config.ArchiveQuestionProperties();
        question.setEnabled(true);
        return cn.jia.chat.archive.config.ArchiveQuestionAccessPolicy.from(question,
                cn.jia.chat.archive.config.ArchiveReaderAccessPolicy.from(properties));
    }
    private String path(String id) { return "/archive/v1/me/questions/" + id; }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private void clean() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS=0");
                try {
                    for (String table : new String[]{"archive_outbox", "archive_question_event",
                            "archive_question_mutation", "archive_question", "archive_idempotency", "archive_note",
                            "archive_bookmark", "archive_reader_progress", "archive_paragraph", "archive_chapter",
                            "archive_edition", "archive_work"}) statement.execute("DROP TABLE IF EXISTS " + table);
                } finally { statement.execute("SET FOREIGN_KEY_CHECKS=1"); }
            }
            return null;
        });
    }
}
