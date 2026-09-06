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
    @Test void ResultReceiptsAndDeliveryIdentityCannotBeOverwritten() {
        var triggers=EconomySkillApplicationSchemaInitializer.expectedTriggers();
        assertEquals(6,triggers.size());
        assertTrue(triggers.containsKey("trg_skill_app_result_no_update"));assertTrue(triggers.containsKey("trg_skill_app_binding_no_delete"));
    }
}
