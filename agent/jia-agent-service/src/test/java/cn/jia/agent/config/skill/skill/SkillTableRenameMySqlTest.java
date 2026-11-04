package cn.jia.agent.config.skill.skill;

import cn.jia.agent.config.skill.SkillSchemaInitializer;
import cn.jia.agent.config.skill.SkillApplicationSchemaInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the exact operator SQL against MySQL, including rollback and name collision. */
@EnabledIfEnvironmentVariable(named = SkillMySqlTestFixture.URL_ENV, matches = ".+")
class SkillTableRenameMySqlTest {
    @Test
    void preservesRowsForeignKeysAndImmutableTriggersAndCanReverse() throws Exception {
        try (var fixture = new SkillMySqlTestFixture()) {
            fixture.start();
            JdbcTemplate jdbc = fixture.newDatabase("rename").jdbc();
            new SkillSchemaInitializer(jdbc).afterPropertiesSet();
            new SkillApplicationSchemaInitializer(jdbc).afterPropertiesSet();
            String management = resource("db/economy-skill-management-sm-c1.sql");
            for (String sql : cn.jia.core.sql.SchemaSqlText.splitSql(management)) jdbc.execute(sql);
            jdbc.update("INSERT INTO agent_skill_actor_root(tenant_id,client_id,actor_id) VALUES ('0','client','owner')");
            jdbc.update("INSERT INTO agent_skill_result_receipt(tenant_id,client_id,message_id,installation_id,request_hash,outcome,create_time) VALUES ('0','client','msg','install',UNHEX(REPEAT('01',32)),'INSTALLED',1)");
            List<String> before = catalog(jdbc);
            jdbc.execute(resource("db/migrations/20261104-agent-skill-table-names-down.sql"));
            assertEquals(21, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'economy_skill_%'", Integer.class));
            assertEquals("owner", jdbc.queryForObject("SELECT actor_id FROM economy_skill_actor_root", String.class));
            // A real conflicting target fails the whole multi-table rename, not a partial migration.
            jdbc.execute("CREATE TABLE agent_skill_agent_version (id BIGINT PRIMARY KEY)");
            assertThrows(org.springframework.dao.DataAccessException.class,
                    () -> jdbc.execute(resource("db/migrations/20261104-agent-skill-table-names-up.sql")));
            assertEquals(21, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'economy_skill_%'", Integer.class));
            jdbc.execute("DROP TABLE agent_skill_agent_version");
            jdbc.execute(resource("db/migrations/20261104-agent-skill-table-names-up.sql"));
            assertEquals(before, catalog(jdbc));
            assertEquals(18, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'agent_skill_%'", Integer.class));
            assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'economy_skill_%'", Integer.class));
            assertEquals("owner", jdbc.queryForObject("SELECT actor_id FROM agent_skill_actor_root", String.class));
            assertEquals("01".repeat(32), jdbc.queryForObject("SELECT HEX(request_hash) FROM agent_skill_result_receipt", String.class));
            assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update("DELETE FROM agent_skill_result_receipt"));
            assertEquals(before, catalog(jdbc));
        }
    }
    private static String resource(String name) throws java.io.IOException {
        return new ClassPathResource(name).getContentAsString(StandardCharsets.UTF_8);
    }
    private static List<String> catalog(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
            SELECT CONCAT(table_name,':',constraint_name,':',column_name,':',referenced_table_name,':',referenced_column_name) AS value
            FROM information_schema.key_column_usage WHERE constraint_schema=DATABASE() AND referenced_table_name IS NOT NULL
            UNION ALL
            SELECT CONCAT(trigger_name,':',event_object_table,':',action_statement)
            FROM information_schema.triggers WHERE trigger_schema=DATABASE() ORDER BY value
            """, String.class);
    }
}
