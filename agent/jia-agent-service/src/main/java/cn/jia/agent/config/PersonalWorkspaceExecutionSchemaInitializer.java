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
    static final String TASK_PUBLICATION_MIGRATION_RESOURCE = "db/agent-personal-workspace-v1_13-task-publication.sql";
    static final String CONVERSATION_EXECUTION_RESOURCE = "db/agent-personal-workspace-v1_14-conversation-execution.sql";
    static final String CONVERSATION_OUTPUT_RESOURCE = "db/agent-personal-workspace-v1_14-conversation-output.sql";
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
        migrateTaskPublication();
        migrateConversationMode();
        verifyTaskExecution();
        verifyTaskPublication();
        verifyConversationMode();
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
                 WHERE execution_mode NOT IN ('PRIVATE','TASK','CONVERSATION')
                    OR execution_state NOT IN ('QUEUED','INPUTS_REVOKED','OUTPUT_STAGED','OUTPUT_COMMITTED','FAILED')
                    OR (execution_mode='PRIVATE' AND (work_item_id IS NOT NULL OR lease_token IS NOT NULL
                        OR lease_work_item_version IS NOT NULL OR lease_expires_at IS NOT NULL))
                    OR (execution_mode='TASK' AND (work_item_id IS NULL OR lease_token IS NULL
                        OR lease_work_item_version IS NULL OR lease_work_item_version < 0
                        OR lease_expires_at IS NULL OR lease_expires_at <= 0))
                    OR (execution_mode='CONVERSATION' AND (work_item_id IS NOT NULL OR lease_token IS NOT NULL
                        OR lease_work_item_version IS NOT NULL OR lease_expires_at IS NOT NULL
                        OR conversation_id IS NULL OR task_grant_id IS NULL OR task_grant_version IS NULL
                        OR task_grant_version < 1 OR assignment_revision IS NULL OR assignment_revision < 0
                        OR permitted_operation IS NULL))
                """, Integer.class);
        if (invalid == null || invalid != 0) throw new IllegalStateException("Task execution schema is invalid");
    }

    static String taskPublicationMigrationStatement() {
        final String source;
        try { source=new ClassPathResource(TASK_PUBLICATION_MIGRATION_RESOURCE).getContentAsString(StandardCharsets.UTF_8); }
        catch (IOException failure) { throw new IllegalStateException("Task publication migration is missing", failure); }
        String statement=source.lines().filter(line -> !line.stripLeading().startsWith("--")).reduce("", (a,b)->a+b+'\n').strip();
        String normalized=statement.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        if (!normalized.startsWith("alter table agent_personal_workspace_execution_output ")
                || !normalized.contains("add column artifact_id varchar(100)")
                || !normalized.contains("add column artifact_version int")
                || !normalized.contains("add column formal_delivery_id varchar(100)")
                || !normalized.contains("add column publication_state varchar(16) not null default 'pending'")
                || !normalized.contains("add column publication_revision bigint not null default 0")
                || !normalized.contains("add column publication_failure_code varchar(64)")
                || !normalized.contains("add constraint chk_pwexo_publication_state check")
                || !normalized.contains("add constraint chk_pwexo_publication_mapping check")
                || normalized.contains(" insert ") || normalized.contains(" update ") || normalized.contains(" delete ")
                || normalized.contains(" drop ") || normalized.contains(" create trigger ") || !statement.endsWith(";")) {
            throw new IllegalStateException("Unsafe task publication migration");
        }
        return statement.substring(0, statement.length() - 1);
    }
    private void migrateTaskPublication() {
        Integer present=jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
                 AND table_name='agent_personal_workspace_execution_output'
                 AND column_name IN ('artifact_id','artifact_version','formal_delivery_id','publication_state',
                                     'publication_revision','publication_failure_code')
                """, Integer.class);
        if (present == null) throw new IllegalStateException("Task publication schema discovery failed");
        if (present == 0) jdbc.execute(taskPublicationMigrationStatement());
        else if (present != 6) throw new IllegalStateException("Task publication schema is partial");
    }
    private void verifyTaskPublication() {
        Integer invalid=jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_personal_workspace_execution_output
                 WHERE publication_state NOT IN ('PENDING','PUBLISHED','FAILED')
                    OR publication_revision IS NULL OR publication_revision < 0
                    OR (publication_state='PENDING' AND (artifact_id IS NOT NULL OR artifact_version IS NOT NULL
                        OR formal_delivery_id IS NOT NULL OR publication_failure_code IS NOT NULL))
                    OR (publication_state='PUBLISHED' AND (artifact_id IS NULL OR artifact_version IS NULL
                        OR artifact_version < 1 OR formal_delivery_id IS NULL OR publication_failure_code IS NOT NULL))
                    OR (publication_state='FAILED' AND publication_failure_code IS NULL)
                """, Integer.class);
        if (invalid == null || invalid != 0) throw new IllegalStateException("Task publication schema is invalid");
    }

    /** Atomic ALTER on each table; a crash between the two is resumable, partial ALTER fails closed. */
    private void migrateConversationMode() {
        int executionColumns = requiredColumnCount("agent_personal_workspace_execution",
                "'task_grant_id','task_grant_version','assignment_revision','permitted_operation'");
        if (executionColumns == 0) jdbc.execute(conversationMigrationStatement(CONVERSATION_EXECUTION_RESOURCE,
                "agent_personal_workspace_execution", "drop check chk_pwex_mode", "drop check chk_pwex_task_bridge"));
        else if (executionColumns != 4) throw new IllegalStateException("Conversation execution schema is partial");
        int outputColumns = requiredColumnCount("agent_personal_workspace_execution_output", "'output_purpose'");
        if (outputColumns == 0) jdbc.execute(conversationMigrationStatement(CONVERSATION_OUTPUT_RESOURCE,
                "agent_personal_workspace_execution_output", "drop check chk_pwexo_commit", "add constraint chk_pwexo_conversation"));
        else if (outputColumns != 1) throw new IllegalStateException("Conversation output schema is partial");
    }
    private int requiredColumnCount(String table, String columns) {
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name='"+table+"' AND column_name IN ("+columns+")",
                Integer.class);
        if (count==null) throw new IllegalStateException("Conversation schema discovery unavailable");
        return count;
    }
    static String conversationMigrationStatement(String resource,String table,String drop,String add) {
        if (!List.of(CONVERSATION_EXECUTION_RESOURCE,CONVERSATION_OUTPUT_RESOURCE).contains(resource)
                || !List.of("agent_personal_workspace_execution","agent_personal_workspace_execution_output").contains(table))
            throw new IllegalArgumentException("Conversation migration resource invalid");
        final String source;
        try { source=new ClassPathResource(resource).getContentAsString(StandardCharsets.UTF_8); }
        catch (IOException missing) { throw new IllegalStateException("Conversation migration missing",missing); }
        String statement=source.lines().filter(line->!line.stripLeading().startsWith("--"))
                .reduce("",(a,b)->a+b+'\n').strip();
        String lower=statement.toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");
        if (!lower.startsWith("alter table "+table+" ") || !lower.contains(drop)
                || !lower.contains(add) || !lower.contains("add constraint chk_pwex")
                || lower.contains(" insert ") || lower.contains(" update ") || lower.contains(" delete ")
                || lower.contains(" drop table ") || lower.contains(" create trigger ")
                || !statement.endsWith(";") || statement.substring(0,statement.length()-1).contains(";"))
            throw new IllegalStateException("Unsafe conversation migration");
        return statement.substring(0,statement.length()-1);
    }
    void verifyConversationMode() {
        Integer invalid=jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_personal_workspace_execution
                 WHERE (execution_mode='CONVERSATION' AND (conversation_id IS NULL OR task_grant_id IS NULL
                     OR task_grant_version IS NULL OR task_grant_version<1 OR assignment_revision IS NULL
                     OR assignment_revision<0 OR permitted_operation IS NULL OR work_item_id IS NOT NULL
                     OR lease_token IS NOT NULL OR lease_work_item_version IS NOT NULL OR lease_expires_at IS NOT NULL))
                    OR (execution_mode<>'CONVERSATION' AND (task_grant_id IS NOT NULL
                     OR task_grant_version IS NOT NULL OR assignment_revision IS NOT NULL OR permitted_operation IS NOT NULL))
                """,Integer.class);
        if (invalid==null || invalid!=0) throw new IllegalStateException("Conversation execution row drift");
        Integer badOutputs=jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_personal_workspace_execution_output
                 WHERE output_purpose NOT IN ('FILE','CONVERSATION')
                    OR (output_purpose='CONVERSATION' AND (workspace_file_id IS NOT NULL
                       OR workspace_file_version IS NOT NULL OR publication_state<>'PENDING'
                       OR artifact_id IS NOT NULL OR artifact_version IS NOT NULL OR formal_delivery_id IS NOT NULL))
                """,Integer.class);
        if (badOutputs==null || badOutputs!=0) throw new IllegalStateException("Conversation output row drift");
        requireCheck("agent_personal_workspace_execution","chk_pwex_mode","conversation");
        requireCheck("agent_personal_workspace_execution","chk_pwex_task_bridge","task_grant_id");
        requireCheck("agent_personal_workspace_execution","chk_pwex_conversation_grant","assignment_revision");
        requireCheck("agent_personal_workspace_execution_output","chk_pwexo_commit","output_purpose");
        requireCheck("agent_personal_workspace_execution_output","chk_pwexo_conversation","formal_delivery_id");
    }
    private void requireCheck(String table,String name,String fragment) {
        var rows=jdbc.queryForList("""
                SELECT tc.enforced, cc.check_clause FROM information_schema.table_constraints tc
                JOIN information_schema.check_constraints cc ON cc.constraint_schema=tc.constraint_schema
                   AND cc.constraint_name=tc.constraint_name AND cc.constraint_catalog=tc.constraint_catalog
                WHERE tc.constraint_schema=DATABASE() AND tc.table_name=?
                  AND tc.constraint_type='CHECK' AND tc.constraint_name=?
                """,table,name);
        if (rows.size()!=1 || !"YES".equalsIgnoreCase(Objects.toString(rows.getFirst().get("enforced"),""))
                || !Objects.toString(rows.getFirst().get("check_clause"),"").toLowerCase(Locale.ROOT).contains(fragment))
            throw new IllegalStateException("Conversation CHECK missing or drifted: "+name);
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
