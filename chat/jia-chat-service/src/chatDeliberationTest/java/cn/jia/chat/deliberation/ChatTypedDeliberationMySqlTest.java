package cn.jia.chat.deliberation;

import cn.jia.chat.config.ChatTypedDeliberationSchemaInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Optional real MySQL8 fixture. It creates and drops only an explicitly acknowledged database. */
@EnabledIfEnvironmentVariable(named="CYF_MMD_TYPED_MYSQL_JDBC_URL", matches=".+")
class ChatTypedDeliberationMySqlTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String database;
    private String namespace;

    @BeforeEach void setUp() {
        String url=required("CYF_MMD_TYPED_MYSQL_JDBC_URL");
        String user=required("CYF_MMD_TYPED_MYSQL_USER");
        String password=System.getenv("CYF_MMD_TYPED_MYSQL_PASSWORD");
        String prefix=required("CYF_MMD_TYPED_MYSQL_DATABASE_PREFIX");
        if(password==null||!url.startsWith("jdbc:mysql://")||!prefix.matches("[A-Za-z0-9_]{1,20}")
                ||!"1".equals(required("CYF_MMD_TYPED_MYSQL_OWNER_ACK")))
            throw new IllegalStateException("Typed deliberation isolated MySQL acknowledgement required");
        namespace=prefix+"_typed_followup_";
        database=namespace+UUID.randomUUID().toString().substring(0,8);
        admin=new JdbcTemplate(dataSource(url,user,password));
        String version=admin.queryForObject("SELECT VERSION()",String.class);
        assertTrue(version!=null&&version.startsWith("8."),version);
        admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        jdbc=new JdbcTemplate(dataSource(databaseUrl(url,database),user,password));
        prerequisiteSchema();
    }

    @AfterEach void tearDown() {
        if(admin==null||database==null)return;
        if(!database.startsWith(namespace))throw new IllegalStateException("Unowned typed deliberation fixture database");
        admin.execute("DROP DATABASE IF EXISTS `"+database+"`");
    }

    @Test void pristineToV3RestartAndExactCatalogAreReady() throws Exception {
        var first=new ChatTypedDeliberationSchemaInitializer(jdbc,true,true);
        first.initialize();assertTrue(first.ready());
        assertEquals(List.of("chat_typed_admission","chat_typed_outcome","chat_typed_pending_question","chat_typed_proposal"),
                jdbc.queryForList("""
                        SELECT table_name FROM information_schema.tables
                        WHERE table_schema=DATABASE() AND table_name LIKE 'chat_typed_%'
                        ORDER BY table_name
                        """,String.class));
        assertEquals("APPLIED",jdbc.queryForObject(
                "SELECT stage FROM chat_deliberation_schema_version WHERE version=3",String.class));
        List<String> before=showCreates();
        var restart=new ChatTypedDeliberationSchemaInitializer(jdbc,true,false);
        restart.initialize();assertTrue(restart.ready());assertEquals(before,showCreates());
    }

    @Test void partialAndSameNamedWeakenedCheckNeverBecomeReady() throws Exception {
        var initializer=new ChatTypedDeliberationSchemaInitializer(jdbc,true,true);
        initializer.initialize();assertTrue(initializer.ready());
        jdbc.execute("DROP TABLE chat_typed_admission");
        assertThrows(IllegalStateException.class,
                ()->new ChatTypedDeliberationSchemaInitializer(jdbc,true,true).initialize());

        jdbc.execute("DROP TABLE chat_typed_proposal");
        jdbc.execute("DROP TABLE chat_typed_pending_question");
        jdbc.execute("DROP TABLE chat_typed_outcome");
        new ChatTypedDeliberationSchemaInitializer(jdbc,true,true).initialize();
        jdbc.execute("""
                ALTER TABLE chat_typed_outcome
                  DROP CHECK chk_chat_typed_outcome_revision,
                  ADD CONSTRAINT chk_chat_typed_outcome_revision CHECK (request_revision=1 OR assignment_revision>=0)
                """);
        assertThrows(IllegalStateException.class,
                ()->new ChatTypedDeliberationSchemaInitializer(jdbc,true,false).initialize());
    }

    @Test void realUniqueForeignKeyAndCheckConstraintsRejectInvalidRows() throws Exception {
        new ChatTypedDeliberationSchemaInitializer(jdbc,true,true).initialize();
        insertRequestAndTurn();
        insertOutcome("outcome-a","turn-a");
        assertThrows(DataAccessException.class,()->insertOutcome("outcome-b","turn-a"));
        assertThrows(DataAccessException.class,()->jdbc.update("""
                INSERT INTO chat_typed_pending_question
                  (pending_question_id,outcome_id,tenant_id,owner_jiacn,client_id,conversation_id,
                   conversation_generation,state,state_version,question,required_facts_json,
                   reply_request_id,reply_idempotency_key,reply_body_digest,created_at,updated_at)
                VALUES ('pending','outcome-a','0','owner','client','42',1,'ANSWERED',1,'which?',
                        JSON_ARRAY('SOURCE_SELECTION'),NULL,NULL,NULL,1,1)
                """));
        assertThrows(DataAccessException.class,()->jdbc.update("""
                INSERT INTO chat_typed_proposal
                  (proposal_id,outcome_id,tenant_id,owner_jiacn,client_id,conversation_id,
                   conversation_generation,state,state_version,operation,instruction,source_ref_ids_json,
                   source_selectors_json,parent_request_id,parent_step_id,created_at)
                VALUES ('proposal','outcome-a','0','owner','client','42',1,'PROPOSED',0,
                        'EDIT_IMAGE','edit',JSON_ARRAY(),JSON_ARRAY(),'request',NULL,1)
                """));
        assertThrows(DataAccessException.class,()->jdbc.update("""
                INSERT INTO chat_typed_admission
                  (admission_id,tenant_id,owner_jiacn,client_id,conversation_id,conversation_generation,
                   idempotency_key,request_digest,body_digest,intent,task_id,assignment_revision,
                   parent_outcome_id,pending_question_id,request_id,request_revision,user_message_id,
                   turn_ids_json,source_catalog_json,state,state_version,event_cursor,created_at)
                VALUES ('admission','0','owner','client','42',1,'key','bad','bad','DISCUSSION','task',3,
                        NULL,NULL,'request-a',1,7,JSON_ARRAY('turn-a'),JSON_ARRAY(),'RUNNING',0,1,1)
                """));
    }

    private void prerequisiteSchema() {
        jdbc.execute("""
                CREATE TABLE chat_deliberation_schema_version (
                  version BIGINT NOT NULL,stage VARCHAR(30) NOT NULL,updated_at BIGINT NOT NULL,
                  PRIMARY KEY(version)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        jdbc.execute("""
                CREATE TABLE chat_request (
                  id BIGINT NOT NULL AUTO_INCREMENT,tenant_id VARCHAR(50) NOT NULL,
                  owner_jiacn VARCHAR(50) NOT NULL,client_id VARCHAR(50) NOT NULL,
                  request_id VARCHAR(100) NOT NULL,request_revision BIGINT NOT NULL,
                  request_digest VARCHAR(100) NOT NULL,conversation_id VARCHAR(100) NOT NULL,
                  conversation_generation BIGINT NOT NULL,user_message_id BIGINT NOT NULL,
                  aggregate_state VARCHAR(30) NOT NULL,state_version BIGINT NOT NULL DEFAULT 0,
                  created_at BIGINT NOT NULL,updated_at BIGINT NOT NULL,PRIMARY KEY(id),
                  UNIQUE KEY uk_chat_request_scope_revision
                    (tenant_id,owner_jiacn,client_id,request_id,request_revision)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        jdbc.execute("""
                CREATE TABLE chat_turn (
                  turn_id VARCHAR(64) NOT NULL,tenant_id VARCHAR(50) NOT NULL,
                  owner_jiacn VARCHAR(50) NOT NULL,client_id VARCHAR(50) NOT NULL,
                  request_id VARCHAR(100) NOT NULL,request_revision BIGINT NOT NULL,
                  conversation_id VARCHAR(100) NOT NULL,conversation_generation BIGINT NOT NULL,
                  target_agent_id VARCHAR(100) NOT NULL,snapshot_id VARCHAR(64) NOT NULL,
                  context_digest VARCHAR(100) NOT NULL,dispatch_id VARCHAR(64) NOT NULL,
                  route VARCHAR(20) NOT NULL,state VARCHAR(30) NOT NULL,
                  state_version BIGINT NOT NULL DEFAULT 0,last_delta_seq BIGINT NOT NULL DEFAULT 0,
                  created_at BIGINT NOT NULL,updated_at BIGINT NOT NULL,PRIMARY KEY(turn_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
    }

    private void insertRequestAndTurn() {
        jdbc.update("""
                INSERT INTO chat_request
                  (tenant_id,owner_jiacn,client_id,request_id,request_revision,request_digest,
                   conversation_id,conversation_generation,user_message_id,aggregate_state,state_version,created_at,updated_at)
                VALUES ('0','owner','client','request-a',1,?,'42',1,7,'RUNNING',0,1,1)
                ""","sha256:"+"a".repeat(64));
        jdbc.update("""
                INSERT INTO chat_turn
                  (turn_id,tenant_id,owner_jiacn,client_id,request_id,request_revision,
                   conversation_id,conversation_generation,target_agent_id,snapshot_id,context_digest,
                   dispatch_id,route,state,state_version,last_delta_seq,created_at,updated_at)
                VALUES ('turn-a','0','owner','client','request-a',1,'42',1,'agent','snapshot',?,
                        'dispatch','CHAT','RECEIVED',0,0,1,1)
                ""","sha256:"+"b".repeat(64));
    }

    private void insertOutcome(String outcomeId,String turnId) {
        jdbc.update("""
                INSERT INTO chat_typed_outcome
                  (outcome_id,tenant_id,owner_jiacn,client_id,conversation_id,conversation_generation,
                   request_id,request_revision,turn_id,task_id,assignment_revision,assistant_message_id,
                   final_digest,kind,text,binding_json,facts_json,outcome_json,source_catalog_json,created_at)
                VALUES (?,'0','owner','client','42',1,'request-a',1,?,'task',3,9,?,'ANSWER','answer',
                        JSON_OBJECT(),JSON_OBJECT(),JSON_OBJECT(),JSON_ARRAY(),1)
                """,outcomeId,turnId,"sha256:"+"c".repeat(64));
    }

    private List<String> showCreates() {
        return List.of("chat_typed_outcome","chat_typed_pending_question","chat_typed_proposal","chat_typed_admission").stream().map(table->jdbc.queryForObject(
                "SHOW CREATE TABLE "+table,(rs,row)->rs.getString(2))).toList();
    }

    private static DriverManagerDataSource dataSource(String url,String user,String password) {
        DriverManagerDataSource source=new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");source.setUrl(url);
        source.setUsername(user);source.setPassword(password);return source;
    }
    private static String databaseUrl(String base,String database) {
        int query=base.indexOf('?');String suffix=query<0?"":base.substring(query);
        String plain=query<0?base:base.substring(0,query);int slash=plain.lastIndexOf('/');
        return plain.substring(0,slash+1)+database+suffix;
    }
    private static String required(String name) {
        String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException(name+" is required");return value;
    }
}
