package cn.jia.wx.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WxDailyVoteSchemaInitializerTest {

    @Test
    void migrationContainsOnlyTheTwoAdditiveReceiptTableStatements() {
        List<String> statements = WxDailyVoteSchemaInitializer.migrationStatements();

        assertEquals(2, statements.size());
        assertTrue(statements.getFirst().startsWith("CREATE TABLE IF NOT EXISTS wx_daily_vote_receipt ("));
        assertTrue(statements.getLast().startsWith("CREATE TABLE IF NOT EXISTS wx_daily_vote_message_receipt ("));
        assertTrue(statements.stream().noneMatch(statement -> statement.toUpperCase().contains("DROP ")));
        assertTrue(normalize(statements.getLast()).contains(OWNED_FOREIGN_KEY));
        assertTrue(statements.stream().noneMatch(WxDailyVoteSchemaInitializerTest::containsUnapprovedDelete));
        assertFalse(statements.stream().anyMatch(statement -> statement.toUpperCase().contains("ALTER TABLE")));
    }

    private static final String OWNED_FOREIGN_KEY = "CONSTRAINT FK_WX_DAILY_VOTE_MESSAGE_RECEIPT "
            + "FOREIGN KEY (RECEIPT_ID) REFERENCES WX_DAILY_VOTE_RECEIPT (ID) ON DELETE CASCADE";

    private static String normalize(String statement) {
        return statement.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static boolean containsUnapprovedDelete(String statement) {
        return normalize(statement).replace(OWNED_FOREIGN_KEY, "").contains("DELETE ");
    }

    @Test
    void deleteGuardAllowsOnlyTheExactOwnedForeignKeyAction() {
        assertFalse(containsUnapprovedDelete(OWNED_FOREIGN_KEY));
        assertTrue(containsUnapprovedDelete("DELETE FROM wx_daily_vote_receipt"));
        assertTrue(containsUnapprovedDelete(OWNED_FOREIGN_KEY + "; DELETE FROM wx_daily_vote_receipt"));
        assertTrue(containsUnapprovedDelete(OWNED_FOREIGN_KEY.replace("WX_DAILY_VOTE_RECEIPT (ID)", "OTHER_TABLE (ID)")));
        assertTrue(containsUnapprovedDelete("FOREIGN KEY (id) REFERENCES other_table (id) ON DELETE CASCADE"));
    }

    @Test
    void runsTheVersionedMigrationAndValidatesRequiredIndexes() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(2, 2, 1);

        new WxDailyVoteSchemaInitializer(jdbcTemplate).afterPropertiesSet();

        verify(jdbcTemplate, times(2)).execute(anyString());
        verify(jdbcTemplate, times(3)).queryForObject(anyString(), eq(Integer.class));
    }
}
