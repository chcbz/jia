package cn.jia.chat.dao;

import cn.jia.chat.dao.impl.ChatConversationDaoImpl;
import cn.jia.chat.dao.impl.MemoryPublicationDaoImpl;
import cn.jia.chat.entity.ConversationDataScope;
import cn.jia.chat.entity.MemoryPublicationPayload;
import cn.jia.chat.entity.MemoryPublicationRecord;
import cn.jia.chat.mapper.ChatConversationMapper;
import cn.jia.chat.mapper.MemoryPublicationMapper;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryPublicationDaoIntegrationTest {
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private MemoryPublicationDao dao;
    private MapperProbe mapperProbe;
    private PublicationTransactionHarness transactionHarness;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(TestConfiguration.class);
        jdbc = context.getBean(JdbcTemplate.class);
        dao = context.getBean(MemoryPublicationDao.class);
        mapperProbe = context.getBean(MapperProbe.class);
        transactionHarness = context.getBean(PublicationTransactionHarness.class);
        createSchema();
    }

    @AfterEach
    void tearDown() {
        if (context != null) context.close();
    }

    @Test
    void springContainerAppliesTransactionalAdviceToStageAndBindPayload() throws Exception {
        assertTrue(AopUtils.isAopProxy(dao));
        assertTrue(dao instanceof Advised);
        TransactionInterceptor interceptor = Arrays.stream(((Advised) dao).getAdvisors())
                .map(Advisor::getAdvice)
                .filter(TransactionInterceptor.class::isInstance)
                .map(TransactionInterceptor.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("MemoryPublicationDao has no transaction advice"));
        assertTransactional(interceptor, "stage", ConversationDataScope.class, String.class,
                long.class, String.class, String.class, int.class, long.class);
        assertTransactional(interceptor, "bindPayload", MemoryPublicationRecord.class,
                MemoryPublicationPayload.class, long.class);
    }

    @Test
    void stageCommitsGeneratedIdAndPublicationRow() {
        insertConversation(41L);
        MemoryPublicationRecord staged = dao.stage(
                scope(), "41", 7, "snapshot-1", "document-1", 3, 1_000L);
        assertNotNull(staged.getId());
        assertEquals("ALLOCATED", staged.getState());
        assertEquals(1, publicationCount("snapshot-1"));
        assertEquals(staged.getId(), jdbc.queryForObject(
                "SELECT id FROM chat_memory_publication WHERE snapshot_token = 'snapshot-1'",
                Long.class));
    }

    @Test
    void stageRollsBackInsertWhenDaoFailsAfterMapperInsert() {
        insertConversation(42L);
        mapperProbe.failAfterPublicationInsert.set(true);
        InjectedPublicationFailure thrown = assertThrows(InjectedPublicationFailure.class,
                () -> dao.stage(scope(), "42", 7, "snapshot-internal-failure",
                        "document-internal-failure", 2, 2_000L));
        assertEquals("failure injected after publication insert", thrown.getMessage());
        assertTrue(mapperProbe.insertObservedInTransaction.get());
        assertEquals(0, publicationCount("snapshot-internal-failure"));
        assertEquals(1, conversationCount(42L));
    }

    @Test
    void outerCheckedFailureRollsBackPublicationAndConversationPointer() {
        insertConversation(43L);
        MemoryPublicationPayload payload = MemoryPublicationPayload.generated(
                "transactional summary", new float[]{0.25f, -0.5f});
        InjectedOuterFailure thrown = assertThrows(InjectedOuterFailure.class,
                () -> transactionHarness.publishThenFail(
                        scope(), "43", "snapshot-outer-failure", payload));
        assertEquals("failure injected after publication", thrown.getMessage());
        assertTrue(transactionHarness.transactionObserved());
        assertNotNull(transactionHarness.stagedPublication());
        assertNotNull(transactionHarness.stagedPublication().getId());
        assertEquals(0, publicationCount("snapshot-outer-failure"));
        assertNull(jdbc.queryForObject(
                "SELECT memory_published_token FROM chat_conversation WHERE id = 43",
                String.class));
        assertNull(jdbc.queryForObject(
                "SELECT memory_published_revision FROM chat_conversation WHERE id = 43",
                Long.class));
    }

    @Test
    void bindPayloadCasReturnsPersistedWinnerInsideTransaction() {
        insertConversation(44L);
        MemoryPublicationRecord allocated = dao.stage(
                scope(), "44", 7, "snapshot-cas", "document-cas", 4, 4_000L);
        MemoryPublicationPayload first = MemoryPublicationPayload.generated(
                "first stable summary", new float[]{1.0f, -2.0f});
        MemoryPublicationPayload losingRetry = MemoryPublicationPayload.generated(
                "retry must lose", new float[]{9.0f, 8.0f});
        MemoryPublicationRecord firstWinner = dao.bindPayload(allocated, first, 4_001L);
        MemoryPublicationRecord retryWinner = dao.bindPayload(allocated, losingRetry, 4_002L);
        assertEquals("STAGED", firstWinner.getState());
        assertEquals(first.payloadDigest(), firstWinner.getPayloadDigest());
        assertEquals(first.payloadDigest(), retryWinner.getPayloadDigest());
        assertEquals(first.summaryContent(), retryWinner.getSummaryContent());
        assertArrayEquals(first.embeddingBytes(), retryWinner.getEmbeddingBytes());
        assertEquals(2, mapperProbe.winnerReadsInTransaction.get());
        assertFalse(mapperProbe.winnerReadOutsideTransaction.get());
        assertEquals(first.payloadDigest(), jdbc.queryForObject(
                "SELECT payload_digest FROM chat_memory_publication WHERE id = ?",
                String.class, allocated.getId()));
    }

    private void assertTransactional(
            TransactionInterceptor interceptor, String methodName, Class<?>... parameterTypes)
            throws Exception {
        Method method = MemoryPublicationDao.class.getMethod(methodName, parameterTypes);
        TransactionAttribute attribute = interceptor.getTransactionAttributeSource()
                .getTransactionAttribute(method, AopUtils.getTargetClass(dao));
        assertNotNull(attribute, methodName + " must be transaction-advised");
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRED,
                attribute.getPropagationBehavior());
        assertTrue(attribute.rollbackOn(new Exception("checked")),
                methodName + " must roll back checked exceptions");
    }

    private void insertConversation(long id) {
        jdbc.update("""
                INSERT INTO chat_conversation
                    (id, tenant_id, data_domain_id, client_id, jiacn,
                     lifecycle_generation, data_scope_mode, deleted_at,
                     memory_published_token, memory_published_revision, update_time)
                VALUES (?, '1', 'cyf', 'web', 'tester', 7, 'DOMAIN', NULL, NULL, NULL, 1)
                """, id);
    }

    private int publicationCount(String snapshotToken) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_memory_publication WHERE snapshot_token = ?",
                Integer.class, snapshotToken);
    }

    private int conversationCount(long id) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_conversation WHERE id = ?", Integer.class, id);
    }

    private ConversationDataScope scope() {
        return new ConversationDataScope(
                new BusinessScope(TenantId.of("1"), DataDomainId.of("cyf"), "tester", "web"),
                ConversationDataScope.Mode.DOMAIN);
    }

    private void createSchema() {
        jdbc.execute("""
                CREATE TABLE chat_conversation (
                  id BIGINT PRIMARY KEY,
                  tenant_id VARCHAR(50) NOT NULL,
                  data_domain_id VARCHAR(64),
                  client_id VARCHAR(50),
                  jiacn VARCHAR(50) NOT NULL,
                  lifecycle_generation BIGINT NOT NULL,
                  data_scope_mode VARCHAR(20) NOT NULL,
                  deleted_at BIGINT,
                  memory_published_token VARCHAR(100),
                  memory_published_revision BIGINT,
                  update_time BIGINT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE chat_memory_publication (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  tenant_id VARCHAR(50) NOT NULL,
                  scope_mode VARCHAR(20) NOT NULL,
                  scope_key VARCHAR(64) NOT NULL,
                  owner_jiacn VARCHAR(50) NOT NULL,
                  conversation_id VARCHAR(100) NOT NULL,
                  lifecycle_generation BIGINT NOT NULL,
                  snapshot_token VARCHAR(100) NOT NULL,
                  es_revision BIGINT NOT NULL,
                  document_id VARCHAR(100) NOT NULL,
                  message_count INT NOT NULL,
                  state VARCHAR(20) NOT NULL,
                  summary_content CLOB,
                  embedding_bytes BLOB,
                  payload_digest VARCHAR(64),
                  published_at BIGINT,
                  tombstoned_at BIGINT,
                  create_time BIGINT NOT NULL,
                  update_time BIGINT NOT NULL,
                  UNIQUE (tenant_id, scope_mode, scope_key, owner_jiacn,
                          conversation_id, lifecycle_generation, snapshot_token))
                """);
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TestConfiguration {
        @Bean
        DataSource dataSource() {
            DriverManagerDataSource source = new DriverManagerDataSource();
            source.setDriverClassName("org.h2.Driver");
            source.setUrl("jdbc:h2:mem:memory_publication_tx_" + UUID.randomUUID()
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
            source.setUsername("sa");
            source.setPassword("");
            return source;
        }

        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean PlatformTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(DataSource source) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(ChatConversationMapper.class);
            configuration.addMapper(MemoryPublicationMapper.class);
            GlobalConfig global = new GlobalConfig();
            global.setIdentifierGenerator(new DefaultIdentifierGenerator());
            MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
            bean.setDataSource(source);
            bean.setConfiguration(configuration);
            bean.setGlobalConfig(global);
            SqlSessionFactory factory = bean.getObject();
            if (factory == null) throw new IllegalStateException("missing SqlSessionFactory");
            return new SqlSessionTemplate(factory);
        }

        @Bean ChatConversationMapper chatConversationMapper(SqlSessionTemplate template) {
            return template.getMapper(ChatConversationMapper.class);
        }
        @Bean MapperProbe mapperProbe() { return new MapperProbe(); }
        @Bean MemoryPublicationMapper memoryPublicationMapper(
                SqlSessionTemplate template, MapperProbe probe) {
            MemoryPublicationMapper delegate = template.getMapper(MemoryPublicationMapper.class);
            return (MemoryPublicationMapper) Proxy.newProxyInstance(
                    MemoryPublicationMapper.class.getClassLoader(),
                    new Class<?>[]{MemoryPublicationMapper.class},
                    (proxy, method, args) -> probe.invoke(delegate, method, args));
        }
        @Bean ChatConversationDaoImpl chatConversationDao() { return new ChatConversationDaoImpl(); }
        @Bean MemoryPublicationDaoImpl memoryPublicationDao() { return new MemoryPublicationDaoImpl(); }
        @Bean PublicationTransactionHarness publicationTransactionHarness(MemoryPublicationDao dao) {
            return new PublicationTransactionHarness(dao);
        }
    }

    static final class MapperProbe {
        private final AtomicBoolean failAfterPublicationInsert = new AtomicBoolean();
        private final AtomicBoolean insertObservedInTransaction = new AtomicBoolean();
        private final AtomicInteger winnerReadsInTransaction = new AtomicInteger();
        private final AtomicBoolean winnerReadOutsideTransaction = new AtomicBoolean();

        Object invoke(MemoryPublicationMapper delegate, Method method, Object[] arguments)
                throws Throwable {
            try {
                Object result = method.invoke(delegate, arguments);
                if (method.getName().equals("insertPublication")) {
                    insertObservedInTransaction.set(
                            TransactionSynchronizationManager.isActualTransactionActive());
                    if (failAfterPublicationInsert.get()) {
                        throw new InjectedPublicationFailure(
                                "failure injected after publication insert");
                    }
                }
                if (method.getName().equals("findByIdForUpdate")) {
                    if (TransactionSynchronizationManager.isActualTransactionActive()) {
                        winnerReadsInTransaction.incrementAndGet();
                    } else {
                        winnerReadOutsideTransaction.set(true);
                    }
                }
                return result;
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }
    }

    public static class PublicationTransactionHarness {
        private final MemoryPublicationDao dao;
        private final AtomicBoolean transactionObserved = new AtomicBoolean();
        private final AtomicReference<MemoryPublicationRecord> stagedPublication =
                new AtomicReference<>();

        PublicationTransactionHarness(MemoryPublicationDao dao) { this.dao = dao; }

        @Transactional(rollbackFor = Exception.class)
        public void publishThenFail(
                ConversationDataScope scope, String conversationId, String snapshotToken,
                MemoryPublicationPayload payload) throws InjectedOuterFailure {
            transactionObserved.set(TransactionSynchronizationManager.isActualTransactionActive());
            MemoryPublicationRecord allocated = dao.stage(
                    scope, conversationId, 7, snapshotToken, "document-outer-failure",
                    2, 3_000L);
            stagedPublication.set(allocated);
            MemoryPublicationRecord staged = dao.bindPayload(allocated, payload, 3_001L);
            dao.publish(staged, 3_002L);
            throw new InjectedOuterFailure("failure injected after publication");
        }

        public boolean transactionObserved() {
            return transactionObserved.get();
        }

        public MemoryPublicationRecord stagedPublication() {
            return stagedPublication.get();
        }
    }

    static final class InjectedPublicationFailure extends RuntimeException {
        InjectedPublicationFailure(String message) { super(message); }
    }

    static final class InjectedOuterFailure extends Exception {
        InjectedOuterFailure(String message) { super(message); }
    }
}
