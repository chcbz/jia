package cn.jia.agent.skill;

import cn.jia.agent.config.AgentTaskCreationOperationSchemaInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Skill task intake shares the existing owned MySQL fixture; never migrates a configured server. */
class SkillManagementMaterialMigrationTest extends SkillManagementMySqlFixture {
    @Test void materialCountMigrationKeepsArrayIdentityAndIdempotencyProtection() throws Exception {
        String database="sm_material_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        try {
            var jdbc=new JdbcTemplate(source(database));
            new AgentTaskCreationOperationSchemaInitializer(jdbc).afterPropertiesSet();
            jdbc.execute("ALTER TABLE agent_task_creation_operation DROP CHECK chk_atco_refs, "
                    +"ADD CONSTRAINT chk_atco_refs CHECK (JSON_TYPE(input_refs_json)='ARRAY' "
                    +"AND JSON_LENGTH(input_refs_json) BETWEEN 0 AND 32)");
            insert(jdbc,"original","[]");
            String refs="["+String.join(",",Collections.nCopies(129,"{}"))+"]";
            assertThrows(DataAccessException.class,()->insert(jdbc,"many",refs));
            String sql=new ClassPathResource("db/agent-task-creation-operation-unbounded-materials.sql")
                    .getContentAsString(StandardCharsets.UTF_8).replaceAll("(?m)^\\s*--.*$"," ").trim();
            jdbc.execute(sql);
            new AgentTaskCreationOperationSchemaInitializer(jdbc).afterPropertiesSet();
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_creation_operation",Integer.class));
            insert(jdbc,"many",refs);
            assertEquals(129,jdbc.queryForObject("SELECT JSON_LENGTH(input_refs_json) FROM "
                    +"agent_task_creation_operation WHERE operation_id='many'",Integer.class));
            assertThrows(DataAccessException.class,()->insert(jdbc,"object","{}"));
            assertThrows(DataAccessException.class,()->insert(jdbc,"original","[]"));
            assertEquals("[]",jdbc.queryForObject("SELECT CAST(input_refs_json AS CHAR) FROM "
                    +"agent_task_creation_operation WHERE operation_id='original'",String.class));
        } finally {admin.execute("DROP DATABASE `"+database+"`");}
    }
    private static void insert(JdbcTemplate jdbc,String key,String refs) {
        jdbc.update("INSERT INTO agent_task_creation_operation "
                +"(operation_id,owner_jiacn,idempotency_key,request_hash,operation_state,"
                +"input_refs_json,created_at,tenant_id,client_id) "
                +"VALUES (?,'owner',?,?,'PROCESSING',CAST(? AS JSON),1,'0','client')",
                key,key,"a".repeat(64),refs);
    }
}
