package cn.jia.chat.config;

import cn.jia.chat.service.CanonicalContextJson;
import cn.jia.chat.service.ChatActionFinalValidator;
import cn.jia.chat.service.ChatActionOutcomeContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ChatOutcomeDeliveryIndexBackfillTest {
    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;
    private static final String BINDING = "{\"route\":\"CHAT\",\"targetAgentId\":\"wuyong\"}";

    @BeforeEach void setup() {
        source = new DriverManagerDataSource("jdbc:h2:mem:delivery_backfill_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE chat_typed_outcome (
                  outcome_id VARCHAR(64) PRIMARY KEY,tenant_id VARCHAR_IGNORECASE(50),kind VARCHAR(30),
                  binding_json CLOB,outcome_json CLOB,final_digest VARCHAR(100),text CLOB,
                  delivery_target_agent_id VARCHAR(100),delivery_parent_outcome_id VARCHAR(64))
                """);
    }

    private static String stored(String kind, boolean deliverable, String parent) {
        var relation = parent == null ? null : new ChatActionOutcomeContract.DeliveryRelation("SUBMISSION_REQUEST".equals(kind) ? "SUBMIT" : "APPEND", parent, "sha256:original-parent");
        var outcome = new ChatActionOutcomeContract.Outcome(5, kind, "历史正文", null, null, deliverable, relation, null);
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("schemaVersion", 5); wrapper.put("interactionOutcome", ChatActionFinalValidator.outcomeMap(outcome));
        wrapper.put("inspectionInputReceipt", null); wrapper.put("harmlessExtra", "accepted");
        return CanonicalContextJson.write(wrapper);
    }

    private void insert(String id, String tenant, String kind, String raw) {
        jdbc.update("INSERT INTO chat_typed_outcome(outcome_id,tenant_id,kind,binding_json,outcome_json,final_digest,text) VALUES(?,?,?,?,?,?,?)",
                id, tenant, kind, BINDING, raw, "sha256:unchanged", "历史正文");
    }

    @Test void ordinaryDisabledInitializationDoesNotConnectOrBackfill() throws Exception {
        var unused = org.mockito.Mockito.mock(JdbcTemplate.class);
        var initializer = new ChatTypedDeliberationSchemaInitializer(unused, false, false);
        initializer.initialize();
        assertThrows(IllegalStateException.class, () -> initializer.backfillDeliveryIndex("0"));
        org.mockito.Mockito.verifyNoInteractions(unused);
        assertFalse(initializer.ready());
    }

    @Test void extractionMatchesCurrentWriterAndUsesDeliveryRelationNotAdmissionParent() {
        for (String kind : List.of("ANSWER", "ACTION_REQUEST", "SUBMISSION_REQUEST")) {
            var metadata = ChatOutcomeDeliveryIndexBackfill.metadata(kind, BINDING, stored(kind, true, "delivery-parent"));
            assertEquals(new ChatOutcomeDeliveryIndexBackfill.Metadata("wuyong", "delivery-parent"), metadata);
        }
        assertEquals(new ChatOutcomeDeliveryIndexBackfill.Metadata("wuyong", null),
                ChatOutcomeDeliveryIndexBackfill.metadata("ANSWER", BINDING, stored("ANSWER", true, null)));
        assertEquals(new ChatOutcomeDeliveryIndexBackfill.Metadata(null, null),
                ChatOutcomeDeliveryIndexBackfill.metadata("ANSWER", BINDING, stored("ANSWER", false, null)));
        assertEquals(new ChatOutcomeDeliveryIndexBackfill.Metadata(null, null),
                ChatOutcomeDeliveryIndexBackfill.metadata("ANSWER", BINDING.replace("CHAT", "INSPECT"), stored("ANSWER", false, null)));
        for (String kind : List.of("CLARIFY", "REVISION_REQUEST")) {
            assertEquals(new ChatOutcomeDeliveryIndexBackfill.Metadata(null, null),
                    ChatOutcomeDeliveryIndexBackfill.metadata(kind, BINDING, stored(kind, false, null)));
        }
    }

    @Test void historicalV1AndInspectionV2AreNotInventedAsDeliverables() {
        for (String raw : List.of("{\"schemaVersion\":1,\"kind\":\"ANSWER\"}",
                "{\"schemaVersion\":2,\"interactionOutcome\":{\"kind\":\"ANSWER\"}}")) {
            assertEquals(new ChatOutcomeDeliveryIndexBackfill.Metadata(null, null),
                    ChatOutcomeDeliveryIndexBackfill.metadata("ANSWER", BINDING, raw));
        }
    }

    @Test void unknownOrAmbiguousPersistedSemanticsFailWithoutReinterpreting() {
        for (String raw : List.of("{}", "{\"schemaVersion\":6}", "{\"schemaVersion\":4294967301}",
                "{\"schemaVersion\":1,\"kind\":\"ANSWER\",\"deliverable\":true}",
                "{\"schemaVersion\":1,\"schemaVersion\":5,\"kind\":\"ANSWER\"}",
                stored("ANSWER", true, null).replace("\"deliverable\":true", "\"deliverable\":\"true\""),
                stored("ANSWER", true, null) + " {}")) {
            assertThrows(IllegalArgumentException.class, () -> ChatOutcomeDeliveryIndexBackfill.metadata("ANSWER", BINDING, raw), raw);
        }
        assertThrows(IllegalArgumentException.class, () -> ChatOutcomeDeliveryIndexBackfill.metadata("ANSWER", "{}", stored("ANSWER", true, null)));
        assertThrows(IllegalArgumentException.class, () -> ChatOutcomeDeliveryIndexBackfill.metadata("SUBMISSION_REQUEST", BINDING, stored("SUBMISSION_REQUEST", false, null)));
        assertThrows(IllegalArgumentException.class, () -> ChatOutcomeDeliveryIndexBackfill.metadata("ACTION_REQUEST", BINDING, stored("ANSWER", true, null)));
    }

    @Test void boundedPagesKeepImmutableBytesAndRepeatWithoutUpdates() throws Exception {
        for (int n = 0; n < 205; n++) insert(String.format("o%04d", n), "OwnerTenant", "ANSWER", stored("ANSWER", true, null));
        insert("foreign", "ownertenant", "ANSWER", stored("ANSWER", true, null));
        insert("ordinary", "OwnerTenant", "ANSWER", stored("ANSWER", false, null));
        var before = jdbc.query("SELECT outcome_id,binding_json,outcome_json,final_digest,text FROM chat_typed_outcome ORDER BY outcome_id",
                (rs, n) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
        try (Connection connection = source.getConnection()) {
            var result = ChatOutcomeDeliveryIndexBackfill.run(connection, "OwnerTenant");
            assertEquals(206, result.scanned()); assertEquals(205, result.updated()); assertEquals(3, result.batches());
            var repeated = ChatOutcomeDeliveryIndexBackfill.run(connection, "OwnerTenant");
            assertEquals(206, repeated.scanned()); assertEquals(0, repeated.updated());
        }
        assertNull(jdbc.queryForObject("SELECT delivery_target_agent_id FROM chat_typed_outcome WHERE outcome_id='foreign'", String.class));
        assertEquals(before, jdbc.query("SELECT outcome_id,binding_json,outcome_json,final_digest,text FROM chat_typed_outcome ORDER BY outcome_id",
                (rs, n) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5))));
    }

    @Test void conflictingBatchRollsBackAndPreviouslyCommittedPageIsRestartable() throws Exception {
        for (int n = 0; n < 102; n++) insert(String.format("o%04d", n), "0", "ANSWER", stored("ANSWER", true, null));
        jdbc.update("UPDATE chat_typed_outcome SET delivery_target_agent_id='wrong' WHERE outcome_id='o0101'");
        try (Connection connection = source.getConnection()) {
            assertThrows(IllegalStateException.class, () -> ChatOutcomeDeliveryIndexBackfill.run(connection, "0"));
        }
        assertEquals(100, jdbc.queryForObject("SELECT COUNT(*) FROM chat_typed_outcome WHERE delivery_target_agent_id='wuyong'", Integer.class));
        assertNull(jdbc.queryForObject("SELECT delivery_target_agent_id FROM chat_typed_outcome WHERE outcome_id='o0100'", String.class));
        jdbc.update("UPDATE chat_typed_outcome SET delivery_target_agent_id=NULL WHERE outcome_id='o0101'");
        try (Connection connection = source.getConnection()) {
            assertEquals(2, ChatOutcomeDeliveryIndexBackfill.run(connection, "0").updated());
        }
    }

    @Test void updateFailureRollsBackTheCurrentBatch() throws Exception {
        insert("first", "0", "ANSWER", stored("ANSWER", true, null));
        insert("second", "0", "ANSWER", stored("ANSWER", true, "a-parent-that-will-not-fit"));
        jdbc.execute("ALTER TABLE chat_typed_outcome ALTER COLUMN delivery_parent_outcome_id VARCHAR(3)");
        try (Connection connection = source.getConnection()) {
            assertThrows(RuntimeException.class, () -> ChatOutcomeDeliveryIndexBackfill.run(connection, "0"));
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM chat_typed_outcome WHERE delivery_target_agent_id IS NOT NULL", Integer.class));
    }

    @Test void explicitTransactionCannotBeCommittedByMaintenanceAndEmptyDatabaseIsNoop() throws Exception {
        try (Connection connection = source.getConnection()) {
            assertEquals(0, ChatOutcomeDeliveryIndexBackfill.run(connection, "0").batches());
            connection.setAutoCommit(false);
            assertThrows(IllegalStateException.class, () -> ChatOutcomeDeliveryIndexBackfill.run(connection, "0"));
            assertFalse(connection.getAutoCommit());
        }
    }

    @Test void aPageUsesItsPinnedUpperBoundAndDoesNotChangeReadyState() throws Exception {
        insert("a", "0", "ACTION_REQUEST", stored("ACTION_REQUEST", false, null));
        insert("z", "0", "ANSWER", stored("ANSWER", true, null));
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            var batch = ChatOutcomeDeliveryIndexBackfill.batch(new JdbcTemplate(new SingleConnectionDataSource(connection, true)), "0", "", "a");
            assertEquals(1, batch.scanned()); assertEquals("a", batch.after()); connection.commit();
        }
        assertNull(jdbc.queryForObject("SELECT delivery_target_agent_id FROM chat_typed_outcome WHERE outcome_id='z'", String.class));
        assertEquals("ACTION_REQUEST", jdbc.queryForObject("SELECT kind FROM chat_typed_outcome WHERE outcome_id='a'", String.class));
    }
}
