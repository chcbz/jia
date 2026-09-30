package cn.jia.agent.config;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskCreationOperationSchemaInitializerTest {
    @Test
    void ddlIsOneAdditiveCreateWithBinaryScopeAndEnforcedReceiptConstraints() {
        String ddl = AgentTaskCreationOperationSchemaInitializer.ddl();
        String normalized = ddl.replaceAll("(?m)^\\s*--.*$", " ")
                .replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        assertTrue(normalized.startsWith(
                "create table if not exists agent_task_creation_operation "));
        assertFalse(normalized.contains(";"));
        for (String forbidden : new String[] {" alter table ", " drop ", " insert ",
                " update ", " delete ", " replace "}) {
            assertFalse((" " + normalized + " ").contains(forbidden), forbidden);
        }
        for (String required : new String[] {"engine=innodb", "utf8mb4_0900_bin",
                "uk_atco_scope_key", "uk_atco_scope_operation", "chk_atco_scope",
                "chk_atco_identity", "chk_atco_hash", "chk_atco_refs",
                "chk_atco_receipt", "chk_atco_time", "requirement_revision=1",
                "json_length(input_refs_json) between 0 and 32"}) {
            assertTrue(normalized.contains(required), required);
        }
    }
}
