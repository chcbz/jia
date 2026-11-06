package cn.jia.chat.service.usage;

import cn.jia.chat.deliberation.ChatDispatchAuthorityMapper;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real persisted original-grant query, including revoked grants and replaced executor bindings. */
@EnabledIfEnvironmentVariable(named="ECO_V0_W02_MYSQL_URL", matches=".+")
class ChatUsageAuthorityMySqlTest {
    JdbcTemplate admin, jdbc; String database; ChatDispatchAuthorityMapper mapper;
    @BeforeEach void setup() throws Exception {
        assertEquals("true", System.getenv("ECO_V0_W02_MYSQL_ISOLATED_FIXTURE"));
        String base=System.getenv("ECO_V0_W02_MYSQL_URL"), prefix=System.getenv("ECO_V0_W02_MYSQL_DATABASE_PREFIX");
        assertTrue(base.startsWith("jdbc:mysql://")); assertNotNull(prefix); assertTrue(prefix.matches("[a-z][a-z0-9_]{0,23}"));
        String user=System.getenv("ECO_V0_W02_MYSQL_USER"), password=System.getenv("ECO_V0_W02_MYSQL_PASSWORD");
        admin=new JdbcTemplate(new DriverManagerDataSource(base,user,password));
        database=prefix+"_chatusage_"+Long.toUnsignedString(System.nanoTime(),36);
        admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        int q=base.indexOf('?'); String address=q<0?base:base.substring(0,q);
        String url=address.substring(0,address.indexOf('/',"jdbc:mysql://".length())+1)+database+(q<0?"":base.substring(q));
        var source=new DriverManagerDataSource(url,user,password); jdbc=new JdbcTemplate(source);
        String schema=Files.readString(Path.of("../../agent/jia-agent-mapper/src/main/resources/db/schema.sql"));
        int start=schema.indexOf("CREATE TABLE IF NOT EXISTS agent_runtime ("); assertTrue(start>=0);
        jdbc.execute(schema.substring(start,schema.indexOf(";\n",start)));
        jdbc.execute(Files.readString(Path.of("../../agent/jia-agent-mapper/src/main/resources/db/agent-task-execution-grant-v1.sql")));
        var config=new Configuration(); config.addMapper(ChatDispatchAuthorityMapper.class);
        var factory=new SqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(config);
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(ChatDispatchAuthorityMapper.class);
        jdbc.update("INSERT INTO agent_runtime(agent_id,name,tenant_id,client_id,owner_jiacn,binding_id) VALUES('agent','fixture','0','client','executor',7)");
        jdbc.update("""
                INSERT INTO agent_task_execution_grant(grant_id,tenant_id,client_id,owner_jiacn,task_id,
                  requirement_revision,assignment_revision,target_agent_id,executor_owner_jiacn,executor_binding_id,
                  permitted_operations_json,permitted_tool_policy_ref,input_scope_json,source_business_action_id,
                  idempotency_key,request_hash,policy_revision,grant_version,state,issued_by,created_at)
                VALUES('grant','0','client','owner','task',1,1,'agent','executor',7,'[]','fixture','{}',
                  'action','key',REPEAT('a',64),'fixture',1,'ACTIVE','owner',1)
                """);
    }
    @AfterEach void cleanup() {
        if(admin!=null && database!=null) {
            assertTrue(database.startsWith(System.getenv("ECO_V0_W02_MYSQL_DATABASE_PREFIX")+"_chatusage_"));
            admin.execute("DROP DATABASE `"+database+"`");
        }
    }
    boolean allowed() { return mapper.hasOriginalUsageExecutor("0","client","owner","task","grant","agent","executor"); }
    @Test void lateUsageSurvivesRevocationButNotExecutorReplacement() {
        assertTrue(allowed());
        jdbc.update("UPDATE agent_task_execution_grant SET state='REVOKED',grant_version=2,revoked_at=2");
        assertTrue(allowed());
        jdbc.update("UPDATE agent_runtime SET binding_id=8"); assertFalse(allowed());
        jdbc.update("UPDATE agent_runtime SET binding_id=7,owner_jiacn='other'"); assertFalse(allowed());
        jdbc.update("UPDATE agent_runtime SET owner_jiacn='executor',tenant_id='other'"); assertFalse(allowed());
    }
    @Test void everyOriginalScopeAndIdentityFieldMustMatchExactly() {
        String[] valid={"0","client","owner","task","grant","agent","executor"};
        for(int i=0;i<valid.length;i++) {
            String[] changed=valid.clone(); changed[i]+="X";
            assertFalse(mapper.hasOriginalUsageExecutor(changed[0],changed[1],changed[2],changed[3],changed[4],changed[5],changed[6]));
        }
        assertFalse(mapper.hasOriginalUsageExecutor("0","Client","owner","task","grant","agent","executor"));
        assertFalse(mapper.hasOriginalUsageExecutor("0","client","owner","task","Grant","agent","executor"));
        assertTrue(allowed());
    }
}
