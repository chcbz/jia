package cn.jia.chat.archive.schema;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveReaderDataSchemaResourceTest {
    @Test
    void additiveSchemaFreezesExactOwnerCasTombstoneAndIdempotencyConstraints() throws Exception {
        String sql = read("db/archive-reader-data-schema.sql").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        for (String table : new String[]{"archive_reader_progress", "archive_bookmark",
                "archive_note", "archive_idempotency"}) {
            assertTrue(sql.contains("create table " + table + " ("), table);
        }
        assertTrue(sql.contains("unique key uk_archive_progress_owner_edition (tenant_id, client_id, owner_jiacn, edition_id)"));
        assertTrue(sql.contains("unique key uk_archive_bookmark_owner_id (tenant_id, client_id, owner_jiacn, bookmark_id)"));
        assertTrue(sql.contains("unique key uk_archive_note_owner_id (tenant_id, client_id, owner_jiacn, note_id)"));
        assertTrue(sql.contains("unique key uk_archive_idempotency_scope (tenant_id, client_id, owner_jiacn, http_method, canonical_path, idempotency_key)"));
        assertTrue(sql.contains("constraint fk_archive_note_block foreign key (edition_id, block_id) references archive_chapter (edition_id, block_id)"));
        assertTrue(sql.contains("constraint chk_archive_note_text_bytes check (text is null or octet_length(text) <= 20000)"));
        assertTrue(sql.contains("constraint chk_archive_idempotency_key check (idempotency_key regexp '^[!-~]{1,128}$')"));
        assertTrue(sql.contains("state = 'deleted' and text is null and block_id is null and anchor_json is null"));
        assertFalse(sql.contains("drop table"));
        assertFalse(sql.contains("create table archive_work"));
    }

    private String read(String resource) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
