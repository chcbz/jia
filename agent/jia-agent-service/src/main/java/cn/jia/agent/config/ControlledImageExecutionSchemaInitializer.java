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
@org.springframework.context.annotation.Profile("schema-maintenance")
public final class ControlledImageExecutionSchemaInitializer implements InitializingBean {

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
        ControlledImageFollowupV3SchemaInitializer.validateExecutionRestartCatalog(
                columns, indexes);
    }

    private static String value(Map<String, Object> row, String key) {
        return row.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .findFirst().map(Map.Entry::getValue).map(Object::toString).orElse("");
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
