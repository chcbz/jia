package cn.jia.chat.archive.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveReaderDataSchemaInitializerTest {
    private static final String UUID_PATTERN =
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$";

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
    void mysql8021RegexpLikeMetadataForUuidChecksValidatesAndNormalizesIdempotently() {
        ArchiveSchemaSnapshot rendered = ArchiveReaderDataSchemaCatalog.expectedSnapshot();
        rendered = withCheckClause(rendered, "archive_bookmark", "chk_archive_bookmark_id",
                "regexp_like(bookmark_id,'" + UUID_PATTERN + "')");
        rendered = withCheckClause(rendered, "archive_note", "chk_archive_note_id",
                "regexp_like(`note_id`,_utf8mb4\\'" + UUID_PATTERN + "\\')");
        rendered = withCheckClause(rendered, "archive_idempotency", "chk_archive_idempotency_key",
                "regexp_like(idempotency_key,'^[!-~]{1,128}$')");

        ArchiveSchemaSnapshot actual = rendered;
        assertDoesNotThrow(() -> ArchiveReaderDataSchemaCatalog.validate(actual));
        for (String clause : actual.tables().get("archive_bookmark").checks().values().stream()
                .map(ArchiveSchemaSnapshot.CheckSnapshot::clause).toList()) {
            String normalized = ArchiveSchemaCatalog.normalizeCheck(clause);
            assertEquals(normalized, ArchiveSchemaCatalog.normalizeCheck(normalized));
        }
        for (String clause : actual.tables().get("archive_note").checks().values().stream()
                .map(ArchiveSchemaSnapshot.CheckSnapshot::clause).toList()) {
            String normalized = ArchiveSchemaCatalog.normalizeCheck(clause);
            assertEquals(normalized, ArchiveSchemaCatalog.normalizeCheck(normalized));
        }
    }

    @Test
    void regexpLikeUuidOperandPatternFlagsAndQuotedCaseDriftFailClosed() {
        ArchiveSchemaSnapshot expected = ArchiveReaderDataSchemaCatalog.expectedSnapshot();
        assertCheckDrift(expected, "archive_bookmark", "chk_archive_bookmark_id",
                "regexp_like(note_id,'" + UUID_PATTERN + "')");
        assertCheckDrift(expected, "archive_bookmark", "chk_archive_bookmark_id",
                "regexp_like(bookmark_id,'" + UUID_PATTERN.replace("{12}", "{11}") + "')");
        assertCheckDrift(expected, "archive_bookmark", "chk_archive_bookmark_id",
                "regexp_like(bookmark_id,'" + UUID_PATTERN + "','i')");
        assertCheckDrift(expected, "archive_bookmark", "chk_archive_bookmark_id",
                "regexp_like(bookmark_id,'" + UUID_PATTERN.replace("[89ab]", "[89AB]") + "')");

        assertCheckDrift(expected, "archive_note", "chk_archive_note_id",
                "regexp_like(bookmark_id,'" + UUID_PATTERN + "')");
        assertCheckDrift(expected, "archive_note", "chk_archive_note_id",
                "regexp_like(note_id,'" + UUID_PATTERN.replace("{8}", "{7}") + "')");
        assertCheckDrift(expected, "archive_note", "chk_archive_note_id",
                "regexp_like(note_id,'" + UUID_PATTERN + "','c')");
        assertCheckDrift(expected, "archive_note", "chk_archive_note_id",
                "regexp_like(note_id,'" + UUID_PATTERN.replace("[0-9a-f]", "[0-9A-F]") + "')");
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

    private ArchiveSchemaSnapshot withCheckClause(ArchiveSchemaSnapshot snapshot,
                                                  String tableName,
                                                  String checkName,
                                                  String clause) {
        var tables = new LinkedHashMap<>(snapshot.tables());
        var table = tables.get(tableName);
        var checks = new LinkedHashMap<>(table.checks());
        checks.put(checkName, new ArchiveSchemaSnapshot.CheckSnapshot(clause, true));
        tables.put(tableName, table.withChecks(checks));
        return new ArchiveSchemaSnapshot(tables);
    }

    private void assertCheckDrift(ArchiveSchemaSnapshot expected,
                                  String tableName,
                                  String checkName,
                                  String clause) {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ArchiveReaderDataSchemaCatalog.validate(
                        withCheckClause(expected, tableName, checkName, clause)));
        assertTrue(failure.getMessage().contains(tableName + "." + checkName + ".clause"));
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
