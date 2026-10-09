package cn.jia.chat.service;

import cn.jia.agent.service.AgentService;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.dao.impl.ChatConversationDaoImpl;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.mapper.ChatConversationMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real annotated service transaction and production owner mapper, no Provider or production database. */
@EnabledIfEnvironmentVariable(named="MMD_U1_REFERENCE_MYSQL_URL",matches=".+")
class ChatConversationEventReadMySqlTest {
    private JdbcTemplate admin,jdbc;
    private String database,namespace;
    private DataSourceTransactionManager transactions;
    private ChatConversationDaoImpl conversations;
    private ChatDeliberationDao events;
    private ChatDeliberationService service;

    @BeforeEach void setUp() throws Exception {
        String url=required("MMD_U1_REFERENCE_MYSQL_URL");
        String user=required("MMD_U1_REFERENCE_MYSQL_USER");
        String password=System.getenv("MMD_U1_REFERENCE_MYSQL_PASSWORD");
        String prefix=required("MMD_U1_REFERENCE_MYSQL_DATABASE_PREFIX");
        if(password==null||!url.startsWith("jdbc:mysql://")||!prefix.matches("[A-Za-z0-9_]{1,20}")
                ||!"true".equals(required("MMD_U1_REFERENCE_MYSQL_ISOLATED_FIXTURE")))
            throw new IllegalStateException("isolated MySQL acknowledgement required");
        namespace=prefix+"_event_read_";
        database=namespace+UUID.randomUUID().toString().substring(0,8);
        admin=new JdbcTemplate(ds(url,user,password));
        assertTrue(admin.queryForObject("SELECT VERSION()",String.class).startsWith("8."));
        assertEquals(0,admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",Integer.class,database));
        admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        var source=ds(databaseUrl(url,database),user,password);
        jdbc=new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE chat_conversation (
                  id BIGINT PRIMARY KEY,tenant_id VARCHAR(50),jiacn VARCHAR(50),client_id VARCHAR(50),
                  lifecycle_generation BIGINT,deleted_at BIGINT NULL,conversation_type VARCHAR(50)
                ) ENGINE=InnoDB
                """);
        jdbc.update("INSERT INTO chat_conversation VALUES(42,'0','owner','client',3,NULL,'juyiting')");
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
        config.addMapper(ChatConversationMapper.class);
        var global=new GlobalConfig();global.setIdentifierGenerator(new DefaultIdentifierGenerator());
        var factory=new MybatisSqlSessionFactoryBean();factory.setConfiguration(config);
        factory.setDataSource(source);factory.setGlobalConfig(global);
        var session=new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        conversations=spy(new ChatConversationDaoImpl());
        ReflectionTestUtils.setField(conversations,"baseMapper",session.getMapper(ChatConversationMapper.class));
        transactions=new DataSourceTransactionManager(source);
        events=mock(ChatDeliberationDao.class);
        when(events.eventHighWatermark("0","owner","client","42",3)).thenAnswer(i->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return 7L;
        });
        when(events.replayEvents("0","owner","client","42",3,0,7,500)).thenReturn(List.of());
        var target=new ChatDeliberationService(events,conversations,mock(ChatMessageDao.class),mock(AgentService.class));
        var proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));
        service=(ChatDeliberationService)proxy.getProxy();
    }

    @AfterEach void tearDown() {
        if(admin==null||database==null)return;
        if(!database.startsWith(namespace))throw new IllegalStateException("unowned database");
        admin.execute("DROP DATABASE IF EXISTS `"+database+"`");
    }

    @Test void ownerWatermarkAndReplayUseRealReadOnlyTransactionWithoutLockingRead() {
        assertEquals(7,service.eventHighWatermark("0","owner","client","42",3));
        assertEquals(List.of(),service.replayEventsThrough("0","owner","client","42",3,0,7,500));
        assertEquals(List.of(),service.replayEvents("0","owner","client","42",3,0,500));
        verify(conversations,never()).lockScopedById(anyString(),anyString(),anyString());
        verify(conversations,atLeast(3)).findScopedById("owner","client","42");
    }

    @Test void databaseReproducesReadOnlyLockFailureWhileWritableMutationLockRemainsValid() {
        var read=new TransactionTemplate(transactions);read.setReadOnly(true);
        RuntimeException error=assertThrows(RuntimeException.class,()->read.execute(s->conversations.lockScopedById("owner","client","42")));
        Throwable root=error;while(root.getCause()!=null)root=root.getCause();
        assertTrue(root.getMessage().contains("READ ONLY"),root.toString());
        var write=new TransactionTemplate(transactions);
        assertNotNull(write.execute(s->conversations.lockScopedById("owner","client","42")));
    }

    @Test void tenantOwnerClientCaseAndGenerationMismatchNeverReadEvents() {
        denied("other","owner","client","42",3);
        denied("0","other","client","42",3);
        denied("0","Owner","client","42",3);
        denied("0","owner","other","42",3);
        denied("0","owner","Client","42",3);
        denied("0","owner","client","43",3);
        denied("0","owner","client","42",2);
        verifyNoInteractions(events);
    }

    @Test void deletedAndRecreatedConversationsCannotReplayOldGeneration() {
        jdbc.update("UPDATE chat_conversation SET deleted_at=1,lifecycle_generation=4 WHERE id=42");
        denied("0","owner","client","42",3);
        denied("0","owner","client","42",4);
        jdbc.update("UPDATE chat_conversation SET deleted_at=NULL WHERE id=42");
        denied("0","owner","client","42",3);
        verifyNoInteractions(events);
    }

    private void denied(String tenant,String owner,String client,String id,long generation) {
        assertEquals(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,
                assertThrows(ChatDeliberationException.class,()->service.eventHighWatermark(tenant,owner,client,id,generation)).reason());
        assertEquals(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,
                assertThrows(ChatDeliberationException.class,()->service.replayEventsThrough(tenant,owner,client,id,generation,0,7,500)).reason());
    }
    private static DriverManagerDataSource ds(String url,String user,String password) {
        var ds=new DriverManagerDataSource();ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl(url);ds.setUsername(user);ds.setPassword(password);return ds;
    }
    private static String databaseUrl(String base,String database) {
        int query=base.indexOf('?');String suffix=query<0?"":base.substring(query);
        String plain=query<0?base:base.substring(0,query);return plain.substring(0,plain.lastIndexOf('/')+1)+database+suffix;
    }
    private static String required(String name) {
        String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException(name+" required");return value;
    }
}
