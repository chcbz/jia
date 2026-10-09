package cn.jia.agent.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Creates only an absent grant table; partial or drifted authorization catalogs fail closed. */
public final class AgentTaskExecutionGrantSchemaInitializer implements InitializingBean {
    static final String RESOURCE = "db/agent-task-execution-grant-v1.sql";
    private static final String TABLE = "agent_task_execution_grant";
    private static final Set<String> REQUIRED_COLUMNS = Set.of(
            "id","grant_id","tenant_id","client_id","owner_jiacn","task_id",
            "requirement_revision","assignment_revision","target_agent_id",
            "permitted_operations_json","permitted_tool_policy_ref","input_scope_json",
            "allow_own_task_derived_assets","cost_authorization_ref","source_business_action_id",
            "idempotency_key","request_hash","policy_revision","grant_version","state","issued_by",
            "created_at","revoked_at","revoke_idempotency_key","revoke_request_hash",
            "active_task_guard","create_time","update_time");
    private static final Set<String> REQUIRED_INDEXES = Set.of("PRIMARY","uk_atxg_scope_grant",
            "uk_atxg_scope_action","uk_atxg_active_task","idx_atxg_task_state");
    private static final Set<String> REQUIRED_CHECKS = Set.of("chk_atxg_tenant","chk_atxg_revision",
            "chk_atxg_state","chk_atxg_derived","chk_atxg_revoke","chk_atxg_time");
    private final JdbcTemplate jdbc;
    public AgentTaskExecutionGrantSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc,"jdbc");
    }
    @Override public void afterPropertiesSet() {
        requireMySql();
        Integer present = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?", Integer.class, TABLE);
        if (present == null || present == 0) jdbc.execute(ddl());
        validate();
    }
    static String ddl() {
        try {
            String source = new ClassPathResource(RESOURCE).getContentAsString(StandardCharsets.UTF_8).trim();
            String normalized = source.toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");
            if (!normalized.contains("create table if not exists " + TABLE)
                    || normalized.contains(" alter table ") || normalized.contains(" insert ")
                    || normalized.contains(" update ") || normalized.contains(" delete ")
                    || normalized.contains(" drop ") || normalized.contains(" create trigger ")) {
                throw new IllegalStateException("Unsafe task execution grant DDL");
            }
            return source.endsWith(";") ? source.substring(0,source.length()-1) : source;
        } catch (Exception failure) {
            throw new IllegalStateException("Task execution grant DDL is missing or invalid", failure);
        }
    }
    void validate() {
        String engine = jdbc.queryForObject("SELECT engine FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?", String.class, TABLE);
        if (!"InnoDB".equalsIgnoreCase(engine)) throw new IllegalStateException("Task grant table must be InnoDB");
        Set<String> columns = Set.copyOf(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=?", String.class, TABLE));
        if (!columns.containsAll(REQUIRED_COLUMNS)) throw new IllegalStateException("Task grant columns are partial or drifted");
        Set<String> indexes = Set.copyOf(jdbc.queryForList("SELECT DISTINCT index_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=?", String.class, TABLE));
        if (!indexes.containsAll(REQUIRED_INDEXES)) throw new IllegalStateException("Task grant indexes are partial or drifted");
        validateIndex("PRIMARY", true, List.of("id"));
        validateIndex("uk_atxg_scope_grant", true,
                List.of("tenant_id","client_id","owner_jiacn","grant_id"));
        validateIndex("uk_atxg_scope_action", true,
                List.of("tenant_id","client_id","owner_jiacn","source_business_action_id"));
        validateIndex("uk_atxg_active_task", true,
                List.of("tenant_id","client_id","owner_jiacn","active_task_guard"));
        validateIndex("idx_atxg_task_state", false,
                List.of("tenant_id","client_id","owner_jiacn","task_id","state"));
        Set<String> checks = Set.copyOf(jdbc.queryForList("SELECT constraint_name FROM information_schema.table_constraints WHERE table_schema=DATABASE() AND table_name=? AND constraint_type='CHECK'", String.class, TABLE));
        if (!checks.containsAll(REQUIRED_CHECKS)) throw new IllegalStateException("Task grant checks are partial or drifted");
        List<Map<String,Object>> identity = jdbc.queryForList("SELECT column_name,is_nullable,collation_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name IN ('grant_id','tenant_id','client_id','owner_jiacn','task_id','target_agent_id','source_business_action_id','idempotency_key')", TABLE);
        if (identity.size() != 8 || identity.stream().anyMatch(row -> !"NO".equalsIgnoreCase(Objects.toString(row.get("is_nullable"),"")) || !"utf8mb4_0900_bin".equalsIgnoreCase(Objects.toString(row.get("collation_name"),"")))) {
            throw new IllegalStateException("Task grant identity columns are not byte-exact");
        }
    }
    /** A matching name alone is not a uniqueness guarantee: inspect the actual ordered key. */
    void validateIndex(String name, boolean unique, List<String> expectedColumns) {
        List<IndexColumn> parts = jdbc.query("""
                SELECT non_unique,seq_in_index,column_name,sub_part,expression
                  FROM information_schema.statistics
                 WHERE table_schema=DATABASE() AND table_name=? AND index_name=?
                 ORDER BY seq_in_index
                """, (rs, row) -> new IndexColumn(rs.getInt("non_unique"),
                rs.getInt("seq_in_index"), rs.getString("column_name"),
                rs.getObject("sub_part"), rs.getString("expression")), TABLE, name);
        if (parts.size() != expectedColumns.size()) throw new IllegalStateException("Task grant index structure drift: " + name);
        for (int i=0;i<parts.size();i++) {
            IndexColumn part=parts.get(i);
            if (part.nonUnique() != (unique ? 0 : 1) || part.sequence() != i+1
                    || !expectedColumns.get(i).equalsIgnoreCase(part.column())
                    || part.prefix() != null || part.expression() != null) {
                throw new IllegalStateException("Task grant index structure drift: " + name);
            }
        }
    }
    record IndexColumn(int nonUnique,int sequence,String column,Object prefix,String expression) {}

    private void requireMySql() {
        DataSource source=jdbc.getDataSource();
        if (source==null) throw new IllegalStateException("Task grant schema requires JDBC");
        try (Connection connection=source.getConnection()) {
            String name=connection.getMetaData().getDatabaseProductName();
            if (name==null || !name.toLowerCase(Locale.ROOT).contains("mysql")) throw new IllegalStateException("Task grant schema requires MySQL");
        } catch (Exception failure) { throw new IllegalStateException("Task grant database discovery failed",failure); }
    }
}
