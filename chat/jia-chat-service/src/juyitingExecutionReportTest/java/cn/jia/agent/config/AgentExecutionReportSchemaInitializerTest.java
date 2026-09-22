package cn.jia.agent.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentExecutionReportSchemaInitializerTest {
    @Test
    void reportSchemaRetainsCommentSemicolonsAsExactlyTwoAdditiveTables() {
        List<String> statements = AgentExecutionReportSchemaInitializer.ddlStatements();

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).startsWith(
                "CREATE TABLE IF NOT EXISTS agent_execution_report_head ("));
        assertTrue(statements.get(1).startsWith(
                "CREATE TABLE IF NOT EXISTS agent_execution_report_inbox ("));
        assertTrue(statements.get(1).contains(
                "COMMENT='Durable idempotent Protocol v1 report receipts; payload is a strict safe-field projection'"));
        String normalized = String.join("\n", statements).toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        for (String forbidden : List.of(" alter table ", " insert ", " update ", " delete ",
                " drop ", " create trigger ", " foreign key ")) {
            assertFalse(normalized.contains(forbidden));
        }
    }

    @Test
    void splitterRespectsQuotedSemicolonsAndStripsOrdinaryLineAndBlockComments() {
        String first = "CREATE TABLE `report;``head` ("
                + "v VARCHAR(12) COMMENT 'single; -- # /* literal */', "
                + "w VARCHAR(12) COMMENT \"double;\"\"quoted\")";
        String second = "CREATE TABLE report_inbox (v INT)";
        String source = "-- heading;\n" + first + "; /* ignored; */ # tail;\n"
                + second + "; -- end;";

        assertEquals(List.of(first, second), HallSchemaSql.split(source));
    }

    @Test
    void splitterRejectsUnclosedOrExecutableCommentsBeforeSchemaExecution() {
        for (String source : List.of("CREATE TABLE t (v TEXT COMMENT 'open;",
                "CREATE TABLE t (v INT); /* open;", "/*!80021 DROP TABLE t */;")) {
            assertThrows(IllegalStateException.class, () -> HallSchemaSql.split(source));
        }
    }
}
