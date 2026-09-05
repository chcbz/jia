package cn.jia.agent.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

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
            validateCatalog();
        });
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

            Map<String, IndexSpec> indexes = new TreeMap<>();
            for (Map<String, Object> row : jdbc.queryForList("""
                    SELECT index_name,non_unique,column_name,seq_in_index
                    FROM information_schema.statistics
                    WHERE table_schema=DATABASE() AND table_name=? ORDER BY index_name,seq_in_index
                    """, expected.name())) {
                String name = text(row, "index_name");
                IndexSpec current = indexes.get(name);
                boolean unique = number(row, "non_unique").intValue() == 0;
                if (current == null) current = new IndexSpec(unique, new ArrayList<>());
                if (current.unique() != unique) throw new IllegalStateException("Inconsistent funded-task index");
                current.columns().add(text(row, "column_name"));
                indexes.put(name, current);
            }
            if (!expected.indexes().equals(indexes)) {
                throw new IllegalStateException("Invalid funded-task indexes for " + expected.name());
            }

            Map<String, String> checks = new TreeMap<>();
            for (Map<String, Object> row : jdbc.queryForList("""
                    SELECT tc.constraint_name,cc.check_clause
                    FROM information_schema.table_constraints tc
                    JOIN information_schema.check_constraints cc
                      ON cc.constraint_schema=tc.constraint_schema
                     AND cc.constraint_name=tc.constraint_name
                    WHERE tc.constraint_schema=DATABASE() AND tc.table_name=?
                      AND tc.constraint_type='CHECK'
                    ORDER BY tc.constraint_name
                    """, expected.name())) {
                checks.put(text(row, "constraint_name"), normalizeCheck(text(row, "check_clause")));
            }
            if (!expected.checks().equals(checks)) {
                throw new IllegalStateException("Invalid funded-task checks for " + expected.name());
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
                c("create_time","bigint","NO",null,null,""),
                c("update_time","bigint","NO",null,null,"")), indexes(
                "PRIMARY",true,"id",
                "uk_task_funding_operation_actor_key",true,"tenant_id,client_id,principal_type,principal_id,idempotency_key",
                "uk_task_funding_operation_task",true,"tenant_id,client_id,task_id"), Map.of(
                "chk_task_funding_operation_hash", normalizeCheck("octet_length(request_hash)=32"),
                "chk_task_funding_operation_key", normalizeCheck("octet_length(idempotency_key)=36"),
                "chk_task_funding_operation_status", normalizeCheck("(status='POSTING' AND reserve_transaction_id IS NULL AND receipt_task_version IS NULL AND receipt_created_at IS NULL AND receipt_updated_at IS NULL) OR (status='COMPLETED' AND reserve_transaction_id IS NOT NULL AND receipt_task_version=0 AND receipt_created_at>0 AND receipt_updated_at>0)"))));
        tables.put(TABLES.get(1), new TableSpec(TABLES.get(1), List.of(
                c("id","bigint","NO",null,null,"auto_increment"), c("task_id","varchar(100)","NO",null,COLLATION,""),
                c("funding_mode","varchar(32)","NO",null,COLLATION,""), c("funding_status","varchar(24)","NO",null,COLLATION,""),
                c("payer_principal_type","varchar(20)","NO",null,COLLATION,""), c("payer_principal_id","varchar(100)","NO",null,COLLATION,""),
                c("settlement_policy","varchar(32)","NO",null,COLLATION,""), c("gross_bounty_amount_micro","bigint","NO",null,null,""),
                c("remaining_micro","bigint","NO",null,null,""), c("escrow_id","varchar(100)","YES",null,COLLATION,""),
                c("escrow_version","bigint","YES",null,null,""), c("reserve_transaction_id","varchar(100)","YES",null,COLLATION,""),
                c("required_skill_requirements","text","NO",null,COLLATION,""), c("cancel_idempotency_key","varbinary(36)","YES",null,null,""),
                c("cancel_request_hash","binary(32)","YES",null,null,""), c("refund_transaction_id","varchar(100)","YES",null,COLLATION,""),
                c("cancel_refunded_micro","bigint","YES",null,null,""), c("cancel_task_version","bigint","YES",null,null,""),
                c("refunded_at","bigint","YES",null,null,""), c("version","bigint","NO","0",null,""),
                c("tenant_id","varchar(50)","NO",null,COLLATION,""), c("client_id","varchar(50)","NO",null,COLLATION,""),
                c("create_time","bigint","NO",null,null,""), c("update_time","bigint","NO",null,null,"")), indexes(
                "PRIMARY",true,"id", "idx_agent_task_funding_payer",false,"tenant_id,client_id,payer_principal_type,payer_principal_id,funding_status,id",
                "uk_agent_task_funding_escrow",true,"tenant_id,client_id,escrow_id",
                "uk_agent_task_funding_task",true,"tenant_id,client_id,task_id"), Map.of(
                "chk_agent_task_funding_amount", normalizeCheck("gross_bounty_amount_micro>0 AND remaining_micro>=0 AND remaining_micro<=gross_bounty_amount_micro"),
                "chk_agent_task_funding_mode", normalizeCheck("funding_mode='FUNDED_SINGLE_AGENT'"),
                "chk_agent_task_funding_policy", normalizeCheck("settlement_policy='GROSS_INCLUSIVE'"),
                "chk_agent_task_funding_state", normalizeCheck("(funding_status='RESERVING' AND version=0 AND remaining_micro=gross_bounty_amount_micro AND escrow_id IS NULL AND escrow_version IS NULL AND reserve_transaction_id IS NULL AND cancel_idempotency_key IS NULL AND cancel_request_hash IS NULL AND refund_transaction_id IS NULL AND cancel_refunded_micro IS NULL AND cancel_task_version IS NULL AND refunded_at IS NULL) OR (funding_status='FUNDS_HELD' AND version>=1 AND remaining_micro=gross_bounty_amount_micro AND escrow_id IS NOT NULL AND escrow_version>0 AND reserve_transaction_id IS NOT NULL AND cancel_idempotency_key IS NULL AND cancel_request_hash IS NULL AND refund_transaction_id IS NULL AND cancel_refunded_micro IS NULL AND cancel_task_version IS NULL AND refunded_at IS NULL) OR (funding_status='REFUNDED' AND version>=2 AND remaining_micro=0 AND escrow_id IS NOT NULL AND escrow_version>1 AND reserve_transaction_id IS NOT NULL AND octet_length(cancel_idempotency_key)=36 AND octet_length(cancel_request_hash)=32 AND refund_transaction_id IS NOT NULL AND cancel_refunded_micro>0 AND cancel_refunded_micro<=gross_bounty_amount_micro AND cancel_task_version>0 AND refunded_at>0)"))));
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

    private static String normalizeCheck(String clause) {
        String value = clause == null ? "" : clause.replace("`", "").replace("_utf8mb4", "");
        StringBuilder result = new StringBuilder(value.length());
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\'') quoted = !quoted;
            if (quoted || !Character.isWhitespace(ch)) result.append(quoted ? ch : Character.toLowerCase(ch));
        }
        String normalized = result.toString();
        while (normalized.startsWith("(") && normalized.endsWith(")") && wrapsWholeExpression(normalized)) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean wrapsWholeExpression(String value) {
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\'') quoted = !quoted;
            if (quoted) continue;
            if (ch == '(') depth++;
            else if (ch == ')' && --depth == 0 && i != value.length() - 1) return false;
        }
        return depth == 0;
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

    private static Number number(Map<String, Object> row, String key) {
        Object value = value(row, key);
        if (value instanceof Number number) return number;
        return Integer.valueOf(value.toString());
    }

    private record TableSpec(String name, List<ColumnSpec> columns,
            Map<String, IndexSpec> indexes, Map<String, String> checks) { }
    private record ColumnSpec(String name, String type, String nullable,
            String defaultValue, String collation, String extra) { }
    private record IndexSpec(boolean unique, List<String> columns) { }
}
