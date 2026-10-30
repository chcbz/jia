package cn.jia.agent.dao;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source DDL contract only. MySQL maintenance execution and repeat-run verification remain separate. */
class AgentPersonaSchemaScopeContractTest {
    @Test void freshPrivateCatalogHasScopedUniquenessAndNoDefaultBusinessTenant() throws Exception {
        String schema = resource("db/schema.sql");
        String persona = schema.substring(schema.indexOf("CREATE TABLE IF NOT EXISTS agent_persona ("),
                schema.indexOf("CREATE TABLE IF NOT EXISTS agent_runtime ("));
        assertFalse(persona.contains("DEFAULT '0'"));
        assertFalse(persona.contains("UNIQUE KEY uk_agent_persona_name (name)"));
        assertTrue(persona.contains("uk_agent_persona_scope_code (tenant_id, persona_code, catalog_client_key)"));
        assertTrue(persona.contains("uk_agent_persona_scope_name (tenant_id, name, catalog_client_key)"));
        assertTrue(persona.contains("chk_agent_persona_private_scope"));
        assertTrue(persona.contains("CHAR_LENGTH(client_id) > 0"));
    }

    @Test void explicitMaintenanceDoesNotInferOwnershipAndCreatesReplacementIndexesBeforeDroppingOldOnes() throws Exception {
        String sql = resource("db/agent-persona-private-scope-20261030.sql");
        assertFalse(sql.contains("UPDATE agent_persona"));
        assertFalse(sql.contains("DELETE FROM agent_persona"));
        assertTrue(sql.contains("SIGNAL SQLSTATE '45000'"));
        assertTrue(sql.contains("DROP PROCEDURE IF EXISTS cyf_persona_private_scope_20261030"));
        assertTrue(sql.indexOf("CREATE UNIQUE INDEX uk_agent_persona_scope_code")
                < sql.indexOf("DROP INDEX uk_agent_persona_code"));
        assertTrue(sql.indexOf("Persona scoped unique index shape mismatch")
                < sql.indexOf("DROP INDEX uk_agent_persona_code"));
    }

    private String resource(String name) throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(name)) {
            assertNotNull(input, name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
