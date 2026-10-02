package cn.jia.agent.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Only creates an absent table; rejects drift rather than rewriting existing identity/history. */
public final class AgentTaskRequirementSnapshotSchemaInitializer implements InitializingBean {
    private static final String TABLE = "agent_task_requirement_snapshot";
    private static final Map<String, String> CHECKS = Map.of(
            "chk_atrs_scope",
            "((`tenant_id` = _utf8mb4\\'0\\') and (`owner_jiacn` <> _utf8mb4\\'0\\'))",
            "chk_atrs_content",
            "((`revision` >= 1) and (`task_version_at_confirmation` >= 0) and "
                    + "(char_length(`title`) > 0) and regexp_like(`content_sha256`,"
                    + "_utf8mb4\\'^[0-9a-f]{64}$\\') and (`source` in "
                    + "(_utf8mb4\\'CREATE\\',_utf8mb4\\'RECONFIRM\\')) and (`created_at` > 0))");
    private final JdbcTemplate jdbc;

    public AgentTaskRequirementSnapshotSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override public void afterPropertiesSet() {
        try (var c = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            if (!c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql")
                    || c.getMetaData().getDatabaseMajorVersion()<8)
                throw new IllegalStateException("Requirement snapshot requires MySQL 8");
        } catch (Exception e) { throw new IllegalStateException("Requirement snapshot database unavailable", e); }
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name=?", Integer.class, TABLE);
        if (count == null || count == 0) jdbc.execute(ddl());
        validate();
    }

    static String ddl() {
        try {
            String source = new ClassPathResource("db/agent-task-requirement-snapshot-v1.sql")
                    .getContentAsString(StandardCharsets.UTF_8).trim();
            String normalized = source.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (!normalized.contains("create table if not exists " + TABLE)
                    || normalized.contains(" alter table ") || normalized.contains(" drop ")
                    || normalized.contains(" insert ") || normalized.contains(" update "))
                throw new IllegalStateException("Unsafe snapshot DDL");
            return source.endsWith(";") ? source.substring(0, source.length()-1) : source;
        } catch (Exception e) { throw new IllegalStateException("Invalid snapshot DDL", e); }
    }

    void validate() {
        Map<String,Object> table = jdbc.queryForMap("SELECT engine,table_collation FROM "
                + "information_schema.tables WHERE table_schema=DATABASE() AND table_name=?", TABLE);
        if (!"InnoDB".equalsIgnoreCase(Objects.toString(table.get("engine"), ""))
                || !"utf8mb4_0900_bin".equalsIgnoreCase(Objects.toString(table.get("table_collation"), "")))
            throw new IllegalStateException("Snapshot table engine/collation drift");
        var columns = jdbc.queryForList("SELECT column_name,column_type,is_nullable,collation_name "
                + "FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? "
                + "ORDER BY ordinal_position", TABLE);
        if (!columns.stream().map(r -> Objects.toString(r.get("column_name"), "")).toList()
                .equals(List.of("id","tenant_id","client_id","owner_jiacn","task_id",
                        "revision","confirmation_id","task_version_at_confirmation","title","description","content_sha256","source","created_at")))
            throw new IllegalStateException("Snapshot columns drift");
        for (var r : columns) {
            String name = Objects.toString(r.get("column_name"), "");
            if (List.of("tenant_id","client_id","owner_jiacn","task_id","confirmation_id","title","description","source").contains(name)
                    && !"utf8mb4_0900_bin".equalsIgnoreCase(Objects.toString(r.get("collation_name"), "")))
                throw new IllegalStateException("Snapshot text collation drift: " + name);
            if ((name.equals("title") || name.equals("description"))
                    && !"mediumtext".equalsIgnoreCase(Objects.toString(r.get("column_type"), "")))
                throw new IllegalStateException("Snapshot text width drift: " + name);
            if ((!name.equals("description")) && !"NO".equalsIgnoreCase(Objects.toString(r.get("is_nullable"), "")))
                throw new IllegalStateException("Snapshot nullability drift: " + name);
        }
        List<Map<String,Object>> indexes = jdbc.queryForList("SELECT index_name,non_unique,seq_in_index,column_name,sub_part "
                + "FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? "
                + "ORDER BY index_name,seq_in_index", TABLE);
        index(indexes,"PRIMARY",0,List.of("id"));
        index(indexes,"uk_atrs_scope_revision",0,List.of("tenant_id","client_id","owner_jiacn","task_id","revision"));
        index(indexes,"uk_atrs_confirmation",0,List.of("tenant_id","client_id","owner_jiacn","task_id","confirmation_id"));
        index(indexes,"idx_atrs_task_latest",1,List.of("tenant_id","client_id","owner_jiacn","task_id","revision","id"));
        if (indexes.size()!=17) throw new IllegalStateException("Snapshot extra index drift");
        var checks = jdbc.queryForList("SELECT tc.constraint_name,tc.enforced,cc.check_clause "
                + "FROM information_schema.table_constraints tc JOIN information_schema.check_constraints cc "
                + "ON cc.constraint_catalog=tc.constraint_catalog AND cc.constraint_schema=tc.constraint_schema "
                + "AND cc.constraint_name=tc.constraint_name WHERE tc.constraint_schema=DATABASE() "
                + "AND tc.table_name=? AND tc.constraint_type='CHECK'", TABLE);
        if (checks.size()!=CHECKS.size()) throw new IllegalStateException("Snapshot CHECK drift");
        for (Map.Entry<String, String> expected : CHECKS.entrySet()) {
            var row=checks.stream().filter(r -> expected.getKey().equals(r.get("constraint_name"))).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Snapshot CHECK absent: "+expected.getKey()));
            if (!"YES".equalsIgnoreCase(Objects.toString(row.get("enforced"), ""))
                    || !expected.getValue().equals(Objects.toString(row.get("check_clause"), "")))
                throw new IllegalStateException("Snapshot CHECK drift: "+expected.getKey());
        }
    }
    static void index(List<Map<String,Object>> rows,String name,int nonUnique,List<String> expected) {
        var matching=rows.stream().filter(r -> name.equals(r.get("index_name"))).toList();
        if (matching.size()!=expected.size()) throw new IllegalStateException("Snapshot index drift: "+name);
        for (int i=0;i<expected.size();i++) {
            var r=matching.get(i);
            if (!expected.get(i).equals(r.get("column_name"))
                    || ((Number)r.get("seq_in_index")).intValue()!=i+1
                    || ((Number)r.get("non_unique")).intValue()!=nonUnique || r.get("sub_part")!=null)
                throw new IllegalStateException("Snapshot index drift: "+name);
        }
    }
}
