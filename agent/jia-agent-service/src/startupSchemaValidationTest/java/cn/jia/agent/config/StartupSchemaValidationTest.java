package cn.jia.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StartupSchemaValidationTest {
    @Test
    void modeIsExactAndUnknownValuesFailClosed() {
        assertTrue(SchemaValidationMode.isValidateOnly("validate"));
        assertFalse(SchemaValidationMode.isValidateOnly("install"));
        assertThrows(IllegalStateException.class,
                () -> SchemaValidationMode.isValidateOnly("migrate-and-hope"));
    }

    @Test
    void allStorageInitializersRejectMissingCatalogBeforeMutation() throws Exception {
        JdbcTemplate jdbc = mysqlJdbc();

        assertReadOnlyFailure(jdbc, () -> new PersonalWorkspaceSchemaInitializer(jdbc, true).afterPropertiesSet());
        assertReadOnlyFailure(jdbc, () -> new PersonalWorkspaceExecutionSchemaInitializer(jdbc, true).afterPropertiesSet());
        assertReadOnlyFailure(jdbc, () -> new PersonalWorkspaceConversationLinkSchemaInitializer(jdbc, true).afterPropertiesSet());
        assertReadOnlyFailure(jdbc, () -> new PersonalWorkspaceTaskLinkSchemaInitializer(jdbc, true).afterPropertiesSet());
        assertReadOnlyFailure(jdbc, () -> new HallRequestDraftSchemaInitializer(jdbc, true).afterPropertiesSet());
        assertReadOnlyFailure(jdbc, () -> new HallPrivateCaseSchemaInitializer(jdbc, true).afterPropertiesSet());
        assertReadOnlyFailure(jdbc, () -> new HallPrivateMarkSchemaInitializer(jdbc, true).afterPropertiesSet());
        assertReadOnlyFailure(jdbc, () -> new AgentTaskExecutionGrantSchemaInitializer(jdbc, true).afterPropertiesSet());
        assertReadOnlyFailure(jdbc, () -> new AgentTaskBountyBootstrapOutboxSchemaInitializer(jdbc, true).afterPropertiesSet());
        assertReadOnlyFailure(jdbc, () -> new AgentTaskRequirementSnapshotSchemaInitializer(jdbc, true).afterPropertiesSet());
        assertReadOnlyFailure(jdbc, () -> new AgentTaskCreationOperationSchemaInitializer(jdbc, true).afterPropertiesSet());
    }

    @Test
    void runtimeV1AndFenceRejectMissingCatalogBeforeMutation() throws Exception {
        JdbcTemplate jdbc = mysqlJdbc();
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Integer.class),
                org.mockito.ArgumentMatchers.any())).thenReturn(0);

        assertReadOnlyFailure(jdbc, () -> new AgentRuntimeV1SchemaInitializer(jdbc, true).afterPropertiesSet());
        assertReadOnlyFailure(jdbc,
                () -> new AgentRuntimeSessionFenceSchemaInitializer(jdbc, "validate").afterPropertiesSet());
    }

    @Test
    void compatibleFenceCatalogIsActuallyReadAndAcceptedWithoutMutation() throws Exception {
        JdbcTemplate jdbc = mysqlJdbc();
        when(jdbc.queryForList(anyString(), org.mockito.ArgumentMatchers.<Object>any()))
                .thenAnswer(invocation -> java.util.List.of(java.util.Map.of(
                        "DATA_TYPE", "runtime_session_generation".equals(invocation.getArgument(1))
                                ? "bigint" : "varchar",
                        "IS_NULLABLE", "YES",
                        "CHARACTER_MAXIMUM_LENGTH", 100L)));

        assertDoesNotThrow(() ->
                new AgentRuntimeSessionFenceSchemaInitializer(jdbc, "validate").afterPropertiesSet());

        verify(jdbc, org.mockito.Mockito.times(4)).queryForList(anyString(),
                org.mockito.ArgumentMatchers.<Object>any());
        verify(jdbc, never()).execute(anyString());
        verify(jdbc, never()).update(anyString());
    }

    @Test
    void compatibleRuntimeV1CatalogIsFullyValidatedWithoutMutation() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(contains("engine,table_collation"), eq("agent_runtime_v1_installation")))
                .thenReturn(java.util.Map.of("engine", "InnoDB",
                        "table_collation", "utf8mb4_0900_bin"));
        when(jdbc.queryForList(contains("SELECT column_name,column_type"),
                eq("agent_runtime_v1_installation"))).thenReturn(java.util.List.of(
                        column("id", "bigint", "NO", null, "auto_increment"),
                        column("installation_id", "varchar(100)", "NO", "utf8mb4_0900_bin", ""),
                        column("canonical_agent_id", "varchar(100)", "NO", "utf8mb4_0900_bin", ""),
                        column("manifest_version", "varchar(100)", "NO", "utf8mb4_0900_bin", ""),
                        column("manifest_sha256", "varchar(72)", "NO", "utf8mb4_0900_bin", ""),
                        column("enrollment_secret_hash", "binary(32)", "NO", null, ""),
                        column("enrollment_expires_at", "bigint", "NO", null, ""),
                        column("enrollment_consumed_at", "bigint", "YES", null, ""),
                        column("runtime_authorization_hash", "binary(32)", "YES", null, ""),
                        column("runtime_authorization_issued_at", "bigint", "YES", null, ""),
                        column("status", "varchar(32)", "NO", "utf8mb4_0900_bin", ""),
                        column("last_heartbeat_at", "bigint", "YES", null, ""),
                        column("version", "bigint", "NO", null, ""),
                        column("tenant_id", "varchar(50)", "NO", "utf8mb4_0900_bin", ""),
                        column("client_id", "varchar(50)", "NO", "utf8mb4_0900_bin", ""),
                        column("create_time", "bigint", "YES", null, ""),
                        column("update_time", "bigint", "YES", null, "")));
        when(jdbc.queryForList(contains("information_schema.statistics"),
                eq("agent_runtime_v1_installation"))).thenReturn(java.util.List.of(
                        index("PRIMARY", 0, 1, "id"),
                        index("idx_runtime_v1_identity", 1, 1, "tenant_id"),
                        index("idx_runtime_v1_identity", 1, 2, "client_id"),
                        index("idx_runtime_v1_identity", 1, 3, "canonical_agent_id"),
                        index("idx_runtime_v1_identity", 1, 4, "status"),
                        index("idx_runtime_v1_scope", 1, 1, "tenant_id"),
                        index("idx_runtime_v1_scope", 1, 2, "client_id"),
                        index("idx_runtime_v1_scope", 1, 3, "installation_id"),
                        index("uk_runtime_v1_authorization", 0, 1, "runtime_authorization_hash"),
                        index("uk_runtime_v1_installation", 0, 1, "installation_id")));
        when(jdbc.queryForList(contains("information_schema.check_constraints"),
                eq("agent_runtime_v1_installation"))).thenReturn(java.util.List.of(java.util.Map.of(
                        "constraint_name", "chk_runtime_v1_status", "enforced", "YES",
                        "check_clause", "(`status` in (_utf8mb4'PENDING',_utf8mb4'ACTIVE',"
                                + "_utf8mb4'REVOKED',_utf8mb4'REBINDS_REQUIRED'))")));

        assertDoesNotThrow(() -> new AgentRuntimeV1SchemaInitializer(jdbc, true).validateCatalog());
        verify(jdbc, never()).execute(anyString());
        verify(jdbc, never()).update(anyString());
    }

    private static java.util.Map<String, Object> index(String name, int nonUnique,
            int sequence, String column) {
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("index_name", name);
        row.put("non_unique", nonUnique);
        row.put("seq_in_index", sequence);
        row.put("column_name", column);
        row.put("sub_part", null);
        return row;
    }

    private static java.util.Map<String, Object> column(String name, String type,
            String nullable, String collation, String extra) {
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("column_name", name);
        row.put("column_type", type);
        row.put("is_nullable", nullable);
        row.put("collation_name", collation);
        row.put("extra", extra);
        return row;
    }

    private static void assertReadOnlyFailure(JdbcTemplate jdbc, ThrowingAction action) {
        assertThrows(IllegalStateException.class, action::run);
        verify(jdbc, never()).execute(anyString());
        verify(jdbc, never()).update(anyString());
    }

    private static JdbcTemplate mysqlJdbc() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(jdbc.getDataSource()).thenReturn(source);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        when(metadata.getDatabaseMajorVersion()).thenReturn(8);
        return jdbc;
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
