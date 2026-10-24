package cn.jia.agent.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Installs only the two additive 1.11 C1-B conversation-link tables. */
@org.springframework.context.annotation.Profile("schema-maintenance")
public final class PersonalWorkspaceConversationLinkSchemaInitializer implements InitializingBean {
    static final String RESOURCE = "db/agent-personal-workspace-v1_13-conversation-links.sql";
    static final List<String> TABLES = List.of(
            "agent_personal_workspace_conversation_file_link",
            "agent_personal_workspace_conversation_link_operation");
    private static final Map<String, String> REQUIRED_TENANT_CHECKS = normalizedChecks(Map.of(
            "chk_pwcl_single_tenant", canonicalTenantCatalogCheck(),
            "chk_pwcl_operation_tenant", canonicalTenantCatalogCheck()));
    private final JdbcTemplate jdbc;
    private final boolean validateOnly;

    public PersonalWorkspaceConversationLinkSchemaInitializer(JdbcTemplate jdbc) {
        this(jdbc, false);
    }

    PersonalWorkspaceConversationLinkSchemaInitializer(JdbcTemplate jdbc, boolean validateOnly) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.validateOnly = validateOnly;
    }

    @Override
    public void afterPropertiesSet() {
        requireMySql();
        List<String> present = presentTables();
        if (present.isEmpty()) {
            SchemaValidationMode.requireMutationAllowed(validateOnly,
                    "Personal workspace conversation-link schema");
            ddlStatements().forEach(jdbc::execute);
        } else if (!present.equals(TABLES)) {
            throw new IllegalStateException("Personal workspace conversation-link schema is partial: " + present);
        }
        if (!presentTables().equals(TABLES)) {
            throw new IllegalStateException("Personal workspace conversation-link schema is unavailable");
        }
        validateTenantChecks(jdbc.queryForList("""
                SELECT tc.constraint_name,tc.enforced,cc.check_clause
                  FROM information_schema.table_constraints tc
                  JOIN information_schema.check_constraints cc
                    ON cc.constraint_catalog=tc.constraint_catalog
                   AND cc.constraint_schema=tc.constraint_schema
                   AND cc.constraint_name=tc.constraint_name
                 WHERE tc.table_schema=DATABASE()
                   AND tc.table_name IN
                     ('agent_personal_workspace_conversation_file_link',
                      'agent_personal_workspace_conversation_link_operation')
                   AND tc.constraint_type='CHECK'
                   AND tc.constraint_name IN
                     ('chk_pwcl_single_tenant','chk_pwcl_operation_tenant')
                """));
    }

    static List<String> ddlStatements() {
        final String source;
        try {
            source = new ClassPathResource(RESOURCE).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Personal workspace conversation-link DDL is missing", failure);
        }
        String uncommented = source.lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .reduce("", (left, right) -> left + right + '\n');
        List<String> statements = new ArrayList<>();
        for (String statement : uncommented.split(";")) {
            String normalized = statement.strip();
            if (!normalized.isEmpty()) statements.add(normalized);
        }
        if (statements.size() != TABLES.size()) {
            throw new IllegalStateException("Conversation-link DDL must contain exactly two statements");
        }
        for (int index = 0; index < TABLES.size(); index++) {
            String lower = statements.get(index).toLowerCase(Locale.ROOT);
            if (!lower.startsWith("create table if not exists " + TABLES.get(index) + " ")
                    || lower.contains(" alter table ") || lower.contains(" insert ")
                    || lower.contains(" update ") || lower.contains(" delete ")
                    || lower.contains(" drop ") || lower.contains(" create trigger ")) {
                throw new IllegalStateException("Unsafe personal workspace conversation-link DDL");
            }
        }
        return List.copyOf(statements);
    }

    static void validateTenantChecks(List<Map<String, Object>> rows) {
        Map<String, String> actual = new TreeMap<>();
        for (Map<String, Object> row : rows) {
            String name = Objects.toString(value(row, "constraint_name"), "");
            String clause = Objects.toString(value(row, "check_clause"), "");
            if (!"YES".equalsIgnoreCase(Objects.toString(value(row, "enforced"), ""))
                    || name.isBlank()
                    || actual.put(name, AgentTaskFundingSchemaInitializer.normalizeCheck(clause))
                            != null) {
                throw new IllegalStateException(
                        "Personal workspace conversation-link tenant CHECK catalog is invalid");
            }
        }
        if (!actual.equals(REQUIRED_TENANT_CHECKS)) {
            throw new IllegalStateException(
                    "Personal workspace conversation-link tenant CHECK constraints are invalid");
        }
    }

    private static String canonicalTenantCatalogCheck() {
        return "tenant_id IS NOT NULL AND regexp_like(tenant_id, "
                + "cast('^(0|[1-9][0-9]{0,49})$' AS char charset binary)) "
                + "AND NOT(regexp_like(tenant_id,cast('[^0-9]' AS char charset binary))) "
                + "AND instr(tenant_id,char(10))=0 AND instr(tenant_id,char(13))=0";
    }

    private static Map<String, String> normalizedChecks(Map<String, String> checks) {
        Map<String, String> normalized = new TreeMap<>();
        checks.forEach((name, clause) -> normalized.put(
                name, AgentTaskFundingSchemaInitializer.normalizeCheck(clause)));
        return Map.copyOf(normalized);
    }

    private static Object value(Map<String, Object> row, String key) {
        return row.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .findFirst().map(Map.Entry::getValue).orElse(null);
    }

    private List<String> presentTables() {
        return jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema=DATABASE() AND table_name IN
                   ('agent_personal_workspace_conversation_file_link',
                    'agent_personal_workspace_conversation_link_operation')
                 ORDER BY FIELD(table_name,
                    'agent_personal_workspace_conversation_file_link',
                    'agent_personal_workspace_conversation_link_operation')
                """, String.class);
    }

    private void requireMySql() {
        DataSource source = jdbc.getDataSource();
        if (source == null) throw new IllegalStateException("Conversation-link schema requires JDBC");
        try (Connection connection = source.getConnection()) {
            String database = connection.getMetaData().getDatabaseProductName();
            if (database == null || !database.toLowerCase(Locale.ROOT).contains("mysql")) {
                throw new IllegalStateException("Conversation-link schema requires MySQL");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Conversation-link database discovery failed", failure);
        }
    }
}
