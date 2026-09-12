package cn.jia.economy.config.skillapplication;
import cn.jia.economy.config.EconomySkillApplicationSchemaInitializer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/** Source-only handoff. Verifier must supply an isolated acknowledged W09 MySQL fixture. */
@EnabledIfEnvironmentVariable(named="ECO_V0_W09_MYSQL_URL",matches=".+")
class EconomySkillApplicationSchemaMySqlTest {
    private final EconomySkillApplicationMySqlTestFixture fixture=new EconomySkillApplicationMySqlTestFixture();
    @BeforeEach void start() { fixture.start(); }
    @AfterEach void close() { fixture.close(); }
    @Test void completeCatalogIsIdempotentAndDoesNotTouchAcceptedParentSchemas() {
        var jdbc=fixture.newDatabase("catalog").jdbc();var init=new EconomySkillApplicationSchemaInitializer(jdbc);
        init.afterPropertiesSet();init.afterPropertiesSet();
        assertEquals(6,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class));
        assertEquals(6,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE()",Integer.class));
    }
    @Test void materiallyMalformedFullCatalogFailsWithoutRepair() {
        List<String> mutations=List.of(
            "ALTER TABLE economy_skill_agent_version MODIFY version INT NOT NULL",
            "ALTER TABLE economy_skill_agent_version MODIFY source_hash BINARY(32) NULL",
            "ALTER TABLE economy_skill_agent_version MODIFY version BIGINT NOT NULL DEFAULT 1",
            "ALTER TABLE economy_skill_actor_root MODIFY actor_id VARCHAR(100) COLLATE utf8mb4_general_ci NOT NULL",
            "ALTER TABLE economy_skill_managed_credential DROP INDEX uk_skill_credential_key, ADD KEY uk_skill_credential_key(api_key_id)",
            "ALTER TABLE economy_skill_managed_credential ALTER INDEX uk_skill_credential_key INVISIBLE",
            "ALTER TABLE economy_skill_agent_version DROP CHECK chk_skill_agent_version, ADD CONSTRAINT chk_skill_agent_version CHECK(version>=0)",
            "ALTER TABLE economy_skill_agent_version ALTER CHECK chk_skill_agent_version NOT ENFORCED",
            "ALTER TABLE economy_skill_managed_credential ADD COLUMN unapproved VARCHAR(10) NULL");
        for(int n=0;n<mutations.size();n++) {
            var jdbc=fixture.newDatabase("drift"+n).jdbc();var init=new EconomySkillApplicationSchemaInitializer(jdbc);
            init.afterPropertiesSet();jdbc.execute(mutations.get(n));
            var before=jdbc.queryForList("SHOW CREATE TABLE economy_skill_agent_version");
            assertThrows(IllegalStateException.class,init::afterPropertiesSet,mutations.get(n));
            assertEquals(before,jdbc.queryForList("SHOW CREATE TABLE economy_skill_agent_version"));
        }
    }
    @Test void credentialKeyIsGloballyUniqueAndReceiptsAreImmutable() {
        var jdbc=fixture.newDatabase("immutable").jdbc();new EconomySkillApplicationSchemaInitializer(jdbc).afterPropertiesSet();
        jdbc.update("INSERT INTO economy_skill_managed_credential(credential_id,api_key_id,agent_id,binding_id,version,tenant_id,client_id,live_slot,create_time) VALUES('sc1','key1','agent1',1,1,'t','c',1,1)");
        assertThrows(RuntimeException.class,()->jdbc.update("INSERT INTO economy_skill_managed_credential(credential_id,api_key_id,agent_id,binding_id,version,tenant_id,client_id,live_slot,create_time) VALUES('sc2','key1','agent2',2,1,'other','c',1,1)"));
        jdbc.update("INSERT INTO economy_skill_result_receipt(tenant_id,client_id,message_id,installation_id,request_hash,outcome,create_time) VALUES('t','c','m','i',?,'UNKNOWN',1)",new byte[32]);
        assertThrows(RuntimeException.class,()->jdbc.update("UPDATE economy_skill_result_receipt SET outcome='REFUNDED'"));
        assertThrows(RuntimeException.class,()->jdbc.update("DELETE FROM economy_skill_result_receipt"));
    }
}
