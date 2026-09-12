package cn.jia.agent.schema;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskFundingSchemaContractTest {
    @Test
    void additiveSchemaOwnsExactlyTwoFundedTaskTablesAndStableReceiptColumns() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/agent-task-funding-v0.sql"),
                StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);

        assertEquals(2, sql.split("create table if not exists", -1).length - 1);
        assertTrue(sql.contains("create table if not exists agent_task_funding_operation"));
        assertTrue(sql.contains("create table if not exists agent_task_funding"));
        assertTrue(sql.contains("unique key uk_task_funding_operation_actor_key"));
        assertTrue(sql.contains("cancel_refunded_micro"));
        assertTrue(sql.contains("cancel_task_version"));
        assertTrue(sql.contains("check (octet_length(idempotency_key) = 36)"));
        assertFalse(sql.contains("create table if not exists economy_"));
        assertFalse(sql.contains("alter table"));
    }
}
