package cn.jia.agent.output.service;

import cn.jia.agent.config.OutputDeliverySchemaInitializer;
import cn.jia.agent.config.OutputDeliveryProperties;
import cn.jia.agent.config.OutputObjectSchemaInitializer;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.AgentIdentityAliasDao;
import cn.jia.agent.dao.AgentIdentityRegistryDao;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.impl.AgentIdentityAliasDaoImpl;
import cn.jia.agent.dao.impl.AgentIdentityRegistryDaoImpl;
import cn.jia.agent.dao.impl.AgentPersonaBindingDaoImpl;
import cn.jia.agent.dao.impl.AgentRuntimeDaoImpl;
import cn.jia.agent.mapper.AgentRuntimeMapper;
import cn.jia.agent.mapper.AgentIdentityAliasMapper;
import cn.jia.agent.mapper.AgentIdentityRegistryMapper;
import cn.jia.agent.mapper.AgentPersonaBindingMapper;
import cn.jia.agent.output.OutputAuthorizationException;
import cn.jia.agent.output.OutputConstants;
import cn.jia.agent.output.OutputSourceAccessMode;
import cn.jia.agent.output.OutputSourceAuthorization;
import cn.jia.agent.output.OutputSourceAuthorizer;
import cn.jia.agent.output.OutputTicketAuthorization;
import cn.jia.agent.output.OutputMalwareScanner;
import cn.jia.agent.output.OutputObjectStorage;
import cn.jia.agent.output.dao.OutputUploadDao;
import cn.jia.agent.output.dao.OutputAccessTicketDao;
import cn.jia.agent.output.dao.OutputRunBindingDao;
import cn.jia.agent.output.dao.OutputSourceBindingDao;
import cn.jia.agent.output.dao.impl.OutputAccessTicketDaoImpl;
import cn.jia.agent.output.dao.impl.OutputRunBindingDaoImpl;
import cn.jia.agent.output.dao.impl.OutputSourceBindingDaoImpl;
import cn.jia.agent.output.dao.impl.OutputUploadDaoImpl;
import cn.jia.agent.output.dto.OutputAuthReceiptDTO;
import cn.jia.agent.output.dto.OutputSourceDTO;
import cn.jia.agent.output.dto.OutputUploadCreateDTO;
import cn.jia.agent.output.entity.OutputAccessTicketEntity;
import cn.jia.agent.output.entity.OutputRunBindingEntity;
import cn.jia.agent.output.mapper.OutputAccessTicketMapper;
import cn.jia.agent.output.mapper.OutputRunBindingMapper;
import cn.jia.agent.output.mapper.OutputSourceBindingMapper;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.impl.AgentIdentityServiceImpl;
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
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * MySQL 8 evidence for ticket serialization and current-read fences. The business source is a
 * controlled fixture; output and identity checks use production mapper/DAO/service code behind
 * Spring transaction proxies.
 */
@EnabledIfEnvironmentVariable(named = "OD01_MYSQL_URL", matches = ".+")
class OutputAuthorizationMySqlConcurrencyTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private DataSource dataSource;
    private TransactionTemplate transaction;
    private String database;
    private String baseUrl;
    private String username;
    private String password;
    private boolean databaseCreated;
    private OutputSourceBindingDao sourceDao;
    private OutputRunBindingDao runDao;
    private OutputAccessTicketDao ticketDao;
    private AgentRuntimeDao runtimeDao;
    private AgentIdentityService identityService;
    private OutputRunAuthorizationServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        baseUrl = requiredEnvironment("OD01_MYSQL_URL");
        username = environment("OD01_MYSQL_USER", "root");
        password = environment("OD01_MYSQL_PASSWORD", "");
        admin = new JdbcTemplate(dataSource(baseUrl));
        String version = admin.queryForObject("SELECT VERSION()", String.class);
        assertTrue(version != null && version.startsWith("8.0."),
                "OD01 evidence requires MySQL 8.0, got " + version);
        database = "cyf_od01_auth_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE `" + database
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        databaseCreated = true;

        dataSource = dataSource(urlForDatabase(baseUrl, database));
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        createSchema();
        SqlSessionTemplate template = new SqlSessionTemplate(sqlSessionFactory(dataSource));
        sourceDao = wire(new OutputSourceBindingDaoImpl(),
                template.getMapper(OutputSourceBindingMapper.class));
        runDao = wire(new OutputRunBindingDaoImpl(),
                template.getMapper(OutputRunBindingMapper.class));
        ticketDao = wire(new OutputAccessTicketDaoImpl(),
                template.getMapper(OutputAccessTicketMapper.class));
        runtimeDao = wire(new AgentRuntimeDaoImpl(),
                template.getMapper(AgentRuntimeMapper.class));
        AgentIdentityRegistryDao registryDao = wire(new AgentIdentityRegistryDaoImpl(),
                template.getMapper(AgentIdentityRegistryMapper.class));
        AgentIdentityAliasDao aliasDao = wire(new AgentIdentityAliasDaoImpl(),
                template.getMapper(AgentIdentityAliasMapper.class));
        AgentPersonaBindingDao bindingDao = wire(new AgentPersonaBindingDaoImpl(),
                template.getMapper(AgentPersonaBindingMapper.class));
        identityService = transactionalProxy(
                new AgentIdentityServiceImpl(registryDao, aliasDao, bindingDao),
                AgentIdentityService.class);
        service = service(runDao, ticketDao);
    }

    @AfterEach
    void tearDown() {
        if (admin != null && databaseCreated) {
            assertTrue(database.matches("cyf_od01_auth_[0-9a-f]{32}"));
            admin.execute("DROP DATABASE IF EXISTS `" + database + "`");
        }
    }

    @Test
    void concurrentDifferentSourcesCommitAtMostSixtyTicketsOnOneBinding() throws Exception {
        int attempts = 70;
        int workers = 8;
        for (int index = 0; index < attempts; index++) {
            insertSourceAndRun(index, OutputConstants.RUN_ACTIVE, "READ_WRITE");
        }
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int index = 0; index < attempts; index++) {
            int attempt = index;
            futures.add(pool.submit(() -> {
                if (attempt < workers) ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                try {
                    issue(service, attempt, "runtime-1");
                    return "SUCCESS";
                } catch (OutputAuthorizationException denied) {
                    return denied.getCode();
                }
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();
        try {
            for (Future<String> future : futures) {
                String result = future.get(90, TimeUnit.SECONDS);
                if ("SUCCESS".equals(result)) successes.incrementAndGet();
                if ("OUTPUT_AUTH_RATE_LIMITED".equals(result)) limited.incrementAndGet();
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(OutputConstants.AUTH_REQUESTS_PER_MINUTE, successes.get());
        assertEquals(attempts - OutputConstants.AUTH_REQUESTS_PER_MINUTE, limited.get());
        assertEquals(OutputConstants.AUTH_REQUESTS_PER_MINUTE,
                jdbc.queryForObject("SELECT COUNT(*) FROM output_access_ticket", Integer.class));
    }

    @Test
    void rollbackConsumesNoQuotaAndDynamicSourceBindingAndBearerChecksFailClosed() {
        insertSourceAndRun(0, OutputConstants.RUN_ACTIVE, "READ_WRITE");
        AtomicBoolean failOnce = new AtomicBoolean(true);
        OutputAccessTicketDao failing = new DelegatingTicketDao(ticketDao) {
            @Override
            public int insert(OutputAccessTicketEntity entity) {
                int inserted = super.insert(entity);
                if (failOnce.getAndSet(false)) {
                    throw new IllegalStateException("forced rollback after production insert");
                }
                return inserted;
            }
        };
        assertThrows(IllegalStateException.class, () -> issue(service(runDao, failing), 0, "runtime-1"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM output_access_ticket", Integer.class));

        OutputAuthReceiptDTO receipt = issue(service, 0, "runtime-1");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM output_access_ticket", Integer.class));
        jdbc.update("""
                UPDATE agent_runtime
                SET output_capabilities_runtime_id='runtime-2',
                    output_capabilities_json=NULL,output_capabilities_updated_at=NULL
                WHERE agent_id='agent-1'
                """);
        OutputTicketAuthorization recovered = authorize(
                service, receipt.token(), OutputConstants.OP_UPLOAD, false);
        assertEquals("runtime-1", recovered.runtimeInstanceId());
        assertEquals("7", recovered.bindingId());

        jdbc.update("UPDATE output_source_binding SET ownership_state='REVOKED'");
        assertThrows(OutputAuthorizationException.class, () -> authorize(
                service, receipt.token(), OutputConstants.OP_UPLOAD, false));
        jdbc.update("UPDATE output_source_binding SET ownership_state='ACTIVE'");

        jdbc.update("UPDATE agent_runtime SET binding_id=8 WHERE agent_id='agent-1'");
        assertThrows(OutputAuthorizationException.class, () -> authorize(
                service, receipt.token(), OutputConstants.OP_UPLOAD, false));
        String wrongBearer = receipt.token().substring(0, receipt.token().length() - 1)
                + (receipt.token().endsWith("A") ? "B" : "A");
        assertThrows(OutputAuthorizationException.class, () -> authorize(
                service, wrongBearer, OutputConstants.OP_UPLOAD, false));
    }

    @Test
    void currentGenerationRenewsTicketAfterDispatchFreshnessWindow() {
        insertSourceAndRun(0, OutputConstants.RUN_ACTIVE, "READ_WRITE");
        jdbc.update("""
                UPDATE agent_runtime
                SET output_capabilities_updated_at=?
                WHERE agent_id='agent-1'
                """, System.currentTimeMillis()
                        - OutputConstants.CAPABILITY_FRESHNESS_MILLIS - 1L);

        OutputAuthReceiptDTO receipt = issue(service, 0, "runtime-1");

        assertEquals(runId(0), receipt.runId());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM output_access_ticket", Integer.class));
    }

    @Test
    void terminalRestartGetsStatusOnlyAndOldFullTicketCannotMutate() {
        insertSourceAndRun(0, OutputConstants.RUN_ACTIVE, "READ_WRITE");
        OutputAuthReceiptDTO oldFull = issue(service, 0, "runtime-1");
        jdbc.update("UPDATE output_run_binding SET state='RESULT_SUBMITTED' WHERE run_id=?", runId(0));
        jdbc.update("UPDATE od01_source_root SET access_level='READ_ONLY' WHERE source_id=?", sourceId(0));
        refreshRuntime("runtime-2", 7L);

        OutputAuthReceiptDTO terminal = issue(service, 0, "runtime-2");
        assertEquals(List.of(OutputConstants.OP_STATUS), terminal.operations());
        OutputTicketAuthorization replay = authorize(
                service, terminal.token(), OutputConstants.OP_STATUS, true);
        assertEquals(runId(0), replay.runId());
        assertEquals(runId(0), authorize(
                service, oldFull.token(), OutputConstants.OP_STATUS, true).runId());
        assertThrows(OutputAuthorizationException.class, () -> authorize(
                service, terminal.token(), OutputConstants.OP_STATUS, false));
        assertThrows(OutputAuthorizationException.class, () -> authorize(
                service, oldFull.token(), OutputConstants.OP_UPLOAD, true));

        insertSourceAndRun(1, OutputConstants.RUN_CLOSED, "READ_ONLY");
        OutputAuthReceiptDTO closed = issue(service, 1, "runtime-2");
        assertEquals(List.of(OutputConstants.OP_STATUS), closed.operations());
        refreshRuntime("runtime-3", 8L);
        assertThrows(OutputAuthorizationException.class, () -> authorize(
                service, closed.token(), OutputConstants.OP_STATUS, true));
    }

    @Test
    void productionCapabilityCasClearsRegistrationAndRejectsDelayedOldPresence() {
        long clearedAt = System.currentTimeMillis();
        assertEquals(1, runtimeDao.replaceOutputCapabilities(
                "owner", "client", "owner", "agent-1", 7L, "registration",
                null, null, clearedAt));
        assertNull(jdbc.queryForObject("""
                SELECT output_capabilities_runtime_id FROM agent_runtime WHERE agent_id='agent-1'
                """, String.class));
        assertEquals(0, runtimeDao.refreshOutputCapabilities(
                "owner", "client", "owner", "agent-1", 7L, "runtime-1",
                "[\"output.http.v1\"]", clearedAt + 1));

        assertEquals(1, runtimeDao.replaceOutputCapabilities(
                "owner", "client", "owner", "agent-1", 7L, "registration",
                "runtime-2", "[\"output.http.v1\"]", clearedAt + 2));
        assertEquals(0, runtimeDao.refreshOutputCapabilities(
                "owner", "client", "owner", "agent-1", 7L, "runtime-1",
                "[\"output.http.v1\"]", clearedAt + 3));
        assertEquals(1, runtimeDao.refreshOutputCapabilities(
                "owner", "client", "owner", "agent-1", 7L, "runtime-2",
                "[\"output.http.v1\",\"task.owner-share.v1\"]", clearedAt + 4));
        assertEquals("runtime-2", jdbc.queryForObject("""
                SELECT output_capabilities_runtime_id FROM agent_runtime WHERE agent_id='agent-1'
                """, String.class));
    }

    @Test
    void lockedRunCurrentReadBlocksRevocationTerminalMutationAndRecoveryExpiry() throws Exception {
        insertSourceAndRun(0, OutputConstants.RUN_ACTIVE, "READ_WRITE");
        ProjectionBarrierRunDao revokedBarrier = new ProjectionBarrierRunDao(runDao, runId(0));
        Future<Throwable> revoked = async(() -> issue(service(revokedBarrier, ticketDao), 0, "runtime-1"));
        revokedBarrier.awaitProjection();
        jdbc.update("UPDATE output_run_binding SET state='REVOKED' WHERE run_id=?", runId(0));
        revokedBarrier.release();
        assertInstanceOf(OutputAuthorizationException.class, revoked.get(10, TimeUnit.SECONDS));

        insertSourceAndRun(1, OutputConstants.RUN_ACTIVE, "READ_WRITE");
        OutputAuthReceiptDTO full = issue(service, 1, "runtime-1");
        ProjectionBarrierRunDao terminalBarrier = new ProjectionBarrierRunDao(runDao, runId(1));
        Future<Throwable> terminalMutation = async(() -> authorize(
                service(terminalBarrier, ticketDao), full.token(), OutputConstants.OP_UPLOAD, false));
        terminalBarrier.awaitProjection();
        jdbc.update("UPDATE output_run_binding SET state='CLOSED' WHERE run_id=?", runId(1));
        terminalBarrier.release();
        assertInstanceOf(OutputAuthorizationException.class,
                terminalMutation.get(10, TimeUnit.SECONDS));

        insertSourceAndRun(2, OutputConstants.RUN_ACTIVE, "READ_WRITE");
        jdbc.update("UPDATE output_run_binding SET recovery_until=? WHERE run_id=?",
                System.currentTimeMillis() + 150L, runId(2));
        ProjectionBarrierRunDao expiryBarrier = new ProjectionBarrierRunDao(runDao, runId(2));
        Future<Throwable> expired = async(() -> issue(
                service(expiryBarrier, ticketDao), 2, "runtime-1"));
        expiryBarrier.awaitProjection();
        Thread.sleep(250L);
        expiryBarrier.release();
        assertInstanceOf(OutputAuthorizationException.class, expired.get(10, TimeUnit.SECONDS));
    }

    @Test
    void lockedTicketCurrentReadBlocksRevocationAndExpiryAfterBootstrap() throws Exception {
        insertSourceAndRun(0, OutputConstants.RUN_ACTIVE, "READ_WRITE");
        OutputAuthReceiptDTO revokedReceipt = issue(service, 0, "runtime-1");
        ProjectionBarrierTicketDao revokedBarrier = new ProjectionBarrierTicketDao(
                ticketDao, ticketHash(revokedReceipt.token()));
        Future<Throwable> revoked = async(() -> authorize(
                service(runDao, revokedBarrier), revokedReceipt.token(),
                OutputConstants.OP_UPLOAD, false));
        revokedBarrier.awaitProjection();
        jdbc.update("UPDATE output_access_ticket SET revoked_at=? WHERE ticket_hash=?",
                System.currentTimeMillis(), ticketHash(revokedReceipt.token()));
        revokedBarrier.release();
        assertInstanceOf(OutputAuthorizationException.class, revoked.get(10, TimeUnit.SECONDS));

        OutputAuthReceiptDTO expiringReceipt = issue(service, 0, "runtime-1");
        jdbc.update("UPDATE output_access_ticket SET expires_at=? WHERE ticket_hash=?",
                System.currentTimeMillis() + 150L, ticketHash(expiringReceipt.token()));
        ProjectionBarrierTicketDao expiryBarrier = new ProjectionBarrierTicketDao(
                ticketDao, ticketHash(expiringReceipt.token()));
        Future<Throwable> expired = async(() -> authorize(
                service(runDao, expiryBarrier), expiringReceipt.token(),
                OutputConstants.OP_UPLOAD, false));
        expiryBarrier.awaitProjection();
        Thread.sleep(250L);
        expiryBarrier.release();
        assertInstanceOf(OutputAuthorizationException.class, expired.get(10, TimeUnit.SECONDS));
    }

    @Test
    void productionTicketLocksRemainEnlistedUntilUploadMutationCommits() throws Exception {
        insertSourceAndRun(0, OutputConstants.RUN_ACTIVE, "READ_WRITE");
        OutputAuthReceiptDTO ticket = issue(service, 0, "runtime-1");
        OutputUploadDao delegate = new OutputUploadDaoImpl(jdbc);
        CountDownLatch authorized = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        OutputUploadDao barrier = (OutputUploadDao) Proxy.newProxyInstance(
                OutputUploadDao.class.getClassLoader(), new Class<?>[]{OutputUploadDao.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("ensureScopeQuota")) {
                        authorized.countDown();
                        assertTrue(release.await(10, TimeUnit.SECONDS));
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
        OutputUploadServiceImpl uploads = new OutputUploadServiceImpl(
                service, barrier, mock(OutputObjectStorage.class), mock(OutputMalwareScanner.class),
                new DataSourceTransactionManager(dataSource), new OutputDeliveryProperties(true));
        OutputUploadCreateDTO request = new OutputUploadCreateDTO(
                runId(0), new OutputSourceDTO(OutputConstants.SOURCE_TASK, sourceId(0)),
                "report.md", "13",
                "ecf701f727d9e2d77c4aa49ac6fbbcc997278aca010bddeeb961c10cf54d435a",
                "text/markdown");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> create = pool.submit(() -> uploads.create(
                "Bearer " + ticket.token(), "auth-enlisted-create-01", request));
        assertTrue(authorized.await(10, TimeUnit.SECONDS));
        Future<Integer> revoke = pool.submit(() -> jdbc.update(
                "UPDATE output_access_ticket SET revoked_at=? WHERE ticket_hash=?",
                System.currentTimeMillis(), ticketHash(ticket.token())));
        Thread.sleep(200L);
        assertTrue(!revoke.isDone(), "ticket revocation must wait for the upload transaction's ticket lock");
        release.countDown();
        create.get(10, TimeUnit.SECONDS);
        assertEquals(1, revoke.get(10, TimeUnit.SECONDS));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM output_upload_session", Integer.class));
        assertThrows(OutputAuthorizationException.class, () -> uploads.create(
                "Bearer " + ticket.token(), "auth-enlisted-create-02", request));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM output_upload_session", Integer.class));
        pool.shutdownNow();
    }

    @Test
    void verificationFinalizationRechecksTerminalRevokedSourceAndBindingDuringScan() throws Exception {
        verifyDeniedDuringScan(10, () -> jdbc.update(
                "UPDATE output_run_binding SET state='CLOSED' WHERE run_id=?", runId(10)), true);
        verifyDeniedDuringScan(11, () -> jdbc.update(
                "UPDATE output_run_binding SET state='RESULT_SUBMITTED' WHERE run_id=?",
                runId(11)), true);
        verifyDeniedDuringScan(12, () -> jdbc.update(
                "UPDATE output_run_binding SET state='REVOKED' WHERE run_id=?", runId(12)), false);
        verifyDeniedDuringScan(13, () -> jdbc.update(
                "UPDATE od01_source_root SET access_level='READ_ONLY' WHERE source_id=?",
                sourceId(13)), false);
        verifyDeniedDuringScan(14, () -> jdbc.update(
                "UPDATE agent_runtime SET binding_id=8 WHERE agent_id='agent-1'"), false);
        verifyDeniedDuringScan(15, () -> jdbc.update("""
                UPDATE agent_identity_registry
                SET lifecycle_status='SUSPENDED',suspended_at=?
                WHERE canonical_agent_id='agent-1'
                """, System.currentTimeMillis()), false);
        verifyDeniedDuringScan(16, () -> jdbc.update("""
                UPDATE agent_identity_registry
                SET lifecycle_status='RETIRED',retired_at=?
                WHERE canonical_agent_id='agent-1'
                """, System.currentTimeMillis()), false);
        verifyDeniedDuringScan(17, () -> jdbc.update(
                "DELETE FROM agent_identity_registry WHERE canonical_agent_id='agent-1'"), false);
        verifyDeniedDuringScan(18, () -> jdbc.update(
                "UPDATE agent_persona_binding SET status=0 WHERE id=7"), false);
        verifyAllowedDuringScan(19);
    }

    @Test
    void unexpectedIdentityInfrastructureFailureLeavesVerificationRetryable() throws Exception {
        resetIdentity();
        refreshRuntime("runtime-1", 7L);
        insertSourceAndRun(19, OutputConstants.RUN_ACTIVE, "READ_WRITE");
        OutputAuthReceiptDTO ticket = issue(service, 19, "runtime-1");
        AgentIdentityService failing = (AgentIdentityService) Proxy.newProxyInstance(
                AgentIdentityService.class.getClassLoader(),
                new Class<?>[]{AgentIdentityService.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("lockCurrentActiveIdentityForAuthorization")) {
                        throw new IllegalStateException("identity database unavailable");
                    }
                    try {
                        return method.invoke(identityService, args);
                    } catch (InvocationTargetException error) {
                        throw error.getCause();
                    }
                });
        OutputRunAuthorizationServiceImpl failingAuthorization = service(runDao, ticketDao, failing);
        MemoryStorage storage = new MemoryStorage();
        OutputMalwareScanner clean = (input, maximumBytes) -> {
            input.readAllBytes();
            return new OutputMalwareScanner.ScanResult(true, "test", null);
        };
        OutputUploadServiceImpl uploads = new OutputUploadServiceImpl(
                failingAuthorization, new OutputUploadDaoImpl(jdbc), storage, clean,
                new DataSourceTransactionManager(dataSource), new OutputDeliveryProperties(true));
        byte[] fixture = "hello world!\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        OutputUploadCreateDTO request = uploadRequest(19);
        String bearer = "Bearer " + ticket.token();
        var created = uploads.create(bearer, "verify-infra-create-19", request);
        uploads.put(bearer, created.uploadId(), new ByteArrayInputStream(fixture));

        assertThrows(IllegalStateException.class, () -> uploads.complete(
                bearer, created.uploadId(), "verify-infra-complete-19"));
        assertEquals("VERIFYING", jdbc.queryForObject(
                "SELECT state FROM output_upload_session WHERE upload_id=?",
                String.class, created.uploadId()));
        assertEquals(13L, jdbc.queryForObject(
                "SELECT reserved_bytes FROM output_scope_quota", Long.class));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT stored_bytes FROM output_scope_quota", Long.class));
    }

    @Test
    void verificationFinalizationRechecksRecoveryAfterWaitingForIdentityLock() throws Exception {
        resetIdentity();
        refreshRuntime("runtime-1", 7L);
        int index = 20;
        insertSourceAndRun(index, OutputConstants.RUN_ACTIVE, "READ_WRITE");
        OutputAuthReceiptDTO ticket = issue(service, index, "runtime-1");
        CountDownLatch identityAuthorizationEntered = new CountDownLatch(1);
        AgentIdentityService observedIdentity = (AgentIdentityService) Proxy.newProxyInstance(
                AgentIdentityService.class.getClassLoader(),
                new Class<?>[]{AgentIdentityService.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("lockCurrentActiveIdentityForAuthorization")) {
                        identityAuthorizationEntered.countDown();
                    }
                    try {
                        return method.invoke(identityService, args);
                    } catch (InvocationTargetException error) {
                        throw error.getCause();
                    }
                });
        OutputRunAuthorizationServiceImpl observedAuthorization = service(
                runDao, ticketDao, observedIdentity);
        MemoryStorage storage = new MemoryStorage();
        BlockingScanner scanner = new BlockingScanner();
        OutputUploadServiceImpl uploads = uploadService(observedAuthorization, storage, scanner);
        byte[] fixture = "hello world!\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String bearer = "Bearer " + ticket.token();
        var created = uploads.create(
                bearer, "verify-expiry-create-20", uploadRequest(index));
        uploads.put(bearer, created.uploadId(), new ByteArrayInputStream(fixture));

        CountDownLatch identityLocked = new CountDownLatch(1);
        CountDownLatch releaseIdentity = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> completing = executor.submit(() -> uploads.complete(
                    bearer, created.uploadId(), "verify-expiry-complete-20"));
            assertTrue(scanner.entered.await(10, TimeUnit.SECONDS));
            long recoveryUntil = System.currentTimeMillis() + 2_000L;
            assertEquals(1, jdbc.update(
                    "UPDATE output_run_binding SET recovery_until=? WHERE run_id=?",
                    recoveryUntil, runId(index)));
            Future<?> holdingIdentity = executor.submit(() -> transaction.execute(status -> {
                jdbc.queryForObject(
                        "SELECT id FROM agent_persona_binding WHERE id=7 FOR UPDATE", Long.class);
                identityLocked.countDown();
                await(releaseIdentity, "identity lock release");
                return null;
            }));
            await(identityLocked, "identity lock acquisition");
            scanner.release.countDown();
            await(identityAuthorizationEntered, "persisted identity authorization");
            long remaining = recoveryUntil + 100L - System.currentTimeMillis();
            if (remaining > 0) {
                Thread.sleep(remaining);
            }
            releaseIdentity.countDown();
            completing.get(15, TimeUnit.SECONDS);
            holdingIdentity.get(15, TimeUnit.SECONDS);
        } finally {
            scanner.release.countDown();
            releaseIdentity.countDown();
            executor.shutdownNow();
        }

        assertEquals("REJECTED", jdbc.queryForObject(
                "SELECT state FROM output_upload_session WHERE upload_id=?",
                String.class, created.uploadId()));
        assertEquals("OUTPUT_AUTH_REVOKED", jdbc.queryForObject(
                "SELECT error_code FROM output_upload_session WHERE upload_id=?",
                String.class, created.uploadId()));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT stored_bytes FROM output_scope_quota", Long.class));
        jdbc.update("""
                UPDATE output_storage_cleanup_job SET safe_after=0,next_attempt_at=0
                WHERE upload_id=?
                """, created.uploadId());
        uploads.recover(1);
        assertEquals(0L, jdbc.queryForObject(
                "SELECT reserved_bytes FROM output_scope_quota", Long.class));
    }

    private void verifyDeniedDuringScan(int index, Runnable revoke, boolean receiptReplayAllowed) throws Exception {
        resetIdentity();
        refreshRuntime("runtime-1", 7L);
        insertSourceAndRun(index, OutputConstants.RUN_ACTIVE, "READ_WRITE");
        OutputAuthReceiptDTO ticket = issue(service, index, "runtime-1");
        MemoryStorage storage = new MemoryStorage();
        BlockingScanner scanner = new BlockingScanner();
        OutputUploadServiceImpl uploads = uploadService(service, storage, scanner);
        byte[] fixture = "hello world!\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        OutputUploadCreateDTO request = uploadRequest(index);
        String bearer = "Bearer " + ticket.token();
        String completeKey = "verify-auth-complete-" + index;
        var created = uploads.create(bearer, "verify-auth-create-" + index, request);
        uploads.put(bearer, created.uploadId(), new ByteArrayInputStream(fixture));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> completing = executor.submit(
                    () -> uploads.complete(bearer, created.uploadId(), completeKey));
            assertTrue(scanner.entered.await(10, TimeUnit.SECONDS));
            revoke.run();
            scanner.release.countDown();
            completing.get(15, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertEquals("REJECTED", jdbc.queryForObject(
                "SELECT state FROM output_upload_session WHERE upload_id=?",
                String.class, created.uploadId()));
        assertEquals("OUTPUT_AUTH_REVOKED", jdbc.queryForObject(
                "SELECT error_code FROM output_upload_session WHERE upload_id=?",
                String.class, created.uploadId()));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT stored_bytes FROM output_scope_quota", Long.class));
        if (receiptReplayAllowed) {
            assertEquals("VERIFYING", uploads.complete(
                    bearer, created.uploadId(), completeKey).state());
        } else {
            assertThrows(OutputAuthorizationException.class,
                    () -> uploads.complete(bearer, created.uploadId(), completeKey));
        }
        jdbc.update("""
                UPDATE output_storage_cleanup_job SET safe_after=0,next_attempt_at=0
                WHERE upload_id=?
                """, created.uploadId());
        uploads.recover(1);
        assertEquals(0L, jdbc.queryForObject(
                "SELECT reserved_bytes FROM output_scope_quota", Long.class));
    }

    private void verifyAllowedDuringScan(int index) throws Exception {
        resetIdentity();
        refreshRuntime("runtime-1", 7L);
        insertSourceAndRun(index, OutputConstants.RUN_ACTIVE, "READ_WRITE");
        OutputAuthReceiptDTO ticket = issue(service, index, "runtime-1");
        MemoryStorage storage = new MemoryStorage();
        BlockingScanner scanner = new BlockingScanner();
        OutputUploadServiceImpl uploads = uploadService(service, storage, scanner);
        byte[] fixture = "hello world!\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        OutputUploadCreateDTO request = uploadRequest(index);
        String bearer = "Bearer " + ticket.token();
        var created = uploads.create(bearer, "verify-auth-create-" + index, request);
        uploads.put(bearer, created.uploadId(), new ByteArrayInputStream(fixture));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> completing = executor.submit(() -> uploads.complete(
                    bearer, created.uploadId(), "verify-auth-complete-" + index));
            assertTrue(scanner.entered.await(10, TimeUnit.SECONDS));
            scanner.release.countDown();
            completing.get(15, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertEquals("READY", jdbc.queryForObject(
                "SELECT state FROM output_upload_session WHERE upload_id=?",
                String.class, created.uploadId()));
        assertEquals(13L, jdbc.queryForObject(
                "SELECT stored_bytes FROM output_scope_quota", Long.class));
    }

    private OutputUploadServiceImpl uploadService(
            OutputRunAuthorizationServiceImpl authorization,
            OutputObjectStorage storage,
            OutputMalwareScanner scanner) {
        return new OutputUploadServiceImpl(
                authorization, new OutputUploadDaoImpl(jdbc), storage, scanner,
                new DataSourceTransactionManager(dataSource), new OutputDeliveryProperties(true));
    }

    private OutputUploadCreateDTO uploadRequest(int index) {
        return new OutputUploadCreateDTO(
                runId(index), new OutputSourceDTO(OutputConstants.SOURCE_TASK, sourceId(index)),
                "report.md", "13",
                "ecf701f727d9e2d77c4aa49ac6fbbcc997278aca010bddeeb961c10cf54d435a",
                "text/markdown");
    }

    private OutputRunAuthorizationServiceImpl service(
            OutputRunBindingDao selectedRunDao, OutputAccessTicketDao selectedTicketDao) {
        return service(selectedRunDao,selectedTicketDao,identityService);
    }

    private OutputRunAuthorizationServiceImpl service(
            OutputRunBindingDao selectedRunDao, OutputAccessTicketDao selectedTicketDao,
            AgentIdentityService selectedIdentityService) {
        return new OutputRunAuthorizationServiceImpl(
                new OutputSourceAuthorizerRegistry(List.of(sourceBoundary())),
                sourceDao, selectedRunDao, selectedTicketDao,
                runtimeDao, selectedIdentityService, true);
    }

    private OutputSourceAuthorizer sourceBoundary() {
        return new OutputSourceAuthorizer() {
            @Override
            public String sourceType() {
                return OutputConstants.SOURCE_TASK;
            }

            @Override
            public OutputSourceAuthorization lockAndAuthorize(
                    String tenantId, String clientId, String sourceId, String producerAgentId) {
                return lockAndAuthorize(tenantId, clientId, sourceId, producerAgentId,
                        OutputSourceAccessMode.MUTATION);
            }

            @Override
            public OutputSourceAuthorization lockAndAuthorize(
                    String tenantId, String clientId, String sourceId, String producerAgentId,
                    OutputSourceAccessMode accessMode) {
                List<String> access = jdbc.queryForList("""
                        SELECT access_level FROM od01_source_root
                        WHERE tenant_id=? AND client_id=? AND source_id=? AND producer_agent_id=?
                          AND CAST(tenant_id AS BINARY)=CAST(? AS BINARY)
                          AND CAST(client_id AS BINARY)=CAST(? AS BINARY)
                          AND CAST(source_id AS BINARY)=CAST(? AS BINARY)
                          AND CAST(producer_agent_id AS BINARY)=CAST(? AS BINARY)
                        FOR UPDATE
                        """, String.class, tenantId, clientId, sourceId, producerAgentId,
                        tenantId, clientId, sourceId, producerAgentId);
                if (access.size() != 1) throw denied("task root unavailable");
                boolean writable = "READ_WRITE".equals(access.getFirst());
                boolean readable = writable || "READ_ONLY".equals(access.getFirst());
                if ((accessMode == OutputSourceAccessMode.MUTATION && !writable)
                        || (accessMode == OutputSourceAccessMode.RECEIPT_READ && !readable)) {
                    throw denied("task member access unavailable");
                }
                return new OutputSourceAuthorization(
                        tenantId, clientId, sourceType(), sourceId,
                        tenantId, producerAgentId, writable);
            }
        };
    }

    private void createSchema() throws Exception {
        jdbc.execute("""
                CREATE TABLE agent_runtime (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    agent_id VARCHAR(100) NOT NULL,name VARCHAR(100) NOT NULL,
                    avatar VARCHAR(500),owner_jiacn VARCHAR(50),persona_code VARCHAR(50),
                    persona_name VARCHAR(50),binding_id BIGINT,abilities JSON,endpoint VARCHAR(500),
                    token_hash VARCHAR(200),status VARCHAR(20) NOT NULL,current_task_id VARCHAR(100),
                    current_task_title VARCHAR(200),last_seen_at BIGINT,error_message VARCHAR(1000),
                    create_time BIGINT,update_time BIGINT,tenant_id VARCHAR(50),client_id VARCHAR(50),
                    UNIQUE KEY uk_runtime_agent(agent_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        jdbc.execute("""
                CREATE TABLE agent_persona_binding (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    jiacn VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
                    persona_code VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
                    agent_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
                    bound_at BIGINT NOT NULL,status INT NOT NULL,
                    create_time BIGINT NULL,update_time BIGINT NULL,
                    tenant_id VARCHAR(50) COLLATE utf8mb4_0900_bin NULL,
                    client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NULL,
                    PRIMARY KEY(id),UNIQUE KEY uk_test_binding_agent(agent_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        jdbc.execute("""
                CREATE TABLE agent_identity_registry (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    canonical_agent_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
                    canonical_type VARCHAR(32) COLLATE utf8mb4_0900_bin NOT NULL,
                    lifecycle_status VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL,
                    client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NULL,
                    owner_jiacn VARCHAR(50) COLLATE utf8mb4_0900_bin NULL,
                    tenant_id VARCHAR(50) COLLATE utf8mb4_0900_bin NULL,
                    binding_id BIGINT NULL,provisioned_at BIGINT NULL,activated_at BIGINT NULL,
                    suspended_at BIGINT NULL,retired_at BIGINT NULL,
                    audit_reason VARCHAR(1000) COLLATE utf8mb4_0900_bin NOT NULL,
                    create_time BIGINT NULL,update_time BIGINT NULL,
                    PRIMARY KEY(id),UNIQUE KEY uk_test_identity_agent(canonical_agent_id),
                    UNIQUE KEY uk_test_identity_binding(binding_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        jdbc.execute("""
                CREATE TABLE agent_identity_alias (
                    id BIGINT NOT NULL AUTO_INCREMENT,registry_id BIGINT NOT NULL,
                    canonical_agent_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
                    alias_type VARCHAR(32) COLLATE utf8mb4_0900_bin NOT NULL,
                    alias_value VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
                    alias_status VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL,
                    valid_from BIGINT NOT NULL,valid_to BIGINT NULL,
                    client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
                    owner_jiacn VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
                    tenant_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
                    audit_reason VARCHAR(1000) COLLATE utf8mb4_0900_bin NOT NULL,
                    create_time BIGINT NULL,update_time BIGINT NULL,PRIMARY KEY(id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        new OutputDeliverySchemaInitializer(jdbc).afterPropertiesSet();
        new OutputObjectSchemaInitializer(jdbc).afterPropertiesSet();
        jdbc.execute("""
                CREATE TABLE od01_source_root (
                    tenant_id VARBINARY(200) NOT NULL,client_id VARBINARY(200) NOT NULL,
                    source_id VARBINARY(400) NOT NULL,producer_agent_id VARBINARY(400) NOT NULL,
                    access_level VARCHAR(20) NOT NULL,
                    PRIMARY KEY(tenant_id,client_id,source_id)
                ) ENGINE=InnoDB
                """);
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO agent_runtime(
                    agent_id,name,owner_jiacn,binding_id,abilities,endpoint,token_hash,status,
                    last_seen_at,create_time,update_time,tenant_id,client_id,
                    output_capabilities_json,output_capabilities_runtime_id,
                    output_capabilities_updated_at)
                VALUES ('agent-1','Agent 1','owner',7,'[]','wss://agent','registration',
                        'online',?,?,?,?,?,CAST(? AS JSON),'runtime-1',?)
                """, now, now, now, "owner", "client",
                "[\"output.http.v1\",\"task.owner-share.v1\"]", now);
        jdbc.update("""
                INSERT INTO agent_persona_binding(
                    id,jiacn,persona_code,agent_id,bound_at,status,create_time,update_time,
                    tenant_id,client_id)
                VALUES (7,'owner','test','agent-1',?,1,?,?,'owner','client')
                """, now, now, now);
        jdbc.update("""
                INSERT INTO agent_identity_registry(
                    id,canonical_agent_id,canonical_type,lifecycle_status,client_id,owner_jiacn,
                    tenant_id,binding_id,provisioned_at,activated_at,audit_reason,create_time,update_time)
                VALUES (7,'agent-1','LEGACY_CANONICAL','ACTIVE','client','owner','owner',
                        7,?,?,'OD02 authorization test',?,?)
                """, now, now, now, now);
    }

    private void resetIdentity() {
        long now = System.currentTimeMillis();
        jdbc.update("UPDATE agent_persona_binding SET status=1,update_time=? WHERE id=7", now);
        jdbc.update("""
                INSERT INTO agent_identity_registry(
                    id,canonical_agent_id,canonical_type,lifecycle_status,client_id,owner_jiacn,
                    tenant_id,binding_id,provisioned_at,activated_at,suspended_at,retired_at,
                    audit_reason,create_time,update_time)
                VALUES (7,'agent-1','LEGACY_CANONICAL','ACTIVE','client','owner','owner',
                        7,?,?,NULL,NULL,'OD02 authorization test',?,?)
                ON DUPLICATE KEY UPDATE lifecycle_status='ACTIVE',binding_id=7,
                    suspended_at=NULL,retired_at=NULL,update_time=VALUES(update_time)
                """, now, now, now, now);
    }

    private void insertSourceAndRun(int index, String state, String accessLevel) {
        String sourceId = sourceId(index);
        String runId = runId(index);
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO od01_source_root(
                    tenant_id,client_id,source_id,producer_agent_id,access_level)
                VALUES ('owner','client',?,'agent-1',?)
                """, sourceId, accessLevel);
        jdbc.update("""
                INSERT INTO output_source_binding(
                    tenant_id,client_id,source_type,source_id,owner_jiacn,ownership_state,
                    created_at,updated_at,row_version)
                VALUES ('owner','client','TASK',?,'owner','ACTIVE',?,?,0)
                """, sourceId, now, now);
        jdbc.update("""
                INSERT INTO output_run_binding(
                    tenant_id,client_id,run_id,source_type,source_id,producer_agent_id,
                    binding_id,original_runtime_id,origin_type,origin_id,state,policy_version,
                    recovery_until,max_bytes,max_files,work_item_id,created_at,updated_at,row_version)
                VALUES ('owner','client',?,'TASK',?,'agent-1','7','runtime-1','COMMAND',?,
                        ?,0,?,209715200,100,NULL,?,?,0)
                """, runId, sourceId, "command-" + index, state,
                now + OutputConstants.RUN_RECOVERY_MILLIS, now, now);
    }

    private void refreshRuntime(String runtimeId, long bindingId) {
        long now = System.currentTimeMillis();
        jdbc.update("""
                UPDATE agent_runtime SET binding_id=?,output_capabilities_runtime_id=?,
                    output_capabilities_json=CAST(? AS JSON),output_capabilities_updated_at=?
                WHERE agent_id='agent-1'
                """, bindingId, runtimeId, "[\"output.http.v1\"]", now);
    }

    private OutputAuthReceiptDTO issue(
            OutputRunAuthorizationServiceImpl selected, int index, String runtimeId) {
        return transaction.execute(status -> selected.issueTicket(
                "owner", "client", "agent-1", runtimeId,
                "auth-" + UUID.randomUUID(), runId(index)));
    }

    private OutputTicketAuthorization authorize(
            OutputRunAuthorizationServiceImpl selected, String bearer,
            String operation, boolean receiptReplay) {
        return transaction.execute(status -> selected.authorizeTicket(
                bearer, operation, receiptReplay));
    }

    private Future<Throwable> async(ThrowingRunnable operation) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        return executor.submit(() -> {
            try {
                operation.run();
                return null;
            } catch (Throwable error) {
                return error;
            } finally {
                executor.shutdown();
            }
        });
    }

    private byte[] ticketHash(String bearer) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(bearer.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    private String sourceId(int index) {
        return "task-" + index;
    }

    private String runId(int index) {
        return String.format("%032x", index + 1L);
    }

    private SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(OutputSourceBindingMapper.class);
        configuration.addMapper(OutputRunBindingMapper.class);
        configuration.addMapper(OutputAccessTicketMapper.class);
        configuration.addMapper(AgentRuntimeMapper.class);
        configuration.addMapper(AgentIdentityRegistryMapper.class);
        configuration.addMapper(AgentIdentityAliasMapper.class);
        configuration.addMapper(AgentPersonaBindingMapper.class);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        globalConfig.setBanner(false);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTransactionFactory(new SpringManagedTransactionFactory());
        factory.setConfiguration(configuration);
        factory.setGlobalConfig(globalConfig);
        return factory.getObject();
    }

    @SuppressWarnings("unchecked")
    private <T> T transactionalProxy(Object target, Class<T> interfaceType) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(interfaceType);
        factory.addAdvice(new TransactionInterceptor(
                new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    private <T> T wire(T target, Object mapper) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            for (String fieldName : List.of("mapper", "baseMapper")) {
                try {
                    Field field = type.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    field.set(target, mapper);
                    return target;
                } catch (NoSuchFieldException ignored) {
                    // Continue through the DAO hierarchy.
                }
            }
            type = type.getSuperclass();
        }
        throw new NoSuchFieldException("mapper/baseMapper");
    }

    private DriverManagerDataSource dataSource(String url) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(url);
        source.setUsername(username);
        source.setPassword(password);
        return source;
    }

    private String urlForDatabase(String url, String targetDatabase) {
        int query = url.indexOf('?');
        String head = query >= 0 ? url.substring(0, query) : url;
        String tail = query >= 0 ? url.substring(query) : "";
        int slash = head.indexOf('/', "jdbc:mysql://".length());
        return slash < 0 ? head + "/" + targetDatabase + tail
                : head.substring(0, slash + 1) + targetDatabase + tail;
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }

    private OutputAuthorizationException denied(String message) {
        return new OutputAuthorizationException("OUTPUT_AUTH_FORBIDDEN", message);
    }

    private static final class BlockingScanner implements OutputMalwareScanner {
        private final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        @Override public ScanResult scan(java.io.InputStream input,long maximumBytes)throws IOException{input.readAllBytes();entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IOException("scan barrier timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}return new ScanResult(true,"test",null);}
    }

    private static final class MemoryStorage implements OutputObjectStorage {
        private final Map<String,byte[]> bytes=new ConcurrentHashMap<>();private final Map<String,String> tokens=new ConcurrentHashMap<>();
        @Override public Stored putCreateOnly(String bucket,String key,java.io.InputStream input,long length,String contentType,long deadline)throws IOException{byte[] value=input.readAllBytes();if(bytes.putIfAbsent(key,value)!=null)throw new IOException("exists");return new Stored(null,"etag");}
        @Override public java.io.InputStream open(String bucket,String key,String version)throws IOException{byte[] value=bytes.get(key);if(value==null)throw new IOException("missing");return new ByteArrayInputStream(value);}
        @Override public void putTombstone(String bucket,String key,String token,long deadline){bytes.put(key,new byte[0]);tokens.put(key,token);}
        @Override public Head head(String bucket,String key){byte[] value=bytes.get(key);return new Head(value==null?0:value.length,null,tokens.get(key));}
        @Override public void delete(String bucket,String key,String version){bytes.remove(key);}
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static class DelegatingTicketDao implements OutputAccessTicketDao {
        private final OutputAccessTicketDao delegate;

        private DelegatingTicketDao(OutputAccessTicketDao delegate) {
            this.delegate = delegate;
        }

        @Override
        public int insert(OutputAccessTicketEntity entity) {
            return delegate.insert(entity);
        }

        @Override
        public OutputAccessTicketEntity findByHash(byte[] ticketHash, boolean forUpdate) {
            return delegate.findByHash(ticketHash, forUpdate);
        }

        @Override
        public List<byte[]> lockRecentHashesForBinding(
                String tenantId, String clientId, String bindingId, long since) {
            return delegate.lockRecentHashesForBinding(tenantId, clientId, bindingId, since);
        }
    }

    private static final class ProjectionBarrierTicketDao extends DelegatingTicketDao {
        private final byte[] targetHash;
        private final AtomicBoolean pending = new AtomicBoolean(true);
        private final CountDownLatch projected = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);

        private ProjectionBarrierTicketDao(OutputAccessTicketDao delegate, byte[] targetHash) {
            super(delegate);
            this.targetHash = targetHash.clone();
        }

        @Override
        public OutputAccessTicketEntity findByHash(byte[] ticketHash, boolean forUpdate) {
            OutputAccessTicketEntity result = super.findByHash(ticketHash, forUpdate);
            if (!forUpdate && java.util.Arrays.equals(targetHash, ticketHash)
                    && pending.getAndSet(false)) {
                projected.countDown();
                await(resume, "ticket projection barrier");
            }
            return result;
        }

        private void awaitProjection() {
            await(projected, "ticket projection");
        }

        private void release() {
            resume.countDown();
        }
    }

    private static final class ProjectionBarrierRunDao implements OutputRunBindingDao {
        private final OutputRunBindingDao delegate;
        private final String targetRunId;
        private final AtomicBoolean pending = new AtomicBoolean(true);
        private final CountDownLatch projected = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);

        private ProjectionBarrierRunDao(OutputRunBindingDao delegate, String targetRunId) {
            this.delegate = delegate;
            this.targetRunId = targetRunId;
        }

        @Override
        public int insert(OutputRunBindingEntity entity) {
            return delegate.insert(entity);
        }

        @Override
        public OutputRunBindingEntity findExactByRun(
                String tenantId, String clientId, String runId, boolean forUpdate) {
            OutputRunBindingEntity result = delegate.findExactByRun(
                    tenantId, clientId, runId, forUpdate);
            if (!forUpdate && targetRunId.equals(runId) && pending.getAndSet(false)) {
                projected.countDown();
                await(resume, "run projection barrier");
            }
            return result;
        }

        @Override
        public OutputRunBindingEntity findExactByOrigin(
                String tenantId, String clientId, String sourceType, String sourceId,
                String producerAgentId, String originType, String originId) {
            return delegate.findExactByOrigin(tenantId, clientId, sourceType, sourceId,
                    producerAgentId, originType, originId);
        }

        private void awaitProjection() {
            await(projected, "run projection");
        }

        private void release() {
            resume.countDown();
        }
    }

    private static void await(CountDownLatch latch, String name) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError(name + " timeout");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }
}
