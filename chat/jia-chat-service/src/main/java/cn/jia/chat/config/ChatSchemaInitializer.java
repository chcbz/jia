package cn.jia.chat.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ChatSchemaInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
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
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'chat_conversation'
                  AND index_name = ?
                """, Integer.class, indexName);
        return count != null && count > 0;
    }
}
