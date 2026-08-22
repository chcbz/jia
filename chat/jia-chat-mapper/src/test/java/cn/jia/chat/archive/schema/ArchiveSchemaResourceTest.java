package cn.jia.chat.archive.schema;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveSchemaResourceTest {
    @Test
    void immutableArchiveSchemaFreezesBinaryColumnsKeysAndChecks() throws Exception {
        String sql = read("db/archive-schema.sql").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");

        for (String table : new String[]{"archive_work", "archive_edition", "archive_chapter", "archive_paragraph"}) {
            assertTrue(sql.contains("create table " + table + " ("), table);
        }
        assertTrue(sql.contains("collate=utf8mb4_0900_bin"), sql);
        assertTrue(sql.contains("constraint fk_archive_edition_work foreign key (work_id) references archive_work (work_id)"));
        assertTrue(sql.contains("constraint fk_archive_chapter_edition foreign key (edition_id) references archive_edition (edition_id)"));
        assertTrue(sql.contains("constraint fk_archive_paragraph_block foreign key (edition_id, block_id) references archive_chapter (edition_id, block_id)"));
        assertTrue(sql.contains("constraint fk_archive_work_active_edition foreign key (work_id, active_edition_id) references archive_edition (work_id, edition_id)"));
        assertTrue(sql.contains("unique key uk_archive_chapter_ordinal (edition_id, reader_ordinal)"));
        assertTrue(sql.contains("unique key uk_archive_paragraph_ordinal (edition_id, block_id, ordinal)"));
        assertTrue(sql.contains("check (import_state in ('staging', 'ready'))"));
        assertTrue(sql.contains("check ((block_type = 'preface' and reader_ordinal = 0 and chapter_number is null) or (block_type = 'chapter' and reader_ordinal between 1 and 120 and chapter_number = reader_ordinal))"));
        assertFalse(sql.contains("tenant_id"));
        assertFalse(sql.contains("client_id"));
        assertFalse(sql.contains("owner_jiacn"));
        assertFalse(sql.contains("on delete cascade"));
    }

    private String read(String resource) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
