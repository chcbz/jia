package cn.jia.chat.service;

import cn.jia.agent.service.AgentService;
import cn.jia.chat.dao.impl.*;
import cn.jia.chat.deliberation.*;
import cn.jia.chat.mapper.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Chat SQL and transaction boundary. No Provider calls; task authorization is caller-owned. */
@EnabledIfEnvironmentVariable(named="ECO_V0_W02_MYSQL_URL",matches=".+")
class TaskDiscussionCancellationMySqlTest {
    JdbcTemplate admin,jdbc; String database; TransactionTemplate tx;
    TaskDiscussionCancellation service; ChatDeliberationDao dao;
    @BeforeEach void setup() throws Exception {
        assertEquals("true",System.getenv("ECO_V0_W02_MYSQL_ISOLATED_FIXTURE"));
        String base=System.getenv("ECO_V0_W02_MYSQL_URL"),prefix=System.getenv("ECO_V0_W02_MYSQL_DATABASE_PREFIX");
        assertTrue(base.startsWith("jdbc:mysql://"));assertNotNull(prefix);assertTrue(prefix.matches("[a-z][a-z0-9_]{0,23}"));
        String user=System.getenv("ECO_V0_W02_MYSQL_USER"),password=System.getenv("ECO_V0_W02_MYSQL_PASSWORD");
        admin=new JdbcTemplate(new DriverManagerDataSource(base,user,password));database=prefix+"_chatcancel_"+Long.toUnsignedString(System.nanoTime(),36);
        admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        int q=base.indexOf('?');String address=q<0?base:base.substring(0,q);
        String url=address.substring(0,address.indexOf('/',"jdbc:mysql://".length())+1)+database+(q<0?"":base.substring(q));
        var source=new DriverManagerDataSource(url,user,password);jdbc=new JdbcTemplate(source);
        String schema=Files.readString(Path.of("../jia-chat-mapper/src/test/resources/db/schema.sql"));
        for(String table:List.of("chat_conversation","chat_message")) {
            int start=schema.indexOf("CREATE TABLE "+table+" (");assertTrue(start>=0);
            jdbc.execute(schema.substring(start,schema.indexOf(";\n",start)));
        }
        new ResourceDatabasePopulator(new FileSystemResource("../jia-chat-mapper/src/main/resources/db/chat-deliberation-schema.sql")).execute(source);
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
        for(Class<?> mapper:List.of(ChatDeliberationMapper.class,ChatConversationMapper.class,ChatMessageMapper.class))config.addMapper(mapper);
        var global=new GlobalConfig();global.setIdentifierGenerator(new DefaultIdentifierGenerator());
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);factory.setConfiguration(config);factory.setGlobalConfig(global);
        var sql=new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        dao=spy(new ChatDeliberationDaoImpl(sql.getMapper(ChatDeliberationMapper.class)));
        var conversations=new ChatConversationDaoImpl();ReflectionTestUtils.setField(conversations,"baseMapper",sql.getMapper(ChatConversationMapper.class));
        var messages=new ChatMessageDaoImpl();ReflectionTestUtils.setField(messages,"baseMapper",sql.getMapper(ChatMessageMapper.class));
        var manager=new DataSourceTransactionManager(source);tx=new TransactionTemplate(manager);
        var proxy=new ProxyFactory(new ChatDeliberationService(dao,conversations,messages,mock(AgentService.class)));
        proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        service=(TaskDiscussionCancellation)proxy.getProxy();
    }
    @AfterEach void cleanup() {
        if(admin!=null&&database!=null) { assertTrue(database.startsWith(System.getenv("ECO_V0_W02_MYSQL_DATABASE_PREFIX")+"_chatcancel_"));admin.execute("DROP DATABASE `"+database+"`"); }
    }
    void conversation(long id,String tenant,String owner,String task) {
        jdbc.update("INSERT INTO chat_conversation(id,jiacn,tenant_id,client_id,task_id,conversation_type,lifecycle_generation) VALUES(?,?,?,'client',?,'juyiting',1)",id,owner,tenant,task);
    }
    void turn(String id,long conversation,String tenant,String owner,String route,String state) {
        jdbc.update("""
                INSERT INTO chat_request(tenant_id,owner_jiacn,client_id,request_id,request_revision,request_digest,
                  conversation_id,conversation_generation,user_message_id,aggregate_state,state_version,created_at,updated_at)
                VALUES (?,?,'client',?,1,REPEAT('a',64),?,1,100,'RUNNING',0,100,100)
                """,tenant,owner,"request-"+id,Long.toString(conversation));
        jdbc.update("""
                INSERT INTO chat_turn(turn_id,tenant_id,owner_jiacn,client_id,request_id,request_revision,conversation_id,
                  conversation_generation,target_agent_id,snapshot_id,context_digest,dispatch_id,route,state,state_version,
                  last_delta_seq,created_at,updated_at)
                VALUES (?,?,?,'client',?,1,?,1,'agent',?,REPEAT('a',64),?,?,?,0,0,100,100)
                """,id,tenant,owner,"request-"+id,Long.toString(conversation),"snapshot-"+id,"dispatch-"+id,route,state);
    }
    void cancel(){tx.executeWithoutResult(s->service.cancelTaskDiscussions("0","client","owner","task"));}
    String state(String id){return jdbc.queryForObject("SELECT state FROM chat_turn WHERE turn_id=?",String.class,id);}
    int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    @Test void cancelsOnlyTaskChatAndInspectPreservingFinalsAndOtherScopes() {
        conversation(1,"0","owner","task");conversation(2,"0","owner","other");conversation(3,"0","other-owner","task");conversation(4,"1","owner","task");
        turn("chat",1,"0","owner","CHAT","STREAMING");turn("inspect",1,"0","owner","INSPECT","DISPATCHED");
        turn("final",1,"0","owner","CHAT","FINAL_PERSISTED");turn("execute",1,"0","owner","EXECUTE","RUNNING");
        turn("other",2,"0","owner","CHAT","STREAMING");turn("foreign",3,"0","other-owner","CHAT","STREAMING");turn("tenant",4,"1","owner","CHAT","STREAMING");
        cancel();assertEquals("CANCELLED",state("chat"));assertEquals("CANCELLED",state("inspect"));
        assertEquals("FINAL_PERSISTED",state("final"));assertEquals("RUNNING",state("execute"));
        for(String id:List.of("other","foreign","tenant"))assertEquals("STREAMING",state(id));
        assertEquals(2,count("chat_dispatch_outbox"));assertEquals(2,count("chat_conversation_event"));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM chat_request WHERE aggregate_state='CANCELLED'",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM chat_dispatch_outbox WHERE event_type='CANCEL_REQUESTED' AND status='READY'",Integer.class));
        cancel();assertEquals(2,count("chat_dispatch_outbox"));
    }
    @Test void cancelledPartialIsStoredAsHistoryNotFinalAndReplaysOnce() {
        conversation(1,"0","owner","task");turn("chat",1,"0","owner","CHAT","STREAMING");
        jdbc.update("UPDATE chat_turn SET last_delta_seq=1 WHERE turn_id='chat'");
        var event=new ChatConversationEventEntity().setEventId("delta").setTenantId("0").setOwnerJiacn("owner").setClientId("client")
                .setConversationId("1").setConversationGeneration(1L).setRequestId("request-chat").setTurnId("chat")
                .setDispatchId("dispatch-chat").setEventType("agent_message_delta")
                .setPayloadJson("{\"content\":\"fixture partial\",\"senderName\":\"fixture\"}").setOccurredAt(110L);
        tx.executeWithoutResult(s->{assertEquals(1,dao.insertEvent(event));assertNotNull(event.getEventSequence());assertEquals(event.getEventSequence(),event.getEventVersion());});
        cancel();cancel();assertEquals(1,count("chat_message"));
        assertEquals("fixture partial",jdbc.queryForObject("SELECT content FROM chat_message",String.class));
        assertTrue(jdbc.queryForObject("SELECT metadata FROM chat_message",String.class).contains("CANCELLED"));
        assertNull(jdbc.queryForObject("SELECT final_digest FROM chat_turn",String.class));
        assertNull(jdbc.queryForObject("SELECT final_message_id FROM chat_turn",Long.class));
    }
    @Test void outerRollbackRestoresTurnAggregateAndDurableStop() {
        conversation(1,"0","owner","task");turn("chat",1,"0","owner","CHAT","STREAMING");
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(s->{service.cancelTaskDiscussions("0","client","owner","task");throw new IllegalStateException("later wallet operation failed");}));
        assertEquals("STREAMING",state("chat"));assertEquals(0,count("chat_dispatch_outbox"));assertEquals(0,count("chat_conversation_event"));
        assertEquals("RUNNING",jdbc.queryForObject("SELECT aggregate_state FROM chat_request",String.class));
    }
    @Test void concurrentReplaysProduceOneStopAndOneStateVersion() throws Exception {
        conversation(1,"0","owner","task");turn("chat",1,"0","owner","CHAT","STREAMING");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);Callable<Void> work=()->{start.await();cancel();return null;};
            var a=pool.submit(work);var b=pool.submit(work);start.countDown();a.get(20,TimeUnit.SECONDS);b.get(20,TimeUnit.SECONDS);
        }
        assertEquals("CANCELLED",state("chat"));assertEquals(1,count("chat_dispatch_outbox"));
        assertEquals(1L,jdbc.queryForObject("SELECT state_version FROM chat_turn",Long.class));
    }
    @Test void mandatoryAndExactIdentifiersDoNotLeakToAnotherTask() {
        conversation(1,"0","owner","task");turn("chat",1,"0","owner","CHAT","STREAMING");
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->service.cancelTaskDiscussions("0","client","owner","task"));
        tx.executeWithoutResult(s->{service.cancelTaskDiscussions("0","client","owner","Task");service.cancelTaskDiscussions("0","client","owner","task ");});
        assertEquals("STREAMING",state("chat"));assertEquals(0,count("chat_dispatch_outbox"));
    }
}
