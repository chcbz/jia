package cn.jia.chat.archive.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void mysql8021CharsetIntroducersNormalizeWithoutChangingQuotedLiteralCase() {
        String rendered = "(`import_state` in (_utf8mb4\\'STAGING\\',_utf8mb4\\'READY\\'))";

        assertEquals("import_statein('STAGING','READY')",
                ArchiveSchemaCatalog.normalizeCheck(rendered));
        assertEquals("import_statein('staging','ready')",
                ArchiveSchemaCatalog.normalizeCheck(
                        "(`import_state` in (_utf8mb4\\'staging\\',_utf8mb4\\'ready\\'))"));
    }

    @Test
    void mysql8021CheckClauseNormalizationIsIdempotent() {
        String rendered = "(`import_state` in (_utf8mb4\\'STAGING\\',_utf8mb4\\'READY\\'))";
        String normalized = ArchiveSchemaCatalog.normalizeCheck(rendered);

        assertEquals(normalized, ArchiveSchemaCatalog.normalizeCheck(normalized));
    }

    @Test
    void mysql8021RenderedChecksValidateAgainstCatalog() {
        ArchiveSchemaSnapshot expected = ArchiveSchemaCatalog.expectedSnapshot();
        var tables = new LinkedHashMap<>(expected.tables());
        tables.put("archive_edition", withChecks(expected.tables().get("archive_edition"),
                "chk_archive_edition_counts",
                "((`chapter_count` = 120) and (`preface_paragraph_count` >= 1) and "
                        + "(`chapter_paragraph_count` >= 1) and (`reader_paragraph_count` = "
                        + "(`preface_paragraph_count` + `chapter_paragraph_count`)) and "
                        + "(`source_utf8_byte_length` > 0) and (`preface_utf8_byte_length` > 0) and "
                        + "(`chapter_utf8_byte_length` > 0) and (`reader_utf8_byte_length` = "
                        + "(`preface_utf8_byte_length` + `chapter_utf8_byte_length`)))",
                "chk_archive_edition_state",
                "(`import_state` in (_utf8mb4\\'STAGING\\',_utf8mb4\\'READY\\'))"));
        tables.put("archive_chapter", withChecks(expected.tables().get("archive_chapter"),
                "chk_archive_chapter_metrics",
                "((`paragraph_count` > 0) and (`utf8_byte_length` > 0))",
                "chk_archive_chapter_shape",
                "(((`block_type` = _utf8mb4\\'PREFACE\\') and (`reader_ordinal` = 0) and "
                        + "(`chapter_number` is null)) or ((`block_type` = _utf8mb4\\'CHAPTER\\') and "
                        + "(`reader_ordinal` between 1 and 120) and "
                        + "(`chapter_number` = `reader_ordinal`)))"));
        tables.put("archive_paragraph", withChecks(expected.tables().get("archive_paragraph"),
                "chk_archive_paragraph_metrics",
                "((`ordinal` >= 1) and (`utf8_byte_length` > 0))"));

        assertDoesNotThrow(() -> ArchiveSchemaCatalog.validate(new ArchiveSchemaSnapshot(tables)));
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

    private ArchiveSchemaSnapshot.TableSnapshot withChecks(
            ArchiveSchemaSnapshot.TableSnapshot table, String... namesAndClauses) {
        var checks = new LinkedHashMap<String, ArchiveSchemaSnapshot.CheckSnapshot>();
        for (int index = 0; index < namesAndClauses.length; index += 2) {
            checks.put(namesAndClauses[index],
                    new ArchiveSchemaSnapshot.CheckSnapshot(namesAndClauses[index + 1], true));
        }
        return table.withChecks(checks);
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
