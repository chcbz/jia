package cn.jia.chat.archive.config;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveSchemaInitializerTest {

    @Test
    void schemaResourceContainsEveryRuntimeCatalogColumnIndexForeignKeyAndCheck() throws Exception {
        String sql;
        try (var input = getClass().getClassLoader().getResourceAsStream("db/archive-schema.sql")) {
            org.junit.jupiter.api.Assertions.assertNotNull(input);
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase().replaceAll("\\s+", " ");
        }
        ArchiveSchemaCatalog.expectedSnapshot().tables().forEach((table, definition) -> {
            assertTrue(sql.contains("create table " + table + " ("), table);
            definition.columns().keySet().forEach(column -> assertTrue(sql.contains(column), table + "." + column));
            definition.indexes().keySet().stream().filter(name -> !"PRIMARY".equals(name))
                    .forEach(name -> assertTrue(sql.contains(name.toLowerCase()), name));
            definition.foreignKeys().keySet().forEach(name -> assertTrue(sql.contains(name.toLowerCase()), name));
            definition.checks().keySet().forEach(name -> assertTrue(sql.contains(name.toLowerCase()), name));
        });
    }

    @Test
    void exactCatalogSnapshotPassesAndAnyBinaryCollationOrIndexOrderDriftFails() {
        ArchiveSchemaSnapshot expected = ArchiveSchemaCatalog.expectedSnapshot();
        assertDoesNotThrow(() -> ArchiveSchemaCatalog.validate(expected));

        var tables = new LinkedHashMap<>(expected.tables());
        ArchiveSchemaSnapshot.TableSnapshot paragraph = tables.get("archive_paragraph");
        var columns = new LinkedHashMap<>(paragraph.columns());
        ArchiveSchemaSnapshot.ColumnSnapshot text = columns.get("text");
        columns.put("text", text.withCollation("utf8mb4_0900_ai_ci"));
        tables.put("archive_paragraph", paragraph.withColumns(columns));
        ArchiveSchemaSnapshot collationDrift = new ArchiveSchemaSnapshot(tables);
        IllegalStateException collation = assertThrows(IllegalStateException.class,
                () -> ArchiveSchemaCatalog.validate(collationDrift));
        assertTrue(collation.getMessage().contains("archive_paragraph.text"), collation.getMessage());

        tables = new LinkedHashMap<>(expected.tables());
        ArchiveSchemaSnapshot.TableSnapshot chapters = tables.get("archive_chapter");
        var indexes = new LinkedHashMap<>(chapters.indexes());
        ArchiveSchemaSnapshot.IndexSnapshot ordinal = indexes.get("uk_archive_chapter_ordinal");
        indexes.put("uk_archive_chapter_ordinal",
                new ArchiveSchemaSnapshot.IndexSnapshot(true, List.of("reader_ordinal", "edition_id")));
        tables.put("archive_chapter", chapters.withIndexes(indexes));
        ArchiveSchemaSnapshot indexDrift = new ArchiveSchemaSnapshot(tables);
        IllegalStateException index = assertThrows(IllegalStateException.class,
                () -> ArchiveSchemaCatalog.validate(indexDrift));
        assertTrue(index.getMessage().contains("uk_archive_chapter_ordinal"), index.getMessage());
    }

    @Test
    void partialExistingSchemaIsRejectedInsteadOfMutated() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ArchiveSchemaInitializer.requireAllOrNoneTables(
                        java.util.Set.of("archive_work", "archive_edition")));
        assertTrue(failure.getMessage().contains("partial"), failure.getMessage());
    }
}
