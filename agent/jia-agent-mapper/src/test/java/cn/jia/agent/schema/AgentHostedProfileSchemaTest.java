package cn.jia.agent.schema;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class AgentHostedProfileSchemaTest {
    @Test
    void durableHostedRowStoresOnlyApiKeyIdAndExactStateMachine() throws IOException {
        String sql = resource("db/hosted-persona-binding-schema.sql").toLowerCase();
        assertTrue(sql.contains("api_key_id"));
        assertFalse(sql.matches("(?s).*\\bapi_key\\s+varchar.*"));
        for (String state : new String[]{"prepared", "staged_disabled", "file_enabled", "active", "repair_required"}) {
            assertTrue(sql.contains("'" + state + "'"), state);
        }
        assertTrue(sql.contains("unique key uk_hosted_binding (binding_id)"));
        assertTrue(sql.contains("unique key uk_hosted_profile_key (profile_key)"));
        assertTrue(sql.contains("tenant_id,client_id,owner_jiacn,lifecycle_state"));
        assertTrue(sql.contains("engine=innodb default charset=utf8mb4 collate=utf8mb4_0900_bin"));
        assertTrue(sql.contains("chk_hosted_state"));
        assertTrue(sql.contains("chk_hosted_generation"));
        assertTrue(sql.contains("chk_hosted_repair"));
    }

    private String resource(String name) throws IOException {
        try (var in = getClass().getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
