package cn.jia.chat.output;

import cn.jia.agent.config.OutputDeliveryProperties;
import cn.jia.agent.config.OutputObjectSchemaInitializer;
import cn.jia.agent.dao.AgentTaskArtifactDao;
import cn.jia.agent.dao.AgentTaskEventDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.dao.impl.AgentTaskArtifactDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskEventDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskMetaDaoImpl;
import cn.jia.agent.mapper.AgentTaskArtifactMapper;
import cn.jia.agent.mapper.AgentTaskEventMapper;
import cn.jia.agent.mapper.AgentTaskMetaMapper;
import cn.jia.agent.mapper.AgentTaskOutputMapper;
import cn.jia.agent.output.OutputConstants;
import cn.jia.agent.output.OutputObjectStorage;
import cn.jia.agent.output.OutputRunAuthorizationService;
import cn.jia.agent.output.OutputTicketAuthorization;
import cn.jia.agent.output.OutputVersionProvider;
import cn.jia.agent.output.dao.OutputUploadDao;
import cn.jia.agent.output.dao.impl.OutputUploadDaoImpl;
import cn.jia.agent.output.dto.OutputPublishDTO;
import cn.jia.agent.output.service.OutputDeliveryServiceImpl;
import cn.jia.agent.output.service.TaskOutputVersionProvider;
import cn.jia.agent.service.AgentTaskEventAfterCommitPublisher;
import cn.jia.agent.service.impl.AgentTaskEventWriterImpl;
import cn.jia.chat.config.ChatOutputSchemaInitializer;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatOutputDao;
import cn.jia.chat.dao.impl.ChatConversationDaoImpl;
import cn.jia.chat.dao.impl.ChatOutputDaoImpl;
import cn.jia.chat.mapper.ChatConversationMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(named="OD02_MYSQL_URL",matches=".+")
class OutputDeliveryMySqlIntegrationTest {
    private static final byte[] BYTES="hello world!\n".getBytes(StandardCharsets.UTF_8);
    private static final String HASH="ecf701f727d9e2d77c4aa49ac6fbbcc997278aca010bddeeb961c10cf54d435a";
    private static final String TASK_BEARER="Bearer "+"t".repeat(43);
    private static final String CHAT_BEARER="Bearer "+"c".repeat(43);
    private JdbcTemplate admin,jdbc;private String database;private DataSource dataSource;
    private OutputDeliveryServiceImpl service;
    private OutputUploadDao outputDao;
    private OutputRunAuthorizationService authorization;
    private TaskOutputVersionProvider taskProvider;
    private ConversationOutputVersionProvider chatProvider;

    @BeforeEach void setup() throws Exception {
        String base=env("OD02_MYSQL_URL");admin=new JdbcTemplate(ds(base));
        database="cyf_od03_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        dataSource=ds(url(base,database));jdbc=new JdbcTemplate(dataSource);createBusinessTables();
        new OutputObjectSchemaInitializer(jdbc).afterPropertiesSet();
        new ChatOutputSchemaInitializer(jdbc).run(null);
        SqlSessionTemplate sql=new SqlSessionTemplate(factory(dataSource));
        AgentTaskMetaDao taskDao=wire(new AgentTaskMetaDaoImpl(),sql.getMapper(AgentTaskMetaMapper.class));
        AgentTaskArtifactDao artifactDao=new AgentTaskArtifactDaoImpl(sql.getMapper(AgentTaskArtifactMapper.class));
        AgentTaskWorkItemDao workItemDao=mock(AgentTaskWorkItemDao.class);
        AgentTaskEventDao eventDao=wire(new AgentTaskEventDaoImpl(),sql.getMapper(AgentTaskEventMapper.class));
        var eventWriter=new AgentTaskEventWriterImpl(eventDao,new DataSourceTransactionManager(dataSource),
                mock(AgentTaskEventAfterCommitPublisher.class));
        taskProvider=new TaskOutputVersionProvider(taskDao,workItemDao,artifactDao,
                sql.getMapper(AgentTaskOutputMapper.class),eventWriter);
        ChatConversationDao conversationDao=wire(new ChatConversationDaoImpl(),
                sql.getMapper(ChatConversationMapper.class));
        ChatOutputDao chatOutputDao=new ChatOutputDaoImpl(jdbc);
        chatProvider=new ConversationOutputVersionProvider(conversationDao,chatOutputDao);
        authorization=mock(OutputRunAuthorizationService.class);
        when(authorization.authorizeTicket(anyString(),anyString(),org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(call->{String raw=call.getArgument(0);String type=raw.startsWith("t")?"TASK":"CONVERSATION";
                    return new OutputTicketAuthorization("owner","client",
                            "TASK".equals(type)?"task-run":"chat-run",type,
                            "TASK".equals(type)?"task-1":"101","agent-1","binding-1","runtime-1",
                            List.of("upload","publish","status"),System.currentTimeMillis()+60_000,"ACTIVE");});
        outputDao=new OutputUploadDaoImpl(jdbc);insertReady(outputDao,"task-object","task-run","task.txt");
        insertReady(outputDao,"chat-object","chat-run","chat.txt");
        service=new OutputDeliveryServiceImpl(authorization,outputDao,new FixtureStorage(),
                new DataSourceTransactionManager(dataSource),properties(),List.of(taskProvider,chatProvider));
    }

    @AfterEach void cleanup(){if(admin!=null&&database!=null){assertTrue(database.matches("cyf_od03_[0-9a-f]{32}"));
        admin.execute("DROP DATABASE IF EXISTS `"+database+"`");}}

    @Test void taskAndConversationPublishReplayAndDownloadExactBytesWithoutRuntime() throws Exception {
        var task=service.publish(TASK_BEARER,"task-publish-key01","TASK","task-1",
                request("task-run","artifact-1","task-object",true));
        var chat=service.publish(CHAT_BEARER,"chat-publish-key01","CONVERSATION","101",
                request("chat-run","output-1","chat-object",null));
        assertEquals(HASH,task.sha256());assertEquals(HASH,chat.sha256());
        assertEquals(task,service.publish(TASK_BEARER,"task-publish-key01","TASK","task-1",
                request("task-run","artifact-1","task-object",true)));
        assertEquals(chat,service.publish(CHAT_BEARER,"chat-publish-key01","CONVERSATION","101",
                request("chat-run","output-1","chat-object",null)));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_artifact",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM chat_output",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_event",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM output_mutation_receipt",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM output_object_reference WHERE reference_kind='OWNER_SHARE'",Integer.class));
        try(InputStream in=service.downloadVersion("owner","client","owner","TASK","task-1",
                "artifact-1","1").stream()){assertArrayEquals(BYTES,in.readAllBytes());}
        try(InputStream in=service.downloadVersion("owner","client","owner","CONVERSATION","101",
                "output-1","1").stream()){assertArrayEquals(BYTES,in.readAllBytes());}
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM output_object_reference WHERE reference_kind='READ_PIN' AND state='RELEASED'",Integer.class));
        assertEquals(404,assertThrows(cn.jia.agent.output.OutputDeliveryException.class,()->service.list(
                "other","client","other","TASK","task-1",null,20)).status());
        assertEquals(404,assertThrows(cn.jia.agent.output.OutputDeliveryException.class,()->service.list(
                "owner","client","owner","CONVERSATION","102",null,20)).status());
        jdbc.update("""
                INSERT INTO agent_task_artifact(
                  artifact_id,task_id,producer_agent_id,artifact_type,title,storage_uri,
                  content_hash,artifact_version,visibility,created_at,tenant_id,client_id)
                VALUES ('legacy-link','task-1','agent-1','link','legacy secret',
                        'https://legacy.invalid/private','',1,'task_members',1,'owner','client')
                """);
        jdbc.update("""
                INSERT INTO agent_task_artifact(
                  artifact_id,task_id,producer_agent_id,artifact_type,title,content,run_id,
                  content_byte_length,mime_type,owner_shared_at,retain_until,content_hash,
                  artifact_version,visibility,created_at,tenant_id,client_id)
                VALUES ('case-collision','Task-1','agent-1','document','wrong task',
                        'do not leak','task-run',11,'text/plain',?,?,?,
                        1,'task_members',2,'owner','client')
                """,System.currentTimeMillis(),System.currentTimeMillis()+60_000,
                "a".repeat(64));
        assertEquals(List.of("artifact-1"), service.list("owner","client","owner","TASK",
                "task-1",null,20).items().stream().map(item -> item.outputId()).toList());
    }

    @Test void concurrentFirstVersionPublicationHasOneWinnerAndOneVersionConflict() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try{
            Future<Integer> first=pool.submit(()->publishStatus(start,"race-key-publish-01"));
            Future<Integer> second=pool.submit(()->publishStatus(start,"race-key-publish-02"));
            start.countDown();
            List<Integer> statuses=java.util.stream.Stream.of(first.get(20,TimeUnit.SECONDS),
                    second.get(20,TimeUnit.SECONDS)).sorted().toList();
            assertEquals(List.of(200,409),statuses);
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_artifact WHERE artifact_id='race-output'",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM output_object_reference WHERE output_id='race-output'",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM output_mutation_receipt WHERE operation='publishTaskOutput' AND idempotency_key LIKE 'race-key-publish-%'",Integer.class));
        }finally{pool.shutdownNow();}
    }

    @Test void publicationFailureRollsBackArtifactReferenceEventAndReceiptTogether() {
        OutputVersionProvider failing=new ForwardingProvider(taskProvider){
            @Override public void appendPublicationEvent(PublishRow row){
                throw new IllegalStateException("injected event failure");
            }
        };
        OutputDeliveryServiceImpl candidate=new OutputDeliveryServiceImpl(authorization,outputDao,
                new FixtureStorage(),new DataSourceTransactionManager(dataSource),properties(),
                List.of(failing,chatProvider));

        assertThrows(IllegalStateException.class,()->candidate.publish(TASK_BEARER,
                "rollback-publish-key","TASK","task-1",
                request("task-run","rollback-output","task-object",true)));

        assertEquals(0,jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_task_artifact WHERE artifact_id='rollback-output'",Integer.class));
        assertEquals(0,jdbc.queryForObject(
                "SELECT COUNT(*) FROM output_object_reference WHERE output_id='rollback-output'",Integer.class));
        assertEquals(0,jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_task_event WHERE aggregate_id='rollback-output'",Integer.class));
        assertEquals(0,jdbc.queryForObject(
                "SELECT COUNT(*) FROM output_mutation_receipt WHERE idempotency_key='rollback-publish-key'",Integer.class));
    }

    @Test void publicationObjectLockBeatsConcurrentGcClaimAndCommitsReferenceFirst() throws Exception {
        outputDao.ensureScopeQuota("owner","client",1_000_000,8,System.currentTimeMillis());
        jdbc.update("UPDATE output_object SET delete_after=0,delete_next_at=0 WHERE object_id='task-object'");
        CountDownLatch inserted=new CountDownLatch(1),release=new CountDownLatch(1);
        OutputVersionProvider blocking=new ForwardingProvider(taskProvider){
            @Override public int insert(PublishRow row){inserted.countDown();await(release);return super.insert(row);}
        };
        OutputDeliveryServiceImpl publishing=new OutputDeliveryServiceImpl(authorization,outputDao,
                new FixtureStorage(),new DataSourceTransactionManager(dataSource),properties(),
                List.of(blocking,chatProvider));
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try{
            Future<?> publication=pool.submit(()->publishing.publish(TASK_BEARER,
                    "gc-race-publish-key","TASK","task-1",
                    request("task-run","gc-safe-output","task-object",true)));
            assertTrue(inserted.await(10,TimeUnit.SECONDS));
            Future<Boolean> gc=pool.submit(()->claimForGc("task-object"));
            Thread.sleep(200);
            assertTrue(!gc.isDone(),"GC must wait for the publication's object lock");
            release.countDown();
            publication.get(10,TimeUnit.SECONDS);
            assertEquals(false,gc.get(10,TimeUnit.SECONDS));
            assertEquals("READY",jdbc.queryForObject(
                    "SELECT lifecycle_status FROM output_object WHERE object_id='task-object'",String.class));
            assertEquals(1,jdbc.queryForObject(
                    "SELECT COUNT(*) FROM output_object_reference WHERE output_id='gc-safe-output' AND state='ACTIVE'",Integer.class));
        }finally{release.countDown();pool.shutdownNow();}
    }

    @Test void readPinProtectsObjectWhileDownloadStreamIsOpenThenReleases() throws Exception {
        service.publish(TASK_BEARER,"pin-race-publish-key","TASK","task-1",
                request("task-run","pin-output","task-object",true));
        jdbc.update("UPDATE output_object_reference SET state='RELEASED',released_at=?,hold=FALSE "
                + "WHERE output_id='pin-output'",System.currentTimeMillis());
        jdbc.update("UPDATE output_object SET delete_after=0,delete_next_at=0 WHERE object_id='task-object'");
        outputDao.ensureScopeQuota("owner","client",1_000_000,8,System.currentTimeMillis());
        CountDownLatch opening=new CountDownLatch(1),release=new CountDownLatch(1);
        OutputDeliveryServiceImpl downloading=new OutputDeliveryServiceImpl(authorization,outputDao,
                new BlockingOpenStorage(opening,release),new DataSourceTransactionManager(dataSource),
                properties(),List.of(taskProvider,chatProvider));
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try{
            Future<byte[]> bytes=pool.submit(()->{try(InputStream in=downloading.downloadVersion(
                    "owner","client","owner","TASK","task-1","pin-output","1").stream()){
                return in.readAllBytes();}});
            assertTrue(opening.await(10,TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM output_object_reference "
                    + "WHERE object_id='task-object' AND reference_kind='READ_PIN' AND state='ACTIVE'",Integer.class));
            assertEquals(false,claimForGc("task-object"));
            release.countDown();
            assertArrayEquals(BYTES,bytes.get(10,TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM output_object_reference "
                    + "WHERE object_id='task-object' AND reference_kind='READ_PIN' AND state='RELEASED'",Integer.class));
        }finally{release.countDown();pool.shutdownNow();}
    }

    private int publishStatus(CountDownLatch start,String key){await(start);try{service.publish(TASK_BEARER,
            key,"TASK","task-1",request("task-run","race-output","task-object",true));return 200;}
        catch(cn.jia.agent.output.OutputDeliveryException e){return e.status();}}

    private boolean claimForGc(String objectId){TransactionTemplate tx=new TransactionTemplate(
            new DataSourceTransactionManager(dataSource));Boolean claimed=tx.execute(status->{long now=System.currentTimeMillis();
        outputDao.lockScopeQuota("owner","client");OutputUploadDao.ObjectRow object=outputDao.findObject(
                "owner","client",objectId,true);if(object==null)return false;
        if(outputDao.activeObjectReferences("owner","client",objectId,now)>0)return false;
        return outputDao.claimObjectDelete("owner","client",objectId,"gc-test",now+60_000,now)==1;});
        return Boolean.TRUE.equals(claimed);}

    private static void await(CountDownLatch latch){try{if(!latch.await(15,TimeUnit.SECONDS))
        throw new IllegalStateException("latch timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}

    private OutputPublishDTO request(String run,String id,String object,Boolean share){return new OutputPublishDTO(
            run,"0","hello output","document",null,object,id,"1",null,
            "TASK".equals(run.startsWith("task")?"TASK":"CHAT")?"task_members":null,share);}

    private void insertReady(OutputUploadDao dao,String object,String run,String name)throws Exception{long now=System.currentTimeMillis();byte[] hash=MessageDigest.getInstance("SHA-256").digest(BYTES);
        dao.insertObject(new OutputUploadDao.ObjectRow("owner","client",object,run,"bucket",object,null,
                hash,13L,"text/plain","PASSED","READY","test",now,now+60_000,null,0,null,null,null,null),now);
        dao.insertUpload(new OutputUploadDao.UploadRow("owner","client","upload-"+object,run,"binding-1",object,
                name,13L,hash,"text/plain","READY",1,now,null,now+60_000,now+60_000,13,true,0,null,null,null,null),now);}
    private OutputDeliveryProperties properties(){return new OutputDeliveryProperties(true,null,null,null,
            "bucket","localhost",3310,10,50L*1024*1024,90L*1024*1024,null,false,
            "od03-real-mysql-cursor-signing-key-32");}
    private static final class FixtureStorage implements OutputObjectStorage{
        @Override public Stored putCreateOnly(String b,String k,InputStream i,long l,String t,long d){throw new UnsupportedOperationException();}
        @Override public InputStream open(String b,String k,String v){return new ByteArrayInputStream(BYTES);}
        @Override public void putTombstone(String b,String k,String t,long d){throw new UnsupportedOperationException();}
        @Override public Head head(String b,String k){throw new UnsupportedOperationException();}
        @Override public void delete(String b,String k,String v){throw new UnsupportedOperationException();}}

    private static final class BlockingOpenStorage implements OutputObjectStorage{
        private final CountDownLatch opening,release;
        private BlockingOpenStorage(CountDownLatch opening,CountDownLatch release){this.opening=opening;this.release=release;}
        @Override public Stored putCreateOnly(String b,String k,InputStream i,long l,String t,long d){throw new UnsupportedOperationException();}
        @Override public InputStream open(String b,String k,String v){opening.countDown();await(release);return new ByteArrayInputStream(BYTES);}
        @Override public void putTombstone(String b,String k,String t,long d){throw new UnsupportedOperationException();}
        @Override public Head head(String b,String k){throw new UnsupportedOperationException();}
        @Override public void delete(String b,String k,String v){throw new UnsupportedOperationException();}}

    private static class ForwardingProvider implements OutputVersionProvider{
        private final OutputVersionProvider delegate;
        private ForwardingProvider(OutputVersionProvider delegate){this.delegate=delegate;}
        @Override public String sourceType(){return delegate.sourceType();}
        @Override public void requireOwner(String t,String c,String j,String s,boolean lock){delegate.requireOwner(t,c,j,s,lock);}
        @Override public PublishRow findLatestForUpdate(String t,String c,String s,String o){return delegate.findLatestForUpdate(t,c,s,o);}
        @Override public int insert(PublishRow row){return delegate.insert(row);}
        @Override public void appendPublicationEvent(PublishRow row){delegate.appendPublicationEvent(row);}
        @Override public PublishRow findVersion(String t,String c,String s,String o,long v){return delegate.findVersion(t,c,s,o,v);}
        @Override public List<PublishRow> list(String t,String c,String s,String o,boolean latest,long snapshot,CursorBoundary after,int limit){return delegate.list(t,c,s,o,latest,snapshot,after,limit);}}

    private void createBusinessTables(){
        jdbc.execute("""
                CREATE TABLE agent_task_meta(id BIGINT AUTO_INCREMENT PRIMARY KEY,task_id VARCHAR(100) NOT NULL,
                reward_status VARCHAR(20),assigned_agent_id VARCHAR(100),required_abilities TEXT,reward INT,assigned_at BIGINT,
                started_at BIGINT,completed_at BIGINT,failure_reason VARCHAR(500),collaboration_mode VARCHAR(20),risk_level VARCHAR(20),
                max_agents INT,coordinator_agent_id VARCHAR(100),review_required BOOLEAN,task_version BIGINT,current_event_version BIGINT,
                tenant_id VARCHAR(50) NOT NULL,client_id VARCHAR(50) NOT NULL,create_time BIGINT,update_time BIGINT,
                UNIQUE KEY uk_task(tenant_id,client_id,task_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        jdbc.execute("""
                CREATE TABLE agent_task_artifact(id BIGINT AUTO_INCREMENT PRIMARY KEY,artifact_id VARCHAR(100) NOT NULL,
                task_id VARCHAR(100) NOT NULL,work_item_id VARCHAR(100),producer_agent_id VARCHAR(100) NOT NULL,artifact_type VARCHAR(30) NOT NULL,
                title VARCHAR(255) NOT NULL,content MEDIUMTEXT,storage_uri VARCHAR(1000),object_id VARBINARY(100),run_id VARBINARY(100),
                file_name VARCHAR(255),content_byte_length BIGINT,mime_type VARCHAR(100),owner_shared_at BIGINT,retain_until BIGINT,
                content_hash VARCHAR(128),artifact_version INT NOT NULL,visibility VARCHAR(20),metadata_json TEXT,created_at BIGINT NOT NULL,
                tenant_id VARCHAR(50) NOT NULL,client_id VARCHAR(50) NOT NULL,create_time BIGINT,update_time BIGINT,
                UNIQUE KEY uk_artifact_version(tenant_id,client_id,artifact_id,artifact_version)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        jdbc.execute("""
                CREATE TABLE agent_task_event(id BIGINT AUTO_INCREMENT PRIMARY KEY,task_id VARCHAR(100),event_version BIGINT,
                event_id VARCHAR(100),event_type VARCHAR(50),actor_type VARCHAR(20),actor_id VARCHAR(100),aggregate_type VARCHAR(30),
                aggregate_id VARCHAR(100),event_json TEXT,occurred_at BIGINT,tenant_id VARCHAR(50),client_id VARCHAR(50),
                create_time BIGINT,update_time BIGINT,UNIQUE KEY uk_event(tenant_id,client_id,event_id),
                UNIQUE KEY uk_version(tenant_id,client_id,task_id,event_version)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        jdbc.execute("""
                CREATE TABLE chat_conversation(id BIGINT AUTO_INCREMENT PRIMARY KEY,title VARCHAR(200),jiacn VARCHAR(50),
                conversation_type VARCHAR(30),conversation_scope_type VARCHAR(30),conversation_scope_key VARCHAR(200),task_id VARCHAR(100),
                target_agent_id VARCHAR(100),status INT,tenant_id VARCHAR(50) NOT NULL,client_id VARCHAR(50) NOT NULL,
                create_time BIGINT,update_time BIGINT) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        jdbc.update("INSERT INTO agent_task_meta(task_id,reward_status,collaboration_mode,risk_level,max_agents,review_required,task_version,current_event_version,tenant_id,client_id,create_time,update_time) VALUES ('task-1','running','single','low',1,0,0,0,'owner','client',1,1)");
        jdbc.update("INSERT INTO chat_conversation(id,title,jiacn,conversation_type,status,tenant_id,client_id,create_time,update_time) VALUES (101,'owned','owner','normal',1,'owner','client',1,1),(102,'other','other','normal',1,'other','client',1,1)");
    }

    private SqlSessionFactory factory(DataSource source)throws Exception{MybatisConfiguration c=new MybatisConfiguration();c.setMapUnderscoreToCamelCase(true);
        c.addMapper(AgentTaskMetaMapper.class);c.addMapper(AgentTaskArtifactMapper.class);c.addMapper(AgentTaskOutputMapper.class);
        c.addMapper(AgentTaskEventMapper.class);c.addMapper(ChatConversationMapper.class);GlobalConfig g=new GlobalConfig();
        g.setIdentifierGenerator(new DefaultIdentifierGenerator());g.setBanner(false);MybatisSqlSessionFactoryBean b=new MybatisSqlSessionFactoryBean();
        b.setDataSource(source);b.setTransactionFactory(new SpringManagedTransactionFactory());b.setConfiguration(c);b.setGlobalConfig(g);return b.getObject();}
    private <T>T wire(T target,Object mapper)throws Exception{Class<?> type=target.getClass();while(type!=null){try{Field f=type.getDeclaredField("baseMapper");f.setAccessible(true);f.set(target,mapper);return target;}catch(NoSuchFieldException ignored){type=type.getSuperclass();}}throw new NoSuchFieldException("baseMapper");}
    private DriverManagerDataSource ds(String url){DriverManagerDataSource d=new DriverManagerDataSource();d.setDriverClassName("com.mysql.cj.jdbc.Driver");d.setUrl(url);d.setUsername(env("OD02_MYSQL_USER"));d.setPassword(env("OD02_MYSQL_PASSWORD"));return d;}
    private static String url(String base,String db){int q=base.indexOf('?');String h=q<0?base:base.substring(0,q),tail=q<0?"":base.substring(q);int slash=h.indexOf('/',"jdbc:mysql://".length());return(slash<0?h+"/":h.substring(0,slash+1))+db+tail;}
    private static String env(String n){String v=System.getenv(n);if(v==null)throw new IllegalStateException(n+" missing");return v;}
}
