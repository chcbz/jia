package cn.jia.economy.config;

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

/** Separate additive initializer; it never modifies the accepted W02 five-table catalog. */
public final class EconomyHostingRentSchemaInitializer implements InitializingBean {
    static final String DDL_RESOURCE = "db/economy-v0-hosting-rent.sql";
    static final List<String> TABLES = List.of(
            "economy_hosting_rent_plan",
            "economy_hosting_rent_quote",
            "economy_hosting_lease",
            "economy_hosting_provisioning_intent");
    private static final String COLLATION = "utf8mb4_0900_bin";
    private static final String LOCK = "cyf:economy-v0:hosting-rent-schema";
    private static final List<TriggerSpec> TRIGGERS = List.of(
            new TriggerSpec("trg_hosting_plan_no_update", "economy_hosting_rent_plan", "UPDATE"),
            new TriggerSpec("trg_hosting_plan_no_delete", "economy_hosting_rent_plan", "DELETE"),
            new TriggerSpec("trg_hosting_quote_no_update", "economy_hosting_rent_quote", "UPDATE"),
            new TriggerSpec("trg_hosting_quote_no_delete", "economy_hosting_rent_quote", "DELETE"));

    private final JdbcTemplate jdbc;

    public EconomyHostingRentSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void afterPropertiesSet() {
        requireMySql();
        withLock(() -> {
            List<String> present = presentTables();
            if (present.isEmpty()) {
                for (String ddl : tableDdlStatements()) jdbc.execute(ddl);
            } else if (!present.equals(sorted(TABLES))) {
                throw new IllegalStateException("Partial ECO-V0 hosting-rent schema: " + present);
            }
            validateCatalog();
            ensureImmutableTriggers();
        });
    }

    void validateCatalog() {
        if (!presentTables().equals(sorted(TABLES))) {
            throw new IllegalStateException("ECO-V0 hosting-rent schema must contain exact 4/4 tables");
        }
        for (Map.Entry<String, TableSpec> entry : expectedTables().entrySet()) {
            String table = entry.getKey();
            TableSpec expected = entry.getValue();
            List<TableMeta> meta = jdbc.query("""
                    SELECT engine,table_collation FROM information_schema.tables
                    WHERE table_schema=DATABASE() AND table_name=?
                    """, (rs, row) -> new TableMeta(rs.getString(1), rs.getString(2)), table);
            if (meta.size() != 1 || !"InnoDB".equalsIgnoreCase(meta.getFirst().engine())
                    || !COLLATION.equalsIgnoreCase(meta.getFirst().collation())) {
                throw new IllegalStateException("Invalid hosting-rent table engine/collation: " + table);
            }
            List<String> columns = jdbc.queryForList("""
                    SELECT column_name FROM information_schema.columns
                    WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position
                    """, String.class, table);
            if (!expected.columns().equals(columns)) {
                throw new IllegalStateException("Invalid hosting-rent columns for " + table + ": " + columns);
            }
            Map<String, List<String>> indexes = new TreeMap<>();
            jdbc.query("""
                    SELECT index_name,column_name FROM information_schema.statistics
                    WHERE table_schema=DATABASE() AND table_name=? ORDER BY index_name,seq_in_index
                    """, rs -> indexes.computeIfAbsent(rs.getString(1), ignored -> new ArrayList<>())
                    .add(rs.getString(2)), table);
            if (!expected.indexes().equals(indexes)) {
                throw new IllegalStateException("Invalid hosting-rent indexes for " + table + ": " + indexes);
            }
            Set<String> checks = Set.copyOf(jdbc.queryForList("""
                    SELECT constraint_name FROM information_schema.table_constraints
                    WHERE constraint_schema=DATABASE() AND table_name=? AND constraint_type='CHECK'
                    """, String.class, table));
            if (!expected.checks().equals(checks)) {
                throw new IllegalStateException("Invalid hosting-rent checks for " + table + ": " + checks);
            }
            Integer foreignKeys = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.referential_constraints
                    WHERE (constraint_schema=DATABASE() AND table_name=?)
                       OR (unique_constraint_schema=DATABASE() AND referenced_table_name=?)
                    """, Integer.class, table, table);
            if (foreignKeys == null || foreignKeys != 0) {
                throw new IllegalStateException("Hosting-rent tables must not use database foreign keys: " + table);
            }
        }
    }

    private void ensureImmutableTriggers() {
        Map<String, TriggerMeta> actual = inspectTriggers();
        for (TriggerSpec expected : TRIGGERS) {
            TriggerMeta found = actual.get(expected.name());
            if (found == null) {
                jdbc.execute("CREATE TRIGGER " + expected.name() + " BEFORE " + expected.event()
                        + " ON " + expected.table() + " FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' "
                        + "SET MESSAGE_TEXT='immutable hosting-rent row'; END");
            } else if (!expected.matches(found)) {
                throw new IllegalStateException("Invalid hosting-rent immutable trigger: " + expected.name());
            }
        }
        actual = inspectTriggers();
        if (actual.size() != TRIGGERS.size()
                || TRIGGERS.stream().anyMatch(spec -> !spec.matches(actual.get(spec.name())))) {
            throw new IllegalStateException("Hosting-rent immutable trigger catalog is not exact");
        }
    }

    private Map<String, TriggerMeta> inspectTriggers() {
        Map<String, TriggerMeta> result = new TreeMap<>();
        jdbc.query("""
                SELECT trigger_name,event_object_table,action_timing,event_manipulation,action_statement
                FROM information_schema.triggers
                WHERE trigger_schema=DATABASE()
                  AND (event_object_table IN ('economy_hosting_rent_plan','economy_hosting_rent_quote')
                       OR trigger_name LIKE 'trg_hosting_%')
                ORDER BY trigger_name
                """, rs -> {
            TriggerMeta meta = new TriggerMeta(rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), EconomySchemaInitializer.normalizeTriggerSql(rs.getString(5)));
            if (result.put(meta.name(), meta) != null) {
                throw new IllegalStateException("Duplicate hosting-rent trigger name");
            }
        });
        return result;
    }

    private List<String> presentTables() {
        return jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema=DATABASE()
                  AND table_name IN ('economy_hosting_rent_plan','economy_hosting_rent_quote',
                                     'economy_hosting_lease','economy_hosting_provisioning_intent')
                ORDER BY table_name
                """, String.class);
    }

    static List<String> tableDdlStatements() {
        String sql;
        try {
            sql = new ClassPathResource(DDL_RESOURCE).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Missing hosting-rent DDL resource", failure);
        }
        List<String> statements = EconomySchemaInitializer.splitSql(sql);
        if (statements.size() != TABLES.size()) {
            throw new IllegalStateException("Hosting-rent DDL must contain exactly four CREATE TABLE statements");
        }
        for (int index = 0; index < statements.size(); index++) {
            String normalized = statements.get(index).stripLeading().toLowerCase(Locale.ROOT);
            if (!normalized.startsWith("create table if not exists " + TABLES.get(index) + " ")
                    || normalized.contains(" insert ") || normalized.contains(" update ")
                    || normalized.contains(" delete ") || normalized.contains(" alter table ")) {
                throw new IllegalStateException("Unsafe hosting-rent DDL statement");
            }
        }
        return List.copyOf(statements);
    }

    private void requireMySql() {
        DataSource source = jdbc.getDataSource();
        if (source == null) throw new IllegalStateException("Hosting-rent schema requires DataSource");
        try (Connection connection = source.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            if (product == null || !product.toLowerCase(Locale.ROOT).contains("mysql")) {
                throw new IllegalStateException("Hosting-rent schema requires MySQL; got " + product);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect hosting-rent database", failure);
        }
    }

    private void withLock(Runnable action) {
        DataSource source = jdbc.getDataSource();
        if (source == null) throw new IllegalStateException("Hosting-rent schema requires DataSource");
        try (Connection connection = source.getConnection();
             PreparedStatement acquire = connection.prepareStatement("SELECT GET_LOCK(?,10)")) {
            acquire.setString(1, LOCK);
            try (ResultSet result = acquire.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1 || result.wasNull()) {
                    throw new IllegalStateException("Timed out acquiring hosting-rent schema lock");
                }
            }
            try {
                action.run();
            } finally {
                try (PreparedStatement release = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                    release.setString(1, LOCK);
                    try (ResultSet result = release.executeQuery()) {
                        if (!result.next() || result.getInt(1) != 1 || result.wasNull()) {
                            throw new IllegalStateException("Hosting-rent schema lock was not held");
                        }
                    }
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to serialize hosting-rent schema initialization", failure);
        }
    }

    private static Map<String, TableSpec> expectedTables() {
        Map<String, TableSpec> tables = new LinkedHashMap<>();
        tables.put(TABLES.get(0), new TableSpec(List.of(
                "id","plan_id","plan_version","amount_micro","period_seconds","quote_ttl_seconds",
                "currency","status","tenant_id","client_id","create_time"), indexes(
                "PRIMARY", "id",
                "idx_hosting_plan_status", "tenant_id,client_id,status,plan_id,plan_version",
                "uk_hosting_plan_version", "tenant_id,client_id,plan_id,plan_version"), Set.of(
                "chk_hosting_plan_values","chk_hosting_plan_currency","chk_hosting_plan_status")));
        tables.put(TABLES.get(1), new TableSpec(List.of(
                "id","quote_id","quote_purpose","plan_id","plan_version","amount_micro","period_seconds",
                "principal_type","principal_id","persona_code","agent_id","lease_id","expected_lease_version",
                "idempotency_key","request_hash","expires_at","tenant_id","client_id","create_time"), indexes(
                "PRIMARY", "id",
                "idx_hosting_quote_agent", "tenant_id,client_id,agent_id,expires_at,quote_id",
                "uk_hosting_quote_actor_key", "tenant_id,client_id,principal_type,principal_id,idempotency_key",
                "uk_hosting_quote_id", "tenant_id,client_id,quote_id"), Set.of(
                "chk_hosting_quote_values","chk_hosting_quote_key","chk_hosting_quote_hash",
                "chk_hosting_quote_actor","chk_hosting_quote_purpose")));
        tables.put(TABLES.get(2), new TableSpec(List.of(
                "id","lease_id","principal_type","principal_id","persona_code","agent_id","binding_id",
                "plan_id","plan_version","amount_micro","period_seconds","status","paid_from","paid_through",
                "latest_intent_id","version","tenant_id","client_id","create_time","update_time"), indexes(
                "PRIMARY", "id",
                "idx_hosting_lease_actor", "tenant_id,client_id,principal_type,principal_id,status,lease_id",
                "uk_hosting_lease_agent", "tenant_id,client_id,agent_id",
                "uk_hosting_lease_id", "tenant_id,client_id,lease_id",
                "uk_hosting_lease_intent", "tenant_id,client_id,latest_intent_id"), Set.of(
                "chk_hosting_lease_values","chk_hosting_lease_actor","chk_hosting_lease_state")));
        tables.put(TABLES.get(3), new TableSpec(List.of(
                "id","intent_id","lease_id","quote_id","quote_purpose","principal_type","principal_id",
                "persona_code","agent_id","amount_micro","period_seconds","status","reserve_idempotency_key",
                "reserve_request_hash","reserve_transaction_id","reserved_at","escrow_version",
                "capture_idempotency_key","capture_request_hash","capture_transaction_id","captured_at",
                "refund_idempotency_key","refund_request_hash","refund_transaction_id","refunded_at",
                "outcome_evidence_ref","version","tenant_id","client_id","create_time","update_time"), indexes(
                "PRIMARY", "id",
                "idx_hosting_intent_lease", "tenant_id,client_id,lease_id,version",
                "idx_hosting_intent_status", "tenant_id,client_id,status,update_time,intent_id",
                "uk_hosting_intent_id", "tenant_id,client_id,intent_id",
                "uk_hosting_intent_quote", "tenant_id,client_id,quote_id",
                "uk_hosting_intent_reserve_key", "tenant_id,client_id,principal_type,principal_id,reserve_idempotency_key"), Set.of(
                "chk_hosting_intent_values","chk_hosting_intent_actor","chk_hosting_intent_reserve_key",
                "chk_hosting_intent_reserve_hash","chk_hosting_intent_capture_pair",
                "chk_hosting_intent_refund_pair","chk_hosting_intent_state")));
        return tables;
    }

    private static Map<String, List<String>> indexes(String... values) {
        Map<String, List<String>> result = new TreeMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(values[index], List.of(values[index + 1].split(",")));
        }
        return result;
    }

    private static List<String> sorted(List<String> values) {
        return values.stream().sorted().toList();
    }

    private record TableSpec(List<String> columns, Map<String, List<String>> indexes, Set<String> checks) {
    }

    private record TableMeta(String engine, String collation) {
    }

    private record TriggerMeta(String name, String table, String timing, String event, String statement) {
    }

    private record TriggerSpec(String name, String table, String event) {
        private boolean matches(TriggerMeta actual) {
            if (actual == null) return false;
            String expectedBody = EconomySchemaInitializer.normalizeTriggerSql(
                    "BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='immutable hosting-rent row'; END");
            return name.equals(actual.name()) && table.equals(actual.table())
                    && "BEFORE".equalsIgnoreCase(actual.timing())
                    && event.equalsIgnoreCase(actual.event())
                    && expectedBody.equals(actual.statement());
        }
    }
}
