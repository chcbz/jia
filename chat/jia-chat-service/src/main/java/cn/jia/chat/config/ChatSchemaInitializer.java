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
        if (isH2Database() && !tableExists("chat_conversation")) {
            log.info("Skipping chat schema initialization because H2 does not provide the optional chat_conversation table");
            return;
        }
        addColumnIfMissing("conversation_scope_type", "VARCHAR(20) DEFAULT NULL COMMENT 'Juyiting scope type'");
        addColumnIfMissing("conversation_scope_key", "VARCHAR(120) DEFAULT NULL COMMENT 'Juyiting scope key'");
        addColumnIfMissing("task_id", "VARCHAR(64) DEFAULT NULL COMMENT 'Juyiting bounty task ID'");
        addColumnIfMissing("target_agent_id", "VARCHAR(100) DEFAULT NULL COMMENT 'Juyiting private target agent ID'");
        if (!indexExists("chat_conversation", "idx_chat_conversation_scope")) {
            jdbcTemplate.execute("""
                    CREATE INDEX idx_chat_conversation_scope
                        ON chat_conversation (conversation_type, conversation_scope_type, conversation_scope_key)
                    """);
        }
        ensureTaskThreadTable();
    }

    private void ensureTaskThreadTable() {
        if (!tableExists("agent_task_thread")) {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS agent_task_thread (
                        id                      BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
                        task_id                 VARCHAR(100) NOT NULL COMMENT 'Task ID',
                        thread_type             VARCHAR(20) NOT NULL COMMENT 'team/review/work_item',
                        thread_key              VARCHAR(100) NOT NULL COMMENT 'Stable key within thread type',
                        conversation_id         VARCHAR(100) NOT NULL COMMENT 'chat_conversation ID',
                        created_by_agent_id     VARCHAR(100) NOT NULL COMMENT 'Creating task member Agent ID',
                        status                  VARCHAR(20) NOT NULL DEFAULT 'active' COMMENT 'active/closed',
                        tenant_id               VARCHAR(50) NOT NULL COMMENT 'Owner jiacn scope',
                        client_id               VARCHAR(50) NOT NULL COMMENT 'OAuth/API client scope',
                        create_time             BIGINT DEFAULT NULL COMMENT 'Create time',
                        update_time             BIGINT DEFAULT NULL COMMENT 'Update time',
                        PRIMARY KEY (id),
                        UNIQUE KEY uk_task_thread_scope
                            (tenant_id, client_id, task_id, thread_type, thread_key),
                        UNIQUE KEY uk_task_thread_conversation
                            (tenant_id, client_id, conversation_id),
                        KEY idx_task_thread_task
                            (tenant_id, client_id, task_id, status, create_time)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Scoped task to shared conversation binding'
                    """);
        }
        if (!indexExists("agent_task_thread", "uk_task_thread_scope")) {
            jdbcTemplate.execute("""
                    CREATE UNIQUE INDEX uk_task_thread_scope
                        ON agent_task_thread (tenant_id, client_id, task_id, thread_type, thread_key)
                    """);
        }
        if (!indexExists("agent_task_thread", "uk_task_thread_conversation")) {
            jdbcTemplate.execute("""
                    CREATE UNIQUE INDEX uk_task_thread_conversation
                        ON agent_task_thread (tenant_id, client_id, conversation_id)
                    """);
        }
        if (!indexExists("agent_task_thread", "idx_task_thread_task")) {
            jdbcTemplate.execute("""
                    CREATE INDEX idx_task_thread_task
                        ON agent_task_thread (tenant_id, client_id, task_id, status, create_time)
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

    private boolean indexExists(String tableName, String indexName) {
        if (isH2Database()) {
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM information_schema.index_columns
                    WHERE table_schema = SCHEMA()
                      AND LOWER(table_name) = LOWER(?)
                      AND LOWER(index_name) = LOWER(?)
                    """, Integer.class, tableName, indexName);
            return count != null && count > 0;
        }
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND index_name = ?
                """, Integer.class, tableName, indexName);
        return count != null && count > 0;
    }

    private boolean tableExists(String tableName) {
        if (isH2Database()) {
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = SCHEMA() AND LOWER(table_name) = LOWER(?)
                    """, Integer.class, tableName);
            return count != null && count > 0;
        }
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = ?
                """, Integer.class, tableName);
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
