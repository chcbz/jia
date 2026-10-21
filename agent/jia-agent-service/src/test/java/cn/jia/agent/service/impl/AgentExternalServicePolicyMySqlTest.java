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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import static cn.jia.agent.service.AgentTaskExecutionGrantException.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real InnoDB/production mappers and transaction proxies, no mocked locks or DAOs.
 * Reuses HallReadTaskCreateMySqlTest's acknowledged isolated fixture environment;
 * skips only when its URL is absent. No mysqld runner or production connection fallback.
 */
@EnabledIfEnvironmentVariable(named = "JYT_UX_B01B_MYSQL_URL", matches = ".+")
class AgentExternalServicePolicyMySqlTest {
    private static final String A = "agt_" + "a".repeat(32);
    private static final String B = "agt_" + "b".repeat(32);
    private static final String C = "agt_" + "c".repeat(32);
    private static final AgentTaskExecutionGrantService.Scope TASK_OWNER = scope("client-a", "task-owner");
    private static final AgentTaskExecutionGrantService.Scope PROVIDER = scope("client-a", "provider");
    private JdbcTemplate admin, jdbc;
    private String namespace, database;
    private boolean createdDatabase;
    private DataSourceTransactionManager transactions;
    private AgentExternalServicePolicyService policies;
    private TaskAssigneeAuthorityService authority;
    private AgentIdentityService identities;
    private long providerBinding;

    @BeforeEach void setUp() throws Exception {
        String url = required("JYT_UX_B01B_MYSQL_URL"), user = required("JYT_UX_B01B_MYSQL_USER");
        String password = System.getenv("JYT_UX_B01B_MYSQL_PASSWORD");
        String prefix = required("JYT_UX_B01B_MYSQL_DATABASE_PREFIX");
        if (password == null || !url.startsWith("jdbc:mysql://") || !prefix.matches("[A-Za-z0-9_]{1,20}")
                || !"true".equals(required("JYT_UX_B01B_MYSQL_ISOLATED_FIXTURE"))) {
            throw new IllegalStateException("Acknowledged isolated MySQL fixture required");
        }
        namespace = prefix + "_external_policy_"; database = namespace + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        admin = new JdbcTemplate(dataSource(url, user, password));
        assertNotNull(admin.queryForObject("SELECT VERSION()", String.class));
        assertEquals(0, admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?", Integer.class, database));
        admin.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        createdDatabase = true;
        var source = dataSource(databaseUrl(url, database), user, password);
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
                AgentTaskExecutionGrantMapper.class, AgentRuntimeMapper.class)) configuration.addMapper(mapper);
        GlobalConfig global = new GlobalConfig(); global.setIdentifierGenerator(new DefaultIdentifierGenerator()); global.setBanner(false);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(configuration); factory.setGlobalConfig(global);
        var sql = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        var registry = wire(new AgentIdentityRegistryDaoImpl(), sql.getMapper(AgentIdentityRegistryMapper.class));
        var aliases = wire(new AgentIdentityAliasDaoImpl(), sql.getMapper(AgentIdentityAliasMapper.class));
        var bindings = wire(new AgentPersonaBindingDaoImpl(), sql.getMapper(AgentPersonaBindingMapper.class));
        identities = proxy(new AgentIdentityServiceImpl(registry, aliases, bindings), AgentIdentityService.class);
        var policyDao = new AgentExternalServicePolicyDaoImpl(sql.getMapper(AgentExternalServicePolicyMapper.class));
        var tasks = wire(new AgentTaskMetaDaoImpl(), sql.getMapper(AgentTaskMetaMapper.class));
        var grants = new AgentTaskExecutionGrantDaoImpl(sql.getMapper(AgentTaskExecutionGrantMapper.class));
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
    private static String required(String name) { String value = System.getenv(name); if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + name); return value; }
    private static AgentTaskExecutionGrantService.Scope scope(String client, String owner) { return new AgentTaskExecutionGrantService.Scope("0", client, owner); }
}
