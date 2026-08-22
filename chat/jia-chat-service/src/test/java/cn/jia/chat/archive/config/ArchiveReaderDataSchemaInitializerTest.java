package cn.jia.chat.archive.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveReaderDataSchemaInitializerTest {
    @Test
    void h03CatalogIsSeparateFromH02AllOrNoneSetAndMatchesResource() throws Exception {
        assertTrue(ArchiveSchemaCatalog.TABLE_ORDER.stream().noneMatch(
                ArchiveReaderDataSchemaCatalog.TABLE_ORDER::contains));
        String sql;
        try (var input = getClass().getClassLoader().getResourceAsStream("db/archive-reader-data-schema.sql")) {
            assertNotNull(input);
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase().replaceAll("\\s+", " ");
        }
        ArchiveReaderDataSchemaCatalog.expectedSnapshot().tables().forEach((table, definition) -> {
            assertTrue(sql.contains("create table " + table + " ("), table);
            definition.columns().keySet().forEach(column -> assertTrue(sql.contains(column), table + "." + column));
            definition.indexes().keySet().stream().filter(name -> !"PRIMARY".equals(name))
                    .forEach(name -> assertTrue(sql.contains(name.toLowerCase()), name));
            definition.foreignKeys().keySet().forEach(name -> assertTrue(sql.contains(name.toLowerCase()), name));
            definition.checks().keySet().forEach(name -> assertTrue(sql.contains(name.toLowerCase()), name));
        });
    }

    @Test
    void exactSnapshotPassesAndEveryReliedUponMetadataClassFailsClosed() {
        ArchiveSchemaSnapshot expected = ArchiveReaderDataSchemaCatalog.expectedSnapshot();
        assertDoesNotThrow(() -> ArchiveReaderDataSchemaCatalog.validate(expected));
        var table = expected.tables().get("archive_note");
        var column = table.columns().get("updated_at");
        assertColumnDrift(expected, table, "updated_at", column.withOrdinal(12));
        assertColumnDrift(expected, table, "updated_at", column.withDefaultValue(null));
        assertColumnDrift(expected, table, "updated_at", column.withExtra("default_generated"));
        assertColumnDrift(expected, table, "updated_at", column.withGenerationExpression("1"));
        assertColumnDrift(expected, table, "text", table.columns().get("text").withCollation("utf8mb4_0900_ai_ci"));

        var checks = new LinkedHashMap<>(table.checks());
        checks.put("chk_archive_note_payload", checks.get("chk_archive_note_payload").withEnforced(false));
        var tables = new LinkedHashMap<>(expected.tables());
        tables.put("archive_note", table.withChecks(checks));
        assertThrows(IllegalStateException.class,
                () -> ArchiveReaderDataSchemaCatalog.validate(new ArchiveSchemaSnapshot(tables)));
    }

    @Test
    void partialH03SchemaIsRejectedWithoutAffectingH02Set() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ArchiveReaderDataSchemaInitializer.requireAllOrNoneTables(
                        Set.of("archive_reader_progress", "archive_bookmark")));
        assertTrue(failure.getMessage().contains("partial"));
        assertDoesNotThrow(() -> ArchiveSchemaInitializer.requireAllOrNoneTables(
                Set.copyOf(ArchiveSchemaCatalog.TABLE_ORDER)));
    }

    private void assertColumnDrift(ArchiveSchemaSnapshot expected,
                                   ArchiveSchemaSnapshot.TableSnapshot table,
                                   String name,
                                   ArchiveSchemaSnapshot.ColumnSnapshot replacement) {
        var columns = new LinkedHashMap<>(table.columns());
        columns.put(name, replacement);
        var tables = new LinkedHashMap<>(expected.tables());
        tables.put("archive_note", table.withColumns(columns));
        assertThrows(IllegalStateException.class,
                () -> ArchiveReaderDataSchemaCatalog.validate(new ArchiveSchemaSnapshot(tables)));
    }
}
