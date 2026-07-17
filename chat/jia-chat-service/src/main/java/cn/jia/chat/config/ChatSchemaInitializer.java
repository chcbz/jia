package cn.jia.chat.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatSchemaInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;
    private Boolean h2Database;

    @Override
    public void run(ApplicationArguments args) {
        if (isH2Database() && !h2TableExists()) {
            log.info("Skipping chat schema initialization because H2 does not provide the optional chat_conversation table");
            return;
        }
        addColumnIfMissing("conversation_scope_type", "VARCHAR(20) DEFAULT NULL COMMENT 'Juyiting scope type'");
        addColumnIfMissing("conversation_scope_key", "VARCHAR(120) DEFAULT NULL COMMENT 'Juyiting scope key'");
        addColumnIfMissing("task_id", "VARCHAR(64) DEFAULT NULL COMMENT 'Juyiting bounty task ID'");
        addColumnIfMissing("target_agent_id", "VARCHAR(100) DEFAULT NULL COMMENT 'Juyiting private target agent ID'");
        if (!indexExists("idx_chat_conversation_scope")) {
            jdbcTemplate.execute("""
                    CREATE INDEX idx_chat_conversation_scope
                        ON chat_conversation (conversation_type, conversation_scope_type, conversation_scope_key)
                    """);
        }
    }

    private void addColumnIfMissing(String columnName, String definition) {
        if (!columnExists(columnName)) {
            jdbcTemplate.execute("ALTER TABLE chat_conversation ADD COLUMN " + columnName + " " + definition);
        }
    }

    private boolean columnExists(String columnName) {
        if (isH2Database()) {
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = SCHEMA()
                      AND LOWER(table_name) = 'chat_conversation'
                      AND LOWER(column_name) = LOWER(?)
                    """, Integer.class, columnName);
            return count != null && count > 0;
        }
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'chat_conversation'
                  AND column_name = ?
                """, Integer.class, columnName);
        return count != null && count > 0;
    }

    private boolean indexExists(String indexName) {
        if (isH2Database()) {
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM information_schema.index_columns
                    WHERE table_schema = SCHEMA()
                      AND LOWER(table_name) = 'chat_conversation'
                      AND LOWER(index_name) = LOWER(?)
                    """, Integer.class, indexName);
            return count != null && count > 0;
        }
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'chat_conversation'
                  AND index_name = ?
                """, Integer.class, indexName);
        return count != null && count > 0;
    }

    private boolean h2TableExists() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = SCHEMA() AND LOWER(table_name) = LOWER(?)
                """, Integer.class, "chat_conversation");
        return count != null && count > 0;
    }

    private boolean isH2Database() {
        if (h2Database != null) {
            return h2Database;
        }
        DataSource dataSource = jdbcTemplate.getDataSource();
        if (dataSource == null) {
            h2Database = false;
            return h2Database;
        }
        try (Connection connection = dataSource.getConnection()) {
            String productName = connection.getMetaData().getDatabaseProductName();
            h2Database = productName != null && productName.toLowerCase(Locale.ROOT).contains("h2");
            return h2Database;
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to determine database dialect for chat schema initialization", e);
        }
    }
}
