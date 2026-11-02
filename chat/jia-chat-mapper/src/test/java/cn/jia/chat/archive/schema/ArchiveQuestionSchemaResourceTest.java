package cn.jia.chat.archive.schema;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveQuestionSchemaResourceTest {
    @Test
    void independentQuestionSchemaFreezesMutationEventOutboxAndFencingConstraints() throws Exception {
        String sql = read("db/archive-question-schema.sql").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        for (String table : new String[]{"archive_question", "archive_question_mutation",
                "archive_question_event", "archive_outbox"}) {
            assertTrue(sql.contains("create table " + table + " ("), table);
        }

        assertTrue(sql.contains("unique key uk_archive_question_event_sequence (tenant_id, client_id, owner_jiacn, question_id, sequence)"));
        assertTrue(sql.contains("fencing_token bigint not null"));
        assertTrue(sql.contains("published_sequence bigint not null"));

        assertFalse(sql.contains("案卷书吏"));

        assertTrue(sql.contains("idx_archive_question_owner_created (tenant_id, client_id, owner_jiacn, created_at, row_id)"));

        assertFalse(sql.contains("target_agent"));
        assertFalse(sql.contains("selected_agent"));
        assertFalse(sql.contains("drop table"));
        assertFalse(sql.contains("create table archive_idempotency"));
    }

    private String read(String resource) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
