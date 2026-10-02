package cn.jia.agent.platform;

import cn.jia.agent.config.AgentSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises actual fresh CREATE paths, not a pre-created collaboration schema. */
@EnabledIfEnvironmentVariable(named = "CYF_AAM_SCHEMA_TEST_MYSQL_URL", matches = ".+")
class ArchiveSharedSchemaBootstrapMySqlTest {
    private static final List<String> TABLES = List.of(
            "agent_task_member", "agent_task_work_item", "agent_task_request", "agent_task_artifact");
    private static final List<IndexContract> OWNER_SCOPED_INDEXES = List.of(
            index("agent_task_member", "uk_task_member_scope", true,
                    "tenant_id", "client_id", "owner_jiacn", "task_id", "agent_id"),
            index("agent_task_member", "idx_task_member_agent_status", false,
                    "tenant_id", "client_id", "owner_jiacn", "agent_id", "member_status"),
            index("agent_task_member", "idx_task_member_task_status", false,
                    "tenant_id", "client_id", "owner_jiacn", "task_id", "member_status"),
            index("agent_task_member", "idx_task_member_task_role", false,
                    "tenant_id", "client_id", "owner_jiacn", "task_id", "member_role", "member_status"),
            index("agent_task_work_item", "uk_work_item_scope", true,
                    "tenant_id", "client_id", "owner_jiacn", "work_item_id"),
            index("agent_task_work_item", "idx_work_item_task_status", false,
                    "tenant_id", "client_id", "owner_jiacn", "task_id", "status", "priority"),
            index("agent_task_work_item", "idx_work_item_assignee_status", false,
                    "tenant_id", "client_id", "owner_jiacn", "assignee_agent_id", "status", "lease_until"),
            index("agent_task_work_item", "idx_work_item_task_required", false,
                    "tenant_id", "client_id", "owner_jiacn", "task_id", "required_item", "status"),
            index("agent_task_request", "uk_task_request_scope", true,
                    "tenant_id", "client_id", "owner_jiacn", "request_id"),
            index("agent_task_request", "idx_task_request_task_status", false,
                    "tenant_id", "client_id", "owner_jiacn", "task_id", "status", "priority", "create_time"),
            index("agent_task_request", "idx_task_request_target_status", false,
                    "tenant_id", "client_id", "owner_jiacn", "target_type", "target_id", "status", "due_at"),
            index("agent_task_request", "idx_task_request_work_item", false,
                    "tenant_id", "client_id", "owner_jiacn", "work_item_id", "status"),
            index("agent_task_artifact", "uk_artifact_version", true,
                    "tenant_id", "client_id", "owner_jiacn", "artifact_id", "artifact_version"),
            index("agent_task_artifact", "idx_artifact_task_created", false,
                    "tenant_id", "client_id", "owner_jiacn", "task_id", "created_at"),
            index("agent_task_artifact", "idx_artifact_work_item", false,
                    "tenant_id", "client_id", "owner_jiacn", "work_item_id", "artifact_type", "created_at"),
            index("agent_task_artifact", "idx_artifact_producer", false,
                    "tenant_id", "client_id", "owner_jiacn", "producer_agent_id", "created_at"),
            index("agent_task_artifact", "idx_artifact_hash", false,
                    "tenant_id", "client_id", "owner_jiacn", "content_hash"));
    private JdbcTemplate jdbc;

    @BeforeEach
    void prepareExactDisposableSchema() throws Exception {
        String url = System.getenv("CYF_AAM_SCHEMA_TEST_MYSQL_URL");
        assertEquals("jdbc:mysql://127.0.0.1:34061/aam_runtime_schema_test", url,
                "Only the dedicated local schema owned by this test may be changed");
        DriverManagerDataSource source = new DriverManagerDataSource(url,
                System.getenv("CYF_AAM_SCHEMA_TEST_MYSQL_USER"),
                System.getenv("CYF_AAM_SCHEMA_TEST_MYSQL_PASSWORD"));
        jdbc = new JdbcTemplate(source);
        assertEquals("aam_runtime_schema_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertEquals(34061, jdbc.queryForObject("SELECT @@port", Integer.class));
        for (String table : TABLES) jdbc.execute("DROP TABLE IF EXISTS " + table);
        ClassPathResource schema = new ClassPathResource("db/schema.sql");
        String sql = schema.getContentAsString(StandardCharsets.UTF_8);
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS agent_identity_registry"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS agent_task_meta"));
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, schema);
        }
        // Force the production initializer, rather than db/schema.sql, to create these roots.
        for (String table : TABLES) jdbc.execute("DROP TABLE " + table);
    }

    @Test
    void freshCollaborationRootsMatchOwnerScopedIndexesAndRepeatStably() {
        initialize();
        initialize();
        assertEquals(17, OWNER_SCOPED_INDEXES.size());
        OWNER_SCOPED_INDEXES.forEach(expected -> {
            assertEquals(expected.columns(), columns(expected.table(), expected.name()), expected.label());
            assertEquals(List.of(expected.unique() ? 0 : 1),
                    nonUniqueFlags(expected.table(), expected.name()), expected.label());
        });
    }

    @Test
    void preExistingWrongOwnerScopeStillFailsClosedWithoutRewritingTheIndex() {
        initialize();
        jdbc.execute("ALTER TABLE agent_task_member DROP INDEX uk_task_member_scope, "
                + "ADD UNIQUE INDEX uk_task_member_scope (tenant_id, client_id, task_id, agent_id)");
        IllegalStateException failure = assertThrows(IllegalStateException.class, this::initialize);
        assertTrue(failure.getMessage().contains("agent_task_member.uk_task_member_scope"));
        assertEquals(List.of("tenant_id", "client_id", "task_id", "agent_id"),
                columns("agent_task_member", "uk_task_member_scope"));
    }

    @Test
    void freshUniqueMemberRootSeparatesOwnersButRejectsSameOwnerDuplicates() {
        initialize();
        String insert = "INSERT INTO agent_task_member "
                + "(task_id,owner_jiacn,agent_id,member_role,tenant_id,client_id) "
                + "VALUES ('fixture-task',?,'fixture-agent','worker','0','fixture-client')";
        jdbc.update(insert, "owner-a");
        jdbc.update(insert, "owner-b");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, "owner-a"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_member", Integer.class));
    }

    private void initialize() {
        ReflectionTestUtils.invokeMethod(new AgentSchemaInitializer(jdbc),
                "ensureTaskCollaborationSchema");
    }

    private static IndexContract index(String table, String name, boolean unique, String... columns) {
        return new IndexContract(table, name, unique, List.of(columns));
    }

    private List<String> columns(String table, String name) {
        return jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND INDEX_NAME=? ORDER BY SEQ_IN_INDEX",
                String.class, table, name);
    }

    private List<Integer> nonUniqueFlags(String table, String name) {
        return jdbc.queryForList("SELECT DISTINCT NON_UNIQUE FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND INDEX_NAME=? ORDER BY NON_UNIQUE",
                Integer.class, table, name);
    }

    private record IndexContract(String table, String name, boolean unique, List<String> columns) {
        String label() {
            return table + "." + name;
        }
    }
}
