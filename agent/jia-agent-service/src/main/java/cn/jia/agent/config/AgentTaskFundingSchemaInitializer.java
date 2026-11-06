package cn.jia.agent.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Exact, additive W04 catalog initializer. It never rewrites task or economy foundation tables. */
@org.springframework.context.annotation.Profile("schema-maintenance")
public final class AgentTaskFundingSchemaInitializer implements InitializingBean {
    static final String DDL_RESOURCE = "db/agent-task-funding-v0.sql";
    static final List<String> TABLES = List.of("agent_task_funding_operation", "agent_task_funding");
    private static final String COLLATION = "utf8mb4_0900_bin";
    private static final String LOCK = "cyf:agent-v0:task-funding-schema";

    private final JdbcTemplate jdbc;

    public AgentTaskFundingSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void afterPropertiesSet() {
        requireMySql();
        withLock(() -> {
            List<String> present = presentTables();
            if (present.isEmpty()) {
                for (String statement : tableDdlStatements()) jdbc.execute(statement);
            } else if (!Set.copyOf(present).equals(Set.copyOf(TABLES))) {
                throw new IllegalStateException("Partial ECO-V0 funded-task schema: " + present);
            }
            migrateOwnerScopeIfNecessary();
            // Explicit W04-R3 -> W06 migration, NOT drift repair. Validate the entire exact
            validateCatalog();
        });
    }

    private void migrateOwnerScopeIfNecessary() {
        boolean operationMissing = !columnNames("agent_task_funding_operation").contains("owner_jiacn");
        boolean fundingMissing = !columnNames("agent_task_funding").contains("owner_jiacn");
        if (!operationMissing && !fundingMissing) return;
        if (rowCount("agent_task_funding_operation") != 0 || rowCount("agent_task_funding") != 0) {
            throw new IllegalStateException("Cannot infer owner_jiacn for persisted funded-task rows");
        }
        if (operationMissing) {
            Map<String, IndexSpec> expected = indexes(
                    "PRIMARY",true,"id",
                    "uk_task_funding_operation_actor_key",true,"tenant_id,client_id,principal_type,principal_id,idempotency_key",
                    "uk_task_funding_operation_task",true,"tenant_id,client_id,task_id");
            if (!expected.equals(inspectIndexes("agent_task_funding_operation"))) {
                throw new IllegalStateException("Unexpected ownerless funding-operation indexes");
            }
            jdbc.execute("ALTER TABLE agent_task_funding_operation "
                    + "ADD COLUMN owner_jiacn VARCHAR(50) NOT NULL AFTER client_id, "
                    + "DROP INDEX uk_task_funding_operation_actor_key, "
                    + "DROP INDEX uk_task_funding_operation_task, "
                    + "ADD UNIQUE INDEX uk_task_funding_operation_actor_key "
                    + "(tenant_id,client_id,owner_jiacn,principal_type,principal_id,idempotency_key), "
                    + "ADD UNIQUE INDEX uk_task_funding_operation_task "
                    + "(tenant_id,client_id,owner_jiacn,task_id)");
        }
        if (fundingMissing) {
            Map<String, IndexSpec> expected = indexes(
                    "PRIMARY",true,"id",
                    "idx_agent_task_funding_payer",false,"tenant_id,client_id,payer_principal_type,payer_principal_id,funding_status,id",
                    "uk_agent_task_funding_escrow",true,"tenant_id,client_id,escrow_id",
                    "uk_agent_task_funding_task",true,"tenant_id,client_id,task_id");
            if (!expected.equals(inspectIndexes("agent_task_funding"))) {
                throw new IllegalStateException("Unexpected ownerless funding indexes");
            }
            jdbc.execute("ALTER TABLE agent_task_funding "
                    + "ADD COLUMN owner_jiacn VARCHAR(50) NOT NULL AFTER client_id, "
                    + "DROP INDEX idx_agent_task_funding_payer, "
                    + "DROP INDEX uk_agent_task_funding_escrow, "
                    + "DROP INDEX uk_agent_task_funding_task, "
                    + "ADD INDEX idx_agent_task_funding_payer "
                    + "(tenant_id,client_id,owner_jiacn,payer_principal_type,payer_principal_id,funding_status,id), "
                    + "ADD UNIQUE INDEX uk_agent_task_funding_escrow "
                    + "(tenant_id,client_id,owner_jiacn,escrow_id), "
                    + "ADD UNIQUE INDEX uk_agent_task_funding_task "
                    + "(tenant_id,client_id,owner_jiacn,task_id)");
        }
    }

    private Set<String> columnNames(String table) {
        return Set.copyOf(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name=?
                """, String.class, table));
    }

    private int rowCount(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? -1 : count;
    }

    void validateCatalog() {
        if (!Set.copyOf(presentTables()).equals(Set.copyOf(TABLES))) {
            throw new IllegalStateException("ECO-V0 funded-task schema must contain exact 2/2 tables");
        }
        for (TableSpec expected : expectedTables().values()) {
            List<Map<String, Object>> tableRows = jdbc.queryForList("""
                    SELECT engine,table_collation FROM information_schema.tables
                    WHERE table_schema=DATABASE() AND table_name=?
                    """, expected.name());
            if (tableRows.size() != 1
                    || !"InnoDB".equalsIgnoreCase(text(tableRows.getFirst(), "engine"))
                    || !COLLATION.equalsIgnoreCase(text(tableRows.getFirst(), "table_collation"))) {
                throw new IllegalStateException("Invalid funded-task engine/collation: " + expected.name());
            }

            List<ColumnSpec> columns = jdbc.queryForList("""
                    SELECT column_name,column_type,is_nullable,column_default,collation_name,extra
                    FROM information_schema.columns
                    WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position
                    """, expected.name()).stream().map(AgentTaskFundingSchemaInitializer::column).toList();
            if (!expected.columns().equals(columns)) {
                throw new IllegalStateException("Invalid funded-task columns for " + expected.name());
            }

            Map<String, IndexSpec> indexes = inspectIndexes(expected.name());
            if (!expected.indexes().equals(indexes)) {
                throw new IllegalStateException("Invalid funded-task indexes for " + expected.name()
                        + ": " + indexes);
            }

            Integer foreignKeys = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.referential_constraints
                    WHERE (constraint_schema=DATABASE() AND table_name=?)
                       OR (unique_constraint_schema=DATABASE() AND referenced_table_name=?)
                    """, Integer.class, expected.name(), expected.name());
            if (foreignKeys == null || foreignKeys != 0) {
                throw new IllegalStateException("Funded-task tables must not use database foreign keys");
            }
        }
        rejectUnexpectedTriggers();
    }

    private Map<String, IndexSpec> inspectIndexes(String table) {
        Map<String, List<IndexPart>> grouped = new TreeMap<>();
        jdbc.query("""
                SELECT index_name,non_unique,seq_in_index,column_name,sub_part,
                       index_type,is_visible,collation
                FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name=?
                ORDER BY index_name,seq_in_index
                """, (RowCallbackHandler) rs -> {
            if (rs.getObject("sub_part") != null
                    || !"BTREE".equalsIgnoreCase(rs.getString("index_type"))
                    || !"YES".equalsIgnoreCase(rs.getString("is_visible"))
                    || !"A".equalsIgnoreCase(rs.getString("collation"))) {
                throw new IllegalStateException("Invalid funded-task index component for " + table);
            }
            grouped.computeIfAbsent(rs.getString("index_name"), ignored -> new ArrayList<>())
                    .add(new IndexPart(rs.getInt("non_unique") == 0,
                            rs.getInt("seq_in_index"), rs.getString("column_name")));
        }, table);
        Map<String, IndexSpec> result = new TreeMap<>();
        grouped.forEach((name, parts) -> {
            boolean unique = parts.getFirst().unique();
            List<String> columns = new ArrayList<>();
            for (int index = 0; index < parts.size(); index++) {
                IndexPart part = parts.get(index);
                if (part.sequence() != index + 1 || part.unique() != unique || part.column() == null) {
                    throw new IllegalStateException("Invalid funded-task index ordering for " + table);
                }
                columns.add(part.column());
            }
            result.put(name, new IndexSpec(unique, List.copyOf(columns)));
        });
        return result;
    }

    private void rejectUnexpectedTriggers() {
        List<String> triggers = jdbc.queryForList("""
                SELECT trigger_name FROM information_schema.triggers
                WHERE trigger_schema=DATABASE()
                  AND (event_object_table IN ('agent_task_funding_operation','agent_task_funding')
                       OR trigger_name LIKE 'trg_agent_task_funding%')
                ORDER BY trigger_name
                """, String.class);
        if (!triggers.isEmpty()) {
            throw new IllegalStateException("Funded-task tables must not have triggers: " + triggers);
        }
    }

    private List<String> presentTables() {
        return jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema=DATABASE()
                  AND table_name IN ('agent_task_funding_operation','agent_task_funding')
                ORDER BY table_name
                """, String.class);
    }

    static List<String> tableDdlStatements() {
        String sql;
        try {
            sql = new ClassPathResource(DDL_RESOURCE).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Missing funded-task DDL resource", failure);
        }
        String uncommented = sql.lines().filter(line -> !line.stripLeading().startsWith("--"))
                .reduce("", (left, right) -> left + right + '\n');
        List<String> statements = new ArrayList<>();
        for (String part : uncommented.split(";")) {
            if (!part.isBlank()) statements.add(part.strip());
        }
        if (statements.size() != TABLES.size()) {
            throw new IllegalStateException("Funded-task DDL must contain exactly two statements");
        }
        for (int index = 0; index < statements.size(); index++) {
            String normalized = statements.get(index).toLowerCase(Locale.ROOT);
            if (!normalized.startsWith("create table if not exists " + TABLES.get(index) + " ")
                    || normalized.contains(" alter table ") || normalized.contains(" insert ")
                    || normalized.contains(" update ") || normalized.contains(" delete ")) {
                throw new IllegalStateException("Unsafe funded-task DDL statement");
            }
        }
        return List.copyOf(statements);
    }

    private void requireMySql() {
        DataSource source = jdbc.getDataSource();
        if (source == null) throw new IllegalStateException("Funded-task schema requires DataSource");
        try (Connection connection = source.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            if (product == null || !product.toLowerCase(Locale.ROOT).contains("mysql")) {
                throw new IllegalStateException("Funded-task schema requires MySQL; got " + product);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect funded-task database", failure);
        }
    }

    private void withLock(Runnable action) {
        DataSource source = jdbc.getDataSource();
        if (source == null) throw new IllegalStateException("Funded-task schema requires DataSource");
        try (Connection connection = source.getConnection();
             PreparedStatement acquire = connection.prepareStatement("SELECT GET_LOCK(?,10)")) {
            acquire.setString(1, LOCK);
            try (ResultSet result = acquire.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1 || result.wasNull()) {
                    throw new IllegalStateException("Timed out acquiring funded-task schema lock");
                }
            }
            try {
                action.run();
            } finally {
                try (PreparedStatement release = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                    release.setString(1, LOCK);
                    try (ResultSet result = release.executeQuery()) {
                        if (!result.next() || result.getInt(1) != 1 || result.wasNull()) {
                            throw new IllegalStateException("Funded-task schema lock was not held");
                        }
                    }
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to serialize funded-task schema initialization", failure);
        }
    }

    private static Map<String, TableSpec> expectedTables() {
        Map<String, TableSpec> tables = new LinkedHashMap<>();
        tables.put(TABLES.get(0), new TableSpec(TABLES.get(0), List.of(
                c("id","bigint","NO",null,null,"auto_increment"),
                c("principal_type","varchar(20)","NO",null,COLLATION,""),
                c("principal_id","varchar(100)","NO",null,COLLATION,""),
                c("idempotency_key","varbinary(36)","NO",null,null,""),
                c("request_hash","binary(32)","NO",null,null,""),
                c("task_id","varchar(100)","NO",null,COLLATION,""),
                c("status","varchar(16)","NO","POSTING",COLLATION,""),
                c("reserve_transaction_id","varchar(100)","YES",null,COLLATION,""),
                c("receipt_task_version","bigint","YES",null,null,""),
                c("receipt_created_at","bigint","YES",null,null,""),
                c("receipt_updated_at","bigint","YES",null,null,""),
                c("tenant_id","varchar(50)","NO",null,COLLATION,""),
                c("client_id","varchar(50)","NO",null,COLLATION,""),
                c("owner_jiacn","varchar(50)","NO",null,COLLATION,""),
                c("create_time","bigint","NO",null,null,""),
                c("update_time","bigint","NO",null,null,"")), indexes(
                "PRIMARY",true,"id",
                "uk_task_funding_operation_actor_key",true,"tenant_id,client_id,owner_jiacn,principal_type,principal_id,idempotency_key",
                "uk_task_funding_operation_task",true,"tenant_id,client_id,owner_jiacn,task_id")));
        tables.put(TABLES.get(1), new TableSpec(TABLES.get(1), List.of(
                c("id","bigint","NO",null,null,"auto_increment"), c("task_id","varchar(100)","NO",null,COLLATION,""),
                c("funding_mode","varchar(32)","NO",null,COLLATION,""), c("funding_status","varchar(24)","NO",null,COLLATION,""),
                c("payer_principal_type","varchar(20)","NO",null,COLLATION,""), c("payer_principal_id","varchar(100)","NO",null,COLLATION,""),
                c("settlement_policy","varchar(32)","NO",null,COLLATION,""), c("gross_bounty_amount_wen","bigint","NO",null,null,""),
                c("remaining_wen","bigint","NO",null,null,""), c("escrow_id","varchar(100)","YES",null,COLLATION,""),
                c("escrow_version","bigint","YES",null,null,""), c("reserve_transaction_id","varchar(100)","YES",null,COLLATION,""),
                c("required_skill_requirements","text","NO",null,COLLATION,""), c("cancel_idempotency_key","varbinary(36)","YES",null,null,""),
                c("cancel_request_hash","binary(32)","YES",null,null,""), c("refund_transaction_id","varchar(100)","YES",null,COLLATION,""),
                c("cancel_refunded_wen","bigint","YES",null,null,""), c("cancel_task_version","bigint","YES",null,null,""),
                c("refunded_at","bigint","YES",null,null,""), c("version","bigint","NO","0",null,""),
                c("tenant_id","varchar(50)","NO",null,COLLATION,""), c("client_id","varchar(50)","NO",null,COLLATION,""),
                c("owner_jiacn","varchar(50)","NO",null,COLLATION,""), c("create_time","bigint","NO",null,null,""), c("update_time","bigint","NO",null,null,"")), indexes(
                "PRIMARY",true,"id", "idx_agent_task_funding_payer",false,"tenant_id,client_id,owner_jiacn,payer_principal_type,payer_principal_id,funding_status,id",
                "uk_agent_task_funding_escrow",true,"tenant_id,client_id,owner_jiacn,escrow_id",
                "uk_agent_task_funding_task",true,"tenant_id,client_id,owner_jiacn,task_id")));
        return Map.copyOf(tables);
    }

    private static Map<String, IndexSpec> indexes(Object... values) {
        Map<String, IndexSpec> result = new TreeMap<>();
        for (int i = 0; i < values.length; i += 3) {
            result.put((String) values[i], new IndexSpec((Boolean) values[i + 1],
                    new ArrayList<>(List.of(((String) values[i + 2]).split(",")))));
        }
        return result;
    }

    private static ColumnSpec c(String name, String type, String nullable, String defaultValue,
            String collation, String extra) {
        return new ColumnSpec(name, type, nullable, defaultValue, collation, extra);
    }

    private static ColumnSpec column(Map<String, Object> row) {
        Object defaultValue = value(row, "column_default");
        Object collation = value(row, "collation_name");
        return c(text(row, "column_name"), text(row, "column_type").toLowerCase(Locale.ROOT),
                text(row, "is_nullable"), defaultValue == null ? null : defaultValue.toString(),
                collation == null ? null : collation.toString(), text(row, "extra"));
    }

    private static Object value(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) return entry.getValue();
        }
        return null;
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = value(row, key);
        return value == null ? "" : value.toString();
    }

    private record TableSpec(String name, List<ColumnSpec> columns,
            Map<String, IndexSpec> indexes) { }
    private record ColumnSpec(String name, String type, String nullable,
            String defaultValue, String collation, String extra) { }
    private record IndexSpec(boolean unique, List<String> columns) { }
    private record IndexPart(boolean unique, int sequence, String column) { }

}
