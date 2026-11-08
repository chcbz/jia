package cn.jia.agent.skill;

import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Runs the exact operator SQL against private MySQL, never a configured/production datasource. */
class SkillManagementCatalogTypeMigrationTest extends SkillManagementMySqlFixture {
    private String database;
    private JdbcTemplate jdbc;
    @BeforeEach void setup() throws Exception {
        database="cyf_skill_type_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        var ds=source(database);jdbc=new JdbcTemplate(ds);
        for(String resource:new String[]{"db/economy-v0-skill-marketplace.sql","db/agent-position-skills.sql"})
            new ResourceDatabasePopulator(new ClassPathResource(resource)).execute(ds);
        jdbc.execute("ALTER TABLE agent_skill_product DROP COLUMN skill_type");
        jdbc.update("INSERT INTO agent_skill_product(product_id,name,description,status,tenant_id,client_id,create_time,update_time) VALUES('p','Skill','Shared','PUBLISHED','0','client',1,1)");
        jdbc.update("INSERT INTO agent_skill_product_version(product_version_id,product_id,version_sequence,skill_key,skill_version,price_wen,package_sha256,package_size,approved_permissions_manifest,approved_permissions_sha256,tenant_id,client_id,create_time) VALUES('v','p',1,'maintain','1.0.0',0,?,1,'[]',?,'0','client',1)",new byte[32],new byte[32]);
        var routine=new ClassPathResource("db/migrations/20261108-shared-skill-type.sql").getContentAsString(StandardCharsets.UTF_8);
        jdbc.execute(routine.substring(routine.indexOf("CREATE PROCEDURE"),routine.lastIndexOf("END$$")+3));
    }
    @AfterEach void cleanup(){if(database!=null&&database.matches("cyf_skill_type_[a-f0-9]{32}"))admin.execute("DROP DATABASE `"+database+"`");}
    private void definition(String owner,String type,String version) {
        String json="{\"schemaVersion\":1,\"skills\":{\"maintain\":{\"key\":\"maintain\",\"type\":\""+type+"\",\"productVersionId\":\""+version+"\"}},\"positions\":{},\"appointments\":{},\"installations\":{}}";
        jdbc.update("INSERT INTO agent_position_state VALUES('0','client',?,1,?)",owner,json);
    }
    private int columns(){return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='agent_skill_product' AND column_name='skill_type'",Integer.class);}
    @Test void backfillIsSharedIdempotentAndLeavesOwnerFactsUntouched() {
        definition("maintainer","POSITION","v");
        String before=jdbc.queryForObject("SELECT state_json FROM agent_position_state",String.class);
        jdbc.execute("CALL cyf_shared_skill_type('0','client')");
        assertEquals("POSITION",jdbc.queryForObject("SELECT skill_type FROM agent_skill_product",String.class));
        assertEquals(2L,jdbc.queryForObject("SELECT version FROM agent_skill_product",Long.class));
        jdbc.execute("CALL cyf_shared_skill_type('0','client')");
        assertEquals(2L,jdbc.queryForObject("SELECT version FROM agent_skill_product",Long.class));
        assertEquals(before,jdbc.queryForObject("SELECT state_json FROM agent_position_state",String.class));
    }
    @Test void conflictingOwnerFactsStopBeforeSchemaOrDataChanges() {
        definition("maintainer","POSITION","v");definition("other","GENERAL","v");
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute("CALL cyf_shared_skill_type('0','client')"));
        assertEquals(0,columns());assertEquals(1L,jdbc.queryForObject("SELECT version FROM agent_skill_product",Long.class));
    }
    @Test void unresolvedHistoricalPositionStopsBeforeSchemaChanges() {
        definition("maintainer","POSITION","missing");
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute("CALL cyf_shared_skill_type('0','client')"));
        assertEquals(0,columns());
    }
    @Test void classificationDoesNotLeakAcrossTenantOrClient() {
        definition("maintainer","POSITION","v");
        jdbc.execute("CALL cyf_shared_skill_type('other','client')");
        assertEquals("GENERAL",jdbc.queryForObject("SELECT skill_type FROM agent_skill_product",String.class));
        jdbc.execute("CALL cyf_shared_skill_type('0','other')");
        assertEquals("GENERAL",jdbc.queryForObject("SELECT skill_type FROM agent_skill_product",String.class));
        jdbc.execute("CALL cyf_shared_skill_type('0','client')");
        assertEquals("POSITION",jdbc.queryForObject("SELECT skill_type FROM agent_skill_product",String.class));
    }
}
