package cn.jia.agent.platform;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import java.util.*;

/** Exact schema validation, no domain row backfill or silent migration. */
@Component
@ConditionalOnProperty(prefix="agent.platform-skills",name="enabled",havingValue="true")
public final class PlatformSkillSchemaInitializer implements InitializingBean {
    private final JdbcTemplate jdbc;
    public PlatformSkillSchemaInitializer(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public void afterPropertiesSet() throws Exception {
        var source=Objects.requireNonNull(jdbc.getDataSource());
        try(var connection=source.getConnection()) {
            if(!connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL"))
                throw new IllegalStateException("Platform skill provisioning requires MySQL 8");
        }
        new ResourceDatabasePopulator(new ClassPathResource("db/agent-platform-skills-schema.sql")).execute(source);
        for(String table:List.of("agent_platform_skill_scope","agent_platform_skill_installation")) {
            var properties=jdbc.queryForMap("SELECT ENGINE,TABLE_COLLATION FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?",table);
            var columns=jdbc.queryForList("SELECT COLUMN_NAME,COLUMN_TYPE,COLLATION_NAME,IS_NULLABLE,COLUMN_DEFAULT,EXTRA,GENERATION_EXPRESSION FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?",table);
            var indexes=jdbc.queryForList("SELECT INDEX_NAME,NON_UNIQUE,INDEX_TYPE,SUM(CASE WHEN SUB_PART IS NOT NULL THEN 1 ELSE 0 END) AS PREFIX_COUNT,GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? GROUP BY INDEX_NAME,NON_UNIQUE,INDEX_TYPE",table);
            var checks=jdbc.queryForList("""
                SELECT tc.CONSTRAINT_NAME,tc.ENFORCED,cc.CHECK_CLAUSE
                FROM information_schema.TABLE_CONSTRAINTS tc
                JOIN information_schema.CHECK_CONSTRAINTS cc
                ON cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME
                WHERE tc.CONSTRAINT_SCHEMA=DATABASE() AND tc.TABLE_NAME=? AND tc.CONSTRAINT_TYPE='CHECK'
                """,table);
            PlatformSkillSchemaContract.validate(table,properties,columns,indexes,checks);
            var foreignKeys=jdbc.queryForList("SELECT CONSTRAINT_NAME FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME=?",table);
            var triggers=jdbc.queryForList("SELECT TRIGGER_NAME FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND EVENT_OBJECT_TABLE=?",table);
            if(!foreignKeys.isEmpty() || !triggers.isEmpty())throw new IllegalStateException("Unexpected platform installation foreign key/trigger: "+table);
        }
    }
}
