package cn.jia.chat.archive.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveQuestionSchemaInitializerTest {
    private static final String RESPONDER_HEX = "e6a188e58db7e4b9a6e5908f";

    @Test
    void h05aCatalogIsIndependentFromH02AndH03AndMatchesResource() throws Exception {
        assertTrue(ArchiveQuestionSchemaCatalog.TABLE_ORDER.stream().noneMatch(ArchiveSchemaCatalog.TABLE_ORDER::contains));
        assertTrue(ArchiveQuestionSchemaCatalog.TABLE_ORDER.stream().noneMatch(ArchiveReaderDataSchemaCatalog.TABLE_ORDER::contains));
        String sql;
        try (var input = getClass().getClassLoader().getResourceAsStream("db/archive-question-schema.sql")) {
            assertNotNull(input);
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase().replaceAll("\\s+", " ");
        }
        ArchiveQuestionSchemaCatalog.expectedSnapshot().tables().forEach((table, definition) -> {
            assertTrue(sql.contains("create table " + table + " ("), table);
            definition.columns().keySet().forEach(column -> assertTrue(sql.contains(column), table + "." + column));
            definition.indexes().keySet().stream().filter(name -> !"PRIMARY".equals(name))
                    .forEach(name -> assertTrue(sql.contains(name.toLowerCase()), name));
            definition.foreignKeys().keySet().forEach(name -> assertTrue(sql.contains(name.toLowerCase()), name));
            definition.checks().keySet().forEach(name -> assertTrue(sql.contains(name.toLowerCase()), name));
        });
    }

    @Test
    void exactSnapshotPassesAndColumnIndexForeignKeyAndCheckDriftFailClosed() {
        ArchiveSchemaSnapshot expected = ArchiveQuestionSchemaCatalog.expectedSnapshot();
        assertDoesNotThrow(() -> ArchiveQuestionSchemaCatalog.validate(expected));

        var tables = new LinkedHashMap<>(expected.tables());
        var question = tables.get("archive_question");
        var columns = new LinkedHashMap<>(question.columns());
        columns.put("current_sequence", question.columns().get("current_sequence").withCollation("ascii_bin"));
        tables.put("archive_question", question.withColumns(columns));
        ArchiveSchemaSnapshot columnDrift = new ArchiveSchemaSnapshot(tables);
        assertThrows(IllegalStateException.class,
                () -> ArchiveQuestionSchemaCatalog.validate(columnDrift));

        tables = new LinkedHashMap<>(expected.tables());
        var event = tables.get("archive_question_event");
        var indexes = new LinkedHashMap<>(event.indexes());
        indexes.put("uk_archive_question_event_sequence", new ArchiveSchemaSnapshot.IndexSnapshot(true,
                List.of("tenant_id", "client_id", "owner_jiacn", "sequence", "question_id")));
        tables.put("archive_question_event", event.withIndexes(indexes));
        ArchiveSchemaSnapshot indexDrift = new ArchiveSchemaSnapshot(tables);
        assertThrows(IllegalStateException.class, () -> ArchiveQuestionSchemaCatalog.validate(indexDrift));

        var checks = new LinkedHashMap<>(event.checks());
        checks.put("chk_archive_question_event_sequence", checks.get("chk_archive_question_event_sequence").withEnforced(false));
        tables = new LinkedHashMap<>(expected.tables());
        tables.put("archive_question_event", event.withChecks(checks));
        ArchiveSchemaSnapshot checkDrift = new ArchiveSchemaSnapshot(tables);
        assertThrows(IllegalStateException.class, () -> ArchiveQuestionSchemaCatalog.validate(checkDrift));
    }

    @Test
    void mysql8021AliasesAreNarrowAndIdempotent() {
        assertEquals("length(selected_text)<=8192",
                ArchiveQuestionSchemaCatalog.normalizeCheck("OCTET_LENGTH(`selected_text`) <= 8192"));
        assertEquals("regexp_like(question_id,'OCTET_LENGTH(x)')",
                ArchiveQuestionSchemaCatalog.normalizeCheck("REGEXP_LIKE(`question_id`,'OCTET_LENGTH(x)')"));
        String normalized = ArchiveQuestionSchemaCatalog.normalizeCheck("OCTET_LENGTH(`payload_json`) >= 2");
        assertEquals(normalized, ArchiveQuestionSchemaCatalog.normalizeCheck(normalized));
    }

    @Test
    void responderHexMetadataIsCanonicalAndDriftFailsClosed() {
        ArchiveSchemaSnapshot expected = ArchiveQuestionSchemaCatalog.expectedSnapshot();
        String canonical = "(responder_id='archive-clerk-v1') and (responder_name=convert(0x"
                + RESPONDER_HEX + " using utf8mb4)) and (responder_mode='fallback')";
        assertEquals(canonical, expected.tables().get("archive_question").checks()
                .get("chk_archive_question_responder").clause());
        assertDoesNotThrow(() -> ArchiveQuestionSchemaCatalog.validate(
                withResponderCheck(expected, canonical)));

        assertResponderCheckDrift(expected, canonical.replace(RESPONDER_HEX,
                "e6a188e58db7e4b9a6e5908e"));
        assertResponderCheckDrift(expected, canonical.replace("using utf8mb4", "using utf8mb3"));
        assertResponderCheckDrift(expected, canonical.replace(
                "convert(0x" + RESPONDER_HEX + " using utf8mb4)",
                "unhex('" + RESPONDER_HEX + "')"));
        assertResponderCheckDrift(expected, canonical.replace(
                "convert(0x" + RESPONDER_HEX + " using utf8mb4)",
                "trim(convert(0x" + RESPONDER_HEX + " using utf8mb4))"));
        assertResponderCheckDrift(expected, canonical.replace("responder_name=", "question_text="));
        assertResponderCheckDrift(expected, canonical.replace(
                "convert(0x" + RESPONDER_HEX + " using utf8mb4)", "'案卷书吏'"));
    }

    @Test
    void everyQuestionForeignKeyHasExplicitLeftPrefixIndexAndPartialSetIsRejected() {
        ArchiveQuestionSchemaCatalog.expectedSnapshot().tables().forEach((tableName, table) ->
                table.foreignKeys().forEach((foreignKeyName, foreignKey) -> assertTrue(
                        table.indexes().values().stream().anyMatch(index -> index.columns().size() >= foreignKey.columns().size()
                                && index.columns().subList(0, foreignKey.columns().size()).equals(foreignKey.columns())),
                        tableName + "." + foreignKeyName)));
        assertThrows(IllegalStateException.class,
                () -> ArchiveQuestionSchemaInitializer.requireAllOrNoneTables(Set.of("archive_question")));
        assertDoesNotThrow(() -> ArchiveQuestionSchemaInitializer.requireAllOrNoneTables(
                Set.copyOf(ArchiveQuestionSchemaCatalog.TABLE_ORDER)));
        assertDoesNotThrow(() -> ArchiveReaderDataSchemaInitializer.requireAllOrNoneTables(
                Set.copyOf(ArchiveReaderDataSchemaCatalog.TABLE_ORDER)));
    }

    private ArchiveSchemaSnapshot withResponderCheck(ArchiveSchemaSnapshot snapshot, String clause) {
        var tables = new LinkedHashMap<>(snapshot.tables());
        var question = tables.get("archive_question");
        var checks = new LinkedHashMap<>(question.checks());
        checks.put("chk_archive_question_responder", new ArchiveSchemaSnapshot.CheckSnapshot(clause, true));
        tables.put("archive_question", question.withChecks(checks));
        return new ArchiveSchemaSnapshot(tables);
    }

    private void assertResponderCheckDrift(ArchiveSchemaSnapshot expected, String clause) {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ArchiveQuestionSchemaCatalog.validate(withResponderCheck(expected, clause)));
        assertTrue(failure.getMessage().contains(
                "archive_question.chk_archive_question_responder.clause"));
    }
}
