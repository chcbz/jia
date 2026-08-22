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
        assertTrue(sql.contains("http_method in ('put', 'post')"));
        assertTrue(sql.contains("state in ('pending', 'completed')"));
        assertTrue(sql.contains("unique key uk_archive_question_event_sequence (tenant_id, client_id, owner_jiacn, question_id, sequence)"));
        assertTrue(sql.contains("fencing_token bigint not null"));
        assertTrue(sql.contains("published_sequence bigint not null"));
        assertTrue(sql.contains("responder_id = 'archive-clerk-v1'"));
        assertTrue(sql.contains("responder_name = '案卷书吏'"));
        assertTrue(sql.contains("octet_length(selected_text) between 1 and 8192"));
        assertTrue(sql.contains("attempt_count between 0 and 3"));
        assertTrue(sql.contains("idx_archive_question_owner_created (tenant_id, client_id, owner_jiacn, created_at, row_id)"));
        assertTrue(sql.contains("canonical_path regexp '^[!-~]{1,512}$'"));
        assertTrue(sql.contains("json_valid(payload_json)"));
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
