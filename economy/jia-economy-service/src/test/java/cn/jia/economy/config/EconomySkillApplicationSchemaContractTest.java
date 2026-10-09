package cn.jia.economy.config;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class EconomySkillApplicationSchemaContractTest {
    @Test void independentCatalogDoesNotRewriteW07OrW02Tables() {
        var tables=EconomySkillApplicationSchemaInitializer.expectedTables();
        assertEquals(6,tables.size());
        assertEquals(6,EconomySkillApplicationSchemaInitializer.tableDdlStatements().size());
        assertFalse(tables.containsKey("economy_account"));assertFalse(tables.containsKey("economy_skill_order"));
        assertTrue(tables.containsKey("economy_skill_agent_version"));assertTrue(tables.containsKey("economy_skill_result_receipt"));
    }
    @Test void runtimeOwnershipIsNullableStableAndIndependentOfProductInstallAndLegacyAudit() {
        var binding=EconomySkillApplicationSchemaInitializer.expectedTables().get("economy_skill_delivery_binding");
        var columns=binding.columns();
        for(String name:java.util.List.of("canonical_agent_id","runtime_installation_id","runtime_host_id",
                "api_key_id","registration_hash")) {
            assertTrue(columns.stream().anyMatch(c->name.equals(c.name()) && c.nullable()),name);
        }
        assertTrue(columns.stream().anyMatch(c->"installation_id".equals(c.name()) && !c.nullable()));
        assertTrue(columns.stream().anyMatch(c->"escrow_version".equals(c.name())));
        assertFalse(columns.stream().anyMatch(c->c.name().contains("generation")));
        assertEquals(java.util.List.of("tenant_id","client_id","installation_id"),
                binding.indexes().get("uk_skill_delivery_binding").columns());
    }
    @Test void ResultReceiptsAndDeliveryIdentityCannotBeOverwritten() {
        var triggers=EconomySkillApplicationSchemaInitializer.expectedTriggers();
        assertEquals(6,triggers.size());
        assertTrue(triggers.containsKey("trg_skill_app_result_no_update"));assertTrue(triggers.containsKey("trg_skill_app_binding_no_delete"));
    }
}
