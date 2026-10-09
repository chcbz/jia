package cn.jia.agent.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentRuntimeSessionFenceSchemaInitializerTest {
    @Test void exactlyFourNullableAdditionsWithoutDataOrKeyMutation() throws Exception {
        var statements = AgentRuntimeSessionFenceSchemaInitializer.statements();
        assertEquals(4, statements.size());
        for (int i = 0; i < 4; i++) {
            assertTrue(statements.get(i).startsWith("ALTER TABLE agent_runtime ADD COLUMN "
                    + AgentRuntimeSessionFenceSchemaInitializer.COLUMNS.get(i)));
            assertTrue(statements.get(i).endsWith(" NULL"));
            assertFalse(statements.get(i).contains("NOT NULL"));
            assertFalse(statements.get(i).contains("DEFAULT"));
            assertFalse(statements.get(i).contains("DROP"));
            assertFalse(statements.get(i).contains("UPDATE"));
        }
    }
    private org.springframework.jdbc.core.JdbcTemplate jdbc() throws Exception {
        var jdbc = org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var source = org.mockito.Mockito.mock(javax.sql.DataSource.class);
        var connection = org.mockito.Mockito.mock(java.sql.Connection.class);
        var metadata = org.mockito.Mockito.mock(java.sql.DatabaseMetaData.class);
        org.mockito.Mockito.when(jdbc.getDataSource()).thenReturn(source);
        org.mockito.Mockito.when(source.getConnection()).thenReturn(connection);
        org.mockito.Mockito.when(connection.getMetaData()).thenReturn(metadata);
        org.mockito.Mockito.when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        return jdbc;
    }
    @Test void compatibleRestartDoesNotExecuteDdlOrBackfill() throws Exception {
        var jdbc = jdbc();
        org.mockito.Mockito.when(jdbc.queryForList(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<Object>any()))
                .thenAnswer(inv -> java.util.List.of(java.util.Map.of("DATA_TYPE",
                        "runtime_session_generation".equals(inv.getArgument(1)) ? "bigint" : "varchar",
                        "IS_NULLABLE", "YES", "CHARACTER_MAXIMUM_LENGTH", 100L)));
        new AgentRuntimeSessionFenceSchemaInitializer(jdbc).afterPropertiesSet();
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.never()).execute(org.mockito.ArgumentMatchers.anyString());
    }
    @Test void nonNullableExistingColumnIsPermanentSchemaErrorNotReadiness() throws Exception {
        var jdbc = jdbc();
        org.mockito.Mockito.when(jdbc.queryForList(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<Object>any()))
                .thenReturn(java.util.List.of(java.util.Map.of("DATA_TYPE", "varchar", "IS_NULLABLE", "NO", "CHARACTER_MAXIMUM_LENGTH", 100L)));
        assertThrows(IllegalStateException.class, () -> new AgentRuntimeSessionFenceSchemaInitializer(jdbc).afterPropertiesSet());
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.never()).execute(org.mockito.ArgumentMatchers.anyString());
    }

}
