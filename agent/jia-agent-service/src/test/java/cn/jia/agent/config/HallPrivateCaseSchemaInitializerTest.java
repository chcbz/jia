package cn.jia.agent.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HallPrivateCaseSchemaInitializerTest {
    @Test
    void additiveSchemaFreezesOwnerScopeLineageAndNoDataMutation() {
        List<String> ddl = HallPrivateCaseSchemaInitializer.ddlStatements();
        assertEquals(2, ddl.size());
        assertTrue(ddl.getFirst().contains("COMMENT 'Bounded UI origin; never authority'"));
        assertTrue(ddl.getFirst().contains("COMMENT='Owner-scoped private Hall cases; execution state remains authoritative'"));
        String joined = String.join("\n", ddl);
        String lower = joined.toLowerCase(Locale.ROOT);
        assertTrue(ddl.get(0).startsWith("CREATE TABLE IF NOT EXISTS hall_private_case ("));
        assertTrue(ddl.get(1).startsWith("CREATE TABLE IF NOT EXISTS hall_case_execution ("));
        assertTrue(joined.contains("UNIQUE KEY uk_hall_case_execution_scope (tenant_id, client_id, owner_jiacn, execution_id)"));
        assertTrue(joined.contains("UNIQUE KEY uk_hall_case_revision_scope (tenant_id, client_id, owner_jiacn, case_id, revision_no)"));
        assertTrue(joined.contains("revision_no=1 AND parent_execution_id IS NULL AND source_output_ref_json IS NULL"));
        assertTrue(joined.contains("revision_no>1 AND parent_execution_id IS NOT NULL AND source_output_ref_json IS NOT NULL"));
        assertTrue(joined.contains("COLLATE=utf8mb4_0900_bin"));
        assertFalse(joined.contains("TEXT DEFAULT NULL"));
        for (String forbidden : List.of(" alter table ", " insert ", " update ",
                " delete ", " drop ", " create trigger ")) {
            assertFalse(lower.contains(forbidden));
        }
    }
    @Test
    void quotedSemicolonsDoubledQuotesAndQuotedCommentMarkersStayInOneStatement() {
        String sql = "CREATE TABLE t (`semi;``column` VARCHAR(9) COMMENT 'owner''s; -- # /* literal */', "
                + "other VARCHAR(9) COMMENT \"double;\"\"quoted\") COMMENT='line1;\n-- still literal';";
        assertEquals(List.of(sql.substring(0,sql.length()-1)),HallSchemaSql.split(sql));
    }

    @Test
    void mysqlEscapesConsumePairsRatherThanMistakingAnEscapedSlashForAnEscapedQuote() {
        String slash = Character.toString((char)92);
        String one = "CREATE TABLE t (v TEXT COMMENT 'owner"+slash+"'; retained')";
        String two = "CREATE TABLE u (v TEXT COMMENT 'slash"+slash+slash+"')";
        assertEquals(List.of(one,two),HallSchemaSql.split(one+";"+two+";"));
    }

    @Test
    void onlyUnquotedSeparatorsSplitAndOrdinaryCommentsDoNotBecomeStatements() {
        assertEquals(List.of("CREATE TABLE t (v INT)","CREATE TABLE u (v INT)"),
                HallSchemaSql.split("-- heading;\nCREATE TABLE t (v INT); /* ignored; ' */ # tail;\n"
                        + "CREATE TABLE u (v INT); -- tail without newline;"));
    }

    @Test
    void malformedQuotesCommentsAndExecutableCommentsFailBeforeAnyJdbcCall() {
        for (String sql : List.of("CREATE TABLE t (v TEXT COMMENT 'open;", "CREATE TABLE `open;",
                "CREATE TABLE t (v INT); /* open;", "/*!80021 DROP TABLE t */;",
                "/*M! DROP TABLE t */;")) {
            assertThrows(IllegalStateException.class,()->HallSchemaSql.split(sql));
        }
    }

    @Test
    void parserDoesNotRelaxExactlyTwoOrderedTablesOrPermitAdditionalDdlAndDml() {
        String a = "CREATE TABLE IF NOT EXISTS hall_private_case (id INT)";
        String b = "CREATE TABLE IF NOT EXISTS hall_case_execution (id INT)";
        assertEquals(List.of(a,b),HallPrivateCaseSchemaInitializer.ddlStatements(a+";"+b+";"));
        for (String sql : List.of(a+";", b+";"+a+";", a+";DROP TABLE hall_case_execution;",
                a+";"+b+";CREATE TABLE extra (id INT);", a+";"+b+";INSERT INTO x VALUES (1);")) {
            assertThrows(IllegalStateException.class,()->HallPrivateCaseSchemaInitializer.ddlStatements(sql));
        }
    }

}
