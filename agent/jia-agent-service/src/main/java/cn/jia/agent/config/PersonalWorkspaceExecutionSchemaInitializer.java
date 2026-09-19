package cn.jia.agent.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Installs only additive private-execution/grant/output tables. */
public final class PersonalWorkspaceExecutionSchemaInitializer implements InitializingBean {
    static final String RESOURCE = "db/agent-personal-workspace-v1_11-executions.sql";
    static final String OUTPUT_MIME_MIGRATION_RESOURCE = "db/agent-personal-workspace-v1_13-execution-output-mime.sql";
    static final String TERMINAL_STATE_MIGRATION_RESOURCE = "db/agent-personal-workspace-v1_13-execution-terminal-state.sql";
    static final String TASK_EXECUTION_MIGRATION_RESOURCE = "db/agent-personal-workspace-v1_13-task-execution.sql";
    static final List<String> TABLES = List.of("agent_personal_workspace_execution",
            "agent_personal_workspace_execution_input", "agent_personal_workspace_execution_output");
    private final JdbcTemplate jdbc;
    public PersonalWorkspaceExecutionSchemaInitializer(JdbcTemplate jdbc) { this.jdbc=Objects.requireNonNull(jdbc,"jdbc"); }
    @Override public void afterPropertiesSet() {
        requireMySql(); List<String> present=presentTables();
        if (present.isEmpty()) ddlStatements().forEach(jdbc::execute);
        else if (!present.equals(TABLES)) throw new IllegalStateException("Personal workspace execution schema is partial: " + present);
        if (!presentTables().equals(TABLES)) throw new IllegalStateException("Personal workspace execution schema is unavailable");
        migrateOutputMime();
        verifyOutputMime();
        migrateTerminalState();
        verifyTerminalState();
        migrateTaskExecution();
        verifyTaskExecution();
    }
    static List<String> ddlStatements() {
        final String source;
        try { source=new ClassPathResource(RESOURCE).getContentAsString(StandardCharsets.UTF_8); }
        catch (IOException failure) { throw new IllegalStateException("Personal workspace execution DDL is missing", failure); }
        String stripped=source.lines().filter(line -> !line.stripLeading().startsWith("--")).reduce("", (a,b)->a+b+'\n');
        List<String> statements=new ArrayList<>(); for (String raw:stripped.split(";")) { String statement=raw.strip(); if(!statement.isEmpty()) statements.add(statement); }
        if(statements.size()!=TABLES.size()) throw new IllegalStateException("Execution DDL must contain exactly three statements");
        for(int i=0;i<TABLES.size();i++) { String lower=statements.get(i).toLowerCase(Locale.ROOT); if(!lower.startsWith("create table if not exists "+TABLES.get(i)+" ")||lower.contains(" alter table ")||lower.contains(" insert ")||lower.contains(" update ")||lower.contains(" delete ")||lower.contains(" drop ")||lower.contains(" create trigger ")) throw new IllegalStateException("Unsafe personal workspace execution DDL"); }
        return List.copyOf(statements);
    }
    static String outputMimeMigrationStatement() {
        final String source;
        try { source=new ClassPathResource(OUTPUT_MIME_MIGRATION_RESOURCE).getContentAsString(StandardCharsets.UTF_8); }
        catch (IOException failure) { throw new IllegalStateException("Execution output MIME migration is missing", failure); }
        String statement=source.lines().filter(line -> !line.stripLeading().startsWith("--")).reduce("", (a,b)->a+b+'\n').strip();
        String normalized=statement.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        if (!normalized.startsWith("alter table agent_personal_workspace_execution ")
                || !normalized.contains("add column output_content_mime_type varchar(127) not null default")
                || !normalized.contains("add constraint chk_pwex_output_mime check")
                || normalized.contains(" insert ") || normalized.contains(" update ") || normalized.contains(" delete ")
                || normalized.contains(" drop ") || normalized.contains(" create trigger ") || !statement.endsWith(";")) {
            throw new IllegalStateException("Unsafe execution output MIME migration");
        }
        return statement.substring(0, statement.length() - 1);
    }
    private void migrateOutputMime() {
        Integer present=jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
                 AND table_name='agent_personal_workspace_execution' AND column_name='output_content_mime_type'
                """, Integer.class);
        if (present == null) throw new IllegalStateException("Execution output MIME schema discovery failed");
        if (present == 0) jdbc.execute(outputMimeMigrationStatement());
        else if (present != 1) throw new IllegalStateException("Execution output MIME column is ambiguous");
    }

    static String terminalStateMigrationStatement() {
        final String source;
        try { source=new ClassPathResource(TERMINAL_STATE_MIGRATION_RESOURCE).getContentAsString(StandardCharsets.UTF_8); }
        catch (IOException failure) { throw new IllegalStateException("Execution terminal-state migration is missing", failure); }
        String statement=source.lines().filter(line -> !line.stripLeading().startsWith("--")).reduce("", (a,b)->a+b+'\n').strip();
        String normalized=statement.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        if (!normalized.startsWith("alter table agent_personal_workspace_execution ")
                || !normalized.contains("add column failure_code varchar(64)")
                || !normalized.contains("add column failure_message varchar(255)")
                || !normalized.contains("add column failed_at bigint")
                || !normalized.contains("drop check chk_pwex_state")
                || !normalized.contains("add constraint chk_pwex_state check")
                || !normalized.contains("add constraint chk_pwex_failure check")
                || normalized.contains(" insert ") || normalized.contains(" update ") || normalized.contains(" delete ")
                || normalized.contains(" create trigger ") || !statement.endsWith(";")) {
            throw new IllegalStateException("Unsafe execution terminal-state migration");
        }
        return statement.substring(0, statement.length() - 1);
    }
    private void migrateTerminalState() {
        Integer present=jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
                 AND table_name='agent_personal_workspace_execution'
                 AND column_name IN ('failure_code','failure_message','failed_at')
                """, Integer.class);
        if (present == null) throw new IllegalStateException("Execution terminal-state schema discovery failed");
        if (present == 0) jdbc.execute(terminalStateMigrationStatement());
        else if (present != 3) throw new IllegalStateException("Execution terminal-state schema is partial");
    }
    private void verifyTerminalState() {
        Integer invalid=jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_personal_workspace_execution
                 WHERE execution_state NOT IN ('QUEUED','INPUTS_REVOKED','OUTPUT_COMMITTED','FAILED')
                    OR (execution_state='FAILED' AND (failure_code IS NULL
                        OR failure_code <> 'AGENT_DELIVERY_FAILED' OR failure_message IS NULL OR failed_at IS NULL))
                    OR (execution_state<>'FAILED' AND (failure_code IS NOT NULL
                        OR failure_message IS NOT NULL OR failed_at IS NOT NULL))
                """, Integer.class);
        if (invalid == null || invalid != 0) throw new IllegalStateException("Execution terminal-state schema is invalid");
    }
    static String taskExecutionMigrationStatement() {
        final String source;
        try { source=new ClassPathResource(TASK_EXECUTION_MIGRATION_RESOURCE).getContentAsString(StandardCharsets.UTF_8); }
        catch (IOException failure) { throw new IllegalStateException("Task execution migration is missing", failure); }
        String statement=source.lines().filter(line -> !line.stripLeading().startsWith("--")).reduce("", (a,b)->a+b+'\n').strip();
        String normalized=statement.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        if (!normalized.startsWith("alter table agent_personal_workspace_execution ")
                || !normalized.contains("add column execution_mode varchar(16) not null default 'private'")
                || !normalized.contains("add column work_item_id varchar(100)")
                || !normalized.contains("add column lease_token varchar(100)")
                || !normalized.contains("add column lease_work_item_version bigint")
                || !normalized.contains("add column lease_expires_at bigint")
                || !normalized.contains("drop check chk_pwex_state")
                || !normalized.contains("add constraint chk_pwex_mode check")
                || !normalized.contains("add constraint chk_pwex_task_bridge check")
                || normalized.contains(" insert ") || normalized.contains(" update ") || normalized.contains(" delete ")
                || normalized.contains(" create trigger ") || !statement.endsWith(";")) {
            throw new IllegalStateException("Unsafe task execution migration");
        }
        return statement.substring(0, statement.length() - 1);
    }
    private void migrateTaskExecution() {
        Integer present=jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
                 AND table_name='agent_personal_workspace_execution'
                 AND column_name IN ('execution_mode','work_item_id','lease_token','lease_work_item_version','lease_expires_at')
                """, Integer.class);
        if (present == null) throw new IllegalStateException("Task execution schema discovery failed");
        if (present == 0) jdbc.execute(taskExecutionMigrationStatement());
        else if (present != 5) throw new IllegalStateException("Task execution schema is partial");
    }
    private void verifyTaskExecution() {
        Integer invalid=jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_personal_workspace_execution
                 WHERE execution_mode NOT IN ('PRIVATE','TASK')
                    OR execution_state NOT IN ('QUEUED','INPUTS_REVOKED','OUTPUT_STAGED','OUTPUT_COMMITTED','FAILED')
                    OR (execution_mode='PRIVATE' AND (work_item_id IS NOT NULL OR lease_token IS NOT NULL
                        OR lease_work_item_version IS NOT NULL OR lease_expires_at IS NOT NULL))
                    OR (execution_mode='TASK' AND (work_item_id IS NULL OR lease_token IS NULL
                        OR lease_work_item_version IS NULL OR lease_work_item_version < 0
                        OR lease_expires_at IS NULL OR lease_expires_at <= 0))
                """, Integer.class);
        if (invalid == null || invalid != 0) throw new IllegalStateException("Task execution schema is invalid");
    }

    private void verifyOutputMime() {
        Integer invalid=jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_personal_workspace_execution
                 WHERE output_content_mime_type IS NULL
                    OR output_content_mime_type NOT IN ('image/png','image/jpeg','application/pdf',
                      'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
                      'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
                      'application/vnd.openxmlformats-officedocument.presentationml.presentation')
                """, Integer.class);
        if (invalid == null || invalid != 0) throw new IllegalStateException("Execution output MIME schema is invalid");
    }
    private List<String> presentTables() { return jdbc.queryForList("""
            SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()
             AND table_name IN ('agent_personal_workspace_execution','agent_personal_workspace_execution_input','agent_personal_workspace_execution_output')
             ORDER BY FIELD(table_name,'agent_personal_workspace_execution','agent_personal_workspace_execution_input','agent_personal_workspace_execution_output')
            """, String.class); }
    private void requireMySql() { DataSource source=jdbc.getDataSource(); if(source==null) throw new IllegalStateException("Execution schema requires JDBC"); try(Connection connection=source.getConnection()) { String database=connection.getMetaData().getDatabaseProductName(); if(database==null||!database.toLowerCase(Locale.ROOT).contains("mysql")) throw new IllegalStateException("Execution schema requires MySQL"); } catch(java.sql.SQLException failure) { throw new IllegalStateException("Execution schema database discovery failed",failure); } }
}
