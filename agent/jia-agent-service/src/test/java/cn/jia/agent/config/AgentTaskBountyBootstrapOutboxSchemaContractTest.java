package cn.jia.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentTaskBountyBootstrapOutboxSchemaContractTest {
    @Test
    void ddlFreezesOwnerActionUniquenessClaimOrderAndSafePayload() throws Exception {
        String ddl = new ClassPathResource(
                "db/agent-task-bounty-bootstrap-outbox-v1.sql")
                .getContentAsString(StandardCharsets.UTF_8).toLowerCase();
        assertTrue(ddl.contains("unique key uk_atbbo_scope_action "
                + "(tenant_id,client_id,owner_jiacn,source_business_action_id)"));
        assertTrue(ddl.contains("key idx_atbbo_scope_claim "
                + "(tenant_id,client_id,owner_jiacn,status,next_retry_at,lease_until,id)"));
        assertTrue(ddl.contains("grant_version bigint not null"));
        assertTrue(ddl.contains("permitted_operation varchar(40)"));
        assertTrue(ddl.contains("requirement_anchor varchar(50)"));
        assertTrue(ddl.contains("reference_summary_json json not null"));
        assertTrue(ddl.contains("status in ('pending','claimed','retry','admitted','dead')"));
        assertFalse(ddl.contains("delivered"));
        assertFalse(ddl.contains("access_token"));
        assertFalse(ddl.contains("storage_uri"));
        assertFalse(ddl.contains("localhost"));
        assertFalse(ddl.contains("material_body"));
        assertFalse(ddl.contains("wire_payload"));
        assertFalse(ddl.contains("provider_request"));
    }

    @Test
    void sameNamedButNonUniqueActionIndexFailsClosed() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class),
                eq("agent_task_bounty_bootstrap_outbox"), eq("uk_atbbo_scope_action")))
                .thenReturn(List.of(
                        part(1,1,"tenant_id"), part(1,2,"client_id"),
                        part(1,3,"owner_jiacn"), part(1,4,"source_business_action_id")));
        var schema = new AgentTaskBountyBootstrapOutboxSchemaInitializer(jdbc);
        assertThrows(IllegalStateException.class, () -> schema.validateIndex(
                "uk_atbbo_scope_action", true,
                List.of("tenant_id","client_id","owner_jiacn","source_business_action_id")));
    }

    @Test
    void sameNamedClaimIndexWithWrongColumnOrderFailsClosed() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class),
                eq("agent_task_bounty_bootstrap_outbox"), eq("idx_atbbo_scope_claim")))
                .thenReturn(List.of(
                        part(1,1,"tenant_id"), part(1,2,"client_id"),
                        part(1,3,"owner_jiacn"), part(1,4,"status"),
                        part(1,5,"lease_until"), part(1,6,"next_retry_at"),
                        part(1,7,"id")));
        var schema = new AgentTaskBountyBootstrapOutboxSchemaInitializer(jdbc);
        assertThrows(IllegalStateException.class, () -> schema.validateIndex(
                "idx_atbbo_scope_claim", false,
                List.of("tenant_id","client_id","owner_jiacn","status",
                        "next_retry_at","lease_until","id")));
    }

    private static AgentTaskBountyBootstrapOutboxSchemaInitializer.IndexColumn part(
            int nonUnique, int sequence, String column) {
        return new AgentTaskBountyBootstrapOutboxSchemaInitializer.IndexColumn(
                nonUnique, sequence, column, null, null, "BTREE", "YES");
    }
}
