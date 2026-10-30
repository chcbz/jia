package cn.jia.agent.service.impl;

import cn.jia.agent.config.AgentExternalServicePolicySchemaInitializer;
import cn.jia.agent.dao.*;
import cn.jia.agent.dao.impl.*;
import cn.jia.agent.entity.AgentTaskTargetSummaryDTO;
import cn.jia.agent.mapper.*;
import cn.jia.agent.service.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;

import static cn.jia.agent.service.AgentTaskExecutionGrantException.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real InnoDB/production mappers and transaction proxies, no mocked locks or DAOs.
 * Reuses JuyitingWorkflowMySqlTest's CYF_REVIEW_MYSQLD owned-instance pattern inline,
 * without inheriting another source set's fixture. No external JDBC fallback or runner.
 */
@EnabledIfEnvironmentVariable(named = "CYF_REVIEW_MYSQLD", matches = ".+")
class AgentExternalServicePolicyMySqlTest {
    private static final String A = "agt_" + "a".repeat(32);
    private static final String B = "agt_" + "b".repeat(32);
    private static final String C = "agt_" + "c".repeat(32);
    private static final AgentTaskExecutionGrantService.Scope TASK_OWNER = scope("client-a", "task-owner");
    private static final AgentTaskExecutionGrantService.Scope PROVIDER = scope("client-a", "provider");
    @TempDir static Path scratch;
    private static Process initializationProcess, databaseProcess;
    private static String baseUrl;
    private static JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String namespace, database;
    private boolean createdDatabase;
    private DataSourceTransactionManager transactions;
    private AgentExternalServicePolicyService policies;
    private TaskAssigneeAuthorityService authority;
    private AgentIdentityService identities;
    private long providerBinding;
    private PersonalWorkspaceExecutionMapper executions;

    private AgentTaskExecutionGrantDaoImpl grants;
    private AgentExecutionReportMapper reports;

    @BeforeAll static void startOwnedDatabase() throws Exception {
        Path executable = Path.of(System.getenv("CYF_REVIEW_MYSQLD")).toRealPath();
        assertTrue(Files.isExecutable(executable));
        Path data = Files.createDirectory(scratch.resolve("data"));
        Path log = scratch.resolve("mysql.log");
        String user = System.getProperty("user.name");
        try {
            initializationProcess = new ProcessBuilder(executable.toString(), "--no-defaults", "--initialize-insecure",
                    "--user=" + user, "--datadir=" + data, "--basedir=" + executable.getParent().getParent())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            assertEquals(0, initializationProcess.waitFor(), () -> "Owned MySQL init failed: " + read(log));
            initializationProcess = null;
            int port;
            do { try (var socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); } }
            while (port == 3306 || port == 33060);
            databaseProcess = new ProcessBuilder(executable.toString(), "--no-defaults", "--user=" + user,
                    "--datadir=" + data, "--basedir=" + executable.getParent().getParent(),
                    "--socket=" + scratch.resolve("mysql.sock"), "--pid-file=" + scratch.resolve("mysql.pid"),
                    "--port=" + port, "--bind-address=127.0.0.1", "--mysqlx=OFF", "--skip-log-bin",
                    "--innodb-buffer-pool-size=67108864")
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
            baseUrl = "jdbc:mysql://127.0.0.1:" + port + "/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
            Class.forName("com.mysql.cj.jdbc.Driver");
            while (true) {
                assertTrue(databaseProcess.isAlive(), () -> "Owned MySQL exited: " + read(log));
                try (var connection = DriverManager.getConnection(baseUrl, "root", "")) {
                    assertTrue(connection.getMetaData().getDatabaseProductVersion().startsWith("8.0.21"));
                    break;
                } catch (SQLException notReady) { Thread.sleep(100); }
            }
            admin = new JdbcTemplate(dataSource(baseUrl, "root", ""));
            System.out.println("CYF_EXTERNAL_POLICY_MYSQL owned=true version=" + admin.queryForObject("SELECT VERSION()", String.class));
        } catch (Throwable failure) {
            try { stopOwnedDatabase(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @AfterAll static void stopOwnedDatabase() throws Exception {
        // Only handles returned by this test's ProcessBuilder; never kill by name, PID file or port.
        try { stopOwnedProcess(databaseProcess); }
        finally {
            databaseProcess = null;
            try { stopOwnedProcess(initializationProcess); }
            finally { initializationProcess = null; admin = null; }
        }
    }
    private static void stopOwnedProcess(Process process) throws InterruptedException {
        if (process == null) return;
        process.destroy();
        try { process.waitFor(); }
        catch (InterruptedException interrupted) {
            process.destroyForcibly();
            try { process.waitFor(); } finally { Thread.currentThread().interrupt(); }
            throw interrupted;
        }
    }
    private static String read(Path file) {
        try { return Files.readString(file); }
        catch (Exception failure) { return failure.getClass().getSimpleName(); }
    }

    @BeforeEach void setUp() throws Exception {
        namespace = "cyf_external_policy_"; database = namespace + UUID.randomUUID().toString().replace("-", "");
        assertNotNull(admin);
        assertEquals(0, admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?", Integer.class, database));
        admin.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        createdDatabase = true;
        var source = dataSource(databaseUrl(baseUrl, database), "root", "");
        jdbc = new JdbcTemplate(source); transactions = new DataSourceTransactionManager(source);
        // Reuse the two production identity CREATE statements, not the following legacy migration/procedures.
        String identityDdl = new ClassPathResource("db/agent-identity-schema.sql").getContentAsString(StandardCharsets.UTF_8);
        int migration = identityDdl.indexOf("-- A02 uses a temporary assertion procedure");
        assertTrue(migration > 0, "Identity DDL boundary changed; do not execute a legacy migration in this fixture");
        new ResourceDatabasePopulator(new ByteArrayResource(identityDdl.substring(0, migration).getBytes(StandardCharsets.UTF_8))).execute(source);
        createSupportingTables();
        new AgentExternalServicePolicySchemaInitializer(jdbc).afterPropertiesSet();
        new ResourceDatabasePopulator(new ClassPathResource("db/agent-task-execution-grant-v1.sql")).execute(source);

        MybatisConfiguration configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
        for (Class<?> mapper : List.of(AgentIdentityRegistryMapper.class, AgentIdentityAliasMapper.class,
                AgentPersonaBindingMapper.class, AgentExternalServicePolicyMapper.class, AgentTaskMetaMapper.class,
                AgentTaskExecutionGrantMapper.class, AgentExecutionReportMapper.class, AgentRuntimeMapper.class, PersonalWorkspaceExecutionMapper.class)) configuration.addMapper(mapper);
        GlobalConfig global = new GlobalConfig(); global.setIdentifierGenerator(new DefaultIdentifierGenerator()); global.setBanner(false);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(configuration); factory.setGlobalConfig(global);
        var sql = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        executions = sql.getMapper(PersonalWorkspaceExecutionMapper.class);
        reports = sql.getMapper(AgentExecutionReportMapper.class);
        var registry = wire(new AgentIdentityRegistryDaoImpl(), sql.getMapper(AgentIdentityRegistryMapper.class));
        var aliases = wire(new AgentIdentityAliasDaoImpl(), sql.getMapper(AgentIdentityAliasMapper.class));
        var bindings = wire(new AgentPersonaBindingDaoImpl(), sql.getMapper(AgentPersonaBindingMapper.class));
        identities = proxy(new AgentIdentityServiceImpl(registry, aliases, bindings), AgentIdentityService.class);
        var policyDao = new AgentExternalServicePolicyDaoImpl(sql.getMapper(AgentExternalServicePolicyMapper.class));
        var tasks = wire(new AgentTaskMetaDaoImpl(), sql.getMapper(AgentTaskMetaMapper.class));
        grants = new AgentTaskExecutionGrantDaoImpl(sql.getMapper(AgentTaskExecutionGrantMapper.class));
        var runtimes = wire(new AgentRuntimeDaoImpl(), sql.getMapper(AgentRuntimeMapper.class));
        policies = proxy(new AgentExternalServicePolicyServiceImpl(policyDao, identities, tasks, grants, runtimes), AgentExternalServicePolicyService.class);
        authority = new TaskAssigneeAuthorityServiceImpl(policyDao, identities, grants);
        seed(A, TASK_OWNER, null, "offline"); providerBinding = seed(B, PROVIDER, null, "busy");
        jdbc.update("INSERT INTO agent_task_meta (task_id,tenant_id,client_id,owner_jiacn) VALUES ('task-a','0','client-a','task-owner')");
    }

    @AfterEach void tearDown() {
        if (createdDatabase && admin != null && database != null && namespace != null
                && database.startsWith(namespace) && database.matches("[A-Za-z0-9_]+")) {
            admin.execute("DROP DATABASE `" + database + "`");
            assertEquals(0, admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?", Integer.class, database));
        }
    }

    @Test void reportHeadsKeepIdenticalCommandKeysInSeparateEnvironments() {
        var source = Objects.requireNonNull(jdbc.getDataSource());
        new ResourceDatabasePopulator(new ClassPathResource("db/agent-execution-report-v1.sql")).execute(source);
        jdbc.execute("ALTER TABLE agent_execution_report_head DROP CHECK chk_execution_report_head_tenant, "
                + "ADD CONSTRAINT chk_execution_report_head_tenant CHECK (tenant_id='0')");
        jdbc.execute("ALTER TABLE agent_execution_report_inbox DROP CHECK chk_execution_report_tenant, "
                + "ADD CONSTRAINT chk_execution_report_tenant CHECK (tenant_id='0')");
        var migration = new ResourceDatabasePopulator(new ClassPathResource("db/agent-execution-report-tenant-20261030.sql"));
        migration.execute(source);
        migration.execute(source);
        for (String tenant : List.of("0","1")) {
            var row = new cn.jia.agent.entity.AgentExecutionReportHeadEntity()
                    .setOwnerJiacn("task-owner").setAgentId(A).setRuntimeInstanceId("runtime-a")
                    .setExecutionRef("execution-a").setCommandId("command-a").setAttempt(1)
                    .setLastSequence(0L).setCommittedVersion(0L);
            row.setTenantId(tenant); row.setClientId("client-a");
            row.setCreateTime(1L); row.setUpdateTime(1L);
            assertEquals(1,reports.insertHead(row));
        }
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertEquals("0", reports.lockHead("0","client-a","task-owner",A,"command-a",1).getTenantId());
            assertEquals("1", reports.lockHead("1","client-a","task-owner",A,"command-a",1).getTenantId());
            assertNull(reports.lockHead("1 ","client-a","task-owner",A,"command-a",1));
            assertNull(reports.lockHead("1","client-a","foreign-owner",A,"command-a",1));
            assertNull(reports.lockHead("1","foreign-client","task-owner",A,"command-a",1));
        });
    }

    @Test void identityMigrationPartitionsSameLegacyAliasWithoutChangingProductionIdentity() {
        jdbc.execute("ALTER TABLE agent_persona_binding ADD CONSTRAINT chk_agent_binding_single_tenant CHECK (tenant_id='0')");
        jdbc.execute("CREATE TABLE agent_hosted_profile (id BIGINT PRIMARY KEY, tenant_id VARCHAR(50) NOT NULL, "
                + "CONSTRAINT chk_hosted_single_tenant CHECK (tenant_id='0')) ENGINE=InnoDB");
        var migration = new ResourceDatabasePopulator(
                new ClassPathResource("db/agent-identity-business-tenant-20261030.sql"));
        migration.execute(Objects.requireNonNull(jdbc.getDataSource()));
        migration.execute(jdbc.getDataSource());
        String production = "agt_" + "c".repeat(32), gray = "agt_" + "d".repeat(32);
        seed(production, TASK_OWNER, null, "online");
        seed(gray, new AgentTaskExecutionGrantService.Scope("1","client-a","task-owner"), null, "online");
        for (String agent : List.of(production, gray)) {
            jdbc.update("INSERT INTO agent_identity_alias "
                    + "(registry_id,canonical_agent_id,alias_type,alias_value,alias_status,valid_from,client_id,owner_jiacn,tenant_id,audit_reason) "
                    + "SELECT id,canonical_agent_id,'LEGACY_AGENT_ID','same-alias','ACTIVE',1,client_id,owner_jiacn,tenant_id,'fixture' "
                    + "FROM agent_identity_registry WHERE canonical_agent_id=?", agent);
        }
        assertEquals(production, identities.resolveAgentIdInScope("0","client-a","task-owner","same-alias"));
        assertEquals(gray, identities.resolveAgentIdInScope("1","client-a","task-owner","same-alias"));
        assertThrows(RuntimeException.class, () -> identities.requireCanonicalAgentIdInScope("0","client-a","task-owner",gray));
        assertThrows(RuntimeException.class, () -> identities.requireCanonicalAgentIdInScope("1","client-a","task-owner",production));
        assertThrows(RuntimeException.class, () -> jdbc.update("INSERT INTO agent_identity_alias "
                + "(registry_id,canonical_agent_id,alias_type,alias_value,alias_status,valid_from,client_id,owner_jiacn,tenant_id,audit_reason) "
                + "SELECT id,canonical_agent_id,'LEGACY_AGENT_ID','forged-alias','ACTIVE',1,client_id,owner_jiacn,'1','fixture' "
                + "FROM agent_identity_registry WHERE canonical_agent_id=?", production));
        assertEquals("0", jdbc.queryForObject("SELECT tenant_id FROM agent_identity_registry WHERE canonical_agent_id=?", String.class, A));
    }

    @Test void grantTenantMigrationKeepsProductionAndScopesIdenticalGrantKeys() {
        persistExternalGrant();
        jdbc.execute("ALTER TABLE agent_task_execution_grant DROP CHECK chk_atxg_tenant, "
                + "ADD CONSTRAINT chk_atxg_tenant CHECK (tenant_id='0')");
        var migration = new ResourceDatabasePopulator(
                new ClassPathResource("db/agent-task-execution-grant-tenant-20261030.sql"));
        migration.execute(Objects.requireNonNull(jdbc.getDataSource()));
        migration.execute(jdbc.getDataSource());
        var gray = grants.findByGrant("0", "client-a", "task-owner", "task-a", "grant-a");
        assertNotNull(gray);
        gray.setId(null);
        gray.setTenantId("1");
        grants.insert(gray);
        assertEquals("0", grants.findByGrant("0", "client-a", "task-owner", "task-a", "grant-a").getTenantId());
        assertEquals("1", grants.findByGrant("1", "client-a", "task-owner", "task-a", "grant-a").getTenantId());
        assertNull(grants.findByGrant("1", "client-a", "foreign-owner", "task-a", "grant-a"));
        assertNull(grants.findByGrant("1", "foreign-client", "task-owner", "task-a", "grant-a"));
        assertThrows(IllegalArgumentException.class,
                () -> grants.findByGrant("1 ", "client-a", "task-owner", "task-a", "grant-a"));
        new TransactionTemplate(transactions).executeWithoutResult(status ->
                assertEquals(1, grants.supersedeActiveForTask("1", "client-a", "task-owner", "task-a", 2L)));
        assertEquals("ACTIVE", grants.findByGrant("0", "client-a", "task-owner", "task-a", "grant-a").getState());
        assertEquals("SUPERSEDED", grants.findByGrant("1", "client-a", "task-owner", "task-a", "grant-a").getState());
    }

    @Test void simultaneousOppositeFirstWritesHaveOneCasWinnerAndOneConflict() throws Exception {
        assertEquals(new AgentExternalServicePolicyService.Policy(B, false, "0"), policies.read(PROVIDER, B));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> enable = pool.submit(() -> raceWrite(start, "enable", true));
            Future<Object> disable = pool.submit(() -> raceWrite(start, "disable", false));
            start.countDown();
            List<Object> outcomes = List.of(enable.get(10, TimeUnit.SECONDS), disable.get(10, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter(AgentExternalServicePolicyService.Policy.class::isInstance).count());
            assertEquals(1, outcomes.stream().filter(CONFLICT::equals).count());
            var winner = (AgentExternalServicePolicyService.Policy) outcomes.stream().filter(AgentExternalServicePolicyService.Policy.class::isInstance).findFirst().orElseThrow();
            assertEquals(winner, policies.read(PROVIDER, B)); assertEquals("1", winner.revision());
            assertEquals(1, policyCount());
            assertEquals(winner.enabled(), jdbc.queryForObject("SELECT enabled FROM agent_external_service_policy", Boolean.class));
        } finally { start.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    @Test void simultaneousSameKeyReplaysOneReceiptAndLaterSwitchNeverRewritesOriginal() throws Exception {
        CountDownLatch start = new CountDownLatch(1); ExecutorService pool = Executors.newFixedThreadPool(2);
        AgentExternalServicePolicyService.Policy receipt;
        try {
            Callable<AgentExternalServicePolicyService.Policy> call = () -> { await(start); return policies.write(PROVIDER, B, "same", true, 0); };
            var left = pool.submit(call); var right = pool.submit(call); start.countDown();
            receipt = left.get(10, TimeUnit.SECONDS); assertEquals(receipt, right.get(10, TimeUnit.SECONDS));
        } finally { start.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
        assertEquals(1, policyCount());
        var originalRow = jdbc.queryForMap("SELECT * FROM agent_external_service_policy WHERE idempotency_key='same'");
        assertEquals("2", policies.write(PROVIDER, B, "close", false, 1).revision());
        assertEquals(receipt, policies.write(PROVIDER, B, "same", true, 0));
        assertEquals(receipt, policies.receipt(PROVIDER, B, "same"));
        assertFalse(policies.read(PROVIDER, B).enabled()); assertEquals(2, policyCount());
        assertEquals(originalRow, jdbc.queryForMap("SELECT * FROM agent_external_service_policy WHERE idempotency_key='same'"));
        assertFailure(IDEMPOTENCY_CONFLICT, () -> policies.write(PROVIDER, B, "same", false, 0));
        assertFailure(CONFLICT, () -> policies.write(PROVIDER, B, "stale", true, 0));
        assertFailure(NOT_FOUND, () -> policies.receipt(PROVIDER, B, "unknown"));
        assertEquals(2, policyCount());
    }

    @Test void ownerClientAndAgentAreInReplayScopeAndOuterRollbackLeavesNoReceipt() {
        seed(C, PROVIDER, null, "offline");
        policies.write(PROVIDER, B, "key", true, 0);
        assertFailure(IDEMPOTENCY_CONFLICT, () -> policies.write(PROVIDER, C, "key", true, 0));
        assertFailure(NOT_FOUND, () -> policies.write(TASK_OWNER, B, "foreign", true, 0));
        assertFailure(NOT_FOUND, () -> policies.read(scope("client-b", "provider"), B));
        assertFailure(NOT_FOUND, () -> policies.receipt(TASK_OWNER, B, "key"));
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            policies.write(PROVIDER, C, "rolled-back", true, 0); status.setRollbackOnly();
        });
        assertEquals("0", policies.read(PROVIDER, C).revision());
        assertFailure(NOT_FOUND, () -> policies.receipt(PROVIDER, C, "rolled-back"));
        assertEquals(1, policyCount());
        assertEquals("1", policies.write(TASK_OWNER, A, "key", false, 0).revision());
        assertEquals(2, policyCount(), "same key in another owner scope is independent");
        String foreign = "agt_" + "d".repeat(32);
        var otherClient = scope("client-b", "provider");
        seed(foreign, otherClient, null, "online");
        assertEquals("1", policies.write(otherClient, foreign, "key", true, 0).revision());
        assertEquals(3, policyCount(), "same owner/key in another client scope is independent");
    }

    @Test void assignmentLockBeforeCloseFreezesGrantAndCloseDoesNotRevokeExistingTask() throws Exception {
        policies.write(PROVIDER, B, "open", true, 0);
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1), writerEntered = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var grant = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                lockTask(); // task-root -> binding/identity -> latest policy, matching admission order
                var target = authority.lockForAssignment("0", "client-a", "task-owner", B);
                assertEquals(Long.valueOf(1), target.externalPolicyRevision());
                insertGrant(target); locked.countDown(); await(release); return target;
            }));
            await(locked);
            var close = pool.submit(() -> { writerEntered.countDown(); return policies.write(PROVIDER, B, "close", false, 1); });
            await(writerEntered);
            assertThrows(TimeoutException.class, () -> close.get(200, TimeUnit.MILLISECONDS), "switch must wait for the real identity/binding lock");
            release.countDown(); var admitted = grant.get(10, TimeUnit.SECONDS);
            assertFalse(close.get(10, TimeUnit.SECONDS).enabled());
            assertEquals(admitted, authority.requireGrantTarget("0", "client-a", "task-owner", "task-a", "grant-a", 1));
            assertEquals(B, policies.assignedTarget(TASK_OWNER, "task-a").agentId());
            assertEquals(List.of(A), candidateIds());
            assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT external_policy_revision FROM agent_task_execution_grant", Long.class));
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    @Test void closeLockBeforeAssignmentDeniesNewGrantAfterCommit() throws Exception {
        policies.write(PROVIDER, B, "open", true, 0);
        CountDownLatch closed = new CountDownLatch(1), release = new CountDownLatch(1), assignmentEntered = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var closing = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                var receipt = policies.write(PROVIDER, B, "close", false, 1);
                closed.countDown(); await(release); return receipt;
            }));
            await(closed);
            var assigning = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                lockTask(); assignmentEntered.countDown();
                var target = authority.lockForAssignment("0", "client-a", "task-owner", B);
                insertGrant(target); return target;
            }));
            await(assignmentEntered);
            assertThrows(TimeoutException.class, () -> assigning.get(200, TimeUnit.MILLISECONDS));
            release.countDown(); assertFalse(closing.get(10, TimeUnit.SECONDS).enabled());
            var rejected = assertThrows(ExecutionException.class, () -> assigning.get(10, TimeUnit.SECONDS));
            assertEquals(CONFLICT, assertInstanceOf(AgentTaskExecutionGrantException.class, rejected.getCause()).reason());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_execution_grant", Integer.class));
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    @Test void firstAbsentPolicyWriteAlsoSerializesWithAssignmentAndOwnAgentNeedsNoSwitch() throws Exception {
        // The common identity lock, not a pre-existing policy row, protects revision zero.
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1), writerEntered = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var ownerAssignment = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                var target = authority.lockForAssignment("0", "client-a", "provider", B);
                assertNull(target.externalPolicyRevision()); locked.countDown(); await(release); return target;
            }));
            await(locked);
            var first = pool.submit(() -> { writerEntered.countDown(); return policies.write(PROVIDER, B, "first", true, 0); });
            await(writerEntered); assertThrows(TimeoutException.class, () -> first.get(200, TimeUnit.MILLISECONDS));
            release.countDown(); ownerAssignment.get(10, TimeUnit.SECONDS);
            assertEquals("1", first.get(10, TimeUnit.SECONDS).revision()); assertEquals(1, policyCount());
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    @Test void candidatesArePaginatedCanonicalAndOfflineBusyDoNotFilterLegalTargets() {
        seed(C, PROVIDER, "legacy-provider-c", "offline");
        policies.write(PROVIDER, B, "open-b", true, 0); policies.write(PROVIDER, C, "open-c", true, 0);
        String foreign = "agt_" + "d".repeat(32);
        seed(foreign, scope("client-b", "provider"), null, "online");
        policies.write(scope("client-b", "provider"), foreign, "foreign-open", true, 0);
        var all = new ArrayList<AgentTaskTargetSummaryDTO>(); String cursor = null;
        do {
            var page = policies.candidates(TASK_OWNER, "task-a", cursor, 1);
            assertEquals(1, page.items().size()); all.addAll(page.items()); cursor = page.nextAfterAgentId();
            assertTrue(all.size() <= 3, "cursor must progress rather than repeat a page");
        } while (cursor != null);
        assertEquals(List.of(A, B, C), all.stream().map(AgentTaskTargetSummaryDTO::agentId).toList());
        assertTrue(all.getFirst().isMine()); assertFalse(all.get(1).isMine());
        assertEquals("busy", all.get(1).availability()); assertEquals("offline", all.get(2).availability());
        assertTrue(all.stream().allMatch(item -> item.providerDisplayName() == null));
        assertFailure(NOT_FOUND, () -> policies.candidates(PROVIDER, "task-a", null, 50));
        assertFailure(NOT_FOUND, () -> policies.candidates(scope("client-b", "task-owner"), "task-a", null, 50));
        assertFailure(BAD_REQUEST, () -> policies.candidates(TASK_OWNER, "task-a", null, 0));
        assertFailure(BAD_REQUEST, () -> policies.candidates(TASK_OWNER, "task-a", null, 101));
        assertTrue(policies.candidates(TASK_OWNER, "task-a", C, 50).items().isEmpty());
    }

    @Test void candidateBindingAndAliasMustStayActiveExactScopedAndAudited() {
        long aliasBinding = seed(C, PROVIDER, "legacy-provider-c", "offline");
        policies.write(PROVIDER, C, "open-c", true, 0);
        assertEquals(List.of(A, C), candidateIds());
        jdbc.update("UPDATE agent_identity_alias SET alias_status='REVOKED',valid_to=2 WHERE canonical_agent_id=?", C);
        assertEquals(List.of(A), candidateIds());
        jdbc.update("UPDATE agent_identity_alias SET alias_status='ACTIVE',valid_to=NULL WHERE canonical_agent_id=?", C);
        for (String mutation : List.of("status=0", "bound_at=0", "persona_code=''", "jiacn='another-owner'", "client_id='other-client'", "agent_id='LEGACY-PROVIDER-C'")) {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.update("UPDATE agent_persona_binding SET " + mutation + " WHERE id=?", aliasBinding);
                assertEquals(List.of(A), candidateIds(), mutation); status.setRollbackOnly();
            });
        }
        for (String mutation : List.of("audit_reason=''", "valid_from=0")) {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.update("UPDATE agent_identity_alias SET " + mutation + " WHERE canonical_agent_id=?", C);
                assertEquals(List.of(A), candidateIds(), mutation); status.setRollbackOnly();
            });
        }
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("UPDATE agent_identity_registry SET lifecycle_status='SUSPENDED' WHERE canonical_agent_id=?", C);
            assertEquals(List.of(A), candidateIds()); status.setRollbackOnly();
        });
        assertEquals(List.of(A, C), candidateIds());
        assertFailure(NOT_FOUND, () -> policies.read(PROVIDER, "legacy-provider-c"));
    }

    @Test void oldBindingPolicyCannotOpenReplacementAndRuntimeMismatchIsUnavailableNotLoginLeak() {
        policies.write(PROVIDER, B, "open", true, 0);
        jdbc.update("UPDATE agent_runtime SET binding_id=binding_id+1000,name='internal-login-secret' WHERE agent_id=?", B);
        var item = policies.candidates(TASK_OWNER, "task-a", null, 50).items().getLast();
        assertEquals("unavailable", item.availability()); assertEquals(B, item.displayName()); assertNull(item.providerDisplayName());
        // Simulate an audited replacement projection; old binding's revision must never carry forward.
        jdbc.update("INSERT INTO agent_persona_binding (jiacn,persona_code,agent_id,bound_at,status,tenant_id,client_id) VALUES ('provider','replacement',?,2,1,'0','client-a')", B);
        long replacement = jdbc.queryForObject("SELECT id FROM agent_persona_binding WHERE persona_code='replacement'", Long.class);
        jdbc.update("UPDATE agent_persona_binding SET status=0 WHERE id=?", providerBinding);
        jdbc.update("UPDATE agent_identity_registry SET binding_id=? WHERE canonical_agent_id=?", replacement, B);
        assertEquals("0", policies.read(PROVIDER, B).revision()); assertFalse(policies.read(PROVIDER, B).enabled());
        assertEquals(List.of(A), candidateIds());
        assertEquals("1", policies.write(PROVIDER, B, "replacement-key", true, 0).revision());
        assertEquals(2, policyCount());
    }

    @Test void runtimeConversationLookupUsesFrozenExecutorNotExecutionDataOwner() {
        persistExternalGrant();
        seedExecution("conversation", "CONVERSATION", B, "grant-a", 1L);
        var found = executions.findForRuntime("0", "client-a", "provider", B, "task-a", "conversation");
        assertNotNull(found);
        assertEquals("execution-conversation", found.getExecutionId());
        assertEquals("task-owner", found.getOwnerJiacn(), "data ownership must stay A while executor is B");
        assertEquals("grant-a", found.getTaskGrantId());
        for (String actor : List.of("task-owner", "third-owner", "PROVIDER", "provider ")) {
            assertNull(executions.findForRuntime("0", "client-a", actor, B, "task-a", "conversation"), actor);
        }
        assertNull(executions.findForRuntime("1", "client-a", "provider", B, "task-a", "conversation"));
        for (String client : List.of("other-client", "CLIENT-A", "client-a ")) {
            assertNull(executions.findForRuntime("0", client, "provider", B, "task-a", "conversation"));
        }
        assertNull(executions.findForRuntime("0", "client-a", "provider", A, "task-a", "conversation"));
        assertNull(executions.findForRuntime("0", "client-a", "provider", B, "other-task", "conversation"));
        assertNull(executions.findForRuntime("0", "client-a", "provider", B, "task-a", "other-run"));
        assertNull(executions.findForRuntime("0", "client-a", "provider", B, "task-a ", "conversation"));
        assertNull(executions.findForRuntime("0", "client-a", "provider", B, "task-a", "conversation "));
    }

    @Test void runtimeConversationLookupRequiresExactPersistedGrantBindingAndVersion() {
        persistExternalGrant(); seedExecution("conversation", "CONVERSATION", B, "grant-a", 1L);
        for (String mutation : List.of("task_grant_version=2", "task_grant_id='missing'", "task_grant_id=NULL",
                "owner_jiacn='third-owner'", "execution_mode='UNKNOWN'")) {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.update("UPDATE agent_personal_workspace_execution SET " + mutation + " WHERE execution_id='execution-conversation'");
                assertNull(executions.findForRuntime("0", "client-a", "provider", B, "task-a", "conversation"), mutation);
                status.setRollbackOnly();
            });
        }
        for (String mutation : List.of("grant_version=2", "owner_jiacn='third-owner'", "target_agent_id='another-target'",
                "client_id='other-client'", "task_id='another-task'")) {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.update("UPDATE agent_task_execution_grant SET " + mutation + " WHERE grant_id='grant-a'");
                assertNull(executions.findForRuntime("0", "client-a", "provider", B, "task-a", "conversation"), mutation);
                status.setRollbackOnly();
            });
        }
        assertNotNull(executions.findForRuntime("0", "client-a", "provider", B, "task-a", "conversation"));
        jdbc.update("DELETE FROM agent_task_execution_grant WHERE grant_id='grant-a'");
        assertNull(executions.findForRuntime("0", "client-a", "provider", B, "task-a", "conversation"));
        assertNull(executions.findForRuntime("0", "client-a", "task-owner", B, "task-a", "conversation"));
    }

    @Test void privateAndTaskRuntimeLookupsRemainOwnerOnlyEvenWithForeignExecutorGrant() {
        persistExternalGrant();
        for (String mode : List.of("PRIVATE", "TASK")) {
            // Genuine own execution does not require a conversation grant.
            seedExecution(mode, mode, A, null, null);
            var owned = executions.findForRuntime("0", "client-a", "task-owner", A, "task-a", mode);
            assertNotNull(owned); assertEquals(mode, owned.getExecutionMode());
            for (String actor : List.of("provider", "third-owner", "TASK-OWNER", "task-owner ")) {
                assertNull(executions.findForRuntime("0", "client-a", actor, A, "task-a", mode));
            }
            assertNull(executions.findForRuntime("1", "client-a", "task-owner", A, "task-a", mode));
            assertNull(executions.findForRuntime("0", "other-client", "task-owner", A, "task-a", mode));
            // Even a matching B-target grant cannot widen PRIVATE/TASK to its executor.
            seedExecution(mode + "-foreign-grant", mode, B, "grant-a", 1L);
            assertNull(executions.findForRuntime("0", "client-a", "provider", B, "task-a", mode + "-foreign-grant"));
            assertNotNull(executions.findForRuntime("0", "client-a", "task-owner", B, "task-a", mode + "-foreign-grant"));
        }
    }

    private void persistExternalGrant() {
        policies.write(PROVIDER, B, "open-runtime", true, 0);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            lockTask(); insertGrant(authority.lockForAssignment("0", "client-a", "task-owner", B));
        });
    }
    @Test void taskParticipantIdentityUsesFrozenProviderThroughRealGrantMapper() {
        persistExternalGrant();
        assertEquals(B, authority.requireTaskAgent("0", "client-a", "task-owner", "task-a", B, true));
        assertEquals(B, authority.requireTaskAgent("0", "client-a", "task-owner", "task-a", B, false));
        jdbc.update("UPDATE agent_task_execution_grant SET state='REVOKED',grant_version=2,revoked_at=2,revoke_idempotency_key='revoke-fixture',revoke_request_hash=REPEAT('a',64) WHERE grant_id='grant-a'");
        assertEquals(B, authority.requireTaskAgent("0", "client-a", "task-owner", "task-a", B, false));
        assertThrows(AgentTaskExecutionGrantException.class,
                () -> authority.requireTaskAgent("0", "client-a", "task-owner", "task-a", B, true));
        assertThrows(RuntimeException.class,
                () -> authority.requireTaskAgent("0", "client-a", "task-owner", "TASK-A", B, false));
        assertThrows(RuntimeException.class,
                () -> authority.requireTaskAgent("0", "client-a", "third-owner", "task-a", B, false));
    }

    private void seedExecution(String run, String mode, String target, String grant, Long version) {
        jdbc.update("""
                INSERT INTO agent_personal_workspace_execution
                  (execution_id,tenant_id,client_id,owner_jiacn,task_id,run_id,execution_mode,target_agent_id,task_grant_id,task_grant_version)
                VALUES (?,'0','client-a','task-owner','task-a',?,?,?,?,?)
                """, "execution-" + run, run, mode, target, grant, version);
    }

    private Object raceWrite(CountDownLatch start, String key, boolean enabled) {
        await(start);
        try { return policies.write(PROVIDER, B, key, enabled, 0); }
        catch (AgentTaskExecutionGrantException failure) { return failure.reason(); }
    }
    private int policyCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM agent_external_service_policy", Integer.class); }
    private List<String> candidateIds() { return policies.candidates(TASK_OWNER, "task-a", null, 50).items().stream().map(AgentTaskTargetSummaryDTO::agentId).toList(); }
    private void lockTask() {
        assertEquals("task-a", jdbc.queryForObject("SELECT task_id FROM agent_task_meta WHERE tenant_id='0' AND client_id='client-a' AND owner_jiacn='task-owner' AND task_id='task-a' FOR UPDATE", String.class));
    }
    private void insertGrant(TaskAssigneeAuthorityService.Target target) {
        // Use the real grant schema to persist the authority lock's frozen executor, not a fake in-memory grant.
        jdbc.update("""
                INSERT INTO agent_task_execution_grant
                (grant_id,tenant_id,client_id,owner_jiacn,task_id,requirement_revision,assignment_revision,
                 target_agent_id,executor_owner_jiacn,executor_binding_id,external_policy_revision,
                 permitted_operations_json,permitted_tool_policy_ref,input_scope_json,allow_own_task_derived_assets,
                 source_business_action_id,idempotency_key,request_hash,policy_revision,grant_version,state,issued_by,created_at)
                VALUES ('grant-a','0','client-a','task-owner','task-a',1,1,?,?,?,?,'["READ_TASK"]','fixture','{}',0,
                        'action-a','grant-key',?,'1',1,'ACTIVE','task-owner',1)
                """, target.agentId(), target.ownerJiacn(), target.bindingId(), target.externalPolicyRevision(), "a".repeat(64));
    }
    private long seed(String agent, AgentTaskExecutionGrantService.Scope scope, String alias, String availability) {
        String bindingAgent = alias == null ? agent : alias;
        jdbc.update("INSERT INTO agent_persona_binding (jiacn,persona_code,agent_id,bound_at,status,tenant_id,client_id,create_time,update_time) VALUES (?,?,?,1,1,?,?,1,1)", scope.ownerJiacn(), agent, bindingAgent, scope.tenantId(), scope.clientId());
        long binding = jdbc.queryForObject("SELECT id FROM agent_persona_binding WHERE agent_id=?", Long.class, bindingAgent);
        jdbc.update("""
                INSERT INTO agent_identity_registry (canonical_agent_id,canonical_type,lifecycle_status,client_id,owner_jiacn,tenant_id,
                  binding_id,provisioned_at,activated_at,audit_reason,create_time,update_time)
                VALUES (?,'OPAQUE','ACTIVE',?,?,?,?,1,1,'external-policy-fixture',1,1)
                """, agent, scope.clientId(), scope.ownerJiacn(), scope.tenantId(), binding);
        if (alias != null) {
            long registry = jdbc.queryForObject("SELECT id FROM agent_identity_registry WHERE canonical_agent_id=?", Long.class, agent);
            jdbc.update("""
                    INSERT INTO agent_identity_alias (registry_id,canonical_agent_id,alias_type,alias_value,alias_status,valid_from,
                        client_id,owner_jiacn,tenant_id,audit_reason,create_time,update_time)
                    VALUES (?,?,'LEGACY_AGENT_ID',?,'ACTIVE',1,?,?,?,'audited-fixture-alias',1,1)
                    """, registry, agent, alias, scope.clientId(), scope.ownerJiacn(), scope.tenantId());
        }
        jdbc.update("INSERT INTO agent_runtime (agent_id,name,owner_jiacn,binding_id,status,tenant_id,client_id) VALUES (?,?,?,?,?,?,?)",
                agent, "公开Agent", scope.ownerJiacn(), binding, availability, scope.tenantId(), scope.clientId());
        return binding;
    }
    private void createSupportingTables() {
        // Minimal supporting projections as in AgentIdentityRuntimeMySqlTest; policy/identity/grant DDL are production resources.
        jdbc.execute("""
                CREATE TABLE agent_persona_binding (
                  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, jiacn VARCHAR(50) NOT NULL,
                  persona_code VARCHAR(50) NOT NULL, agent_id VARCHAR(100) NOT NULL,
                  bound_at BIGINT NOT NULL, status INT NOT NULL, tenant_id VARCHAR(50), client_id VARCHAR(50),
                  create_time BIGINT, update_time BIGINT
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        jdbc.execute("""
                CREATE TABLE agent_task_meta (
                  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, task_id VARCHAR(100) NOT NULL,
                  tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL, owner_jiacn VARCHAR(50) NOT NULL,
                  UNIQUE KEY uk_fixture_task (tenant_id,client_id,owner_jiacn,task_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        jdbc.execute("""
                CREATE TABLE agent_personal_workspace_execution (
                  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, execution_id VARCHAR(100) NOT NULL,
                  tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL, owner_jiacn VARCHAR(50) NOT NULL,
                  task_id VARCHAR(100) NOT NULL, run_id VARCHAR(100) NOT NULL, execution_mode VARCHAR(32) NOT NULL,
                  target_agent_id VARCHAR(100) NOT NULL, task_grant_id VARCHAR(100), task_grant_version BIGINT,
                  UNIQUE KEY uk_fixture_execution (tenant_id,client_id,owner_jiacn,execution_id),
                  UNIQUE KEY uk_fixture_run (tenant_id,client_id,owner_jiacn,task_id,run_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        jdbc.execute("""
                CREATE TABLE agent_runtime (
                  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, agent_id VARCHAR(100) NOT NULL,
                  name VARCHAR(100), owner_jiacn VARCHAR(50), binding_id BIGINT, status VARCHAR(20),
                  tenant_id VARCHAR(50), client_id VARCHAR(50), UNIQUE KEY uk_fixture_runtime (agent_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
    }
    private <T> T proxy(Object target, Class<T> type) {
        ProxyFactory factory = new ProxyFactory(target); factory.setInterfaces(type);
        TransactionInterceptor interceptor = new TransactionInterceptor(); interceptor.setTransactionManager(transactions);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource()); factory.addAdvice(interceptor);
        return type.cast(factory.getProxy());
    }
    private static <T> T wire(T dao, Object mapper) throws Exception {
        for (Class<?> type = dao.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField("baseMapper"); field.setAccessible(true); field.set(dao, mapper); return dao; }
            catch (NoSuchFieldException ignored) { /* BaseDaoImpl owns the inherited field. */ }
        }
        throw new NoSuchFieldException("baseMapper");
    }
    private static void assertFailure(AgentTaskExecutionGrantException.Reason reason, org.junit.jupiter.api.function.Executable action) {
        assertEquals(reason, assertThrows(AgentTaskExecutionGrantException.class, action).reason());
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS), "transaction barrier timed out"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    private static DriverManagerDataSource dataSource(String url, String user, String password) {
        var source = new DriverManagerDataSource(); source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(url); source.setUsername(user); source.setPassword(password); return source;
    }
    private static String databaseUrl(String url, String database) {
        int query = url.indexOf('?'); String head = query < 0 ? url : url.substring(0, query);
        String suffix = query < 0 ? "" : url.substring(query); int path = head.indexOf('/', "jdbc:mysql://".length());
        return (path < 0 ? head + "/" : head.substring(0, path + 1)) + database + suffix;
    }
    private static AgentTaskExecutionGrantService.Scope scope(String client, String owner) { return new AgentTaskExecutionGrantService.Scope("0", client, owner); }
}
