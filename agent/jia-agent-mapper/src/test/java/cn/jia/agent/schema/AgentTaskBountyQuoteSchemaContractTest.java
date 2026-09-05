package cn.jia.agent.schema;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskBountyQuoteSchemaContractTest {
    @Test
    void schemaIsAdditiveExactAndContainsClaimFences() throws Exception {
        try (InputStream stream = getClass().getClassLoader()
                .getResourceAsStream("db/agent-task-bounty-quote-v0.sql")) {
            assertTrue(stream != null);
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertEquals(2, sql.split("create table if not exists", -1).length - 1);
            assertTrue(sql.contains("uk_bounty_quote_actor_key"));
            assertTrue(sql.contains("uk_bounty_claim_actor_key"));
            assertTrue(sql.contains("uk_bounty_claim_quote"));
            assertTrue(sql.contains("request_hash binary(32)"));
            assertTrue(sql.contains("task_version bigint not null"));
            assertFalse(sql.contains("alter table agent_task_funding"));
            assertFalse(sql.contains("create table if not exists model_price_book"));
        }
    }
}
