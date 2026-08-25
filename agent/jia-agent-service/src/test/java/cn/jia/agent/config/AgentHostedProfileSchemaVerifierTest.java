package cn.jia.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentHostedProfileSchemaVerifierTest {
    @Test
    void existingHostedTableWithNonTransactionalEngineFailsClosed() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("SELECT ENGINE"), eq(String.class))).thenReturn("MyISAM");
        assertThrows(IllegalStateException.class,
                () -> new AgentSchemaInitializer(jdbc).validateHostedProfileSchema());
        verify(jdbc, never()).execute(startsWith("ALTER TABLE agent_hosted_profile"));
    }
}
