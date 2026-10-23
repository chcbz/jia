package cn.jia.agent.skill;

import cn.jia.agent.config.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Own loopback MySQL8; no production connection or startup DDL hidden in detector. */
@EnabledIfEnvironmentVariable(named="CYF_SKILL_MYSQL_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/.*")
public class SkillExecutionSchemaMySqlTest {
    JdbcTemplate admin,jdbc;DriverManagerDataSource source;String database;
    @BeforeEach void setup() throws Exception {
        if(!"true".equals(System.getenv("CYF_SKILL_MYSQL_ISOLATED")))throw new IllegalStateException("Exclusive fixture required");
        setupIsolated(System.getenv("CYF_SKILL_MYSQL_URL"));
    }
    /** Only called by the self-managed SM-C1 fixture; the standalone env guard remains unchanged. */
    void setupIsolated(String url) throws Exception {
        if(url==null||!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/\\?.*"))throw new IllegalStateException("Owned loopback fixture URL required");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,"root",""));
        assertTrue(admin.queryForObject("SELECT VERSION()",String.class).startsWith("8."));
        database="skill5h_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        source=new DriverManagerDataSource(url.replace("/?","/"+database+"?"),"root","");jdbc=new JdbcTemplate(source);
        new PersonalWorkspaceExecutionSchemaInitializer(jdbc).afterPropertiesSet();
        new AgentTaskProviderCostConsentSchemaInitializer(jdbc).afterPropertiesSet();
        new ControlledImageExecutionSchemaInitializer(jdbc).afterPropertiesSet();
        new ControlledImageFollowupV3SchemaInitializer(jdbc).afterPropertiesSet();
        apply("db/economy-v0-skill-application.sql");
        apply("db/agent-skill-execution-v1.sql");apply("db/agent-personal-workspace-skill-v1.sql");
    }
    void apply(String name) throws Exception {String sql=new ClassPathResource(name).getContentAsString(StandardCharsets.UTF_8).lines().filter(l->!l.stripLeading().startsWith("--")).reduce("",(a,b)->a+b+"\n");for(String s:sql.split(";"))if(!s.isBlank())jdbc.execute(s);}
    @AfterEach void cleanup(){if(database!=null&&database.matches("skill5h_[a-f0-9]{32}"))admin.execute("DROP DATABASE `"+database+"`");}
    SkillExecutionSchemaInitializer detector(){return new SkillExecutionSchemaInitializer(jdbc);}
    @Test void latestCatalogReadOnlyDetectionAndLegacyRestartAccept() {
        detector().afterPropertiesSet();
        new PersonalWorkspaceExecutionSchemaInitializer(jdbc).afterPropertiesSet();
        new ControlledImageExecutionSchemaInitializer(jdbc).afterPropertiesSet();
        new ControlledImageFollowupV3SchemaInitializer(jdbc).afterPropertiesSet();
        detector().afterPropertiesSet();
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_skill_execution",Integer.class));
    }
    void row(String id,int protocol,String mime,String state) {
        jdbc.update("INSERT INTO agent_personal_workspace_execution (execution_id,owner_jiacn,task_id,run_id,conversation_id,target_agent_id,instruction,output_content_mime_type,execution_state,grant_revision,idempotency_key,request_hash,created_at,tenant_id,client_id,execution_mode,task_grant_id,task_grant_version,assignment_revision,permitted_operation,execution_protocol_version,runtime_input_snapshot_digest) VALUES (?,?,?,?,?,?,?,?,?,1,?,?,1,'0','client','CONVERSATION','grant',1,7,?,?,?)",id,"owner","task",id+"-run","42","agent","test",mime,state,id,"a".repeat(64),protocol==4?"EXECUTE_SKILL":"DELIBERATE",protocol,protocol==4?"a".repeat(64):null);
    }
    void attachment(String id) {jdbc.update("INSERT INTO agent_skill_execution (tenant_id,client_id,owner_jiacn,execution_id,skill_key,skill_version,package_sha256,manifest_sha256,snapshot_json,snapshot_sha256,phase,effects_json,created_at,updated_at) VALUES ('0','client','owner',?,'analysis','1.0.0',?,?,'{}',?,'READY','{}',1,1)",id,"a".repeat(64),"a".repeat(64),"a".repeat(64));}
    @Test void realChecksDistinguishGenericNullMimeFromLegacyAndKeepFailureFence() {
        row("new",4,null,"QUEUED");attachment("new");detector().afterPropertiesSet();
        assertThrows(RuntimeException.class,()->row("old",1,null,"QUEUED"));
        assertThrows(RuntimeException.class,()->row("old-result",1,"text/plain","RESULT_COMMITTED"));
        assertThrows(RuntimeException.class,()->jdbc.update("UPDATE agent_personal_workspace_execution SET permitted_operation='EDIT_IMAGE' WHERE execution_id='new'"));
        assertThrows(RuntimeException.class,()->jdbc.update("UPDATE agent_personal_workspace_execution SET execution_state='FAILED',failure_code='custom',failure_message='failure',failed_at=1 WHERE execution_id='new'"));
        assertThrows(RuntimeException.class,()->jdbc.update("UPDATE agent_personal_workspace_execution SET runtime_input_snapshot_digest=NULL WHERE execution_id='new'"));
    }
    @Test void detectorDoesNotRepairMissingAttachmentOrWeakenedChecks() {
        row("half",4,null,"QUEUED");assertThrows(IllegalStateException.class,()->detector().afterPropertiesSet());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_skill_execution",Integer.class));attachment("half");detector().afterPropertiesSet();
        jdbc.execute("ALTER TABLE agent_skill_execution DROP CHECK chk_skill_execution_version,ADD CONSTRAINT chk_skill_execution_version CHECK (version>=-1)");
        assertThrows(IllegalStateException.class,()->detector().afterPropertiesSet());
    }
    @Test void receiptScopeIndexIsExplicitAndDriftIsNotRepaired() {
        detector().afterPropertiesSet();
        jdbc.execute("ALTER TABLE economy_skill_result_receipt DROP INDEX idx_skill_receipt_installation_scope");
        assertThrows(IllegalStateException.class,()->detector().afterPropertiesSet());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='economy_skill_result_receipt' AND index_name='idx_skill_receipt_installation_scope'",Integer.class));
    }
    @Test void realCasAndTransactionPairRollback() {
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        assertThrows(IllegalStateException.class,()->tx.execute(status->{row("rollback",4,null,"QUEUED");attachment("rollback");throw new IllegalStateException("rollback");}));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_personal_workspace_execution",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_skill_execution",Integer.class));
        row("cas",4,null,"QUEUED");attachment("cas");
        assertEquals(1,jdbc.update("UPDATE agent_skill_execution SET phase='RUNNING',version=version+1 WHERE execution_id='cas' AND version=0 AND phase='READY'"));
        assertEquals(0,jdbc.update("UPDATE agent_skill_execution SET phase='RUNNING',version=version+1 WHERE execution_id='cas' AND version=0 AND phase='READY'"));
        detector().afterPropertiesSet();
    }
    // Additional cases are executed by the non-skippable owned SM-C1 TestFactory.
    void detectorRejectsMissingTaskBindingColumnWithoutRepair() {
        detector().afterPropertiesSet();
        jdbc.execute("ALTER TABLE agent_skill_execution DROP COLUMN task_skill_binding_json");
        assertThrows(IllegalStateException.class,()->detector().afterPropertiesSet());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='agent_skill_execution' AND column_name='task_skill_binding_json'",Integer.class));
    }
    void detectorRejectsWrongTaskBindingTypeWithoutRepair() {
        detector().afterPropertiesSet();
        jdbc.execute("ALTER TABLE agent_skill_execution MODIFY task_skill_binding_json LONGTEXT NULL");
        assertThrows(IllegalStateException.class,()->detector().afterPropertiesSet());
        assertEquals("longtext",jdbc.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='agent_skill_execution' AND column_name='task_skill_binding_json'",String.class));
    }
    void detectorRejectsNonNullableTaskBindingWithoutRepair() {
        detector().afterPropertiesSet();
        jdbc.execute("ALTER TABLE agent_skill_execution MODIFY task_skill_binding_json JSON NOT NULL");
        assertThrows(IllegalStateException.class,()->detector().afterPropertiesSet());
        assertEquals("NO",jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='agent_skill_execution' AND column_name='task_skill_binding_json'",String.class));
    }
    void detectorRejectsOrphanAndMismatchedPairedStateWithoutRepair() {
        attachment("orphan");assertThrows(IllegalStateException.class,()->detector().afterPropertiesSet());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_skill_execution",Integer.class));
        row("orphan",4,null,"QUEUED");detector().afterPropertiesSet();
        jdbc.update("UPDATE agent_skill_execution SET phase='CANCELLED' WHERE execution_id='orphan'");
        assertThrows(IllegalStateException.class,()->detector().afterPropertiesSet());
        assertEquals("QUEUED",jdbc.queryForObject("SELECT execution_state FROM agent_personal_workspace_execution",String.class));
        assertEquals("CANCELLED",jdbc.queryForObject("SELECT phase FROM agent_skill_execution",String.class));
    }

}
