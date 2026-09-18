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
        assertTrue(statements.get(0).contains(
                "CONSTRAINT chk_pws_file_origin CHECK (origin_kind IN ('USER_UPLOAD','AGENT_DELIVERY'))"));
        assertTrue(statements.get(1).startsWith("CREATE TABLE IF NOT EXISTS agent_personal_workspace_file_version ("));
        assertTrue(statements.get(2).startsWith("CREATE TABLE IF NOT EXISTS agent_personal_workspace_operation ("));
    }

    @Test
    void legacySchemaMigratesOnceAndValidatesSafeBackfill() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubTables(jdbc);
        when(jdbc.queryForList(columnsSql())).thenReturn(List.of(sourceColumn()), completeColumns());
        when(jdbc.queryForList(checksSql())).thenReturn(List.of(sourceCheck()), completeChecks());
        when(jdbc.queryForObject(legacyInvalidRowsSql(), eq(Integer.class))).thenReturn(0);
        when(jdbc.queryForObject(invalidRowsSql(), eq(Integer.class))).thenReturn(0);

        new PersonalWorkspaceSchemaInitializer(jdbc).initializeSchema();

        verify(jdbc).execute(PersonalWorkspaceSchemaInitializer.originMigrationStatement());
    }

    @Test
    void partialMigrationFailsClosedWithoutAttemptingRepair() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubTables(jdbc);
        when(jdbc.queryForList(columnsSql())).thenReturn(completeColumns(), completeColumns());
        when(jdbc.queryForList(checksSql())).thenReturn(List.of(sourceCheck()));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new PersonalWorkspaceSchemaInitializer(jdbc).initializeSchema());

        assertTrue(failure.getMessage().contains("CHECK is partial"));
        verify(jdbc, never()).execute(PersonalWorkspaceSchemaInitializer.originMigrationStatement());
    }

    @Test
    void failedAtomicMigrationAbortsStartupAndRemainsRetryableFromLegacyCatalog() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubTables(jdbc);
        when(jdbc.queryForList(columnsSql())).thenReturn(List.of(sourceColumn()));
        when(jdbc.queryForList(checksSql())).thenReturn(List.of(sourceCheck()));
        when(jdbc.queryForObject(legacyInvalidRowsSql(), eq(Integer.class))).thenReturn(0);
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
        when(jdbc.queryForList(columnsSql())).thenReturn(List.of(sourceColumn()));
        when(jdbc.queryForList(checksSql())).thenReturn(List.of());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new PersonalWorkspaceSchemaInitializer(jdbc).initializeSchema());

        assertTrue(failure.getMessage().contains("legacy provenance CHECK is partial"));
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

    private static String checksSql() {
        return """
                SELECT tc.constraint_name,cc.check_clause,tc.enforced
                  FROM information_schema.table_constraints tc
                  JOIN information_schema.check_constraints cc
                    ON cc.constraint_catalog=tc.constraint_catalog
                   AND cc.constraint_schema=tc.constraint_schema
                   AND cc.constraint_name=tc.constraint_name
                 WHERE tc.constraint_schema=DATABASE()
                   AND tc.table_name='agent_personal_workspace_file'
                   AND tc.constraint_type='CHECK'
                   AND tc.constraint_name IN ('chk_pws_file_source','chk_pws_file_origin')
                 ORDER BY tc.constraint_name
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

    private static List<Map<String, Object>> completeChecks() {
        return List.of(originCheck(), sourceCheck());
    }

    private static Map<String, Object> sourceCheck() {
        return check("chk_pws_file_source", "source_kind = 'UPLOAD'");
    }

    private static Map<String, Object> originCheck() {
        return check("chk_pws_file_origin", "origin_kind IN ('USER_UPLOAD','AGENT_DELIVERY')");
    }

    private static Map<String, Object> check(String name, String clause) {
        return Map.of("constraint_name", name, "check_clause", clause, "enforced", "YES");
    }
}
