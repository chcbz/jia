package cn.jia.economy.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import cn.jia.economy.config.EconomySchemaInitializer.ColumnDefinition;
import cn.jia.economy.config.EconomySchemaInitializer.IndexDefinition;
import cn.jia.economy.config.EconomySchemaInitializer.CheckDefinition;

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
import java.util.TreeMap;

/** Separate additive initializer; it never modifies the accepted W02 five-table catalog. */
public final class EconomyHostingRentSchemaInitializer implements InitializingBean {
    static final String DDL_RESOURCE = "db/economy-v0-hosting-rent.sql";
    static final List<String> TABLES = List.of(
            "economy_hosting_rent_plan",
            "economy_hosting_rent_quote",
            "economy_hosting_lease",
            "economy_hosting_provisioning_intent", "economy_hosting_reprovision");
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
            throw new IllegalStateException("ECO-V0 hosting-rent schema must contain exact 5/5 tables");
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
            List<ColumnDefinition> columns = jdbc.query("""
                    SELECT column_name,data_type,column_type,is_nullable,column_default,collation_name,extra
                    FROM information_schema.columns
                    WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position
                    """, (rs, row) -> new ColumnDefinition(rs.getString("column_name"),
                    rs.getString("data_type"), rs.getString("column_type"),
                    "YES".equalsIgnoreCase(rs.getString("is_nullable")), rs.getString("column_default"),
                    rs.getString("collation_name"), rs.getString("extra")), table);
            if (!expected.columns().equals(columns)) {
                throw new IllegalStateException("Invalid hosting-rent columns for " + table + ": " + columns);
            }
            Map<String, IndexDefinition> indexes = inspectIndexes(table);
            if (!expected.indexes().equals(indexes)) {
                throw new IllegalStateException("Invalid hosting-rent indexes for " + table + ": " + indexes);
            }
            Map<String, CheckDefinition> checks = inspectChecks(table);
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

    private Map<String, IndexDefinition> inspectIndexes(String table) {
        Map<String, List<IndexPart>> grouped = new TreeMap<>();
        // Explicit callback avoids JdbcTemplate's ResultSetExtractor/RowCallbackHandler ambiguity.
        jdbc.query("""
                SELECT index_name,non_unique,seq_in_index,column_name,sub_part,index_type,is_visible,collation
                FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name=? ORDER BY index_name,seq_in_index
                """, (RowCallbackHandler) rs -> {
            if (rs.getObject("sub_part") != null
                    || !"BTREE".equalsIgnoreCase(rs.getString("index_type"))
                    || !"YES".equalsIgnoreCase(rs.getString("is_visible"))
                    || !"A".equalsIgnoreCase(rs.getString("collation"))) {
                throw new IllegalStateException("Invalid hosting-rent index component for " + table);
            }
            grouped.computeIfAbsent(rs.getString("index_name"), ignored -> new ArrayList<>())
                    .add(new IndexPart(rs.getInt("non_unique") == 0,
                            rs.getInt("seq_in_index"), rs.getString("column_name")));
        }, table);
        Map<String, IndexDefinition> result = new TreeMap<>();
        grouped.forEach((name, parts) -> {
            boolean unique = parts.getFirst().unique();
            List<String> columns = new ArrayList<>();
            for (int index = 0; index < parts.size(); index++) {
                IndexPart part = parts.get(index);
                if (part.sequence() != index + 1 || part.unique() != unique || part.column() == null) {
                    throw new IllegalStateException("Invalid hosting-rent index ordering for " + table);
                }
                columns.add(part.column());
            }
            result.put(name, new IndexDefinition(name, unique, List.copyOf(columns)));
        });
        return result;
    }

    private Map<String, CheckDefinition> inspectChecks(String table) {
        List<CheckDefinition> rows = jdbc.query("""
                SELECT tc.constraint_name,cc.check_clause,tc.enforced
                FROM information_schema.table_constraints tc
                JOIN information_schema.check_constraints cc
                  ON cc.constraint_catalog=tc.constraint_catalog
                 AND cc.constraint_schema=tc.constraint_schema
                 AND cc.constraint_name=tc.constraint_name
                WHERE tc.constraint_schema=DATABASE() AND tc.table_name=? AND tc.constraint_type='CHECK'
                ORDER BY tc.constraint_name
                """, (rs, row) -> new CheckDefinition(rs.getString("constraint_name"),
                EconomySchemaInitializer.normalizeCheckClause(rs.getString("check_clause")),
                "YES".equalsIgnoreCase(rs.getString("enforced"))), table);
        Map<String, CheckDefinition> result = new TreeMap<>();
        for (CheckDefinition row : rows) {
            if (result.put(row.name(), row) != null) {
                throw new IllegalStateException("Ambiguous hosting-rent CHECK catalog for " + table);
            }
        }
        return result;
    }

    private void ensureImmutableTriggers() {
        Map<String, TriggerMeta> actual = inspectTriggers();
        for (TriggerMeta found : actual.values()) {
            if (TRIGGERS.stream().noneMatch(spec -> spec.matches(found))) {
                throw new IllegalStateException("Invalid hosting-rent immutable trigger: " + found.name());
            }
        }
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
        Map<String, TriggerMeta> verified = inspectTriggers();
        if (verified.size() != TRIGGERS.size()
                || TRIGGERS.stream().anyMatch(spec -> !spec.matches(verified.get(spec.name())))) {
            throw new IllegalStateException("Hosting-rent immutable trigger catalog is not exact");
        }
    }

    private Map<String, TriggerMeta> inspectTriggers() {
        Map<String, TriggerMeta> result = new TreeMap<>();
        jdbc.query("""
                SELECT trigger_name,event_object_table,action_timing,event_manipulation,action_statement
                FROM information_schema.triggers
                WHERE trigger_schema=DATABASE()
                  AND (event_object_table IN ('economy_hosting_rent_plan','economy_hosting_rent_quote',
                                              'economy_hosting_lease','economy_hosting_provisioning_intent','economy_hosting_reprovision')
                       OR trigger_name LIKE 'trg_hosting_%')
                ORDER BY trigger_name
                """, (RowCallbackHandler) rs -> {
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
                                     'economy_hosting_lease','economy_hosting_provisioning_intent','economy_hosting_reprovision')
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
            throw new IllegalStateException("Hosting-rent DDL must contain exactly five CREATE TABLE statements");
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
        tables.put("economy_hosting_rent_plan", table(List.of(
                column("id", "bigint", false, null, "auto_increment"),
                column("plan_id", "varchar(100)", false, null, ""),
                column("plan_version", "bigint", false, null, ""),
                column("amount_micro", "bigint", false, null, ""),
                column("period_seconds", "bigint", false, null, ""),
                column("quote_ttl_seconds", "bigint", false, null, ""),
                column("currency", "varchar(16)", false, null, ""),
                column("status", "varchar(16)", false, null, ""),
                column("tenant_id", "varchar(50)", false, null, ""),
                column("client_id", "varchar(50)", false, null, ""),
                column("create_time", "bigint", false, null, "")), List.of(
                index("PRIMARY", true, "id"),
                index("uk_hosting_plan_version", true, "tenant_id,client_id,plan_id,plan_version"),
                index("idx_hosting_plan_status", false, "tenant_id,client_id,status,plan_id,plan_version")),
                check("chk_hosting_plan_values", "plan_version > 0 AND amount_micro > 0 AND period_seconds > 0 AND quote_ttl_seconds > 0"),
                check("chk_hosting_plan_currency", "currency = 'SILVER'"),
                check("chk_hosting_plan_status", "status IN ('ACTIVE','DISABLED')")));
        tables.put("economy_hosting_rent_quote", table(List.of(
                column("id", "bigint", false, null, "auto_increment"),
                column("quote_id", "varchar(100)", false, null, ""),
                column("quote_purpose", "varchar(16)", false, null, ""),
                column("plan_id", "varchar(100)", false, null, ""),
                column("plan_version", "bigint", false, null, ""),
                column("amount_micro", "bigint", false, null, ""),
                column("period_seconds", "bigint", false, null, ""),
                column("principal_type", "varchar(20)", false, null, ""),
                column("principal_id", "varchar(100)", false, null, ""),
                column("persona_code", "varchar(100)", false, null, ""),
                column("agent_id", "varchar(100)", false, null, ""),
                column("lease_id", "varchar(100)", true, null, ""),
                column("expected_lease_version", "bigint", true, null, ""),
                column("idempotency_key", "varbinary(36)", false, null, ""),
                column("request_hash", "binary(32)", false, null, ""),
                column("expires_at", "bigint", false, null, ""),
                column("tenant_id", "varchar(50)", false, null, ""),
                column("client_id", "varchar(50)", false, null, ""),
                column("create_time", "bigint", false, null, "")), List.of(
                index("PRIMARY", true, "id"),
                index("uk_hosting_quote_id", true, "tenant_id,client_id,quote_id"),
                index("uk_hosting_quote_actor_key", true, "tenant_id,client_id,principal_type,principal_id,idempotency_key"),
                index("idx_hosting_quote_agent", false, "tenant_id,client_id,agent_id,expires_at,quote_id")),
                check("chk_hosting_quote_values", "plan_version > 0 AND amount_micro > 0 AND period_seconds > 0 AND expires_at > create_time"),
                check("chk_hosting_quote_key", "OCTET_LENGTH(idempotency_key) = 36"),
                check("chk_hosting_quote_hash", "OCTET_LENGTH(request_hash) = 32"),
                check("chk_hosting_quote_actor", "principal_type = 'USER'"),
                check("chk_hosting_quote_purpose", "(quote_purpose = 'INITIAL' AND lease_id IS NULL AND expected_lease_version IS NULL) OR (quote_purpose = "
                        + "'RENEWAL' AND lease_id IS NOT NULL AND expected_lease_version IS NOT NULL AND expected_lease_version > 0)")));
        tables.put("economy_hosting_lease", table(List.of(
                column("id", "bigint", false, null, "auto_increment"),
                column("lease_id", "varchar(100)", false, null, ""),
                column("principal_type", "varchar(20)", false, null, ""),
                column("principal_id", "varchar(100)", false, null, ""),
                column("persona_code", "varchar(100)", false, null, ""),
                column("agent_id", "varchar(100)", false, null, ""),
                column("binding_id", "varchar(100)", true, null, ""),
                column("live_slot", "tinyint", true, "1", ""),
                column("plan_id", "varchar(100)", false, null, ""),
                column("plan_version", "bigint", false, null, ""),
                column("amount_micro", "bigint", false, null, ""),
                column("period_seconds", "bigint", false, null, ""),
                column("status", "varchar(24)", false, null, ""),
                column("paid_from", "bigint", true, null, ""),
                column("paid_through", "bigint", true, null, ""),
                column("latest_intent_id", "varchar(100)", false, null, ""),
                column("version", "bigint", false, null, ""),
                column("tenant_id", "varchar(50)", false, null, ""),
                column("client_id", "varchar(50)", false, null, ""),
                column("create_time", "bigint", false, null, ""),
                column("update_time", "bigint", false, null, "")), List.of(
                index("PRIMARY", true, "id"),
                index("uk_hosting_lease_id", true, "tenant_id,client_id,lease_id"),
                index("uk_hosting_lease_agent", true, "tenant_id,client_id,agent_id,live_slot"),
                index("uk_hosting_lease_intent", true, "tenant_id,client_id,latest_intent_id"),
                index("idx_hosting_lease_actor", false, "tenant_id,client_id,principal_type,principal_id,status,lease_id")),
                check("chk_hosting_lease_values", "plan_version > 0 AND amount_micro > 0 AND period_seconds > 0 AND version > 0"),
                check("chk_hosting_lease_actor", "principal_type = 'USER'"),
                check("chk_hosting_lease_live_slot", "(status = 'REFUNDED' AND live_slot IS NULL) OR (status IN ('PROVISIONING','ACTIVE') AND live_slot IS NOT NULL "
                        + "AND live_slot = 1)"),
                check("chk_hosting_lease_state", "(status = 'PROVISIONING' AND paid_from IS NULL AND paid_through IS NULL) OR (status = 'ACTIVE' AND paid_from "
                        + "IS NOT NULL AND paid_through IS NOT NULL AND paid_through > paid_from) OR (status = 'REFUNDED' AND paid_from "
                        + "IS NULL AND paid_through IS NULL)")));
        tables.put("economy_hosting_provisioning_intent", table(List.of(
                column("id", "bigint", false, null, "auto_increment"),
                column("intent_id", "varchar(100)", false, null, ""),
                column("lease_id", "varchar(100)", false, null, ""),
                column("quote_id", "varchar(100)", false, null, ""),
                column("quote_purpose", "varchar(16)", false, null, ""),
                column("principal_type", "varchar(20)", false, null, ""),
                column("principal_id", "varchar(100)", false, null, ""),
                column("persona_code", "varchar(100)", false, null, ""),
                column("agent_id", "varchar(100)", false, null, ""),
                column("amount_micro", "bigint", false, null, ""),
                column("period_seconds", "bigint", false, null, ""),
                column("status", "varchar(32)", false, null, ""),
                column("reserve_idempotency_key", "varbinary(36)", false, null, ""),
                column("reserve_request_hash", "binary(32)", false, null, ""),
                column("reserve_transaction_id", "varchar(100)", false, null, ""),
                column("reserved_at", "bigint", false, null, ""),
                column("escrow_version", "bigint", false, null, ""),
                column("capture_idempotency_key", "varbinary(36)", true, null, ""),
                column("capture_request_hash", "binary(32)", true, null, ""),
                column("capture_transaction_id", "varchar(100)", true, null, ""),
                column("captured_at", "bigint", true, null, ""),
                column("refund_idempotency_key", "varbinary(36)", true, null, ""),
                column("refund_request_hash", "binary(32)", true, null, ""),
                column("refund_transaction_id", "varchar(100)", true, null, ""),
                column("refunded_at", "bigint", true, null, ""),
                column("managed_api_key_id", "varchar(100)", true, null, ""),
                column("outcome_evidence_ref", "varchar(100)", true, null, ""),
                column("service_ready_at", "bigint", true, null, ""),
                column("paid_from", "bigint", true, null, ""),
                column("paid_through", "bigint", true, null, ""),
                column("version", "bigint", false, null, ""),
                column("tenant_id", "varchar(50)", false, null, ""),
                column("client_id", "varchar(50)", false, null, ""),
                column("create_time", "bigint", false, null, ""),
                column("update_time", "bigint", false, null, "")), List.of(
                index("PRIMARY", true, "id"),
                index("uk_hosting_intent_id", true, "tenant_id,client_id,intent_id"),
                index("uk_hosting_intent_quote", true, "tenant_id,client_id,quote_id"),
                index("uk_hosting_intent_reserve_key", true, "tenant_id,client_id,principal_type,principal_id,reserve_idempotency_key"),
                index("idx_hosting_intent_lease", false, "tenant_id,client_id,lease_id,version"),
                index("idx_hosting_intent_status", false, "tenant_id,client_id,status,update_time,intent_id")),
                check("chk_hosting_intent_values", "amount_micro > 0 AND period_seconds > 0 AND escrow_version > 0 AND version > 0"),
                check("chk_hosting_intent_actor", "principal_type = 'USER'"),
                check("chk_hosting_intent_reserve_key", "OCTET_LENGTH(reserve_idempotency_key) = 36"),
                check("chk_hosting_intent_reserve_hash", "OCTET_LENGTH(reserve_request_hash) = 32"),
                check("chk_hosting_intent_capture_pair", "(capture_idempotency_key IS NULL AND capture_request_hash IS NULL AND capture_transaction_id IS NULL AND "
                        + "captured_at IS NULL) OR (capture_idempotency_key IS NOT NULL AND capture_request_hash IS NOT NULL AND "
                        + "OCTET_LENGTH(capture_idempotency_key) = 36 AND OCTET_LENGTH(capture_request_hash) = 32 AND "
                        + "capture_transaction_id IS NOT NULL AND captured_at IS NOT NULL)"),
                check("chk_hosting_intent_refund_pair", "(refund_idempotency_key IS NULL AND refund_request_hash IS NULL AND refund_transaction_id IS NULL AND "
                        + "refunded_at IS NULL) OR (refund_idempotency_key IS NOT NULL AND refund_request_hash IS NOT NULL AND "
                        + "OCTET_LENGTH(refund_idempotency_key) = 36 AND OCTET_LENGTH(refund_request_hash) = 32 AND "
                        + "refund_transaction_id IS NOT NULL AND refunded_at IS NOT NULL)"),
                check("chk_hosting_intent_paid_period", "(status = 'ACTIVE' AND paid_from IS NOT NULL AND paid_through IS NOT NULL AND paid_through > paid_from) OR (status <> 'ACTIVE' AND paid_from IS NULL AND paid_through IS NULL)"),
                check("chk_hosting_intent_state", "(status = 'FUNDS_RESERVED' AND service_ready_at IS NULL AND capture_transaction_id IS NULL AND "
                        + "refund_transaction_id IS NULL) OR (status IN ('PROVISIONING_UNKNOWN','FAILED_NO_EFFECT') AND service_ready_at "
                        + "IS NULL AND outcome_evidence_ref IS NOT NULL AND capture_transaction_id IS NULL AND refund_transaction_id IS "
                        + "NULL) OR (status = 'SERVICE_READY' AND service_ready_at IS NOT NULL AND service_ready_at >= reserved_at AND "
                        + "outcome_evidence_ref IS NOT NULL AND capture_transaction_id IS NULL AND refund_transaction_id IS NULL) OR "
                        + "(status = 'ACTIVE' AND service_ready_at IS NOT NULL AND service_ready_at >= reserved_at AND "
                        + "outcome_evidence_ref IS NOT NULL AND capture_transaction_id IS NOT NULL AND refund_transaction_id IS NULL) OR "
                        + "(status = 'REFUNDED' AND service_ready_at IS NULL AND outcome_evidence_ref IS NOT NULL AND "
                        + "capture_transaction_id IS NULL AND refund_transaction_id IS NOT NULL)")));
        tables.put("economy_hosting_reprovision", table(List.of(
                column("id", "bigint", false, null, "auto_increment"),
                column("request_id", "varchar(100)", false, null, ""),
                column("lease_id", "varchar(100)", false, null, ""),
                column("intent_id", "varchar(100)", false, null, ""),
                column("agent_id", "varchar(100)", false, null, ""),
                column("persona_code", "varchar(100)", false, null, ""),
                column("principal_id", "varchar(100)", false, null, ""),
                column("idempotency_key", "varbinary(36)", false, null, ""),
                column("request_hash", "binary(32)", false, null, ""),
                column("lease_version", "bigint", false, null, ""),
                column("paid_through", "bigint", false, null, ""),
                column("requested_at", "bigint", false, null, ""),
                column("status", "varchar(32)", false, null, ""),
                column("live_slot", "tinyint", true, "1", ""),
                column("version", "bigint", false, null, ""),
                column("service_ready_at", "bigint", true, null, ""),
                column("evidence_ref", "varchar(100)", true, null, ""),
                column("tenant_id", "varchar(50)", false, null, ""),
                column("client_id", "varchar(50)", false, null, "")), List.of(
                index("PRIMARY", true, "id"),
                index("uk_hosting_reprovision_id", true, "tenant_id,client_id,request_id"),
                index("uk_hosting_reprovision_key", true, "tenant_id,client_id,principal_id,idempotency_key"),
                index("uk_hosting_reprovision_live", true, "tenant_id,client_id,lease_id,live_slot"),
                index("idx_hosting_reprovision_pending", false, "status,id")),
                check("chk_hosting_reprovision_values", "lease_version > 0 AND version > 0 AND requested_at > 0 AND paid_through > requested_at"),
                check("chk_hosting_reprovision_key", "OCTET_LENGTH(idempotency_key) = 36 AND OCTET_LENGTH(request_hash) = 32"),
                check("chk_hosting_reprovision_state", "(status IN ('ACCEPTED','PROVISIONING_UNKNOWN') AND live_slot IS NOT NULL AND live_slot = 1 AND service_ready_at IS NULL) OR (status = 'SERVICE_READY' AND live_slot IS NULL AND service_ready_at IS NOT NULL AND service_ready_at >= requested_at AND evidence_ref IS NOT NULL) OR (status = 'FAILED_NO_EFFECT' AND live_slot IS NULL AND service_ready_at IS NULL AND evidence_ref IS NOT NULL)")));
        return Map.copyOf(tables);
    }

    private static TableSpec table(List<ColumnDefinition> columns, List<IndexDefinition> indexes,
                                   CheckDefinition... checks) {
        Map<String, IndexDefinition> indexMap = new TreeMap<>();
        indexes.forEach(index -> indexMap.put(index.name(), index));
        Map<String, CheckDefinition> checkMap = new TreeMap<>();
        for (CheckDefinition check : checks) checkMap.put(check.name(), check);
        return new TableSpec(List.copyOf(columns), Map.copyOf(indexMap), Map.copyOf(checkMap));
    }

    private static ColumnDefinition column(String name, String type, boolean nullable,
                                           String defaultValue, String extra) {
        String dataType = type.contains("(") ? type.substring(0, type.indexOf('(')) : type;
        return new ColumnDefinition(name, dataType, type, nullable, defaultValue,
                dataType.equals("varchar") ? COLLATION : null, extra);
    }

    private static IndexDefinition index(String name, boolean unique, String columns) {
        return new IndexDefinition(name, unique, List.of(columns.split(",")));
    }

    private static CheckDefinition check(String name, String clause) {
        return new CheckDefinition(name, EconomySchemaInitializer.normalizeCheckClause(clause), true);
    }

    private static List<String> sorted(List<String> values) {
        return values.stream().sorted().toList();
    }

    private record TableSpec(List<ColumnDefinition> columns, Map<String, IndexDefinition> indexes,
                             Map<String, CheckDefinition> checks) {
    }

    private record IndexPart(boolean unique, int sequence, String column) {
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
