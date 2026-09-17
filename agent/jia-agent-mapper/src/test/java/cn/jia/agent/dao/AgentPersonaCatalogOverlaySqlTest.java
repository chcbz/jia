package cn.jia.agent.dao;

import cn.jia.agent.mapper.AgentPersonaBindingMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the actual catalog SELECT against an isolated in-memory fixture; no production DB. */
class AgentPersonaCatalogOverlaySqlTest {
    @Test
    void foreignBindingIsOccupiedWithoutDisclosingIdentityRuntimeOrReferenceFlag() throws Exception {
        try (Connection c = fixture(); Statement s = c.createStatement();
             ResultSet rows = s.executeQuery(sql("Bob"))) {
            assertTrue(rows.next());
            assertEquals("linchong", rows.getString("persona_code"));
            assertEquals("0", rows.getString("binding_tenant_id"));
            assertEquals("Alice", rows.getString("binding_owner_jiacn"));
            assertEquals(1, rows.getInt("binding_status"));
            for (String field : new String[]{"binding_id", "binding_agent_id", "identity_id",
                    "identity_binding_id", "identity_tenant_id", "identity_client_id",
                    "identity_owner_jiacn", "canonical_agent_id", "canonical_type", "lifecycle_status",
                    "agent_reference_valid", "runtime_id", "runtime_tenant_id", "runtime_client_id",
                    "runtime_owner_jiacn", "runtime_binding_id", "runtime_agent_id", "runtime_abilities", "runtime_status"}) {
                assertNull(rows.getObject(field), field + " must be SQL NULL for a foreign owner");
            }
            assertFalse(rows.next());
        }
    }

    @Test
    void exactOwnerRetainsVerifiedIdentityAndRuntimeProjection() throws Exception {
        try (Connection c = fixture(); Statement s = c.createStatement();
             ResultSet rows = s.executeQuery(sql("Alice"))) {
            assertTrue(rows.next());
            assertEquals(1, rows.getLong("binding_id"));
            assertEquals("agent-alice", rows.getString("binding_agent_id"));
            assertEquals(1, rows.getLong("identity_id"));
            assertEquals("agent-alice", rows.getString("canonical_agent_id"));
            assertTrue(rows.getBoolean("agent_reference_valid"));
            assertEquals(1, rows.getLong("runtime_id"));
            assertEquals("online", rows.getString("runtime_status"));
            assertFalse(rows.next());
        }
    }

    @Test
    void foreignTenantOrClientCannotSeeTheBinding() throws Exception {
        try (Connection c = fixture(); Statement s = c.createStatement()) {
            for (String query : new String[]{sql("Alice").replace("'client-a'", "'client-b'"),
                    sql("Alice").replace("'0'", "'1'")}) {
                try (ResultSet rows = s.executeQuery(query)) { assertFalse(rows.next()); }
            }
        }
    }

    private String sql(String owner) throws Exception {
        String query = String.join(" ", AgentPersonaBindingMapper.class.getMethod(
                "findCatalogOverlay", String.class, String.class, String.class, int.class)
                .getAnnotation(Select.class).value());
        return query.replace("#{tenantId}", "'0'").replace("#{clientId}", "'client-a'")
                .replace("#{ownerJiacn}", "'" + owner + "'").replace("#{activeStatus}", "1");
    }

    private Connection fixture() throws Exception {
        Connection c = DriverManager.getConnection("jdbc:h2:mem:catalog_" + UUID.randomUUID() + ";MODE=MySQL");
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE agent_persona (persona_code VARCHAR(50), tenant_id VARCHAR(50), client_id VARCHAR(50), active INT)");
            s.execute("CREATE TABLE agent_persona_binding (id BIGINT, tenant_id VARCHAR(50), client_id VARCHAR(50), owner_jiacn VARCHAR(50), persona_code VARCHAR(50), agent_id VARCHAR(100), status INT)");
            s.execute("CREATE TABLE agent_identity_registry (id BIGINT, binding_id BIGINT, tenant_id VARCHAR(50), client_id VARCHAR(50), owner_jiacn VARCHAR(50), canonical_agent_id VARCHAR(100), canonical_type VARCHAR(50), lifecycle_status VARCHAR(50))");
            s.execute("CREATE TABLE agent_runtime (id BIGINT, binding_id BIGINT, tenant_id VARCHAR(50), client_id VARCHAR(50), owner_jiacn VARCHAR(50), agent_id VARCHAR(100), abilities VARCHAR(100), status VARCHAR(50))");
            s.execute("CREATE TABLE agent_identity_alias (registry_id BIGINT, canonical_agent_id VARCHAR(100), alias_value VARCHAR(100), alias_type VARCHAR(50), alias_status VARCHAR(50), valid_to BIGINT, tenant_id VARCHAR(50), client_id VARCHAR(50), owner_jiacn VARCHAR(50))");
            s.execute("INSERT INTO agent_persona VALUES ('linchong','0',NULL,1)");
            s.execute("INSERT INTO agent_persona_binding VALUES (1,'0','client-a','Alice','linchong','agent-alice',1)");
            s.execute("INSERT INTO agent_identity_registry VALUES (1,1,'0','client-a','Alice','agent-alice','LEGACY_CANONICAL','ACTIVE')");
            s.execute("INSERT INTO agent_runtime VALUES (1,1,'0','client-a','Alice','agent-alice','[\"private-skill\"]','online')");
        }
        return c;
    }
}
