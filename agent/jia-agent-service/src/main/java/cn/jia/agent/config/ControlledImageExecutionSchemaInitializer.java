package cn.jia.agent.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Additive controlled authority identity on existing conversation executions. */
public final class ControlledImageExecutionSchemaInitializer implements InitializingBean {
    /** Exact MySQL 8.0.21 CHECK_CLAUSE rendering captured from the additive v1_17 DDL. */
    private static final String EXPECTED_CHECK = "((`controlled_consent_id` is null) or ((`execution_mode` = _utf8mb4\'CONVERSATION\') and regexp_like(`controlled_consent_id`,cast(_utf8mb4\'^consent_[0-9a-f]{32}$\' as char charset binary)) and (`permitted_operation` = _utf8mb4\'GENERATE_IMAGE\') and (`output_content_mime_type` = _utf8mb4\'image/png\')))";
    private final JdbcTemplate jdbc;

    public ControlledImageExecutionSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void afterPropertiesSet() {
        Integer table = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name='agent_personal_workspace_execution'",
                Integer.class);
        if (!Objects.equals(table, 1)) {
            throw new IllegalStateException("Conversation execution schema unavailable");
        }
        Integer column = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name='agent_personal_workspace_execution' "
                + "AND column_name='controlled_consent_id'", Integer.class);
        if (Objects.equals(column, 0)) {
            jdbc.execute(ddl());
        } else if (!Objects.equals(column, 1)) {
            throw new IllegalStateException("Controlled execution schema partial");
        }

        List<Map<String, Object>> columns = jdbc.queryForList("SELECT column_name,column_type,"
                + "is_nullable,collation_name,column_default FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name='agent_personal_workspace_execution' "
                + "ORDER BY ordinal_position").stream()
                .filter(row -> ControlledImageFollowupV3SchemaInitializer.isOwnedExecutionColumn(
                        value(row, "column_name")))
                .toList();
        List<Map<String, Object>> indexes = jdbc.queryForList("SELECT index_name,non_unique,"
                + "seq_in_index,column_name,sub_part FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name='agent_personal_workspace_execution' "
                + "ORDER BY index_name,seq_in_index").stream()
                .filter(row -> ControlledImageFollowupV3SchemaInitializer.isOwnedExecutionIndex(
                        value(row, "index_name")))
                .toList();
        List<Map<String, Object>> checks = jdbc.queryForList("SELECT tc.constraint_name,"
                + "tc.enforced,cc.check_clause FROM information_schema.table_constraints tc JOIN "
                + "information_schema.check_constraints cc ON cc.constraint_catalog=tc.constraint_catalog "
                + "AND cc.constraint_schema=tc.constraint_schema "
                + "AND cc.constraint_name=tc.constraint_name WHERE tc.constraint_schema=DATABASE() "
                + "AND tc.table_name='agent_personal_workspace_execution' "
                + "AND tc.constraint_type='CHECK'").stream()
                .filter(row -> ControlledImageFollowupV3SchemaInitializer.isOwnedExecutionCheck(
                        value(row, "constraint_name")))
                .toList();
        ControlledImageFollowupV3SchemaInitializer.validateExecutionRestartCatalog(
                columns, indexes, checks);
    }

    static void validateCheck(List<Map<String, Object>> rows) {
        if (rows.size() != 1
                || !"YES".equalsIgnoreCase(Objects.toString(rows.getFirst().get("enforced"), ""))
                || !canonicalCheck(EXPECTED_CHECK).equals(canonicalCheck(Objects.toString(
                        rows.getFirst().get("check_clause"), "")))) {
            throw new IllegalStateException("Controlled execution CHECK drift");
        }
    }

    private static String value(Map<String, Object> row, String key) {
        return row.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .findFirst().map(Map.Entry::getValue).map(Object::toString).orElse("");
    }

    private static String canonicalCheck(String value) {
        try {
            return AgentTaskCreationOperationSchemaInitializer.canonicalCheckExpression(value);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalStateException("Controlled execution malformed CHECK catalog", malformed);
        }
    }

    static String ddl() {
        try {
            String source = new ClassPathResource(
                    "db/agent-personal-workspace-v1_17-controlled-image.sql")
                    .getContentAsString(StandardCharsets.UTF_8).trim();
            String normalized = source.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (!normalized.startsWith("alter table agent_personal_workspace_execution ")
                    || !normalized.contains("add column controlled_consent_id")
                    || !normalized.contains("add unique key uk_pwex_controlled_consent")
                    || !normalized.contains("add constraint chk_pwex_controlled_consent check")
                    || normalized.contains(" insert ") || normalized.contains(" update ")
                    || normalized.contains(" delete ") || normalized.contains(" drop ")
                    || !source.endsWith(";")
                    || source.substring(0, source.length() - 1).contains(";")) {
                throw new IllegalStateException("Unsafe controlled execution DDL");
            }
            return source.substring(0, source.length() - 1);
        } catch (Exception failure) {
            throw new IllegalStateException("Invalid controlled execution DDL", failure);
        }
    }
}
