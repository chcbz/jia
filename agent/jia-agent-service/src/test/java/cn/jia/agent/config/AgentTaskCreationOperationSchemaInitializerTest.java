package cn.jia.agent.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskCreationOperationSchemaInitializerTest {
    @Test
    void ddlIsOneAdditiveCreateWithBinaryScopeWithoutDatabaseChecks() {
        String ddl = AgentTaskCreationOperationSchemaInitializer.ddl();
        String normalized = ddl.replaceAll("(?m)^\\s*--.*$", " ")
                .replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        assertTrue(normalized.startsWith(
                "create table if not exists agent_task_creation_operation "));
        assertFalse(normalized.contains(";"));
        assertFalse(normalized.contains("check ("));
        for (String forbidden : new String[] {" alter table ", " drop ", " insert ",
                " update ", " delete ", " replace "}) {
            assertFalse((" " + normalized + " ").contains(forbidden), forbidden);
        }
        for (String required : new String[] {"engine=innodb", "utf8mb4_0900_bin",
                "uk_atco_scope_key", "uk_atco_scope_operation"}) {
            assertTrue(normalized.contains(required), required);
        }
    }

}
