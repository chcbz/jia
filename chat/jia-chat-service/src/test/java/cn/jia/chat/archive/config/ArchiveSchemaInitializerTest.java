package cn.jia.chat.archive.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;

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
    void exactCatalogSnapshotPassesAndBinaryCollationOrIndexOrderDriftFails() {
        ArchiveSchemaSnapshot expected = ArchiveSchemaCatalog.expectedSnapshot();
        assertDoesNotThrow(() -> ArchiveSchemaCatalog.validate(expected));

        var tables = new LinkedHashMap<>(expected.tables());
        ArchiveSchemaSnapshot.TableSnapshot paragraph = tables.get("archive_paragraph");
        var columns = new LinkedHashMap<>(paragraph.columns());
        ArchiveSchemaSnapshot.ColumnSnapshot text = columns.get("text");
        columns.put("text", text.withCollation("utf8mb4_0900_ai_ci"));
        tables.put("archive_paragraph", paragraph.withColumns(columns));
        assertDrift(new ArchiveSchemaSnapshot(tables), "archive_paragraph.text");

        tables = new LinkedHashMap<>(expected.tables());
        ArchiveSchemaSnapshot.TableSnapshot chapters = tables.get("archive_chapter");
        var indexes = new LinkedHashMap<>(chapters.indexes());
        indexes.put("uk_archive_chapter_ordinal",
                new ArchiveSchemaSnapshot.IndexSnapshot(true, List.of("reader_ordinal", "edition_id")));
        tables.put("archive_chapter", chapters.withIndexes(indexes));
        assertDrift(new ArchiveSchemaSnapshot(tables), "uk_archive_chapter_ordinal");
    }

    @Test
    void columnOrdinalDefaultExtraAndGenerationDriftAllFailClosed() {
        ArchiveSchemaSnapshot expected = ArchiveSchemaCatalog.expectedSnapshot();
        ArchiveSchemaSnapshot.TableSnapshot work = expected.tables().get("archive_work");

        assertColumnDrift(expected, work, "updated_at",
                work.columns().get("updated_at").withOrdinal(4), "updated_at");
        assertColumnDrift(expected, work, "created_at",
                work.columns().get("created_at").withDefaultValue(null), "created_at");
        assertColumnDrift(expected, work, "updated_at",
                work.columns().get("updated_at").withExtra("default_generated"), "updated_at");
        assertColumnDrift(expected, work, "title",
                work.columns().get("title").withGenerationExpression("lower(`work_id`)"), "title");
    }

    @Test
    void disabledCheckAndBinaryLiteralCaseDriftFailClosed() {
        ArchiveSchemaSnapshot expected = ArchiveSchemaCatalog.expectedSnapshot();
        ArchiveSchemaSnapshot.TableSnapshot edition = expected.tables().get("archive_edition");
        var checks = new LinkedHashMap<>(edition.checks());
        ArchiveSchemaSnapshot.CheckSnapshot state = checks.get("chk_archive_edition_state");
        checks.put("chk_archive_edition_state", state.withEnforced(false));
        var tables = new LinkedHashMap<>(expected.tables());
        tables.put("archive_edition", edition.withChecks(checks));
        assertDrift(new ArchiveSchemaSnapshot(tables), "chk_archive_edition_state");

        checks = new LinkedHashMap<>(edition.checks());
        checks.put("chk_archive_edition_state", new ArchiveSchemaSnapshot.CheckSnapshot(
                "import_state in ('staging','ready')", true));
        tables = new LinkedHashMap<>(expected.tables());
        tables.put("archive_edition", edition.withChecks(checks));
        assertDrift(new ArchiveSchemaSnapshot(tables), "chk_archive_edition_state");
    }

    @Test
    void partialExistingSchemaIsRejectedInsteadOfMutated() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ArchiveSchemaInitializer.requireAllOrNoneTables(
                        java.util.Set.of("archive_work", "archive_edition")));
        assertTrue(failure.getMessage().contains("partial"), failure.getMessage());
    }

    private void assertColumnDrift(ArchiveSchemaSnapshot expected,
                                   ArchiveSchemaSnapshot.TableSnapshot table,
                                   String columnName,
                                   ArchiveSchemaSnapshot.ColumnSnapshot replacement,
                                   String expectedMessage) {
        var columns = new LinkedHashMap<>(table.columns());
        columns.put(columnName, replacement);
        var tables = new LinkedHashMap<>(expected.tables());
        tables.put("archive_work", table.withColumns(columns));
        assertDrift(new ArchiveSchemaSnapshot(tables), expectedMessage);
    }

    private void assertDrift(ArchiveSchemaSnapshot snapshot, String expectedMessage) {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ArchiveSchemaCatalog.validate(snapshot));
        assertTrue(failure.getMessage().contains(expectedMessage), failure.getMessage());
    }
}
