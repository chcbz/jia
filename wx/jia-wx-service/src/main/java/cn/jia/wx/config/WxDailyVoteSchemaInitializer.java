package cn.jia.wx.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Explicitly opt-in additive installer for the daily-vote receipt schema.
 *
 * <p>The production release owner enables this only for a scoped migration run with the versioned
 * SQL reviewed and backed up. Keeping this disabled by default prevents an application rollout
 * from silently performing DDL. Runtime callback handling separately degrades safely when the
 * schema is absent.</p>
 */
@Component
@ConditionalOnProperty(prefix = "wx.daily-vote.schema", name = "install-on-startup", havingValue = "true")
public final class WxDailyVoteSchemaInitializer implements InitializingBean {

    static final String RESOURCE = "db/wx-daily-vote-receipt-schema-v1.sql";

    private final JdbcTemplate jdbcTemplate;

    public WxDailyVoteSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void afterPropertiesSet() {
        migrationStatements().forEach(jdbcTemplate::execute);
        validateInstalledSchema();
    }

    static List<String> migrationStatements() {
        String script;
        try (var input = new ClassPathResource(RESOURCE).getInputStream()) {
            script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load " + RESOURCE, exception);
        }
        String withoutLineComments = script.replaceAll("(?m)^\\s*--.*(?:\\R|$)", "");
        return Arrays.stream(withoutLineComments.split(";"))
                .map(String::trim)
                .filter(statement -> !statement.isEmpty())
                .toList();
    }

    private void validateInstalledSchema() {
        Integer tableCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name IN ('wx_daily_vote_receipt', 'wx_daily_vote_message_receipt')
                  AND engine = 'InnoDB'
                """, Integer.class);
        if (tableCount == null || tableCount != 2) {
            throw new IllegalStateException("Daily-vote receipt schema installation did not create both InnoDB tables");
        }
        assertIndexCount("wx_daily_vote_receipt", 2,
                "uk_wx_daily_vote_message", "uk_wx_daily_vote_user_question");
        assertIndexCount("wx_daily_vote_message_receipt", 1, "uk_wx_daily_vote_message_alias");
    }

    private void assertIndexCount(String table, int expected, String... indexes) {
        String quotedIndexes = Arrays.stream(indexes)
                .map(index -> "'" + index + "'")
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow();
        Integer actual = jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = '%s'
                  AND non_unique = 0 AND index_name IN (%s)
                """.formatted(table, quotedIndexes), Integer.class);
        if (actual == null || actual != expected) {
            throw new IllegalStateException("Daily-vote receipt schema has incompatible indexes for " + table);
        }
    }
}
