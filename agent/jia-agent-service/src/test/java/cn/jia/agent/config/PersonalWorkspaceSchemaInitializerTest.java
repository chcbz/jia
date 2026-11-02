package cn.jia.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersonalWorkspaceSchemaInitializerTest {
    @Test
    void freshSchemaPreservesLegacySourceAndAddsExplicitOrigin() {
        var statements = PersonalWorkspaceSchemaInitializer.ddlStatements();
        assertEquals(3, statements.size());
        assertTrue(statements.get(0).startsWith("CREATE TABLE IF NOT EXISTS agent_personal_workspace_file ("));
        assertTrue(statements.get(0).contains("source_kind VARCHAR(24) NOT NULL"));
        assertTrue(statements.get(0).contains("origin_kind VARCHAR(24) NOT NULL DEFAULT 'USER_UPLOAD'"));
        org.junit.jupiter.api.Assertions.assertFalse(statements.get(0).toLowerCase().contains("check ("));
        assertTrue(statements.get(1).startsWith("CREATE TABLE IF NOT EXISTS agent_personal_workspace_file_version ("));
        assertTrue(statements.get(2).startsWith("CREATE TABLE IF NOT EXISTS agent_personal_workspace_operation ("));
    }

    @Test
    void legacySchemaMigratesOnceAndValidatesSafeBackfill() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubTables(jdbc);
        when(jdbc.queryForList(columnsSql())).thenReturn(List.of(sourceColumn()), completeColumns());
        when(jdbc.queryForObject(eq(legacyInvalidRowsSql()), eq(Integer.class))).thenReturn(0);
        when(jdbc.queryForObject(eq(invalidRowsSql()), eq(Integer.class))).thenReturn(0);

        new PersonalWorkspaceSchemaInitializer(jdbc).initializeSchema();

        verify(jdbc).execute(PersonalWorkspaceSchemaInitializer.originMigrationStatement());
    }

    @Test
    void partialMigrationFailsClosedWithoutAttemptingRepair() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubTables(jdbc);
        when(jdbc.queryForList(columnsSql())).thenReturn(List.of());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new PersonalWorkspaceSchemaInitializer(jdbc).initializeSchema());

        assertTrue(failure.getMessage().contains("columns are partial"));
        verify(jdbc, never()).execute(PersonalWorkspaceSchemaInitializer.originMigrationStatement());
    }

    @Test
    void failedAtomicMigrationAbortsStartupAndRemainsRetryableFromLegacyCatalog() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubTables(jdbc);
        when(jdbc.queryForList(columnsSql())).thenReturn(List.of(sourceColumn()));
        when(jdbc.queryForObject(eq(legacyInvalidRowsSql()), eq(Integer.class))).thenReturn(0);
        String migration = PersonalWorkspaceSchemaInitializer.originMigrationStatement();
        doThrow(new DataAccessResourceFailureException("migration failed")).when(jdbc).execute(migration);

        assertThrows(DataAccessResourceFailureException.class,
                () -> new PersonalWorkspaceSchemaInitializer(jdbc).initializeSchema());
        verify(jdbc).execute(migration);
    }

    @Test
    void malformedLegacyCatalogFailsBeforeMigration() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubTables(jdbc);
        when(jdbc.queryForList(columnsSql())).thenReturn(List.of(column("source_kind", "UNEXPECTED")));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new PersonalWorkspaceSchemaInitializer(jdbc).initializeSchema());

        assertTrue(failure.getMessage().contains("provenance column is invalid"));
        verify(jdbc, never()).execute(PersonalWorkspaceSchemaInitializer.originMigrationStatement());
    }

    private static void stubTables(JdbcTemplate jdbc) {
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenReturn(PersonalWorkspaceSchemaInitializer.TABLES,
                        PersonalWorkspaceSchemaInitializer.TABLES);
    }

    private static String columnsSql() {
        return """
                SELECT column_name,column_type,is_nullable,column_default,collation_name
                  FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='agent_personal_workspace_file'
                   AND column_name IN ('source_kind','origin_kind')
                 ORDER BY FIELD(column_name,'source_kind','origin_kind')
                """;
    }

    private static String legacyInvalidRowsSql() {
        return """
                SELECT COUNT(*) FROM agent_personal_workspace_file
                 WHERE source_kind IS NULL OR source_kind <> 'UPLOAD'
                """;
    }

    private static String invalidRowsSql() {
        return """
                SELECT COUNT(*) FROM agent_personal_workspace_file
                 WHERE source_kind IS NULL OR source_kind <> 'UPLOAD'
                    OR origin_kind IS NULL
                    OR origin_kind NOT IN ('USER_UPLOAD','AGENT_DELIVERY')
                """;
    }

    private static List<Map<String, Object>> completeColumns() {
        return List.of(sourceColumn(), column("origin_kind", "USER_UPLOAD"));
    }

    private static Map<String, Object> sourceColumn() {
        return column("source_kind", null);
    }

    private static Map<String, Object> column(String name, String defaultValue) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("column_name", name);
        row.put("column_type", "varchar(24)");
        row.put("is_nullable", "NO");
        row.put("column_default", defaultValue);
        row.put("collation_name", "utf8mb4_0900_bin");
        return row;
    }

}
