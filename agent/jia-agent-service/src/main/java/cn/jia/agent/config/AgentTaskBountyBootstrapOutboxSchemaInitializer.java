package cn.jia.agent.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Creates only an absent bootstrap table; any partial or drifted catalog fails closed. */
public final class AgentTaskBountyBootstrapOutboxSchemaInitializer implements InitializingBean {
    static final String RESOURCE = "db/agent-task-bounty-bootstrap-outbox-v1.sql";
    static final String TABLE = "agent_task_bounty_bootstrap_outbox";
    private static final List<String> REQUIRED_COLUMNS = List.of(
            "id","bootstrap_id","tenant_id","client_id","owner_jiacn","task_id",
            "source_business_action_id","payload_hash","requirement_revision",
            "requirement_anchor","assignment_revision","target_agent_id","grant_id",
            "grant_version","permitted_operation","reference_summary_json",
            "reference_summary_sha256","status","attempt_count","next_retry_at",
            "lease_owner","lease_until","admitted_conversation_id","admitted_request_id",
            "last_error_code","version","created_at","reconciled_at","create_time","update_time");
    private static final Map<String,List<String>> REQUIRED_INDEXES = Map.of(
            "PRIMARY", List.of("id"),
            "uk_atbbo_scope_bootstrap", List.of("tenant_id","client_id","owner_jiacn","bootstrap_id"),
            "uk_atbbo_scope_action", List.of("tenant_id","client_id","owner_jiacn","source_business_action_id"),
            "idx_atbbo_scope_claim", List.of("tenant_id","client_id","owner_jiacn","status","next_retry_at","lease_until","id"),
            "idx_atbbo_scope_task", List.of("tenant_id","client_id","owner_jiacn","task_id","assignment_revision"));
    private static final Set<String> UNIQUE_INDEXES = Set.of(
            "PRIMARY","uk_atbbo_scope_bootstrap","uk_atbbo_scope_action");
    private static final Set<String> REQUIRED_CHECKS = Set.of(
            "chk_atbbo_tenant","chk_atbbo_revision","chk_atbbo_hash",
            "chk_atbbo_references","chk_atbbo_state","chk_atbbo_lifecycle","chk_atbbo_time");

    private final JdbcTemplate jdbc;

    public AgentTaskBountyBootstrapOutboxSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void afterPropertiesSet() {
        requireMySql();
        Integer present = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name=?", Integer.class, TABLE);
        if (present == null || present == 0) jdbc.execute(ddl());
        validate();
    }

    static String ddl() {
        try {
            String source = new ClassPathResource(RESOURCE)
                    .getContentAsString(StandardCharsets.UTF_8).trim();
            String normalized = source.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (!normalized.contains("create table if not exists " + TABLE)
                    || normalized.contains(" alter table ") || normalized.contains(" insert ")
                    || normalized.contains(" update ") || normalized.contains(" delete ")
                    || normalized.contains(" drop ") || normalized.contains(" create trigger ")) {
                throw new IllegalStateException("Unsafe bounty bootstrap outbox DDL");
            }
            return source.endsWith(";") ? source.substring(0, source.length() - 1) : source;
        } catch (Exception failure) {
            throw new IllegalStateException("Bounty bootstrap outbox DDL is missing or invalid", failure);
        }
    }

    void validate() {
        Map<String,Object> table = jdbc.queryForMap("SELECT engine,table_collation FROM "
                + "information_schema.tables WHERE table_schema=DATABASE() AND table_name=?", TABLE);
        if (!"InnoDB".equalsIgnoreCase(Objects.toString(table.get("engine"), ""))
                || !"utf8mb4_0900_bin".equalsIgnoreCase(
                Objects.toString(table.get("table_collation"), ""))) {
            throw new IllegalStateException("Bounty bootstrap table engine/collation drift");
        }
        List<Map<String,Object>> columnRows = jdbc.queryForList("SELECT column_name,is_nullable,"
                + "collation_name FROM information_schema.columns WHERE table_schema=DATABASE() "
                + "AND table_name=? ORDER BY ordinal_position", TABLE);
        if (!REQUIRED_COLUMNS.equals(columnRows.stream()
                .map(row -> Objects.toString(row.get("column_name"), "")).toList())) {
            throw new IllegalStateException("Bounty bootstrap columns are partial or drifted");
        }
        Set<String> byteExact = Set.of("bootstrap_id","tenant_id","client_id","owner_jiacn",
                "task_id","source_business_action_id","requirement_anchor","target_agent_id",
                "grant_id","permitted_operation");
        for (Map<String,Object> row : columnRows) {
            String name = Objects.toString(row.get("column_name"), "");
            if (byteExact.contains(name) && (!"NO".equalsIgnoreCase(
                    Objects.toString(row.get("is_nullable"), ""))
                    || !"utf8mb4_0900_bin".equalsIgnoreCase(
                    Objects.toString(row.get("collation_name"), "")))) {
                throw new IllegalStateException("Bounty bootstrap identity column drift: " + name);
            }
        }
        Set<String> actualIndexes = Set.copyOf(jdbc.queryForList("SELECT DISTINCT index_name FROM "
                + "information_schema.statistics WHERE table_schema=DATABASE() AND table_name=?",
                String.class, TABLE));
        if (!actualIndexes.equals(REQUIRED_INDEXES.keySet())) {
            throw new IllegalStateException("Bounty bootstrap indexes are partial or drifted");
        }
        REQUIRED_INDEXES.forEach((name, columns) ->
                validateIndex(name, UNIQUE_INDEXES.contains(name), columns));

        Map<String,String> checks = new LinkedHashMap<>();
        for (Map<String,Object> row : jdbc.queryForList("""
                SELECT tc.constraint_name,cc.check_clause,tc.enforced
                  FROM information_schema.table_constraints tc
                  JOIN information_schema.check_constraints cc
                    ON cc.constraint_catalog=tc.constraint_catalog
                   AND cc.constraint_schema=tc.constraint_schema
                   AND cc.constraint_name=tc.constraint_name
                 WHERE tc.constraint_schema=DATABASE() AND tc.table_name=?
                   AND tc.constraint_type='CHECK'
                 ORDER BY tc.constraint_name
                """, TABLE)) {
            String name = Objects.toString(row.get("constraint_name"), "");
            if (!"YES".equalsIgnoreCase(Objects.toString(row.get("enforced"), ""))
                    || checks.put(name, Objects.toString(row.get("check_clause"), "")) != null) {
                throw new IllegalStateException("Bounty bootstrap CHECK is ambiguous or unenforced");
            }
        }
        if (!checks.keySet().equals(REQUIRED_CHECKS)) {
            throw new IllegalStateException("Bounty bootstrap checks are partial or drifted");
        }
        requireCheck(checks, "chk_atbbo_tenant", "tenant_id", "='0'");
        requireCheck(checks, "chk_atbbo_revision", "grant_version", "attempt_count", "version");
        requireCheck(checks, "chk_atbbo_hash", "payload_hash", "reference_summary_sha256");
        requireCheck(checks, "chk_atbbo_references", "json_type", "reference_summary_json", "array");
        requireCheck(checks, "chk_atbbo_state", "pending", "claimed", "retry", "admitted", "dead");
        requireCheck(checks, "chk_atbbo_lifecycle", "admitted_conversation_id",
                "admitted_request_id", "lease_owner", "reconciled_at");
        requireCheck(checks, "chk_atbbo_time", "created_at", "lease_until", "reconciled_at");
    }

    void validateIndex(String name, boolean unique, List<String> expectedColumns) {
        List<IndexColumn> parts = jdbc.query("""
                SELECT non_unique,seq_in_index,column_name,sub_part,expression,index_type,is_visible
                  FROM information_schema.statistics
                 WHERE table_schema=DATABASE() AND table_name=? AND index_name=?
                 ORDER BY seq_in_index
                """, (rs, row) -> new IndexColumn(rs.getInt("non_unique"),
                rs.getInt("seq_in_index"), rs.getString("column_name"),
                rs.getObject("sub_part"), rs.getString("expression"),
                rs.getString("index_type"), rs.getString("is_visible")), TABLE, name);
        if (parts.size() != expectedColumns.size()) {
            throw new IllegalStateException("Bounty bootstrap index structure drift: " + name);
        }
        for (int index = 0; index < parts.size(); index++) {
            IndexColumn part = parts.get(index);
            if (part.nonUnique() != (unique ? 0 : 1) || part.sequence() != index + 1
                    || !expectedColumns.get(index).equalsIgnoreCase(part.column())
                    || part.prefix() != null || part.expression() != null
                    || !"BTREE".equalsIgnoreCase(part.indexType())
                    || !"YES".equalsIgnoreCase(part.visible())) {
                throw new IllegalStateException("Bounty bootstrap index structure drift: " + name);
            }
        }
    }

    record IndexColumn(int nonUnique, int sequence, String column, Object prefix,
            String expression, String indexType, String visible) { }

    private static void requireCheck(Map<String,String> checks, String name, String... fragments) {
        String normalized = AgentTaskFundingSchemaInitializer.normalizeCheck(checks.get(name));
        for (String fragment : fragments) {
            if (!normalized.contains(fragment.toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("Bounty bootstrap CHECK expression drift: " + name);
            }
        }
    }

    private void requireMySql() {
        DataSource source = jdbc.getDataSource();
        if (source == null) throw new IllegalStateException("Bounty bootstrap schema requires JDBC");
        try (Connection connection = source.getConnection()) {
            String name = connection.getMetaData().getDatabaseProductName();
            if (name == null || !name.toLowerCase(Locale.ROOT).contains("mysql")) {
                throw new IllegalStateException("Bounty bootstrap schema requires MySQL");
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Bounty bootstrap database discovery failed", failure);
        }
    }
}
