package cn.jia.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.List;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class AgentTaskExecutionGrantSchemaContractTest {
    @Test
    void sameNamedButNonUniqueActiveGuardIndexMustFailClosed() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.query(anyString(),any(RowMapper.class),eq("agent_task_execution_grant"),
                eq("uk_atxg_active_task"))).thenReturn(List.of(
                    new AgentTaskExecutionGrantSchemaInitializer.IndexColumn(1,1,"tenant_id",null,null),
                    new AgentTaskExecutionGrantSchemaInitializer.IndexColumn(1,2,"client_id",null,null),
                    new AgentTaskExecutionGrantSchemaInitializer.IndexColumn(1,3,"owner_jiacn",null,null),
                    new AgentTaskExecutionGrantSchemaInitializer.IndexColumn(1,4,"active_task_guard",null,null)));
        var schema=new AgentTaskExecutionGrantSchemaInitializer(jdbc);
        assertThrows(IllegalStateException.class,()->schema.validateIndex("uk_atxg_active_task",true,
                List.of("tenant_id","client_id","owner_jiacn","active_task_guard")));
    }


    @Test
    void ddlFreezesScopeIdempotencyActiveAssignmentAndNoExecutionSecrets() throws Exception {
        String ddl=new ClassPathResource("db/agent-task-execution-grant-v1.sql")
                .getContentAsString(StandardCharsets.UTF_8).toLowerCase();
        assertTrue(ddl.contains("unique key uk_atxg_scope_action (tenant_id,client_id,owner_jiacn,source_business_action_id)"));
        assertTrue(ddl.contains("unique key uk_atxg_active_task (tenant_id,client_id,owner_jiacn,active_task_guard)"));
        assertTrue(ddl.contains("generated always as"));
        assertTrue(ddl.contains("cost_authorization_ref"));
        assertTrue(ddl.contains("state in ('active','revoked','superseded')"));
        assertFalse(ddl.contains("storage_uri")); assertFalse(ddl.contains("lease_token"));
        assertFalse(ddl.contains("runtime_token")); assertFalse(ddl.contains("tool_command"));
        assertFalse(ddl.contains("provider_request"));
    }
}
