package cn.jia.agent.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AgentSchemaInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agent_task_note (
                    id                  BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
                    task_id             VARCHAR(100) NOT NULL COMMENT 'Task ID',
                    author_id           VARCHAR(100) DEFAULT NULL COMMENT 'Author ID',
                    author_type         VARCHAR(20) NOT NULL DEFAULT 'user' COMMENT 'user/agent/system',
                    note_type           VARCHAR(20) NOT NULL DEFAULT 'summary' COMMENT 'summary/report/meeting/system',
                    content             TEXT NOT NULL COMMENT 'Note content',
                    created_at          BIGINT NOT NULL COMMENT 'Note created time',
                    create_time         BIGINT DEFAULT NULL COMMENT 'Create time',
                    update_time         BIGINT DEFAULT NULL COMMENT 'Update time',
                    tenant_id           VARCHAR(50) DEFAULT NULL COMMENT 'Tenant ID',
                    client_id           VARCHAR(50) DEFAULT NULL COMMENT 'Client ID',
                    PRIMARY KEY (id),
                    KEY idx_agent_task_note_task_id (task_id),
                    KEY idx_agent_task_note_created_at (created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent task notes'
                """);
    }
}
