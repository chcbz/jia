package cn.jia.chat.schema;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationDataDomainMigrationTest {
    @Test
    void migrationFailsBeforeAdmissionIndexesWhenIdentityPollutionExists() throws Exception {
        String sql = read("db/conversation-data-domain-migration.sql")
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");

        int backfill = sql.indexOf("update chat_message set data_domain_id = 'cyf'");
        int guard = sql.indexOf("create temporary table cyf_conversation_domain_migration_guard");
        int conversationCheck = sql.indexOf("add constraint ck_chat_conversation_data_domain");
        int admissionIndex = sql.indexOf("create index idx_chat_conversation_domain_owner");

        assertTrue(backfill >= 0, sql);
        assertTrue(guard > backfill, sql);
        assertTrue(conversationCheck > guard, sql);
        assertTrue(admissionIndex > conversationCheck, sql);
        assertTrue(sql.contains("constraint ck_cyf_conversation_domain_migration_guard check (guard_value = 1)"), sql);
        assertTrue(sql.contains("or m.tenant_id <> c.tenant_id"), sql);
        assertTrue(sql.contains("or binary m.tenant_id <> binary c.tenant_id"), sql);
        assertTrue(sql.contains("or m.data_domain_id <> c.data_domain_id"), sql);
        assertTrue(sql.contains("or binary m.data_domain_id <> binary c.data_domain_id"), sql);
        assertTrue(sql.contains("or m.jiacn <> c.jiacn"), sql);
        assertTrue(sql.contains("or binary m.jiacn <> binary c.jiacn"), sql);
    }

    private String read(String resource) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
