package cn.jia.agent.config;

import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

class AgentSchemaInitializerTest extends BaseMockTest {
    @Mock
    JdbcTemplate jdbcTemplate;

    @Test
    void runCreatesTaskNoteTableIfMissing() {
        AgentSchemaInitializer initializer = new AgentSchemaInitializer(jdbcTemplate);

        initializer.run(null);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).execute(sqlCaptor.capture());
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS agent_task_note"));
        assertTrue(sql.contains("idx_agent_task_note_task_id"));
    }
}
