package cn.jia.tenantcard;

import cn.jia.agent.dao.PersonalWorkspaceConversationLinkDao.OperationRow;
import cn.jia.agent.dao.impl.AgentTaskCreationOperationDaoImpl;
import cn.jia.agent.dao.impl.PersonalWorkspaceConversationLinkDaoImpl;
import cn.jia.agent.dao.impl.PersonalWorkspaceTaskLinkDaoImpl;
import cn.jia.agent.entity.AgentTaskCreationOperationEntity;
import cn.jia.agent.mapper.AgentTaskCreationOperationMapper;
import cn.jia.agent.mapper.PersonalWorkspaceConversationFileLinkMapper;
import cn.jia.agent.mapper.PersonalWorkspaceTaskFileLinkMapper;
import cn.jia.common.mybatis.TenantTablePolicy;
import cn.jia.common.mybatis.TenantWriteGuard;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.TrustedTenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantCardA13Test {
    private static final String TENANT = "1";
    private static final String CLIENT = "client-1";
    private static final String OWNER = "owner-1";
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final BusinessScope SCOPE = new BusinessScope(
            TenantId.of(TENANT), DataDomainId.of("cyf"), OWNER, CLIENT);

    @AfterEach
    void clearContext() {
        TrustedTenantContext.clear();
    }

    @Test
    void allThreeReservationsReplayTheExactRowWithoutMutatingFirstValues() throws Exception {
        Fixture fixture = fixture();

        AgentTaskCreationOperationEntity creation = inTransaction(fixture, () ->
                fixture.creationDao().reserveAndLock(TENANT, CLIENT, OWNER, "create-key",
                        HASH_A, "create-op-1", "[]", 101L));
        AgentTaskCreationOperationEntity creationReplay = inTransaction(fixture, () ->
                fixture.creationDao().reserveAndLock(TENANT, CLIENT, OWNER, "create-key",
                        HASH_B, "create-op-2", "[1]", 202L));
        assertEquals(creation.getId(), creationReplay.getId());
        assertEquals("create-op-1", creationReplay.getOperationId());
        assertEquals(HASH_A, creationReplay.getRequestHash());
        assertEquals(101L, creationReplay.getUpdateTime());

        OperationRow conversation = inTransaction(fixture, () ->
                fixture.conversationDao().reserveOperation(TENANT, CLIENT, OWNER, "CREATE",
                        "conversation-key", HASH_A, "conversation-op-1", 111L));
        OperationRow conversationReplay = inTransaction(fixture, () ->
                fixture.conversationDao().reserveOperation(TENANT, CLIENT, OWNER, "CREATE",
                        "conversation-key", HASH_B, "conversation-op-2", 222L));
        assertEquals(conversation.getId(), conversationReplay.getId());
        assertEquals("conversation-op-1", conversationReplay.getOperationId());
        assertEquals(HASH_A, conversationReplay.getRequestHash());
        assertEquals(111L, fixture.jdbc().queryForObject("""
                SELECT update_time FROM agent_personal_workspace_conversation_link_operation
                WHERE id=?
                """, Long.class, conversation.getId()));

        cn.jia.agent.dao.PersonalWorkspaceTaskLinkDao.OperationRow task =
                inTransaction(fixture, () -> fixture.taskDao().reserveOperation(
                        TENANT, CLIENT, OWNER, "CREATE", "task-key", HASH_A,
                        "task-op-1", 121L));
        cn.jia.agent.dao.PersonalWorkspaceTaskLinkDao.OperationRow taskReplay =
                inTransaction(fixture, () -> fixture.taskDao().reserveOperation(
                        TENANT, CLIENT, OWNER, "CREATE", "task-key", HASH_B,
                        "task-op-2", 242L));
        assertEquals(task.getId(), taskReplay.getId());
        assertEquals("task-op-1", taskReplay.getOperationId());
        assertEquals(HASH_A, taskReplay.getRequestHash());
        assertEquals(121L, fixture.jdbc().queryForObject("""
                SELECT update_time FROM agent_personal_workspace_task_link_operation
                WHERE id=?
                """, Long.class, task.getId()));
        assertLockingSqlShapes(fixture.lockingSql());
    }

    @Test
    void unrelatedUniqueAndCaseInsensitiveCollisionsFailClosedWithoutTouchingTenantZero()
            throws Exception {
        Fixture fixture = fixture();
        fixture.jdbc().update("""
                INSERT INTO agent_task_creation_operation
                  (operation_id,owner_jiacn,idempotency_key,request_hash,operation_state,
                   input_refs_json,created_at,tenant_id,client_id,create_time,update_time)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, "shared-operation", OWNER, "tenant-zero-key", HASH_A, "PROCESSING",
                "[]", 31L, "0", CLIENT, 31L, 31L);

        assertThrows(IllegalStateException.class, () -> inTransaction(fixture, () ->
                fixture.creationDao().reserveAndLock(TENANT, CLIENT, OWNER, "tenant-one-key",
                        HASH_B, "shared-operation", "[]", 41L)));
        assertEquals(HASH_A, fixture.jdbc().queryForObject("""
                SELECT request_hash FROM agent_task_creation_operation
                WHERE tenant_id='0' AND operation_id='shared-operation'
                """, String.class));
        assertEquals(31L, fixture.jdbc().queryForObject("""
                SELECT update_time FROM agent_task_creation_operation
                WHERE tenant_id='0' AND operation_id='shared-operation'
                """, Long.class));

        inTransaction(fixture, () -> fixture.conversationDao().reserveOperation(
                TENANT, CLIENT, OWNER, "CREATE", "Case-Key", HASH_A,
                "conversation-case-1", 51L));
        assertThrows(IllegalStateException.class, () -> inTransaction(fixture, () ->
                fixture.conversationDao().reserveOperation(TENANT, CLIENT, OWNER, "CREATE",
                        "case-key", HASH_B, "conversation-case-2", 61L)));

        inTransaction(fixture, () -> fixture.taskDao().reserveOperation(
                TENANT, CLIENT, OWNER, "CREATE", "Task-Key", HASH_A,
                "task-case-1", 71L));
        assertThrows(IllegalStateException.class, () -> inTransaction(fixture, () ->
                fixture.taskDao().reserveOperation(TENANT, CLIENT, OWNER, "CREATE",
                        "task-key", HASH_B, "task-case-2", 81L)));
    }

    @Test
    void nonDuplicateIntegrityErrorsAreNotConvertedIntoReplays() throws Exception {
        Fixture fixture = fixture();

        assertNonDuplicate(() -> inTransaction(fixture, () ->
                fixture.creationDao().reserveAndLock(TENANT, CLIENT, OWNER, "bad-create",
                        HASH_A, "forbidden", "[]", 101L)));
        assertNonDuplicate(() -> inTransaction(fixture, () ->
                fixture.conversationDao().reserveOperation(TENANT, CLIENT, OWNER, "CREATE",
                        "bad-conversation", HASH_A, "forbidden", 102L)));
        assertNonDuplicate(() -> inTransaction(fixture, () ->
                fixture.taskDao().reserveOperation(TENANT, CLIENT, OWNER, "CREATE",
                        "bad-task", HASH_A, "forbidden", 103L)));
    }

    @Test
    void surroundingTransactionRollbackStillRemovesAllReservations() throws Exception {
        Fixture fixture = fixture();

        inScope(() -> fixture.transactions().execute(status -> {
            fixture.creationDao().reserveAndLock(TENANT, CLIENT, OWNER, "rollback-create",
                    HASH_A, "rollback-create-op", "[]", 201L);
            fixture.conversationDao().reserveOperation(TENANT, CLIENT, OWNER, "CREATE",
                    "rollback-conversation", HASH_A, "rollback-conversation-op", 202L);
            fixture.taskDao().reserveOperation(TENANT, CLIENT, OWNER, "CREATE",
                    "rollback-task", HASH_A, "rollback-task-op", 203L);
            status.setRollbackOnly();
            return null;
        }));

        assertEquals(0, fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM agent_task_creation_operation", Integer.class));
        assertEquals(0, fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM agent_personal_workspace_conversation_link_operation",
                Integer.class));
        assertEquals(0, fixture.jdbc().queryForObject(
                "SELECT COUNT(*) FROM agent_personal_workspace_task_link_operation",
                Integer.class));
    }

    @Test
    void twoBoundedTransactionsConcurrentlyReplayOneCreationReservation() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch firstHasLock = new CountDownLatch(1);
        CountDownLatch secondTransactionStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                Future<AgentTaskCreationOperationEntity> first = workers.submit(() ->
                        inScope(() -> fixture.transactions().execute(status -> {
                            AgentTaskCreationOperationEntity row = fixture.creationDao()
                                    .reserveAndLock(TENANT, CLIENT, OWNER, "concurrent-key",
                                            HASH_A, "concurrent-owner", "[]", 301L);
                            firstHasLock.countDown();
                            await(secondTransactionStarted);
                            await(releaseFirst);
                            return row;
                        })));
                Future<AgentTaskCreationOperationEntity> replay = workers.submit(() -> {
                    await(firstHasLock);
                    return inScope(() -> fixture.transactions().execute(status -> {
                        secondTransactionStarted.countDown();
                        return fixture.creationDao().reserveAndLock(
                                TENANT, CLIENT, OWNER, "concurrent-key", HASH_B,
                                "concurrent-replay", "[1]", 302L);
                    }));
                });
                await(secondTransactionStarted);
                releaseFirst.countDown();
                AgentTaskCreationOperationEntity firstRow = first.get(8, TimeUnit.SECONDS);
                AgentTaskCreationOperationEntity replayRow = replay.get(8, TimeUnit.SECONDS);
                assertEquals(firstRow.getId(), replayRow.getId());
                assertEquals("concurrent-owner", replayRow.getOperationId());
                assertEquals(HASH_A, replayRow.getRequestHash());
                assertEquals(1, fixture.jdbc().queryForObject("""
                        SELECT COUNT(*) FROM agent_task_creation_operation
                        WHERE tenant_id='1' AND idempotency_key='concurrent-key'
                        """, Integer.class));
            });
        } finally {
            releaseFirst.countDown();
            workers.shutdownNow();
        }
    }

    private static void assertNonDuplicate(ThrowingCall call) {
        DataIntegrityViolationException failure = assertThrows(
                DataIntegrityViolationException.class, call::run);
        assertFalse(failure instanceof DuplicateKeyException, failure.toString());
    }

    private static void assertLockingSqlShapes(LockingSqlRecorder recorder) {
        List<String> lockingSql = recorder.lockingSql();
        for (String table : TABLES) {
            String sql = lockingSql.stream()
                    .filter(candidate -> candidate.contains(table.toUpperCase(Locale.ROOT)))
                    .findFirst().orElseThrow(() -> new AssertionError(
                            "missing post-TenantLine locking SQL for " + table));
            assertTrue(sql.endsWith(" FOR UPDATE"), sql);
            assertFalse(sql.contains(" LIMIT "), sql);
            assertFalse(sql.contains("FOR UPDATE LIMIT"), sql);
            assertTrue(sql.contains("TENANT_ID = '1'"), sql);
        }
    }

    private static <T> T inTransaction(Fixture fixture, Supplier<T> work) {
        return inScope(() -> fixture.transactions().execute(status -> work.get()));
    }

    private static <T> T inScope(Supplier<T> work) {
        return TrustedTenantContext.withScope(SCOPE, work);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("bounded concurrency latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("bounded concurrency interrupted", exception);
        }
    }

    private static Fixture fixture() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setUrl("jdbc:h2:mem:a13_" + UUID.randomUUID()
                + ";MODE=MYSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        source.setUsername("sa");
        source.setPassword("");
        DataSource dataSource = source;
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentTaskCreationOperationMapper.class);
        configuration.addMapper(PersonalWorkspaceConversationFileLinkMapper.class);
        configuration.addMapper(PersonalWorkspaceTaskFileLinkMapper.class);
        MybatisPlusInterceptor tenantPlugin = new MybatisPlusInterceptor();
        tenantPlugin.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override public Expression getTenantId() {
                return new StringValue(TrustedTenantContext.requireTenantId().value());
            }

            @Override public boolean ignoreTable(String tableName) {
                return !TABLES.contains(tableName.toLowerCase());
            }
        }));
        configuration.addInterceptor(tenantPlugin);
        configuration.addInterceptor(new TenantWriteGuard(
                TenantTablePolicy.of(TABLES, Set.of())));
        LockingSqlRecorder lockingSql = new LockingSqlRecorder();
        configuration.addInterceptor(lockingSql);
        configuration.addInterceptor(new H2BinaryCastAdapter());
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        factoryBean.setGlobalConfig(globalConfig);
        SqlSessionFactory sessionFactory = factoryBean.getObject();
        if (sessionFactory == null) throw new IllegalStateException("missing SqlSessionFactory");
        SqlSessionTemplate sessions = new SqlSessionTemplate(sessionFactory);
        AgentTaskCreationOperationMapper creation = sessions.getMapper(
                AgentTaskCreationOperationMapper.class);
        PersonalWorkspaceConversationFileLinkMapper conversation = sessions.getMapper(
                PersonalWorkspaceConversationFileLinkMapper.class);
        PersonalWorkspaceTaskFileLinkMapper task = sessions.getMapper(
                PersonalWorkspaceTaskFileLinkMapper.class);
        return new Fixture(new AgentTaskCreationOperationDaoImpl(creation),
                new PersonalWorkspaceConversationLinkDaoImpl(conversation),
                new PersonalWorkspaceTaskLinkDaoImpl(task), jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                lockingSql);
    }

    private static final Set<String> TABLES = Set.of(
            "agent_task_creation_operation",
            "agent_personal_workspace_conversation_link_operation",
            "agent_personal_workspace_task_link_operation");

    private static void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE agent_task_creation_operation (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  operation_id VARCHAR_IGNORECASE(100) NOT NULL UNIQUE,
                  owner_jiacn VARCHAR_IGNORECASE(50) NOT NULL,
                  idempotency_key VARCHAR_IGNORECASE(100) NOT NULL,
                  request_hash VARCHAR(64) NOT NULL,
                  operation_state VARCHAR(20) NOT NULL,
                  task_id VARCHAR(100), requirement_revision BIGINT,
                  input_refs_json VARCHAR(16384) NOT NULL,
                  created_at BIGINT NOT NULL, completed_at BIGINT,
                  tenant_id VARCHAR_IGNORECASE(50) NOT NULL,
                  client_id VARCHAR_IGNORECASE(50) NOT NULL,
                  create_time BIGINT NOT NULL, update_time BIGINT NOT NULL,
                  CONSTRAINT uq_creation_scope UNIQUE
                    (tenant_id,client_id,owner_jiacn,idempotency_key),
                  CONSTRAINT ck_creation_operation CHECK (operation_id <> 'forbidden'))
                """);
        createLinkOperationTable(jdbc,
                "agent_personal_workspace_conversation_link_operation", "conversation");
        createLinkOperationTable(jdbc,
                "agent_personal_workspace_task_link_operation", "task");
    }

    private static void createLinkOperationTable(JdbcTemplate jdbc, String table, String suffix) {
        jdbc.execute("""
                CREATE TABLE %s (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  operation_id VARCHAR_IGNORECASE(100) NOT NULL UNIQUE,
                  owner_jiacn VARCHAR_IGNORECASE(50) NOT NULL,
                  operation_type VARCHAR_IGNORECASE(20) NOT NULL,
                  idempotency_key VARCHAR_IGNORECASE(100) NOT NULL,
                  request_hash VARCHAR(64) NOT NULL,
                  operation_state VARCHAR(20) NOT NULL,
                  relation_id VARCHAR(100), created_at BIGINT NOT NULL, completed_at BIGINT,
                  tenant_id VARCHAR_IGNORECASE(50) NOT NULL,
                  client_id VARCHAR_IGNORECASE(50) NOT NULL,
                  create_time BIGINT NOT NULL, update_time BIGINT NOT NULL,
                  CONSTRAINT uq_%s_scope UNIQUE
                    (tenant_id,client_id,owner_jiacn,operation_type,idempotency_key),
                  CONSTRAINT ck_%s_operation CHECK (operation_id <> 'forbidden'))
                """.formatted(table, suffix, suffix));
    }

    private record Fixture(
            AgentTaskCreationOperationDaoImpl creationDao,
            PersonalWorkspaceConversationLinkDaoImpl conversationDao,
            PersonalWorkspaceTaskLinkDaoImpl taskDao,
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            LockingSqlRecorder lockingSql) { }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }

    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare",
            args = {Connection.class, Integer.class}))
    private static final class LockingSqlRecorder implements Interceptor {
        private final List<String> lockingSql = new CopyOnWriteArrayList<>();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            StatementHandler handler = (StatementHandler) invocation.getTarget();
            String sql = handler.getBoundSql().getSql().replaceAll("\\s+", " ")
                    .trim().toUpperCase(Locale.ROOT);
            if (sql.contains(" FOR UPDATE")) lockingSql.add(sql);
            return invocation.proceed();
        }

        List<String> lockingSql() {
            return List.copyOf(lockingSql);
        }
    }

    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare",
            args = {Connection.class, Integer.class}))
    private static final class H2BinaryCastAdapter implements Interceptor {
        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            StatementHandler handler = (StatementHandler) invocation.getTarget();
            String sql = handler.getBoundSql().getSql();
            SystemMetaObject.forObject(handler.getBoundSql()).setValue("sql",
                    sql.replaceAll("(?i)\\bAS\\s+BINARY\\s*\\)", "AS VARBINARY)"));
            return invocation.proceed();
        }
    }
}
