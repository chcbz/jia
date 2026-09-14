package cn.jia.wx.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

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
        assertTrue(statements.getFirst().contains("CREATE TABLE IF NOT EXISTS wx_daily_vote_receipt"));
        assertTrue(statements.getLast().contains("CREATE TABLE IF NOT EXISTS wx_daily_vote_message_receipt"));
        assertTrue(statements.stream().noneMatch(statement -> statement.toUpperCase().contains("DROP ")));
        assertTrue(statements.stream().noneMatch(statement -> statement.toUpperCase().contains("DELETE ")));
        assertFalse(statements.stream().anyMatch(statement -> statement.toUpperCase().contains("ALTER TABLE")));
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
