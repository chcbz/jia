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
    static final List<String> TABLES = List.of("agent_personal_workspace_execution",
            "agent_personal_workspace_execution_input", "agent_personal_workspace_execution_output");
    private final JdbcTemplate jdbc;
    public PersonalWorkspaceExecutionSchemaInitializer(JdbcTemplate jdbc) { this.jdbc=Objects.requireNonNull(jdbc,"jdbc"); }
    @Override public void afterPropertiesSet() {
        requireMySql(); List<String> present=presentTables();
        if (present.isEmpty()) ddlStatements().forEach(jdbc::execute);
        else if (!present.equals(TABLES)) throw new IllegalStateException("Personal workspace execution schema is partial: " + present);
        if (!presentTables().equals(TABLES)) throw new IllegalStateException("Personal workspace execution schema is unavailable");
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
    private List<String> presentTables() { return jdbc.queryForList("""
            SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()
             AND table_name IN ('agent_personal_workspace_execution','agent_personal_workspace_execution_input','agent_personal_workspace_execution_output')
             ORDER BY FIELD(table_name,'agent_personal_workspace_execution','agent_personal_workspace_execution_input','agent_personal_workspace_execution_output')
            """, String.class); }
    private void requireMySql() { DataSource source=jdbc.getDataSource(); if(source==null) throw new IllegalStateException("Execution schema requires JDBC"); try(Connection connection=source.getConnection()) { String database=connection.getMetaData().getDatabaseProductName(); if(database==null||!database.toLowerCase(Locale.ROOT).contains("mysql")) throw new IllegalStateException("Execution schema requires MySQL"); } catch(java.sql.SQLException failure) { throw new IllegalStateException("Execution schema database discovery failed",failure); } }
}
