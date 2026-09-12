package cn.jia.agent.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** W06 single-table exact catalog. One tx-bound callback connection also holds the startup lock. */
public final class AgentTaskSettlementSchemaInitializer implements InitializingBean {
    static final String TABLE = "agent_task_bounty_settlement";
    static final String RESOURCE = "db/agent-task-bounty-settlement-v0.sql";
    private static final String LOCK = "cyf:agent-v0:bounty-settlement-schema";
    private final JdbcTemplate jdbc;

    public AgentTaskSettlementSchemaInitializer(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void afterPropertiesSet() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            if (!connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql")) {
                throw new IllegalStateException("W06 schema requires MySQL");
            }
            JdbcTemplate locked = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            if (!Integer.valueOf(1).equals(locked.queryForObject("SELECT GET_LOCK(?,10)", Integer.class, LOCK))) {
                throw new IllegalStateException("W06 schema lock timeout");
            }
            try {
                Integer present = locked.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema=DATABASE() AND table_name=?", Integer.class, TABLE);
                if (Integer.valueOf(0).equals(present)) locked.execute(ddl());
                validateCatalog(locked);
            } finally {
                if (!Integer.valueOf(1).equals(locked.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, LOCK))) {
                    throw new IllegalStateException("W06 schema lock lost");
                }
            }
            return null;
        });
    }

    static String ddl() {
        try {
            String sql = new ClassPathResource(RESOURCE).getContentAsString(StandardCharsets.UTF_8);
            String statement = sql.lines().filter(line -> !line.stripLeading().startsWith("--"))
                    .reduce("", (left, right) -> left + right + '\n').strip();
            if (statement.chars().filter(ch -> ch == ';').count() != 1
                    || !statement.startsWith("CREATE TABLE IF NOT EXISTS " + TABLE + " (")) {
                throw new IllegalStateException("Invalid W06 additive DDL");
            }
            return statement.substring(0, statement.length() - 1);
        } catch (java.io.IOException failure) { throw new IllegalStateException("W06 DDL missing", failure); }
    }

    private static void validateCatalog(JdbcTemplate jdbc) {
        List<Map<String, Object>> table = jdbc.queryForList("SELECT engine,table_collation FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name=?", TABLE);
        if (table.size() != 1 || !"InnoDB".equalsIgnoreCase(text(table.getFirst(), "engine"))
                || !"utf8mb4_0900_bin".equalsIgnoreCase(text(table.getFirst(), "table_collation"))) fail("engine/collation");
        Map<String, String> expectedColumns = columns();
        List<Map<String, Object>> columns = jdbc.queryForList("SELECT column_name,column_type,is_nullable,"
                + "column_default,collation_name,extra FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position", TABLE);
        if (!new ArrayList<>(expectedColumns.keySet()).equals(columns.stream().map(row -> text(row, "column_name")).toList())) fail("columns");
        for (Map<String, Object> row : columns) {
            String name = text(row, "column_name");
            String type = expectedColumns.get(name);
            String collation = type.startsWith("varchar") || type.equals("text") ? "utf8mb4_0900_bin" : null;
            if (!type.equals(text(row, "column_type")) || !"NO".equals(text(row, "is_nullable"))
                    || row.get("column_default") != null || !java.util.Objects.equals(collation, text(row, "collation_name"))
                    || !(name.equals("id") ? "auto_increment" : "").equals(text(row, "extra"))) fail("column " + name);
        }
        Map<String, List<String>> indexes = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT index_name,column_name,non_unique,sub_part,"
                + "collation,index_type,is_visible FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name=? ORDER BY index_name,seq_in_index", TABLE)) {
            if (!"0".equals(text(row, "non_unique")) || row.get("sub_part") != null
                    || !"A".equals(text(row, "collation")) || !"BTREE".equals(text(row, "index_type"))
                    || !"YES".equals(text(row, "is_visible"))) fail("index properties");
            indexes.computeIfAbsent(text(row, "index_name"), ignored -> new ArrayList<>()).add(text(row, "column_name"));
        }
        if (!Map.of("PRIMARY", List.of("id"), "uk_bounty_settlement_task", List.of("tenant_id", "client_id", "task_id"),
                "uk_bounty_settlement_actor_key", List.of("tenant_id", "client_id", "principal_type", "principal_id", "idempotency_key"))
                .equals(indexes)) fail("indexes");
        Map<String, String> checks = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT tc.constraint_name,cc.check_clause,tc.enforced FROM information_schema.table_constraints tc
                JOIN information_schema.check_constraints cc ON cc.constraint_catalog=tc.constraint_catalog
                  AND cc.constraint_schema=tc.constraint_schema AND cc.constraint_name=tc.constraint_name
                WHERE tc.constraint_schema=DATABASE() AND tc.table_name=? AND tc.constraint_type='CHECK'
                """, TABLE)) {
            if (!"YES".equals(text(row, "enforced"))) fail("unenforced CHECK");
            if (checks.put(text(row, "constraint_name"), AgentTaskFundingSchemaInitializer.normalizeCheck(text(row, "check_clause"))) != null) fail("duplicate CHECK");
        }
        if (!checks().equals(checks)) fail("CHECK expressions");
        Integer foreignKeys = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.referential_constraints "
                + "WHERE (constraint_schema=DATABASE() AND table_name=?) "
                + "OR (unique_constraint_schema=DATABASE() AND referenced_table_name=?)", Integer.class, TABLE, TABLE);
        Integer triggers = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.triggers "
                + "WHERE trigger_schema=DATABASE() AND (event_object_table=? OR trigger_name LIKE '%bounty_settlement%')", Integer.class, TABLE);
        if (!Integer.valueOf(0).equals(foreignKeys) || !Integer.valueOf(0).equals(triggers)) fail("foreign keys/triggers");
    }

    private static Map<String, String> columns() {
        Map<String, String> columns = new LinkedHashMap<>();
        String[] names = {"id", "tenant_id", "client_id", "principal_type", "principal_id", "idempotency_key", "request_hash",
                "task_id", "quote_id", "agent_id", "escrow_id", "status", "gross_micro", "actual_compute_micro", "platform_fee_micro",
                "agent_payout_micro", "refunded_micro", "task_version", "funding_version", "escrow_version", "settled_at", "transaction_ids"};
        String[] types = {"bigint", "varchar(50)", "varchar(50)", "varchar(20)", "varchar(100)", "varbinary(36)", "binary(32)",
                "varchar(100)", "varchar(100)", "varchar(100)", "varchar(100)", "varchar(16)", "bigint", "bigint", "bigint",
                "bigint", "bigint", "bigint", "bigint", "bigint", "bigint", "text"};
        for (int i = 0; i < names.length; i++) columns.put(names[i], types[i]);
        return columns;
    }

    private static Map<String, String> checks() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("chk_bounty_settlement_identity", "principal_type='USER' AND OCTET_LENGTH(idempotency_key)=36 AND OCTET_LENGTH(request_hash)=32");
        result.put("chk_bounty_settlement_state", "status='SETTLED' AND task_version>0 AND funding_version>=2 AND escrow_version>1 AND settled_at>0 AND OCTET_LENGTH(transaction_ids)>2");
        result.put("chk_bounty_settlement_amount", "gross_micro>0 AND actual_compute_micro>=0 AND platform_fee_micro>=0 AND agent_payout_micro>=0 AND refunded_micro=0 AND gross_micro=actual_compute_micro + platform_fee_micro + agent_payout_micro");
        result.replaceAll((name, expression) -> AgentTaskFundingSchemaInitializer.normalizeCheck(expression));
        return result;
    }
    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }
    private static void fail(String part) { throw new IllegalStateException("Invalid W06 settlement catalog: " + part); }
}
