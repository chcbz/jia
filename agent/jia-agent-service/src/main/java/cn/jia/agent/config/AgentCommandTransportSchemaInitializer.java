package cn.jia.agent.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Conditional D01 initializer for the three reliable Agent command transport tables.
 *
 * <p>The caller registers this bean only when {@code agent.command-outbox.enabled=true}.
 * This initializer performs DDL and read-only catalog validation only: it never backfills or
 * mutates application rows.</p>
 */
public final class AgentCommandTransportSchemaInitializer implements InitializingBean {
    static final String DDL_RESOURCE = "db/agent-command-transport-schema.sql";
    static final List<String> TABLES = List.of(
            "agent_command_delivery", "agent_outbox_event", "agent_consumer_inbox");
    private static final Set<String> TABLE_SET = Set.copyOf(TABLES);
    private static final String BINARY_COLLATION = "utf8mb4_0900_bin";

    private final JdbcTemplate jdbcTemplate;

    public AgentCommandTransportSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    public void afterPropertiesSet() {
        requireMySql();
        List<String> present = inspectPresentTables();
        if (!present.isEmpty() && present.size() != TABLES.size()) {
            throw partialSchema(present);
        }
        if (present.isEmpty()) {
            for (String statement : ddlStatements()) {
                jdbcTemplate.execute(statement);
            }
            present = inspectPresentTables();
            if (!TABLE_SET.equals(Set.copyOf(present))) {
                throw new IllegalStateException("D01 command transport schema creation did not produce exact 3/3 tables: "
                        + present);
            }
        }
        validateSchema();
    }

    void validateSchema() {
        List<String> present = inspectPresentTables();
        if (present.size() != TABLES.size() || !TABLE_SET.equals(Set.copyOf(present))) {
            throw partialSchema(present);
        }
        for (TableExpectation expected : expectedTables().values()) {
            validateTable(expected);
        }
    }

    private void requireMySql() {
        DataSource dataSource = jdbcTemplate.getDataSource();
        if (dataSource == null) {
            throw new IllegalStateException("D01 command transport schema requires a JDBC DataSource");
        }
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            if (product == null || !product.toLowerCase(Locale.ROOT).contains("mysql")) {
                throw new IllegalStateException(
                        "D01 command transport schema requires MySQL; isolated acceptance targets 8.0.21, got " + product);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Unable to determine database dialect for D01 command transport schema", e);
        }
    }

    private List<String> inspectPresentTables() {
        return jdbcTemplate.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name IN ('agent_command_delivery',
                                     'agent_outbox_event',
                                     'agent_consumer_inbox')
                ORDER BY table_name
                """, String.class);
    }

    private void validateTable(TableExpectation expected) {
        List<TableDefinition> tables = jdbcTemplate.query("""
                SELECT engine, table_collation
                FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = ?
                """, (rs, rowNum) -> new TableDefinition(
                rs.getString("engine"), rs.getString("table_collation")), expected.name());
        if (tables.size() != 1
                || !"InnoDB".equalsIgnoreCase(tables.get(0).engine())
                || !BINARY_COLLATION.equalsIgnoreCase(tables.get(0).collation())) {
            throw new IllegalStateException("D01 transport table " + expected.name()
                    + " must be exact InnoDB/" + BINARY_COLLATION + ", got " + tables);
        }

        List<ColumnDefinition> columns = jdbcTemplate.query("""
                SELECT column_name, data_type, column_type, is_nullable,
                       column_default, collation_name, extra
                FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ?
                ORDER BY ordinal_position
                """, (rs, rowNum) -> new ColumnDefinition(
                rs.getString("column_name"), rs.getString("data_type"),
                rs.getString("column_type"), "YES".equalsIgnoreCase(rs.getString("is_nullable")),
                rs.getString("column_default"), rs.getString("collation_name"),
                rs.getString("extra")), expected.name());
        if (!expected.columns().equals(columns)) {
            throw new IllegalStateException("D01 transport table " + expected.name()
                    + " has incompatible columns: " + columns);
        }

        Map<String, IndexDefinition> actualIndexes = inspectIndexes(expected.name());
        if (!expected.indexes().equals(actualIndexes)) {
            throw new IllegalStateException("D01 transport table " + expected.name()
                    + " has incompatible indexes: " + actualIndexes);
        }

        Integer foreignKeys = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.referential_constraints
                WHERE constraint_schema = DATABASE() AND table_name = ?
                """, Integer.class, expected.name());
        if (foreignKeys == null || foreignKeys != 0) {
            throw new IllegalStateException("D01 transport table " + expected.name()
                    + " must not declare database foreign keys");
        }
    }

    private Map<String, IndexDefinition> inspectIndexes(String table) {
        List<IndexColumn> rows = jdbcTemplate.query("""
                SELECT index_name, non_unique, seq_in_index, column_name,
                       sub_part, index_type, is_visible
                FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = ?
                ORDER BY index_name, seq_in_index
                """, (rs, rowNum) -> new IndexColumn(
                rs.getString("index_name"), rs.getInt("non_unique") != 0,
                rs.getInt("seq_in_index"), rs.getString("column_name"),
                (Integer) rs.getObject("sub_part"), rs.getString("index_type"),
                rs.getString("is_visible")), table);
        Map<String, List<IndexColumn>> grouped = new TreeMap<>();
        for (IndexColumn row : rows) {
            if (row.subPart() != null
                    || !"BTREE".equalsIgnoreCase(row.indexType())
                    || !"YES".equalsIgnoreCase(row.visible())) {
                throw new IllegalStateException("D01 transport table " + table
                        + " has incompatible index component: " + row);
            }
            grouped.computeIfAbsent(row.name(), ignored -> new ArrayList<>()).add(row);
        }
        Map<String, IndexDefinition> result = new TreeMap<>();
        for (Map.Entry<String, List<IndexColumn>> entry : grouped.entrySet()) {
            List<IndexColumn> components = entry.getValue();
            boolean nonUnique = components.get(0).nonUnique();
            List<String> names = new ArrayList<>();
            for (int index = 0; index < components.size(); index++) {
                IndexColumn component = components.get(index);
                if (component.sequence() != index + 1 || component.nonUnique() != nonUnique) {
                    throw new IllegalStateException("D01 transport table " + table
                            + " has incompatible index ordering: " + components);
                }
                names.add(component.column());
            }
            result.put(entry.getKey(), new IndexDefinition(!nonUnique, List.copyOf(names)));
        }
        return result;
    }

    static List<String> ddlStatements() {
        String sql;
        try {
            sql = new ClassPathResource(DDL_RESOURCE).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Missing D01 transport DDL resource " + DDL_RESOURCE, e);
        }
        List<String> statements = splitSql(sql);
        if (statements.size() != TABLES.size()) {
            throw new IllegalStateException("D01 transport DDL must contain exactly three statements");
        }
        for (int index = 0; index < statements.size(); index++) {
            String normalized = statements.get(index).replaceAll("(?m)^\\s*--.*$", " ")
                    .replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
            String requiredPrefix = "create table if not exists " + TABLES.get(index) + " ";
            boolean containsDml = normalized.matches("(?s).*\\binsert\\s+into\\b.*")
                    || normalized.matches("(?s).*\\bupdate\\s+[`a-z0-9_]+\\s+set\\b.*")
                    || normalized.matches("(?s).*\\bdelete\\s+from\\b.*")
                    || normalized.matches("(?s).*\\breplace\\s+into\\b.*")
                    || normalized.matches("(?s).*\\bmerge\\s+into\\b.*");
            if (!normalized.startsWith(requiredPrefix) || containsDml) {
                throw new IllegalStateException("D01 transport DDL contains unsafe or reordered SQL");
            }
        }
        return statements;
    }

    static List<String> splitSql(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean lineComment = false;
        for (int index = 0; index < sql.length(); index++) {
            char character = sql.charAt(index);
            if (lineComment) {
                if (character == '\n' || character == '\r') {
                    lineComment = false;
                    current.append(' ');
                }
                continue;
            }
            if (!quoted && character == '-' && index + 1 < sql.length()
                    && sql.charAt(index + 1) == '-') {
                lineComment = true;
                index++;
                continue;
            }
            if (character == '\'' && (index == 0 || sql.charAt(index - 1) != '\\')) {
                quoted = !quoted;
            }
            if (character == ';' && !quoted) {
                String statement = current.toString().trim();
                if (!statement.isEmpty()) {
                    statements.add(statement);
                }
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            statements.add(tail);
        }
        return List.copyOf(statements);
    }

    static Map<String, TableExpectation> expectedTables() {
        Map<String, TableExpectation> tables = new LinkedHashMap<>();
        tables.put("agent_command_delivery", new TableExpectation(
                "agent_command_delivery",
                List.of(
                        id(), varchar("command_id", 100, false, null),
                        varchar("task_id", 100, false, null),
                        varchar("work_item_id", 100, true, null),
                        varchar("target_agent_id", 100, false, null),
                        varchar("command_type", 64, false, null),
                        mediumblob("command_payload"), binary32("command_payload_hash"),
                        varchar("status", 32, false, "PENDING"), integer("attempt_count", false, "0"),
                        bigint("next_retry_at", true, null), varchar("lease_owner", 100, true, null),
                        bigint("lease_until", true, null), varchar("active_message_id", 100, true, null),
                        integer("active_attempt", false, "0"), bigint("expires_at", false, null),
                        varchar("last_error", 2000, true, null), bigint("version", false, "0"),
                        varchar("replay_parent_message_id", 100, true, null),
                        varchar("replay_requester_id", 100, true, null),
                        varchar("replay_approver_id", 100, true, null),
                        varchar("replay_reason", 1000, true, null),
                        varchar("tenant_id", 50, false, null), varchar("client_id", 50, false, null),
                        bigint("create_time", true, null), bigint("update_time", true, null)),
                indexes(
                        index("PRIMARY", true, "id"),
                        index("uk_delivery_command", true, "tenant_id", "client_id", "command_id"),
                        index("idx_delivery_retry", false, "status", "next_retry_at", "expires_at", "id"),
                        index("idx_delivery_lease", false, "status", "lease_until", "id"),
                        index("idx_delivery_agent", false, "tenant_id", "client_id", "target_agent_id",
                                "status", "next_retry_at", "id"),
                        index("idx_delivery_active_message", false, "tenant_id", "client_id",
                                "active_message_id", "active_attempt"))));
        tables.put("agent_outbox_event", new TableExpectation(
                "agent_outbox_event",
                List.of(
                        id(), varchar("event_id", 100, false, null),
                        varchar("message_id", 100, false, null), varchar("command_id", 100, false, null),
                        bigint("delivery_id", false, null), varchar("aggregate_type", 30, false, null),
                        varchar("aggregate_id", 100, false, null), varchar("destination", 100, false, null),
                        varchar("routing_key", 100, false, null), mediumblob("wire_payload"),
                        binary32("wire_payload_hash"), varchar("status", 32, false, "PENDING"),
                        integer("attempt_count", false, "0"), bigint("next_retry_at", true, null),
                        varchar("lease_owner", 100, true, null), bigint("lease_until", true, null),
                        integer("active_attempt", false, "0"), bigint("expires_at", false, null),
                        varchar("publisher_confirm_status", 20, false, "NONE"),
                        bigint("confirmed_at", true, null), varchar("confirm_error", 2000, true, null),
                        varchar("mandatory_return_status", 20, false, "NONE"),
                        bigint("returned_at", true, null), integer("return_reply_code", true, null),
                        varchar("return_reply_text", 1000, true, null), bigint("published_at", true, null),
                        varchar("last_error", 2000, true, null), bigint("version", false, "0"),
                        varchar("replay_parent_message_id", 100, true, null),
                        varchar("replay_requester_id", 100, true, null),
                        varchar("replay_approver_id", 100, true, null),
                        varchar("replay_reason", 1000, true, null),
                        varchar("tenant_id", 50, false, null), varchar("client_id", 50, false, null),
                        bigint("create_time", true, null), bigint("update_time", true, null)),
                indexes(
                        index("PRIMARY", true, "id"),
                        index("uk_outbox_event_id", true, "tenant_id", "client_id", "event_id"),
                        index("idx_outbox_publish", false, "status", "next_retry_at", "expires_at", "id"),
                        index("idx_outbox_lease", false, "status", "lease_until", "id"),
                        index("idx_outbox_message", false, "tenant_id", "client_id", "message_id"),
                        index("idx_outbox_delivery", false, "tenant_id", "client_id", "delivery_id", "status", "id"),
                        index("idx_outbox_command", false, "tenant_id", "client_id", "command_id", "create_time", "id"))));
        tables.put("agent_consumer_inbox", new TableExpectation(
                "agent_consumer_inbox",
                List.of(
                        id(), varchar("consumer_name", 100, false, null),
                        varchar("message_id", 100, false, null), varchar("event_id", 100, false, null),
                        varchar("command_id", 100, false, null), bigint("delivery_id", false, null),
                        mediumblob("wire_payload"), binary32("wire_payload_hash"),
                        varchar("status", 32, false, "RECEIVED"),
                        varchar("result_status", 32, true, null), integer("attempt_count", false, "0"),
                        bigint("next_retry_at", true, null), varchar("lease_owner", 100, true, null),
                        bigint("lease_until", true, null), integer("active_attempt", false, "0"),
                        bigint("expires_at", false, null), bigint("processed_at", true, null),
                        varchar("last_error", 2000, true, null), bigint("version", false, "0"),
                        varchar("replay_parent_message_id", 100, true, null),
                        varchar("replay_requester_id", 100, true, null),
                        varchar("replay_approver_id", 100, true, null),
                        varchar("replay_reason", 1000, true, null),
                        varchar("tenant_id", 50, false, null), varchar("client_id", 50, false, null),
                        bigint("create_time", true, null), bigint("update_time", true, null)),
                indexes(
                        index("PRIMARY", true, "id"),
                        index("uk_consumer_message", true, "tenant_id", "client_id", "consumer_name", "message_id"),
                        index("idx_inbox_retry", false, "status", "next_retry_at", "expires_at", "id"),
                        index("idx_inbox_lease", false, "status", "lease_until", "id"),
                        index("idx_inbox_command", false, "tenant_id", "client_id", "command_id", "status", "id"),
                        index("idx_inbox_processed", false, "tenant_id", "client_id", "consumer_name",
                                "result_status", "processed_at", "id"))));
        return Map.copyOf(tables);
    }

    private static ColumnDefinition id() {
        return new ColumnDefinition("id", "bigint", "bigint", false, null, null, "auto_increment");
    }

    private static ColumnDefinition varchar(String name, int length, boolean nullable, String defaultValue) {
        return new ColumnDefinition(name, "varchar", "varchar(" + length + ")", nullable,
                defaultValue, BINARY_COLLATION, "");
    }

    private static ColumnDefinition bigint(String name, boolean nullable, String defaultValue) {
        return new ColumnDefinition(name, "bigint", "bigint", nullable, defaultValue, null, "");
    }

    private static ColumnDefinition integer(String name, boolean nullable, String defaultValue) {
        return new ColumnDefinition(name, "int", "int", nullable, defaultValue, null, "");
    }

    private static ColumnDefinition mediumblob(String name) {
        return new ColumnDefinition(name, "mediumblob", "mediumblob", false, null, null, "");
    }

    private static ColumnDefinition binary32(String name) {
        return new ColumnDefinition(name, "binary", "binary(32)", false, null, null, "");
    }

    private static Map<String, IndexDefinition> indexes(IndexEntry... entries) {
        Map<String, IndexDefinition> result = new TreeMap<>();
        for (IndexEntry entry : entries) {
            result.put(entry.name(), entry.definition());
        }
        return Map.copyOf(result);
    }

    private static IndexEntry index(String name, boolean unique, String... columns) {
        return new IndexEntry(name, new IndexDefinition(unique, List.of(columns)));
    }

    private static IllegalStateException partialSchema(List<String> present) {
        return new IllegalStateException("D01 command transport schema is partial; expected exact 0/3 or 3/3 tables, got "
                + present.size() + "/3: " + present);
    }

    static record TableExpectation(
            String name, List<ColumnDefinition> columns, Map<String, IndexDefinition> indexes) {
    }

    static record TableDefinition(String engine, String collation) {
    }

    static record ColumnDefinition(
            String name, String dataType, String columnType, boolean nullable,
            String defaultValue, String collation, String extra) {
    }

    static record IndexDefinition(boolean unique, List<String> columns) {
    }

    private record IndexEntry(String name, IndexDefinition definition) {
    }

    private record IndexColumn(
            String name, boolean nonUnique, int sequence, String column,
            Integer subPart, String indexType, String visible) {
    }
}
