package cn.jia.chat.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Strict additive MySQL contract. Existing incompatible definitions are never silently repaired. */
@Slf4j
@Component
public class ChatDeliberationSchemaInitializer implements ApplicationRunner {
    static final List<String> TABLES = List.of(
            "chat_context_snapshot", "chat_request", "chat_turn", "chat_dispatch_outbox",
            "chat_conversation_event");
    private static final String COLLATION = "utf8mb4_0900_bin";
    private static final String MIGRATION_LOCK = "cyf:chat-deliberation:v2";
    private static final Map<String, Map<String, ColumnDef>> COLUMNS = columns();
    private static final Map<String, Map<String, IndexDef>> INDEXES = indexes();

    private final JdbcTemplate jdbc;
    @Value("${cyf.chat.deliberation-schema.allow-additive-migration:false}")
    private boolean allowAdditiveMigration;

    public ChatDeliberationSchemaInitializer(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (isH2()) return;
        DataSource source = jdbc.getDataSource();
        if (source == null) throw new IllegalStateException("Chat deliberation schema requires a DataSource");
        try (Connection lockConnection = source.getConnection()) {
            acquireMigrationLock(lockConnection);
            try { initializeWhileLocked(); }
            finally { releaseMigrationLock(lockConnection); }
        }
    }

    private void initializeWhileLocked() throws Exception {
        String sql = new String(new ClassPathResource("db/chat-deliberation-schema.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        for (String statement : sql.replaceAll("(?m)^--.*$", "").split(";")) {
            if (!statement.isBlank()) jdbc.execute(statement.strip());
        }
        if (allowAdditiveMigration) recordMigrationStage("PREFLIGHT");
        for (String table : TABLES) if (allowAdditiveMigration) addKnownMissingColumnsAndIndexes(table);
        if (allowAdditiveMigration) backfillLegacyData();
        for (String table : TABLES) {
            validateTable(table);
            validateColumns(table);
            validateIndexes(table);
        }
        if (allowAdditiveMigration) recordMigrationStage("APPLIED");
    }

    private void acquireMigrationLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?,10)")) {
            statement.setString(1, MIGRATION_LOCK);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1 || result.wasNull())
                    throw new IllegalStateException("Timed out acquiring chat deliberation migration lock");
            }
        }
    }

    private void releaseMigrationLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, MIGRATION_LOCK);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1 || result.wasNull())
                    throw new IllegalStateException("Chat deliberation migration lock was not held");
            }
        }
    }

    private void recordMigrationStage(String stage) {
        jdbc.update("""
                INSERT INTO chat_deliberation_schema_version(version,stage,updated_at) VALUES(2,?,?)
                ON DUPLICATE KEY UPDATE stage=VALUES(stage),updated_at=VALUES(updated_at)
                """, stage, System.currentTimeMillis());
    }

    private void addKnownMissingColumnsAndIndexes(String table) {
        Map<String, Map<String, Object>> actualColumns = readColumns(table);
        validateExistingColumnsBeforeExpansion(table, actualColumns);
        for (var entry : COLUMNS.get(table).entrySet()) {
            if (!actualColumns.containsKey(entry.getKey())) {
                String ddl = entry.getValue().ddl();
                if ("chat_dispatch_outbox".equals(table)
                        && ("available_at".equals(entry.getKey()) || "attempt_count".equals(entry.getKey())
                        || "fencing_token".equals(entry.getKey()))) {
                    ddl = ddl.replace(" NOT NULL", " DEFAULT NULL").replace(" DEFAULT 0", "");
                }
                jdbc.execute("ALTER TABLE " + table + " ALGORITHM=INPLACE, LOCK=NONE, ADD COLUMN "
                        + entry.getKey() + " " + ddl);
            }
        }
        if ("chat_dispatch_outbox".equals(table)) {
            recordMigrationStage("EXPANDED");
            jdbc.update("UPDATE chat_dispatch_outbox SET available_at=COALESCE(available_at,created_at), "
                    + "attempt_count=COALESCE(attempt_count,0), fencing_token=COALESCE(fencing_token,0) "
                    + "WHERE available_at IS NULL OR attempt_count IS NULL OR fencing_token IS NULL");
            recordMigrationStage("BACKFILLED");
            jdbc.execute("ALTER TABLE chat_dispatch_outbox ALGORITHM=INPLACE, LOCK=NONE, "
                    + "MODIFY available_at BIGINT NOT NULL, MODIFY attempt_count INT NOT NULL DEFAULT 0, "
                    + "MODIFY fencing_token BIGINT NOT NULL DEFAULT 0");
            recordMigrationStage("TIGHTENED");
        }
        Map<String, IndexDef> actualIndexes = readIndexes(table);
        for (var entry : INDEXES.get(table).entrySet()) {
            IndexDef actual = actualIndexes.get(entry.getKey());
            if (entry.getValue().equals(actual)) continue;
            if (actual != null && isLegacyReadyIndex(table, entry.getKey(), actual)) {
                jdbc.execute("ALTER TABLE chat_dispatch_outbox ALGORITHM=INPLACE, LOCK=NONE, "
                        + "DROP INDEX idx_chat_outbox_ready, ADD INDEX idx_chat_outbox_ready "
                        + "(status,available_at,event_id)");
                continue;
            }
            if (actual != null) {
                throw incompatible(table + " index " + entry.getKey(), entry.getValue().toString(), actual.toString());
            }
            IndexDef def = entry.getValue();
            String prefix = "PRIMARY".equals(entry.getKey()) ? "ADD PRIMARY KEY"
                    : def.unique() ? "ADD UNIQUE INDEX " + entry.getKey() : "ADD INDEX " + entry.getKey();
            jdbc.execute("ALTER TABLE " + table + " ALGORITHM=INPLACE, LOCK=NONE, " + prefix + " ("
                    + String.join(",", def.columns()) + ")");
        }
    }

    private void backfillLegacyData() {
        // 1b5fa4ce rows predate relay metadata and the durable event journal. Rebuild only from
        // authoritative request/turn/snapshot/message rows; unrecoverable work is terminalized.
        jdbc.update("""
                UPDATE chat_dispatch_outbox o
                JOIN chat_turn t ON t.turn_id=o.turn_id AND t.dispatch_id=o.dispatch_id
                JOIN chat_context_snapshot s ON s.snapshot_id=t.snapshot_id
                JOIN chat_request r ON r.tenant_id=t.tenant_id AND r.owner_jiacn=t.owner_jiacn
                  AND r.client_id=t.client_id AND r.request_id=t.request_id AND r.request_revision=t.request_revision
                JOIN chat_message m ON m.id=r.user_message_id
                SET o.payload_json=JSON_OBJECT(
                  'conversationId',t.conversation_id,'conversationGeneration',CAST(t.conversation_generation AS CHAR),
                  'requestId',t.request_id,'requestRevision',CAST(t.request_revision AS CHAR),
                  'turnId',t.turn_id,'dispatchId',t.dispatch_id,'targetAgentId',t.target_agent_id,
                  'contextSnapshotId',t.snapshot_id,'contextHash',t.context_digest,'route',t.route,
                  'content',m.content,'sourceVector',CAST(s.source_vector_json AS JSON),
                  'factsManifest',CAST(s.facts_manifest_json AS JSON)),
                  o.status=CASE WHEN o.status IN ('SENT','DEAD') THEN o.status ELSE 'READY' END,
                  o.available_at=COALESCE(o.available_at,o.created_at),o.last_error=NULL
                WHERE o.event_type='DISPATCH' AND JSON_VALID(s.source_vector_json)
                  AND JSON_VALID(s.facts_manifest_json)
                  AND (NOT JSON_VALID(o.payload_json) OR JSON_EXTRACT(o.payload_json,'$.conversationGeneration') IS NULL
                    OR JSON_EXTRACT(o.payload_json,'$.contextSnapshotId') IS NULL)
                """);
        jdbc.update("""
                UPDATE chat_dispatch_outbox o LEFT JOIN chat_turn t ON t.turn_id=o.turn_id AND t.dispatch_id=o.dispatch_id
                LEFT JOIN chat_context_snapshot s ON s.snapshot_id=t.snapshot_id
                LEFT JOIN chat_request r ON r.tenant_id=t.tenant_id AND r.owner_jiacn=t.owner_jiacn
                  AND r.client_id=t.client_id AND r.request_id=t.request_id AND r.request_revision=t.request_revision
                LEFT JOIN chat_message m ON m.id=r.user_message_id
                SET o.status='DEAD',o.last_error='LEGACY_DISPATCH_UNRECOVERABLE',o.available_at=NULL,
                    o.lease_owner=NULL,o.lease_until=NULL,o.updated_at=GREATEST(o.updated_at,UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000)
                WHERE o.event_type='DISPATCH' AND o.status NOT IN ('SENT','DEAD')
                  AND (t.turn_id IS NULL OR s.snapshot_id IS NULL OR r.id IS NULL OR m.id IS NULL
                    OR NOT JSON_VALID(s.source_vector_json) OR NOT JSON_VALID(s.facts_manifest_json))
                """);
        jdbc.update("""
                INSERT IGNORE INTO chat_conversation_event
                  (event_id,tenant_id,owner_jiacn,client_id,conversation_id,conversation_generation,request_id,
                   turn_id,dispatch_id,event_type,event_version,payload_json,occurred_at)
                SELECT COALESCE(o.event_id,SHA2(CONCAT('legacy-final:',t.dispatch_id),256)),
                  t.tenant_id,t.owner_jiacn,t.client_id,t.conversation_id,t.conversation_generation,
                  t.request_id,t.turn_id,t.dispatch_id,'agent_message',0,
                  JSON_OBJECT('type','agent_message','requestId',t.request_id,'turnId',t.turn_id,
                    'dispatchId',t.dispatch_id,'targetAgentId',t.target_agent_id,'contextSnapshotId',t.snapshot_id,
                    'messageId',CAST(m.id AS CHAR),'content',m.content,'senderType','agent',
                    'senderName',COALESCE(m.sender_name,t.target_agent_id)),COALESCE(m.create_time,t.updated_at)
                FROM chat_turn t JOIN chat_message m ON m.id=t.final_message_id
                LEFT JOIN chat_dispatch_outbox o ON o.turn_id=t.turn_id AND o.event_type='FINAL_PERSISTED'
                WHERE t.state IN ('FINAL_PERSISTED','PUBLISHED') AND t.final_digest IS NOT NULL
                """);
        jdbc.update("UPDATE chat_conversation_event SET event_version=event_sequence WHERE event_version=0");
        jdbc.update("""
                UPDATE chat_dispatch_outbox o
                JOIN chat_turn t ON t.turn_id=o.turn_id AND t.dispatch_id=o.dispatch_id
                JOIN chat_message m ON m.id=t.final_message_id
                JOIN chat_conversation_event e ON e.event_id=o.event_id AND e.turn_id=t.turn_id
                SET o.payload_json=JSON_OBJECT('requestId',t.request_id,'turnId',t.turn_id,
                  'dispatchId',t.dispatch_id,'targetAgentId',t.target_agent_id,
                  'contextSnapshotId',t.snapshot_id,'messageId',CAST(m.id AS CHAR),
                  'conversationId',t.conversation_id,'conversationGeneration',CAST(t.conversation_generation AS CHAR),
                  'content',m.content,'senderName',COALESCE(m.sender_name,t.target_agent_id),
                  'eventSequence',CAST(e.event_sequence AS CHAR),'eventVersion',CAST(e.event_version AS CHAR),
                  'finalDigest',t.final_digest),
                  o.status=CASE WHEN o.status IN ('SENT','DEAD') THEN o.status ELSE 'READY' END,
                  o.available_at=COALESCE(o.available_at,o.created_at),o.last_error=NULL
                WHERE o.event_type='FINAL_PERSISTED'
                  AND (NOT JSON_VALID(o.payload_json) OR JSON_EXTRACT(o.payload_json,'$.eventSequence') IS NULL)
                """);
        jdbc.update("""
                UPDATE chat_dispatch_outbox o LEFT JOIN chat_turn t ON t.turn_id=o.turn_id AND t.dispatch_id=o.dispatch_id
                LEFT JOIN chat_message m ON m.id=t.final_message_id
                LEFT JOIN chat_conversation_event e ON e.event_id=o.event_id AND e.turn_id=t.turn_id
                SET o.status='DEAD',o.last_error='LEGACY_FINAL_UNRECOVERABLE',o.available_at=NULL,
                    o.lease_owner=NULL,o.lease_until=NULL,o.updated_at=GREATEST(o.updated_at,UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000)
                WHERE o.event_type='FINAL_PERSISTED' AND o.status NOT IN ('SENT','DEAD')
                  AND (t.turn_id IS NULL OR m.id IS NULL OR e.event_sequence IS NULL OR t.final_digest IS NULL)
                """);
        jdbc.update("""
                UPDATE chat_dispatch_outbox o JOIN chat_turn t ON t.turn_id=o.turn_id AND t.dispatch_id=o.dispatch_id
                SET o.payload_json=JSON_OBJECT('requestId',t.request_id,'turnId',t.turn_id,
                  'dispatchId',t.dispatch_id,'targetAgentId',t.target_agent_id,
                  'conversationId',t.conversation_id,'conversationGeneration',CAST(t.conversation_generation AS CHAR),
                  'reason',COALESCE(t.terminal_reason,'USER_REQUESTED')),
                  o.status=CASE WHEN o.status IN ('SENT','DEAD') THEN o.status ELSE 'READY' END,
                  o.available_at=COALESCE(o.available_at,o.created_at),o.last_error=NULL
                WHERE o.event_type='CANCEL_REQUESTED' AND NOT JSON_VALID(o.payload_json)
                """);
        jdbc.update("""
                INSERT IGNORE INTO chat_conversation_event
                  (event_id,tenant_id,owner_jiacn,client_id,conversation_id,conversation_generation,request_id,
                   turn_id,dispatch_id,event_type,event_version,payload_json,occurred_at)
                SELECT SHA2(CONCAT('legacy-resync:',t.dispatch_id),256),t.tenant_id,t.owner_jiacn,t.client_id,
                  t.conversation_id,t.conversation_generation,t.request_id,t.turn_id,t.dispatch_id,'resync_required',0,
                  JSON_OBJECT('type','resync_required','requestId',t.request_id,'turnId',t.turn_id,
                    'dispatchId',t.dispatch_id,'reason','LEGACY_FINAL_UNRECOVERABLE'),t.updated_at
                FROM chat_turn t LEFT JOIN chat_message m ON m.id=t.final_message_id
                WHERE t.state IN ('FINAL_PERSISTED','PUBLISHED') AND (t.final_digest IS NULL OR m.id IS NULL)
                """);
        jdbc.update("UPDATE chat_conversation_event SET event_version=event_sequence WHERE event_version=0");
        recordMigrationStage("DATA_BACKFILLED");
    }

    private void validateExistingColumnsBeforeExpansion(String table, Map<String, Map<String, Object>> actual) {
        for (var entry : actual.entrySet()) {
            ColumnDef expected = COLUMNS.get(table).get(entry.getKey());
            if (expected == null) continue; // Harmless additive columns are validated by the final strict contract.
            Map<String, Object> row = entry.getValue();
            boolean nullable = "YES".equalsIgnoreCase(text(row, "is_nullable"));
            boolean expandableRelayColumn = "chat_dispatch_outbox".equals(table)
                    && ("available_at".equals(entry.getKey()) || "attempt_count".equals(entry.getKey())
                    || "fencing_token".equals(entry.getKey()));
            String collation = nullableText(row, "collation_name");
            if (!expected.type().equalsIgnoreCase(text(row, "data_type"))
                    || !Objects.equals(expected.length(), nullableNumber(row, "character_maximum_length"))
                    || !expected.extra().equals(text(row, "extra").toLowerCase(Locale.ROOT))
                    || expected.collated() && !COLLATION.equalsIgnoreCase(collation)
                    || !expandableRelayColumn && expected.nullable() != nullable) {
                throw incompatible(table + "." + entry.getKey(), expected.toString(), row.toString());
            }
        }
    }

    private boolean isLegacyReadyIndex(String table, String name, IndexDef actual) {
        return "chat_dispatch_outbox".equals(table) && "idx_chat_outbox_ready".equals(name)
                && actual.equals(ix(false, "tenant_id", "owner_jiacn", "client_id", "status", "updated_at"));
    }

    private void validateTable(String table) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT engine, table_collation FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name=?
                """, table);
        if (rows.size() != 1 || !"InnoDB".equalsIgnoreCase(text(rows.getFirst(), "engine"))
                || !COLLATION.equalsIgnoreCase(text(rows.getFirst(), "table_collation"))) {
            throw incompatible(table, "InnoDB/" + COLLATION, rows.toString());
        }
    }

    private void validateColumns(String table) {
        Map<String, Map<String, Object>> actual = readColumns(table);
        Map<String, ColumnDef> expected = COLUMNS.get(table);
        if (!actual.keySet().equals(expected.keySet())) {
            throw incompatible(table + " columns", expected.keySet().toString(), actual.keySet().toString());
        }
        expected.forEach((name, def) -> {
            Map<String, Object> row = actual.get(name);
            boolean nullable = "YES".equalsIgnoreCase(text(row, "is_nullable"));
            String extra = text(row, "extra").toLowerCase(Locale.ROOT);
            String defaultValue = nullableText(row, "column_default");
            String collation = nullableText(row, "collation_name");
            if (!def.type().equalsIgnoreCase(text(row, "data_type"))
                    || !Objects.equals(def.length(), nullableNumber(row, "character_maximum_length"))
                    || def.nullable() != nullable || !Objects.equals(def.defaultValue(), defaultValue)
                    || !def.extra().equals(extra)
                    || def.collated() && !COLLATION.equalsIgnoreCase(collation)) {
                throw incompatible(table + "." + name, def.toString(), row.toString());
            }
        });
    }

    private void validateIndexes(String table) {
        Map<String, IndexDef> actual = readIndexes(table);
        Map<String, IndexDef> expected = INDEXES.get(table);
        for (var entry : expected.entrySet()) {
            if (!entry.getValue().equals(actual.get(entry.getKey()))) {
                throw incompatible(table + " index " + entry.getKey(), entry.getValue().toString(),
                        String.valueOf(actual.get(entry.getKey())));
            }
        }
    }

    private Map<String, Map<String, Object>> readColumns(String table) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT column_name,data_type,character_maximum_length,is_nullable,column_default,collation_name,extra
                FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position
                """, table);
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) result.put(text(row, "column_name"), row);
        return result;
    }

    private Map<String, IndexDef> readIndexes(String table) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT index_name,non_unique,seq_in_index,column_name,sub_part
                FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name=? ORDER BY index_name,seq_in_index
                """, table);
        Map<String, java.util.ArrayList<String>> columns = new LinkedHashMap<>();
        Map<String, Boolean> unique = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String name = text(row, "index_name");
            if (row.get("SUB_PART") != null || row.get("sub_part") != null) {
                throw incompatible(table + " index prefix", "none", row.toString());
            }
            columns.computeIfAbsent(name, ignored -> new java.util.ArrayList<>()).add(text(row, "column_name"));
            unique.put(name, number(row, "non_unique") == 0L);
        }
        Map<String, IndexDef> result = new LinkedHashMap<>();
        columns.forEach((name, cols) -> result.put(name, new IndexDef(unique.get(name), List.copyOf(cols))));
        return result;
    }

    private static Map<String, Map<String, ColumnDef>> columns() {
        Map<String, Map<String, ColumnDef>> all = new LinkedHashMap<>();
        all.put("chat_context_snapshot", ordered(
                c("snapshot_id", v(64,false)), c("tenant_id",v(50,false)), c("owner_jiacn",v(50,false)),
                c("client_id",v(50,false)), c("conversation_id",v(100,false)), c("conversation_generation",b(false,null)),
                c("request_id",v(100,false)), c("request_revision",b(false,null)), c("target_agent_id",v(100,false)),
                c("route",v(20,false)), c("source_vector_json",txt("text",false)), c("facts_manifest_json",txt("mediumtext",false)),
                c("context_digest",v(100,false)), c("created_at",b(false,null))));
        all.put("chat_request", ordered(c("id",auto()), c("tenant_id",v(50,false)), c("owner_jiacn",v(50,false)),
                c("client_id",v(50,false)), c("request_id",v(100,false)), c("request_revision",b(false,null)),
                c("request_digest",v(100,false)), c("conversation_id",v(100,false)), c("conversation_generation",b(false,null)),
                c("user_message_id",b(false,null)), c("aggregate_state",v(30,false)), c("state_version",b(false,"0")),
                c("created_at",b(false,null)), c("updated_at",b(false,null))));
        all.put("chat_turn", ordered(c("turn_id",v(64,false)), c("tenant_id",v(50,false)), c("owner_jiacn",v(50,false)),
                c("client_id",v(50,false)), c("request_id",v(100,false)), c("request_revision",b(false,null)),
                c("conversation_id",v(100,false)), c("conversation_generation",b(false,null)), c("target_agent_id",v(100,false)),
                c("snapshot_id",v(64,false)), c("context_digest",v(100,false)), c("dispatch_id",v(64,false)),
                c("route",v(20,false)), c("state",v(30,false)), c("state_version",b(false,"0")),
                c("last_delta_seq",b(false,"0")), c("last_delta_digest",v(100,true)), c("final_digest",v(100,true)),
                c("final_message_id",b(true,null)), c("terminal_reason",v(500,true)), c("created_at",b(false,null)), c("updated_at",b(false,null))));
        all.put("chat_dispatch_outbox", ordered(c("event_id",v(64,false)), c("tenant_id",v(50,false)),
                c("owner_jiacn",v(50,false)), c("client_id",v(50,false)), c("turn_id",v(64,false)),
                c("dispatch_id",v(64,false)), c("event_type",v(30,false)), c("status",v(30,false)),
                c("payload_json",txt("mediumtext",false)), c("version",b(false,"0")),
                c("available_at",b(false,null)), c("lease_owner",v(100,true)), c("lease_until",b(true,null)),
                c("attempt_count",i(false,"0")), c("fencing_token",b(false,"0")), c("last_error",v(500,true)),
                c("sent_at",b(true,null)), c("created_at",b(false,null)), c("updated_at",b(false,null))));
        all.put("chat_conversation_event", ordered(c("event_sequence",auto()), c("event_id",v(64,false)),
                c("tenant_id",v(50,false)), c("owner_jiacn",v(50,false)), c("client_id",v(50,false)),
                c("conversation_id",v(100,false)), c("conversation_generation",b(false,null)),
                c("request_id",v(100,true)), c("turn_id",v(64,true)), c("dispatch_id",v(64,true)),
                c("event_type",v(40,false)), c("event_version",b(false,null)), c("payload_json",txt("mediumtext",false)),
                c("occurred_at",b(false,null))));
        return Map.copyOf(all);
    }

    private static Map<String, Map<String, IndexDef>> indexes() {
        Map<String, Map<String, IndexDef>> all = new LinkedHashMap<>();
        all.put("chat_context_snapshot", Map.of(
                "PRIMARY", ix(true,"snapshot_id"),
                "uk_chat_snapshot_request_target", ix(true,"tenant_id","owner_jiacn","client_id","request_id","request_revision","target_agent_id"),
                "idx_chat_snapshot_conversation", ix(false,"tenant_id","owner_jiacn","client_id","conversation_id","conversation_generation","created_at")));
        all.put("chat_request", Map.of("PRIMARY",ix(true,"id"),
                "uk_chat_request_scope_revision",ix(true,"tenant_id","owner_jiacn","client_id","request_id","request_revision"),
                "idx_chat_request_conversation",ix(false,"tenant_id","owner_jiacn","client_id","conversation_id","conversation_generation","created_at")));
        all.put("chat_turn", Map.of("PRIMARY",ix(true,"turn_id"),
                "uk_chat_turn_request_target",ix(true,"tenant_id","owner_jiacn","client_id","request_id","target_agent_id"),
                "uk_chat_turn_dispatch",ix(true,"dispatch_id"), "uk_chat_turn_snapshot",ix(true,"snapshot_id"),
                "idx_chat_turn_conversation",ix(false,"tenant_id","owner_jiacn","client_id","conversation_id","conversation_generation","state","updated_at")));
        all.put("chat_dispatch_outbox", Map.of("PRIMARY",ix(true,"event_id"),
                "uk_chat_outbox_turn_event",ix(true,"turn_id","event_type"),
                "idx_chat_outbox_ready",ix(false,"status","available_at","event_id"),
                "idx_chat_outbox_scope",ix(false,"tenant_id","owner_jiacn","client_id","turn_id","event_type")));
        all.put("chat_conversation_event", Map.of("PRIMARY",ix(true,"event_sequence"),
                "uk_chat_conversation_event_id",ix(true,"event_id"),
                "idx_chat_conversation_event_replay",ix(false,"tenant_id","owner_jiacn","client_id","conversation_id","conversation_generation","event_sequence")));
        return Map.copyOf(all);
    }

    @SafeVarargs private static Map<String, ColumnDef> ordered(Map.Entry<String,ColumnDef>... entries) {
        Map<String,ColumnDef> result=new LinkedHashMap<>(); for(var e:entries) result.put(e.getKey(),e.getValue()); return Map.copyOf(result);
    }
    private static Map.Entry<String,ColumnDef> c(String n,ColumnDef d){return Map.entry(n,d);}
    private static ColumnDef v(long n,boolean nullable){return new ColumnDef("varchar",n,nullable,null,"",true,"VARCHAR("+n+") CHARACTER SET utf8mb4 COLLATE "+COLLATION+(nullable?" DEFAULT NULL":" NOT NULL"));}
    private static ColumnDef b(boolean nullable,String d){return new ColumnDef("bigint",null,nullable,d,"",false,"BIGINT"+(nullable?" DEFAULT NULL":" NOT NULL")+(d==null?"":" DEFAULT "+d));}
    private static ColumnDef auto(){return new ColumnDef("bigint",null,false,null,"auto_increment",false,"BIGINT NOT NULL AUTO_INCREMENT");}
    private static ColumnDef i(boolean nullable,String d){return new ColumnDef("int",null,nullable,d,"",false,"INT"+(nullable?" DEFAULT NULL":" NOT NULL")+(d==null?"":" DEFAULT "+d));}
    private static ColumnDef txt(String type,boolean nullable){return new ColumnDef(type,null,nullable,null,"",true,type.toUpperCase()+" CHARACTER SET utf8mb4 COLLATE "+COLLATION+(nullable?" DEFAULT NULL":" NOT NULL"));}
    private static IndexDef ix(boolean unique,String... c){return new IndexDef(unique,List.of(c));}
    private static String text(Map<String,Object> row,String key){Object v=value(row,key); return v==null?"":String.valueOf(v);}
    private static String nullableText(Map<String,Object> row,String key){Object v=value(row,key); return v==null?null:String.valueOf(v);}
    private static Long nullableNumber(Map<String,Object> row,String key){Object v=value(row,key); return v==null?null:(v instanceof Number n?n.longValue():Long.valueOf(String.valueOf(v)));}
    private static long number(Map<String,Object> row,String key){Long v=nullableNumber(row,key); if(v==null)throw new IllegalStateException("Missing numeric metadata: "+key); return v;}
    private static Object value(Map<String,Object> row,String key){for(var e:row.entrySet())if(e.getKey().equalsIgnoreCase(key))return e.getValue();return null;}
    private static IllegalStateException incompatible(String what,String expected,String actual){return new IllegalStateException("Incompatible "+what+"; expected="+expected+", actual="+actual);}
    private boolean isH2() throws Exception { DataSource source=jdbc.getDataSource(); if(source==null)return false; try(Connection c=source.getConnection()){String n=c.getMetaData().getDatabaseProductName();return n!=null&&n.toLowerCase(Locale.ROOT).contains("h2");}}
    private record ColumnDef(String type,Long length,boolean nullable,String defaultValue,String extra,boolean collated,String ddl) { }
    private record IndexDef(boolean unique,List<String> columns) { }
}
