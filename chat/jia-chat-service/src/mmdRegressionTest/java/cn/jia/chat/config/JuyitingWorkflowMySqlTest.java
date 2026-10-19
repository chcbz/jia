package cn.jia.chat.config;

import cn.jia.agent.config.AgentSelectedOutputFinalizationSchemaInitializer;
import cn.jia.agent.service.SelectedOutputFinalizationDigest.FormalArtifactSource;
import cn.jia.chat.service.ChatDeliveryReviewContract;
import cn.jia.chat.service.ChatDeliveryReviewContract.Accept;
import cn.jia.chat.service.ChatDeliveryReviewContract.Receipt;
import cn.jia.chat.service.ChatDeliveryReviewContract.Scope;
import cn.jia.chat.service.ChatSelectedOutputFinalizationStore;
import cn.jia.chat.service.ChatSelectedOutputFinalizationStore.Selection;
import cn.jia.chat.service.ChatTaskDeliveryReviewStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real MySQL, production schema initializers/stores and Spring transactions. No Provider or production target. */
@EnabledIfEnvironmentVariable(named="CYF_REVIEW_MYSQLD", matches=".+")
class JuyitingWorkflowMySqlTest {
    private static final Scope OWNER = new Scope("0", "client", "owner");
    private static final Accept ACCEPT = new Accept("delivery", 7, 0, 1, "a".repeat(64));
    @TempDir static Path scratch;
    private static Process databaseProcess;
    private static String baseUrl;
    private static JdbcTemplate admin;
    private String database;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private AnnotationConfigApplicationContext app;
    private ChatTaskDeliveryReviewStore reviews;
    private ChatSelectedOutputFinalizationStore promotions;

    @Configuration @EnableTransactionManagement(proxyTargetClass=true)
    static class Transactions { }

    @BeforeAll static void startOwnedDatabase() throws Exception {
        Path executable = Path.of(System.getenv("CYF_REVIEW_MYSQLD")).toRealPath();
        assertTrue(Files.isExecutable(executable));
        Path data = Files.createDirectory(scratch.resolve("data"));
        Path log = scratch.resolve("mysql.log");
        String user = System.getProperty("user.name");
        var init = new ProcessBuilder(executable.toString(), "--no-defaults", "--initialize-insecure",
                "--user="+user, "--datadir="+data, "--basedir="+executable.getParent().getParent())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertEquals(0, init.waitFor(), () -> "Owned MySQL init failed: "+read(log));
        int port;
        do { try(var socket=new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) { port=socket.getLocalPort(); } }
        while(port==3306 || port==33060);
        databaseProcess = new ProcessBuilder(executable.toString(), "--no-defaults", "--user="+user,
                "--datadir="+data, "--basedir="+executable.getParent().getParent(),
                "--socket="+scratch.resolve("mysql.sock"), "--pid-file="+scratch.resolve("mysql.pid"),
                "--port="+port, "--bind-address=127.0.0.1", "--mysqlx=OFF", "--skip-log-bin",
                "--innodb-buffer-pool-size=67108864")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
        baseUrl="jdbc:mysql://127.0.0.1:"+port+"/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        Class.forName("com.mysql.cj.jdbc.Driver");
        try {
            boolean ready=false;
            while(!ready) {
                assertTrue(databaseProcess.isAlive(), () -> "Owned MySQL exited: "+read(log));
                try(var connection=DriverManager.getConnection(baseUrl,"root","")) {
                    assertTrue(connection.getMetaData().getDatabaseProductVersion().startsWith("8.0.21"));
                    ready=true;
                } catch(SQLException notReady) { Thread.sleep(100); }
            }
            admin=new JdbcTemplate(new DriverManagerDataSource(baseUrl,"root",""));
            System.out.println("CYF_REVIEW_MYSQL owned=true version="+admin.queryForObject("SELECT VERSION()",String.class));
        } catch(Throwable failure) { stopOwnedDatabase(); throw failure; }
    }
    @AfterAll static void stopOwnedDatabase() throws Exception {
        if(databaseProcess!=null) { databaseProcess.destroy(); databaseProcess.waitFor(); databaseProcess=null; }
    }
    private static String read(Path file) { try { return Files.readString(file); } catch(Exception e) { return e.getClass().getSimpleName(); } }

    @BeforeEach void createDisposableSchema() {
        database="cyf_review_"+UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        var source=new DriverManagerDataSource(baseUrl.replace("/mysql?", "/"+database+"?"),"root","");
        jdbc=new JdbcTemplate(source);
        var manager=new DataSourceTransactionManager(source);
        tx=new TransactionTemplate(manager);
        app=new AnnotationConfigApplicationContext();
        app.register(Transactions.class);
        app.registerBean("transactionManager",DataSourceTransactionManager.class,()->manager);
        app.registerBean(ChatTaskDeliveryReviewStore.class,()->new ChatTaskDeliveryReviewStore(jdbc));
        app.registerBean(ChatSelectedOutputFinalizationStore.class,()->new ChatSelectedOutputFinalizationStore(jdbc));
        app.registerBean(cn.jia.chat.service.ChatTypedReviewContextStore.class,()->new cn.jia.chat.service.ChatTypedReviewContextStore(jdbc));
        app.refresh();
        reviews=app.getBean(ChatTaskDeliveryReviewStore.class);
        promotions=app.getBean(ChatSelectedOutputFinalizationStore.class);
        assertTrue(AopUtils.isAopProxy(reviews)); assertTrue(AopUtils.isAopProxy(promotions));
        createSourceTables();
    }
    @AfterEach void dropOnlyOwnedSchema() {
        if(app!=null)app.close();
        if(database!=null) {
            assertTrue(database.matches("cyf_review_[0-9a-f]{32}"));
            admin.execute("DROP DATABASE `"+database+"`");
        }
    }
    private void createSourceTables() {
        jdbc.execute("CREATE TABLE agent_task_meta (tenant_id VARCHAR(50),client_id VARCHAR(50),owner_jiacn VARCHAR(50),task_id VARCHAR(100),reward_status VARCHAR(32),task_version BIGINT,PRIMARY KEY(tenant_id,client_id,task_id)) ENGINE=InnoDB");
        jdbc.update("INSERT INTO agent_task_meta VALUES ('0','client','owner','task','reviewing',7)");
        jdbc.execute("CREATE TABLE agent_task_formal_delivery (tenant_id VARCHAR(50),client_id VARCHAR(50),task_id VARCHAR(100),delivery_id VARCHAR(100),state VARCHAR(32),version BIGINT,PRIMARY KEY(tenant_id,client_id,task_id,delivery_id)) ENGINE=InnoDB");
        jdbc.update("INSERT INTO agent_task_formal_delivery VALUES ('0','client','task','delivery','submitted',0)");
        jdbc.execute("CREATE TABLE agent_task_execution_grant (tenant_id VARCHAR(50),client_id VARCHAR(50),owner_jiacn VARCHAR(50),task_id VARCHAR(100),state VARCHAR(32),assignment_revision BIGINT)");
        jdbc.update("INSERT INTO agent_task_execution_grant VALUES ('0','client','owner','task','ACTIVE',1)");
        jdbc.execute("CREATE TABLE chat_conversation_asset (tenant_id VARCHAR(50),owner_jiacn VARCHAR(50),client_id VARCHAR(50),conversation_id VARCHAR(100),conversation_generation BIGINT,request_id VARCHAR(100),step_id VARCHAR(100),execution_id VARCHAR(100),run_id VARCHAR(100),output_id VARCHAR(100),content_mime_type VARCHAR(127),sha256 CHAR(64),byte_length BIGINT)");
        jdbc.execute("CREATE TABLE chat_conversation (id BIGINT,tenant_id VARCHAR(50),jiacn VARCHAR(50),client_id VARCHAR(50),deleted_at BIGINT,lifecycle_generation BIGINT,task_id VARCHAR(100),conversation_type VARCHAR(32),conversation_scope_type VARCHAR(32),conversation_scope_key VARCHAR(100))");
        jdbc.execute("CREATE TABLE chat_request (tenant_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,owner_jiacn VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,request_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,request_revision BIGINT NOT NULL,user_message_id BIGINT NOT NULL,conversation_id VARCHAR(100),conversation_generation BIGINT,PRIMARY KEY(tenant_id,owner_jiacn,client_id,request_id,request_revision)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE chat_message (id BIGINT,tenant_id VARCHAR(50),jiacn VARCHAR(50),client_id VARCHAR(50),conversation_id VARCHAR(100),content MEDIUMTEXT,metadata MEDIUMTEXT,message_type VARCHAR(32),sender_type VARCHAR(32))");
    }
    private ChatSelectedOutputFinalizationSchemaInitializer chatSchema() {
        return new ChatSelectedOutputFinalizationSchemaInitializer(jdbc,mock(ChatSchemaReadiness.class));
    }
    private void initialize() throws Exception {
        chatSchema().afterPropertiesSet();
        new AgentSelectedOutputFinalizationSchemaInitializer(jdbc).afterPropertiesSet();
        new ChatTaskDeliveryReviewSchemaInitializer(jdbc,mock(ChatSchemaReadiness.class)).afterPropertiesSet();
        new ChatTypedReviewContextSchemaInitializer(jdbc,mock(ChatDeliberationSchemaInitializer.class),true).afterPropertiesSet();
    }
    private static String resource(String path) {
        try { return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8).lines()
                .filter(l->!l.stripLeading().startsWith("--")).reduce("",(a,b)->a+b+'\n').strip(); }
        catch(Exception failure) { throw new IllegalStateException(failure); }
    }
    private void execute(String path) { jdbc.execute(resource(path)); }
    private String definition(String table) { return (String)jdbc.queryForMap("SHOW CREATE TABLE "+table).get("Create Table"); }
    private String allDefinitions() { return String.join("\n",List.of("chat_selected_output_finalization","chat_selected_output_finalization_item","agent_selected_output_finalization","chat_task_delivery_review_operation","chat_typed_review_context").stream().map(this::definition).toList()); }
    private <T> T inTransaction(Supplier<T> body) { return tx.execute(status->body.get()); }
    private void rootLock() { assertEquals(7,jdbc.queryForObject("SELECT task_version FROM agent_task_meta WHERE BINARY tenant_id=BINARY '0' AND BINARY client_id=BINARY 'client' AND BINARY owner_jiacn=BINARY 'owner' AND BINARY task_id=BINARY 'task' FOR UPDATE",Integer.class)); }
    private Receipt committed(ChatTaskDeliveryReviewStore.Operation op) {
        return new Receipt(1,op.operationId(),"task","delivery","ACCEPT","COMMITTED","2","COMPLETED","8","ACCEPTED","1",null,null,null,null,false);
    }
    private Selection formal(String artifact,int version) { return new Selection(null,null,null,"a".repeat(64),"原版本","RESULT",null,new FormalArtifactSource("old-delivery",artifact,version)); }
    private ChatSelectedOutputFinalizationStore.Operation create(String id,String key,List<Selection> selections) {
        return promotions.create("0","owner","client",id,"task",key,"b".repeat(64),"42",7,1,"新一轮",selections);
    }

    @Test void freshAndRepeatInitializersUseRealMySqlCheckMetadata() throws Exception {
        initialize(); String before=allDefinitions(); initialize(); assertEquals(before,allDefinitions());
        assertEquals("YES",jdbc.queryForObject("SELECT enforced FROM information_schema.table_constraints WHERE table_schema=DATABASE() AND constraint_name='chk_csofi_source'",String.class));
    }
    private void downgradeKnownPredecessor() {
        jdbc.execute("ALTER TABLE chat_selected_output_finalization DROP CHECK chk_csof_contract, DROP COLUMN contract_version");
        var oldCheck=ChatTaskDeliveryReviewSchemaInitializer.checks(resource("db/chat-selected-output-finalization-completed-message-v1.sql"));
        // Use the exact original SQL expression, not its comparison-normalized representation.
        String predecessor=resource("db/chat-selected-output-finalization-completed-message-v1.sql");
        String check=predecessor.substring(predecessor.indexOf("ADD CONSTRAINT chk_csofi_source CHECK ")+"ADD CONSTRAINT chk_csofi_source CHECK ".length());
        assertFalse(oldCheck.isEmpty());
        jdbc.execute("ALTER TABLE chat_selected_output_finalization_item DROP CHECK chk_csofi_source, DROP INDEX uk_csofi_formal, DROP COLUMN source_delivery_id, DROP COLUMN source_artifact_id, DROP COLUMN source_artifact_version, MODIFY request_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL, ADD CONSTRAINT chk_csofi_source CHECK "+check);
        jdbc.execute("ALTER TABLE agent_selected_output_finalization DROP CHECK chk_asof_contract, DROP COLUMN contract_version");
    }
    @Test void knownUpgradePreservesV1RowsAndIsRepeatable() throws Exception {
        initialize(); downgradeKnownPredecessor();
        jdbc.update("INSERT INTO chat_selected_output_finalization(operation_id,tenant_id,owner_jiacn,client_id,task_id,idempotency_key,request_digest,conversation_id,expected_task_version,expected_assignment_revision,summary,state,stage,state_version,task_state,task_version,created_at,updated_at) VALUES('old','0','owner','client','task','old-key-1',?,'42',7,1,'历史','pending','PROMOTING',1,'reviewing',7,1,1)","f".repeat(64));
        jdbc.update("INSERT INTO chat_selected_output_finalization_item(tenant_id,owner_jiacn,client_id,operation_id,item_order,request_id,step_id,output_id,sha256,title,purpose) VALUES('0','owner','client','old',0,'request','step','output',?,'旧成果','RESULT')","a".repeat(64));
        String before=jdbc.queryForMap("SELECT * FROM chat_selected_output_finalization WHERE operation_id='old'").toString();
        initialize();
        var row=jdbc.queryForMap("SELECT * FROM chat_selected_output_finalization WHERE operation_id='old'");
        assertEquals(1,row.remove("contract_version")); assertEquals(before,row.toString());
        String schema=allDefinitions(); initialize(); assertEquals(schema,allDefinitions());
        assertEquals(1,promotions.findByKey("0","owner","client","task","old-key-1").contractVersion());
        assertThrows(ChatSelectedOutputFinalizationStore.Conflict.class,()->create("new","old-key-1",List.of(formal("artifact",1))));
    }
    @Test void operationFirstInterruptedUpgradeResumes() throws Exception {
        initialize(); downgradeKnownPredecessor(); execute("db/chat-selected-output-finalization-contract-v2.sql");
        initialize(); assertDoesNotThrow(()->ChatSelectedOutputFinalizationSchemaInitializer.validateV2(jdbc));
    }
    @Test void itemFirstInterruptedUpgradeResumes() throws Exception {
        initialize(); downgradeKnownPredecessor(); execute("db/chat-selected-output-finalization-formal-source-v2.sql");
        initialize(); assertDoesNotThrow(()->ChatSelectedOutputFinalizationSchemaInitializer.validateV2(jdbc));
    }
    @Test void unknownPartialSourceSchemaIsRejectedBeforeOtherTableUpgrade() throws Exception {
        initialize(); downgradeKnownPredecessor();
        jdbc.execute("ALTER TABLE chat_selected_output_finalization_item ADD source_delivery_id VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL");
        String before=allDefinitions(); assertThrows(IllegalStateException.class,()->chatSchema().afterPropertiesSet());
        assertEquals(before,allDefinitions());
    }
    @Test void sameNamedWeakenedCheckCannotUpgradeOrMaskDrift() throws Exception {
        initialize(); downgradeKnownPredecessor();
        jdbc.execute("ALTER TABLE chat_selected_output_finalization_item DROP CHECK chk_csofi_source, ADD CONSTRAINT chk_csofi_source CHECK (1=1)");
        String before=allDefinitions(); assertThrows(IllegalStateException.class,()->chatSchema().afterPropertiesSet());
        assertEquals(before,allDefinitions());
    }
    @Test void changedFormalUniqueIndexIsRejectedWithoutRepair() throws Exception {
        initialize(); jdbc.execute("ALTER TABLE chat_selected_output_finalization_item DROP INDEX uk_csofi_formal, ADD INDEX uk_csofi_formal (tenant_id,owner_jiacn,client_id,operation_id,source_delivery_id,source_artifact_id,source_artifact_version)");
        String before=allDefinitions(); assertThrows(IllegalStateException.class,()->chatSchema().afterPropertiesSet()); assertEquals(before,allDefinitions());
    }
    @Test void exclusiveSourceUnionAndPositiveVersionAreActuallyEnforced() throws Exception {
        initialize(); var original=create("promotion","promotion-key",List.of(formal("artifact",2),formal("artifact2",7)));
        assertEquals(List.of(2,7),original.selections().stream().map(s->s.formalArtifactSource().sourceArtifactVersion()).toList());
        for(String assignment:List.of("request_id='hybrid'","source_artifact_version=0","source_delivery_id=NULL","source_artifact_id=NULL","step_id='hybrid'","source_kind='OUTPUT'")) {
            assertThrows(DataAccessException.class,()->jdbc.update("UPDATE chat_selected_output_finalization_item SET "+assignment+" WHERE operation_id='promotion' AND item_order=0"),assignment);
        }
        assertEquals(original,promotions.findByKey("0","owner","client","task","promotion-key"));
    }
    @Test void duplicateSelectionRollsBackEntireNewOperationNotAPartialBatch() throws Exception {
        initialize(); var selected=formal("artifact",2);
        assertThrows(DataAccessException.class,()->create("promotion","promotion-key",List.of(selected,selected)));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chat_selected_output_finalization",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chat_selected_output_finalization_item",Integer.class));
    }
    @Test void promotionRaceAfterRepeatableReadSnapshotReturnsSameCompleteWinner() throws Exception {
        initialize(); var gate=new CyclicBarrier(2);
        var results=concurrent(()->inTransaction(()->{
            assertNull(promotions.findByKey("0","owner","client","task","promotion-key")); await(gate);
            return create("promotion","promotion-key",List.of(formal("artifact",2),formal("artifact2",7)));
        }));
        assertEquals(results.getFirst(),results.get(1)); assertEquals(2,results.getFirst().selections().size());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM chat_selected_output_finalization",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM chat_selected_output_finalization_item",Integer.class));
    }
    @Test void acceptDecisionAndReceiptRollbackTogetherAfterReceiptWasWritten() throws Exception {
        initialize(); var op=reviews.admit(OWNER,"task","review-key",ACCEPT);
        assertThrows(IllegalTransactionStateException.class,()->reviews.settle(OWNER,op,committed(op)));
        assertThrows(IllegalStateException.class,()->inTransaction(()->{
            rootLock(); var locked=reviews.require(OWNER,"task",op.operationId(),true);
            jdbc.update("UPDATE agent_task_meta SET reward_status='completed',task_version=8 WHERE task_id='task'");
            jdbc.update("UPDATE agent_task_formal_delivery SET state='accepted',version=1 WHERE task_id='task'");
            reviews.settle(OWNER,locked,committed(op)); throw new IllegalStateException("rollback after receipt");
        }));
        assertEquals("reviewing",jdbc.queryForObject("SELECT reward_status FROM agent_task_meta",String.class));
        assertEquals("submitted",jdbc.queryForObject("SELECT state FROM agent_task_formal_delivery",String.class));
        assertEquals("PROCESSING",reviews.require(OWNER,"task",op.operationId(),false).state());
        assertNull(reviews.require(OWNER,"task",op.operationId(),false).receiptJson());
    }
    @Test void duplicateAcceptanceHasOneCommittedReceiptAndStaleCasCannotOverwriteIt() throws Exception {
        initialize(); var op=reviews.admit(OWNER,"task","review-key",ACCEPT);
        var results=concurrent(()->inTransaction(()->{
            rootLock(); var locked=reviews.require(OWNER,"task",op.operationId(),true);
            if(!"PROCESSING".equals(locked.state()))return ChatTaskDeliveryReviewStore.committedReceipt(locked);
            return reviews.settle(OWNER,locked,committed(locked));
        }));
        assertEquals(results.getFirst(),results.get(1));
        var committed=reviews.require(OWNER,"task",op.operationId(),false);
        assertEquals(2,committed.stateVersion()); assertEquals(results.getFirst(),ChatTaskDeliveryReviewStore.committedReceipt(committed));
        assertThrows(ChatDeliveryReviewContract.Conflict.class,()->inTransaction(()->reviews.settle(OWNER,op,committed(op))));
        assertEquals(committed,reviews.require(OWNER,"task",op.operationId(),false));
    }
    @Test void reviewSameKeyRaceUnderRepeatableReadDoesNotCreateOrphanOrNewDecision() throws Exception {
        initialize(); var gate=new CyclicBarrier(2);
        var results=concurrent(()->inTransaction(()->{
            assertNull(reviews.byKey(OWNER,"task","review-key",false)); await(gate);
            return reviews.admit(OWNER,"task","review-key",ACCEPT);
        }));
        assertEquals(results.getFirst(),results.get(1));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM chat_task_delivery_review_operation",Integer.class));
    }
    @Test void binaryOwnerScopeAndReadOnlyLookupDoNotChangeProgress() throws Exception {
        initialize(); var op=reviews.admit(OWNER,"task","review-key",ACCEPT);
        for(var wrong:List.of(new Scope("0","CLIENT","owner"),new Scope("0","client","OWNER"),new Scope("1","client","owner")))
            assertNull(reviews.byKey(wrong,"task","review-key",false));
        String before=jdbc.queryForMap("SELECT * FROM chat_task_delivery_review_operation").toString();
        for(int i=0;i<3;i++) { assertEquals("PROCESSING",reviews.progress(OWNER,op).state()); assertEquals(op,reviews.byKey(OWNER,"task","review-key",false)); }
        assertEquals(before,jdbc.queryForMap("SELECT * FROM chat_task_delivery_review_operation").toString());
        assertThrows(ChatDeliveryReviewContract.IdempotencyConflict.class,()->reviews.admit(OWNER,"task","review-key",new Accept("delivery",8,0,1,"a".repeat(64))));
        assertEquals(before,jdbc.queryForMap("SELECT * FROM chat_task_delivery_review_operation").toString());
    }
    @Test void revisionReceiptCannotCommitWithoutChildLineage() throws Exception {
        initialize();
        jdbc.update("INSERT INTO chat_task_delivery_review_operation(tenant_id,owner_jiacn,client_id,operation_id,task_id,delivery_id,idempotency_key,request_sha256,batch_sha256,request_json,decision,state,state_version,origin,source_request_id,source_message_id,parent_outcome_id,parent_final_digest,target_artifact_ids_json,created_at,updated_at) VALUES('0','owner','client','revision','task','delivery','revision-key',?,?,'{}','REQUEST_CHANGES','PROCESSING',1,'MESSAGE_REVISION','original-request','123','outcome',?,'[\"artifact\"]',1,1)","a".repeat(64),"b".repeat(64),"sha256:"+"c".repeat(64));
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE chat_task_delivery_review_operation SET state='COMMITTED',receipt_json='{}',state_version=2 WHERE operation_id='revision'"));
        assertEquals("PROCESSING",jdbc.queryForObject("SELECT state FROM chat_task_delivery_review_operation",String.class));
    }
    @Test void frozenReviewBasisRequiresRealParentAndSameSpringTransactionAndNeverInitializesOnRead() throws Exception {
        assertThrows(IllegalStateException.class,()->new ChatTypedReviewContextSchemaInitializer(jdbc,mock(ChatDeliberationSchemaInitializer.class),false).afterPropertiesSet());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='chat_typed_review_context'",Integer.class));
        initialize();
        var contexts=app.getBean(cn.jia.chat.service.ChatTypedReviewContextStore.class);
        var scope=new cn.jia.chat.deliberation.ChatTypedDeliberationStore.Scope("0","owner","client","42",7);
        assertTrue(AopUtils.isAopProxy(contexts));
        assertThrows(IllegalTransactionStateException.class,()->contexts.insert(scope,"request",1,null,1));
        assertThrows(IllegalStateException.class,()->contexts.require(scope,"request",1));
        assertThrows(DataAccessException.class,()->inTransaction(()->{contexts.insert(scope,"request",1,null,1);return null;}));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chat_typed_review_context",Integer.class));
        assertThrows(IllegalStateException.class,()->inTransaction(()->{
            jdbc.update("INSERT INTO chat_request VALUES('0','owner','client','rollback',1,123,'42',7)");
            contexts.insert(scope,"rollback",1,null,1); throw new IllegalStateException("after both rows");
        }));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chat_request",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chat_typed_review_context",Integer.class));
        inTransaction(()->{
            jdbc.update("INSERT INTO chat_request VALUES('0','owner','client','request',1,123,'42',7)");
            contexts.insert(scope,"request",1,null,1); return null;
        });
        String original=jdbc.queryForMap("SELECT * FROM chat_typed_review_context").toString();
        assertNull(contexts.require(scope,"request",1));
        assertThrows(DataAccessException.class,()->inTransaction(()->{contexts.insert(scope,"request",1,null,2);return null;}));
        assertThrows(IllegalStateException.class,()->contexts.require(new cn.jia.chat.deliberation.ChatTypedDeliberationStore.Scope("0","owner","client","42",8),"request",1));
        assertEquals(original,jdbc.queryForMap("SELECT * FROM chat_typed_review_context").toString());
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE chat_typed_review_context SET basis_json='[]'"));
        assertThrows(DataAccessException.class,()->jdbc.update("DELETE FROM chat_request WHERE request_id='request'"));
    }
    @Test void realMapperPageFilterAndGroupedTotalsShareTheSamePhaseAndExactOwnerScope() throws Exception {
        initialize();
        jdbc.execute("ALTER TABLE agent_task_meta ADD id BIGINT NOT NULL AUTO_INCREMENT UNIQUE, ADD required_abilities TEXT, ADD assigned_agent_id VARCHAR(100), ADD reward DECIMAL(18,2), ADD assigned_at BIGINT, ADD started_at BIGINT, ADD completed_at BIGINT, ADD failure_reason TEXT, ADD create_time BIGINT DEFAULT 1, ADD update_time BIGINT DEFAULT 1");
        jdbc.execute("ALTER TABLE agent_task_meta CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        jdbc.execute("ALTER TABLE agent_task_formal_delivery ADD revision BIGINT NOT NULL DEFAULT 1");
        jdbc.execute("CREATE TABLE task_plan(id BIGINT,jiacn VARCHAR(50),client_id VARCHAR(50),name VARCHAR(100),description TEXT,amount DECIMAL(18,2),create_time BIGINT,update_time BIGINT)");
        // The actual mapper's keyword expression includes these owner-scoped collaborators.
        jdbc.execute("CREATE TABLE agent_task_member(id BIGINT,tenant_id VARCHAR(50),client_id VARCHAR(50),owner_jiacn VARCHAR(50),task_id VARCHAR(100),agent_id VARCHAR(100),member_role VARCHAR(24),member_status VARCHAR(24))");
        jdbc.execute("CREATE TABLE agent_runtime(tenant_id VARCHAR(50),client_id VARCHAR(50),owner_jiacn VARCHAR(50),agent_id VARCHAR(100),name VARCHAR(100))");
        jdbc.update("DELETE FROM agent_task_meta"); jdbc.update("DELETE FROM agent_task_formal_delivery");
        var expected=new LinkedHashMap<String,String>();
        expected.put("1001","PENDING_ASSIGNMENT"); expected.put("1002","IN_PROGRESS");
        expected.put("1003","PENDING_ACCEPTANCE"); expected.put("1004","COMPLETED");
        expected.put("1005","UNKNOWN"); expected.put("1006","UNKNOWN");
        expected.put("1007","UNKNOWN"); expected.put("1008","UNKNOWN"); expected.put("1009","HISTORY");
        var statuses=List.of("open","assigned","reviewing","completed","reviewing","running","reviewing","REVIEWING","cancelled");
        int index=0;
        for(var item:expected.entrySet()) {
            jdbc.update("INSERT INTO agent_task_meta(tenant_id,client_id,owner_jiacn,task_id,reward_status,task_version,required_abilities) VALUES('0','client','owner',?,?,7,'[\"writing\"]')",item.getKey(),statuses.get(index++));
            jdbc.update("INSERT INTO task_plan(id,jiacn,client_id,name,description,amount,create_time,update_time) VALUES(?,'owner','client','检索目标','说明',0,1,1)",Long.parseLong(item.getKey()));
        }
        for(String id:List.of("1003","1005","1006","1007")) jdbc.update("INSERT INTO agent_task_formal_delivery VALUES('0','client',?,'first','submitted',0,1)",id);
        jdbc.update("INSERT INTO agent_task_formal_delivery VALUES('0','client','1005','second','submitted',0,2)");
        jdbc.update("INSERT INTO agent_task_formal_delivery VALUES('0','client','1007','second','changes_requested',0,2)");
        // Case-folding and padding at the default source-table collation must not widen authority.
        for(String wrong:List.of("OWNER","owner ")) jdbc.update("INSERT INTO agent_task_meta(tenant_id,client_id,owner_jiacn,task_id,reward_status,task_version) VALUES('0','client',?,?,'completed',7)",wrong,"foreign-"+wrong);
        var config=new com.baomidou.mybatisplus.core.MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new org.apache.ibatis.mapping.Environment("owned-review-mysql",new org.mybatis.spring.transaction.SpringManagedTransactionFactory(),jdbc.getDataSource()));
        config.addMapper(cn.jia.agent.mapper.AgentTaskMetaMapper.class);
        var mapper=new org.mybatis.spring.SqlSessionTemplate(new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(config)).getMapper(cn.jia.agent.mapper.AgentTaskMetaMapper.class);
        assertEquals(9,mapper.countSearchExactInScope("0","client","owner",null,null,null,null));
        assertEquals(9,mapper.countSearchExactInScope("0","client","owner",null,null,"检索目标",null));
        var actual=new LinkedHashMap<String,String>();
        for(int offset=0;offset<9;offset+=2) for(var row:mapper.searchPageExactInScope("0","client","owner",null,null,"检索目标",offset,2,null)) {
            assertNull(actual.put(row.getTaskId(),row.getPhase()),"pagination duplicated an item");
        }
        assertEquals(expected,actual);
        var grouped=mapper.countSearchByStatusExactInScope("0","client","owner",null,"检索目标",null);
        assertEquals(9,grouped.stream().mapToLong(cn.jia.agent.mapper.AgentTaskStatusCountRow::getTaskCount).sum());
        var counts=new HashMap<String,Long>();grouped.forEach(row->counts.merge(row.getPhase(),row.getTaskCount(),Long::sum));
        for(var phase:List.of("PENDING_ASSIGNMENT","IN_PROGRESS","PENDING_ACCEPTANCE","COMPLETED","HISTORY","UNKNOWN")) {
            long count=expected.values().stream().filter(phase::equals).count();
            assertEquals(count,counts.get(phase));
            assertEquals(count,mapper.countSearchExactInScope("0","client","owner",null,null,"检索目标",phase));
            assertEquals(count,mapper.searchPageExactInScope("0","client","owner",null,null,"检索目标",0,99,phase).size());
        }
        assertEquals(0,mapper.countSearchExactInScope("0","CLIENT","owner",null,null,null,null));
    }
    private static void await(CyclicBarrier gate) { try { gate.await(); } catch(Exception failure) {throw new IllegalStateException(failure);} }
    private static <T> List<T> concurrent(Supplier<T> body) throws Exception {
        var pool=Executors.newFixedThreadPool(2);
        try { var a=pool.submit(body::get); var b=pool.submit(body::get); return List.of(a.get(),b.get()); }
        finally {pool.shutdown(); assertTrue(pool.awaitTermination(30,TimeUnit.SECONDS));}
    }
}
