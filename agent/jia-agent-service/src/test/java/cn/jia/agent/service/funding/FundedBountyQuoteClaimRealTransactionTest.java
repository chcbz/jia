package cn.jia.agent.service.funding;

import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitSafetyProperties;
import cn.jia.agent.dao.impl.AgentIdentityAliasDaoImpl;
import cn.jia.agent.dao.impl.AgentIdentityRegistryDaoImpl;
import cn.jia.agent.dao.impl.AgentPersonaBindingDaoImpl;
import cn.jia.agent.dao.impl.AgentRuntimeDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskMemberDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskMetaDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskWorkItemDaoImpl;
import cn.jia.agent.entity.AgentTaskCreateDTO;
import cn.jia.agent.entity.funding.AgentModelPreferenceDTO;
import cn.jia.agent.entity.funding.AgentTaskClaimReceiptDTO;
import cn.jia.agent.entity.funding.AgentTaskClaimRequestDTO;
import cn.jia.agent.entity.funding.AgentTaskQuoteDTO;
import cn.jia.agent.entity.funding.AgentTaskQuoteRequestDTO;
import cn.jia.agent.mapper.AgentIdentityAliasMapper;
import cn.jia.agent.mapper.AgentIdentityRegistryMapper;
import cn.jia.agent.mapper.AgentPersonaBindingMapper;
import cn.jia.agent.mapper.AgentRuntimeMapper;
import cn.jia.agent.mapper.AgentTaskBountyQuoteMapper;
import cn.jia.agent.mapper.AgentTaskFundingMapper;
import cn.jia.agent.mapper.AgentTaskMemberMapper;
import cn.jia.agent.mapper.AgentTaskMetaMapper;
import cn.jia.agent.mapper.AgentTaskWorkItemMapper;
import cn.jia.agent.service.AgentCommandTransportWriter;
import cn.jia.agent.service.AgentTaskAggregationService;
import cn.jia.agent.service.impl.AgentCommandTransportCapture;
import cn.jia.agent.service.impl.AgentIdentityServiceImpl;
import cn.jia.agent.service.impl.AgentLegacyTaskCompatibilityService;
import cn.jia.agent.service.impl.AgentTaskMutationTransactionImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bounded W05 integration source: real W04 reserve/task/event collaborators plus real quote,
 * identity/binding/runtime, assignment/member/work-item mappers and one physical Spring TM.
 * Rabbit/outbox flags deliberately stay OFF; this does not prove enabled invitation delivery.
 * The only failure double throws AFTER production claim-receipt SQL, never synthesizing DB results.
 */
class FundedBountyQuoteClaimRealTransactionTest {
    private static final String TENANT = "Tenant-W04";
    private static final String CLIENT = "Client-W04";
    private static final String USER = "jwt-sub-w04";
    private static final FundedBountyActor ACTOR = new FundedBountyActor(TENANT, CLIENT, USER);
    private static final String AGENT_A = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String AGENT_B = "agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final long GROSS = 1_000_000_000L;

    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private PlatformTransactionManager tm;
    private FundedBountyServiceRealTransactionTest.IntegrationServices foundation;
    private FundedBountyServiceRealTransactionTest.RecordingBroker broker;
    private FundedBountyQuoteClaimServiceImpl service;
    private final FailAfterClaimReceipt fault = new FailAfterClaimReceipt();
    private String taskId;

    @BeforeEach
    void setUp() throws Exception {
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "w05-preview", Map.of("economy.preview.enabled", "true")));
        context.register(FundedBountyServiceRealTransactionTest.TestConfiguration.class);
        context.refresh();
        DataSource source = context.getBean(DataSource.class);
        tm = context.getBean(PlatformTransactionManager.class);
        jdbc = new JdbcTemplate(source);
        new ResourceDatabasePopulator(new ClassPathResource("w04/funded-integration-h2.sql"),
                new ClassPathResource("w05/claim-integration-h2.sql")).execute(source);
        // Load the actual W05 DDL, removing MySQL-only collation/engine syntax, not constraints.
        String quoteDdl;
        try (var input = new ClassPathResource("db/agent-task-bounty-quote-v0.sql").getInputStream()) {
            quoteDdl = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace(" COLLATE utf8mb4_0900_bin", "")
                    .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "");
        }
        new ResourceDatabasePopulator(new ByteArrayResource(quoteDdl.getBytes(StandardCharsets.UTF_8)))
                .execute(source);
        foundation = context.getBean(FundedBountyServiceRealTransactionTest.IntegrationServices.class);
        broker = context.getBean(FundedBountyServiceRealTransactionTest.RecordingBroker.class);
        SqlSessionTemplate template = claimMappers(source);
        SqlSessionTemplate rootTemplate = context.getBean(SqlSessionTemplate.class);
        AgentTaskMetaDaoImpl meta = wire(new AgentTaskMetaDaoImpl(), rootTemplate.getMapper(AgentTaskMetaMapper.class));
        AgentIdentityServiceImpl identity = new AgentIdentityServiceImpl(
                wire(new AgentIdentityRegistryDaoImpl(), template.getMapper(AgentIdentityRegistryMapper.class)),
                wire(new AgentIdentityAliasDaoImpl(), template.getMapper(AgentIdentityAliasMapper.class)),
                wire(new AgentPersonaBindingDaoImpl(), template.getMapper(AgentPersonaBindingMapper.class)));
        AgentRuntimeDaoImpl runtime = wire(new AgentRuntimeDaoImpl(), template.getMapper(AgentRuntimeMapper.class));
        AgentTaskMutationTransactionImpl mutation = new AgentTaskMutationTransactionImpl(meta, tm);
        AgentTaskAggregationService unusedAggregation = (AgentTaskAggregationService) Proxy.newProxyInstance(
                AgentTaskAggregationService.class.getClassLoader(), new Class<?>[]{AgentTaskAggregationService.class},
                (proxy, method, args) -> { throw new AssertionError("claim must not call aggregation: " + method); });
        AgentLegacyTaskCompatibilityService assignment = new AgentLegacyTaskCompatibilityService(meta,
                new AgentTaskMemberDaoImpl(template.getMapper(AgentTaskMemberMapper.class)),
                new AgentTaskWorkItemDaoImpl(template.getMapper(AgentTaskWorkItemMapper.class)),
                unusedAggregation, identity, mutation, foundation.events());
        ReflectionTestUtils.setField(assignment, "fundedBountyLegacyGuard", foundation.guard());
        AgentCommandTransportCapture capture = new AgentCommandTransportCapture(
                new AgentRabbitSafetyGate(new AgentRabbitSafetyProperties(null, null, null, null, null, null)),
                new StaticListableBeanFactory().getBeanProvider(AgentCommandTransportWriter.class));
        context.registerBean(AgentTaskBountyQuoteMapper.class, () -> template.getMapper(AgentTaskBountyQuoteMapper.class));
        context.registerBean(AgentTaskFundingMapper.class, () -> rootTemplate.getMapper(AgentTaskFundingMapper.class));
        context.registerBean(cn.jia.agent.service.AgentTaskMutationTransaction.class, () -> mutation);
        context.registerBean(cn.jia.agent.service.AgentIdentityService.class, () -> identity);
        context.registerBean(cn.jia.agent.dao.AgentRuntimeDao.class, () -> runtime);
        context.registerBean(AgentLegacyTaskCompatibilityService.class, () -> assignment);
        context.registerBean(AgentCommandTransportCapture.class, () -> capture);
        // Use actual production constructor selection and ObjectProvider fallback, not new(service).
        context.registerBean(FundedBountyQuoteClaimServiceImpl.class);
        service = context.getBean(FundedBountyQuoteClaimServiceImpl.class);
        seedAgent(1, AGENT_A);
        seedAgent(2, AGENT_B);
        jdbc.update("""
                INSERT INTO economy_account(account_id,owner_type,owner_id,purpose,currency,balance_micro,
                    allow_negative,status,version,tenant_id,client_id,create_time,update_time)
                VALUES('wallet-fixture','USER',?,'AVAILABLE','SILVER',?,0,'ACTIVE',0,?,?,1,1)
                """, USER, GROSS, TENANT, CLIENT);
        AgentTaskCreateDTO create = new AgentTaskCreateDTO();
        create.setTitle("W05 claim transaction");
        create.setRequiredAbilities(List.of("test"));
        create.setGrossBountyAmountMicro(Long.toString(GROSS));
        create.setSettlementPolicy("GROSS_INCLUSIVE");
        create.setRequiredSkillRequirements(List.of());
        taskId = foundation.funding().create(ACTOR, key(1), FundedBountyRequestDigest.create(create), create).getId();
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            try {
                if (jdbc != null) jdbc.execute("DROP ALL OBJECTS"); // UUID-owned H2 from the R3 configuration only.
            } finally {
                context.close();
            }
        }
    }

    @Test
    void productionSpringConstructorCreatesRealQuoteClaimBean() {
        assertEquals(service, context.getBean(FundedBountyQuoteClaimService.class));
        assertTrue(quote(AGENT_A, 2).verifiedSkillMatch());
    }

    @Test
    void nullStaleAndForeignRuntimeBindingCannotQuoteOrClaimAndLeaveNoRows() {
        for (Long binding : java.util.Arrays.asList(null, 999L, 2L)) {
            jdbc.update("UPDATE agent_runtime SET binding_id=? WHERE agent_id=?", binding, AGENT_A);
            assertEquals("TASK_OR_COUNTERPARTY_NOT_FOUND", assertThrows(FundedBountyException.class,
                    () -> quote(AGENT_A, 2)).code());
            assertEquals(0, count("agent_task_bounty_quote"));
            assertEquals(1, count("agent_task_event"));
        }
        jdbc.update("UPDATE agent_runtime SET binding_id=1 WHERE agent_id=?", AGENT_A);
        AgentTaskQuoteDTO quote = quote(AGENT_A, 2);
        AgentTaskClaimRequestDTO request = claimRequest(AGENT_A, quote.quoteId());
        for (Long binding : java.util.Arrays.asList(null, 999L, 2L)) {
            jdbc.update("UPDATE agent_runtime SET binding_id=? WHERE agent_id=?", binding, AGENT_A);
            assertEquals("TASK_OR_COUNTERPARTY_NOT_FOUND", assertThrows(FundedBountyException.class, () ->
                    service.claim(ACTOR, key(3), FundedBountyRequestDigest.claim(taskId, request), taskId, request)).code());
            assertEquals(0, count("agent_task_bounty_claim_operation"));
            assertEquals(0, count("agent_task_member")); assertEquals(0, count("agent_task_work_item"));
            assertEquals(1, count("agent_task_event")); assertEquals(1, broker.wakeups.size());
            assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM agent_task_bounty_quote", String.class));
            assertEquals(0L, jdbc.queryForObject("SELECT task_version FROM agent_task_meta", Long.class));
        }
        jdbc.update("UPDATE agent_runtime SET binding_id=1 WHERE agent_id=?", AGENT_A);
        service.claim(ACTOR, key(3), FundedBountyRequestDigest.claim(taskId, request), taskId, request);
        assertClaimedOnce(AGENT_A);
    }

    @Test
    void refundedTaskKeepsOwnedImmutableQuoteReplayButRejectsNewQuote() {
        AgentTaskQuoteDTO before = quote(AGENT_A, 2);
        foundation.funding().cancel(ACTOR, key(3), FundedBountyRequestDigest.cancel(taskId, "0"), taskId, 0);
        assertEquals(before, quote(AGENT_A, 2));
        assertThrows(FundedBountyException.class, () -> quote(AGENT_A, 4));
        AgentTaskQuoteRequestDTO request = quoteRequest(AGENT_A);
        assertThrows(FundedBountyException.class, () -> service.quote(new FundedBountyActor(TENANT, CLIENT, "other"),
                key(2), FundedBountyRequestDigest.quote(taskId, request), taskId, request));
        assertEquals(1, count("agent_task_bounty_quote"));
        assertEquals(2, count("economy_transaction"));
    }

    @Test
    void explicitClaimCommitsOnceWithImmutableReceiptAndNoWakeupBeforeOuterCommit() {
        AgentTaskQuoteDTO quote = quote(AGENT_A, 2);
        AgentTaskClaimRequestDTO request = claimRequest(AGENT_A, quote.quoteId());
        byte[] digest = FundedBountyRequestDigest.claim(taskId, request);
        assertEquals("QUOTE_REQUIRED", assertThrows(FundedBountyException.class, () ->
                foundation.guard().requireAssignmentAllowed(TENANT, CLIENT, taskId, false, 1, false)).code());
        AgentTaskClaimReceiptDTO receipt = new TransactionTemplate(tm).execute(status -> {
            AgentTaskClaimReceiptDTO pending = service.claim(ACTOR, key(3), digest, taskId, request);
            assertEquals(1, broker.wakeups.size(), "claim event wakeups must wait for physical commit");
            assertEquals(1, count("agent_task_bounty_claim_operation"));
            return pending;
        });
        assertEquals(AGENT_A, receipt.agentId());
        assertEquals(quote.quoteId(), receipt.quoteId());
        assertEquals("assigned", receipt.status());
        assertEquals("1", receipt.taskVersion());
        assertTrue(receipt.claimedAt().matches("[1-9][0-9]*"));
        assertEquals(List.of("taskId", "agentId", "quoteId", "status", "taskVersion", "claimedAt"),
                Arrays.stream(AgentTaskClaimReceiptDTO.class.getRecordComponents()).map(c -> c.getName()).toList());
        assertClaimedOnce(AGENT_A);
        assertEquals(receipt, service.claim(ACTOR, key(3), digest, taskId, request));
        assertClaimedOnce(AGENT_A);
    }

    @Test
    void failureAfterRealReceiptUpdateRollsBackClaimQuoteAssignmentAndEveryEventThenSameKeyRetries() {
        AgentTaskQuoteDTO quote = quote(AGENT_A, 2);
        AgentTaskClaimRequestDTO request = claimRequest(AGENT_A, quote.quoteId());
        byte[] digest = FundedBountyRequestDigest.claim(taskId, request);
        fault.enabled.set(true);
        assertThrows(RuntimeException.class, () -> service.claim(ACTOR, key(3), digest, taskId, request));
        assertTrue(fault.fired.get(), "failure must occur after actual completed-receipt SQL");
        assertEquals(0, count("agent_task_bounty_claim_operation"));
        assertEquals(0, count("agent_task_member"));
        assertEquals(0, count("agent_task_work_item"));
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM agent_task_bounty_quote", String.class));
        assertEquals("open", jdbc.queryForObject("SELECT reward_status FROM agent_task_meta", String.class));
        assertEquals(0L, jdbc.queryForObject("SELECT task_version FROM agent_task_meta", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT current_event_version FROM agent_task_meta", Long.class));
        assertEquals(1, count("agent_task_event"));
        assertEquals(1, broker.wakeups.size());
        assertReserveUnchanged();
        fault.enabled.set(false);
        AgentTaskClaimReceiptDTO receipt = service.claim(ACTOR, key(3), digest, taskId, request);
        assertEquals(receipt, service.claim(ACTOR, key(3), digest, taskId, request));
        assertClaimedOnce(AGENT_A);
    }

    @Test
    void twoExplicitAgentsRacingTheSameRootProduceOneClaimAndNoLosingRows() throws Exception {
        AgentTaskQuoteDTO a = quote(AGENT_A, 2);
        AgentTaskQuoteDTO b = quote(AGENT_B, 3);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        List<Object> outcomes;
        try {
            Future<Object> first = workers.submit(() -> concurrentClaim(start, AGENT_A, a.quoteId(), 4));
            Future<Object> second = workers.submit(() -> concurrentClaim(start, AGENT_B, b.quoteId(), 5));
            start.countDown();
            outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            workers.shutdownNow(); // Only these test-owned workers.
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(1L, outcomes.stream().filter(AgentTaskClaimReceiptDTO.class::isInstance).count());
        assertEquals(1L, outcomes.stream().filter("TASK_VERSION_CONFLICT"::equals).count());
        AgentTaskClaimReceiptDTO winner = (AgentTaskClaimReceiptDTO) outcomes.stream()
                .filter(AgentTaskClaimReceiptDTO.class::isInstance).findFirst().orElseThrow();
        assertClaimedOnce(winner.agentId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_bounty_quote WHERE status='CLAIMED'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_bounty_quote WHERE status='OPEN'", Integer.class));
    }

    @Test
    void completedClaimAndClaimedQuoteRejectSqlUnknownNullBypasses() {
        AgentTaskQuoteDTO quote = quote(AGENT_A, 2);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_bounty_quote "
                + "WHERE status='OPEN' AND claimed_at IS NULL", Integer.class));
        AgentTaskClaimRequestDTO request = claimRequest(AGENT_A, quote.quoteId());
        AgentTaskClaimReceiptDTO receipt = service.claim(
                ACTOR, key(3), FundedBountyRequestDigest.claim(taskId, request), taskId, request);
        // Otherwise-valid committed production rows ensure each failure is the intended CHECK.
        DataAccessException quoteFailure = assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE agent_task_bounty_quote SET claimed_at=NULL WHERE quote_id=?", quote.quoteId()));
        assertTrue(quoteFailure.getMostSpecificCause().getMessage().toLowerCase(java.util.Locale.ROOT)
                .contains("chk_bounty_quote_state"));
        for (String assignment : List.of("receipt_task_version=NULL", "claimed_at=NULL",
                "receipt_task_version=NULL,claimed_at=NULL")) {
            DataAccessException failure = assertThrows(DataAccessException.class, () -> jdbc.update(
                    "UPDATE agent_task_bounty_claim_operation SET " + assignment + " WHERE quote_id=?",
                    quote.quoteId()));
            assertTrue(failure.getMostSpecificCause().getMessage().toLowerCase(java.util.Locale.ROOT)
                    .contains("chk_bounty_claim_state"));
        }
        assertEquals(Long.valueOf(receipt.claimedAt()), jdbc.queryForObject(
                "SELECT claimed_at FROM agent_task_bounty_quote WHERE quote_id=?", Long.class, quote.quoteId()));
        assertEquals(receipt, service.claim(ACTOR, key(3), FundedBountyRequestDigest.claim(taskId, request),
                taskId, request));
        assertClaimedOnce(AGENT_A);
    }

    @Test
    void mergedQuoteAndClaimDigestsRetainW04MalformedSurrogateRejection() {
        AgentTaskQuoteRequestDTO quote = quoteRequest(AGENT_A);
        quote.setContextRevision("\ud800");
        assertThrows(IllegalArgumentException.class, () -> FundedBountyRequestDigest.quote(taskId, quote));
        AgentTaskClaimRequestDTO claim = claimRequest(AGENT_A, "q_bad\udc00");
        assertThrows(IllegalArgumentException.class, () -> FundedBountyRequestDigest.claim(taskId, claim));
    }

    private Object concurrentClaim(CountDownLatch start, String agent, String quoteId, int key) throws Exception {
        assertTrue(start.await(10, TimeUnit.SECONDS));
        AgentTaskClaimRequestDTO request = claimRequest(agent, quoteId);
        try {
            return service.claim(ACTOR, key(key), FundedBountyRequestDigest.claim(taskId, request), taskId, request);
        } catch (FundedBountyException failure) {
            return failure.code();
        }
    }

    private void assertClaimedOnce(String winner) {
        assertEquals(1, count("agent_task_bounty_claim_operation"));
        assertEquals(1, count("agent_task_member"));
        assertEquals(1, count("agent_task_work_item"));
        assertEquals(winner, jdbc.queryForObject("SELECT assigned_agent_id FROM agent_task_meta", String.class));
        assertEquals(winner, jdbc.queryForObject("SELECT agent_id FROM agent_task_member", String.class));
        assertEquals(winner, jdbc.queryForObject("SELECT assignee_agent_id FROM agent_task_work_item", String.class));
        assertEquals(1L, jdbc.queryForObject("SELECT task_version FROM agent_task_meta", Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_event WHERE event_type=?",
                Integer.class, TaskEventType.TASK_ASSIGNED));
        // Preserve the existing M2 child-event catalog: creation + task assignment + member + work item.
        assertEquals(4, count("agent_task_event"));
        assertEquals(4L, jdbc.queryForObject("SELECT current_event_version FROM agent_task_meta", Long.class));
        assertEquals(4, broker.wakeups.size());
        for (var wakeup : broker.wakeups) assertEquals(1, wakeup.visibleEventCount());
        assertReserveUnchanged();
    }

    private void assertReserveUnchanged() {
        assertEquals("FUNDS_HELD", jdbc.queryForObject("SELECT funding_status FROM agent_task_funding", String.class));
        assertEquals(GROSS, jdbc.queryForObject("SELECT remaining_micro FROM agent_task_funding", Long.class));
        assertEquals(1, count("economy_transaction"));
        assertEquals(2, count("economy_entry"));
        assertEquals(0L, jdbc.queryForObject("SELECT SUM(signed_amount_micro) FROM economy_entry", Long.class));
        assertEquals(GROSS, jdbc.queryForObject("SELECT SUM(balance_micro) FROM economy_account", Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM economy_account WHERE balance_micro<0", Integer.class));
    }

    private AgentTaskQuoteDTO quote(String agent, int key) {
        AgentTaskQuoteRequestDTO request = quoteRequest(agent);
        return service.quote(ACTOR, key(key), FundedBountyRequestDigest.quote(taskId, request), taskId, request);
    }

    private static AgentTaskQuoteRequestDTO quoteRequest(String agent) {
        AgentModelPreferenceDTO model = new AgentModelPreferenceDTO();
        model.setProvider(FundedBountyPreviewPriceBook.PROVIDER);
        model.setModel(FundedBountyPreviewPriceBook.MODEL);
        AgentTaskQuoteRequestDTO request = new AgentTaskQuoteRequestDTO();
        request.setAgentId(agent);
        request.setModelPreference(model);
        request.setContextRevision("0");
        request.setMinimumAcceptedPayoutMicro("1");
        return request;
    }

    private static AgentTaskClaimRequestDTO claimRequest(String agent, String quoteId) {
        AgentTaskClaimRequestDTO request = new AgentTaskClaimRequestDTO();
        request.setAgentId(agent);
        request.setQuoteId(quoteId);
        request.setTaskVersion("0");
        request.setAllowQueue(false);
        return request;
    }

    private void seedAgent(long id, String agent) {
        jdbc.update("INSERT INTO agent_persona_binding(id,jiacn,persona_code,agent_id,bound_at,status,tenant_id,client_id) "
                + "VALUES(?,?,?, ?,1,1,?,?)", id, TENANT, "fixture-" + id, agent, TENANT, CLIENT);
        jdbc.update("INSERT INTO agent_identity_registry(id,canonical_agent_id,canonical_type,lifecycle_status,"
                + "client_id,owner_jiacn,tenant_id,binding_id,provisioned_at,activated_at,audit_reason) "
                + "VALUES(?,?,'OPAQUE','ACTIVE',?,?,?, ?,1,1,'W05 isolated fixture')", id, agent, CLIENT, TENANT, TENANT, id);
        jdbc.update("INSERT INTO agent_runtime(agent_id,name,owner_jiacn,binding_id,abilities,status,tenant_id,client_id) "
                + "VALUES(?,'fixture',?,?,'[\"test\"]','online',?,?)", agent, TENANT, id, TENANT, CLIENT);
    }

    private SqlSessionTemplate claimMappers(DataSource source) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        for (Class<?> mapper : List.of(AgentTaskBountyQuoteMapper.class, AgentTaskMemberMapper.class,
                AgentTaskWorkItemMapper.class, AgentIdentityRegistryMapper.class, AgentIdentityAliasMapper.class,
                AgentPersonaBindingMapper.class, AgentRuntimeMapper.class)) configuration.addMapper(mapper);
        configuration.addInterceptor(fault);
        GlobalConfig global = new GlobalConfig();
        global.setIdentifierGenerator(new DefaultIdentifierGenerator());
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        factory.setGlobalConfig(global);
        SqlSessionFactory sessions = factory.getObject();
        if (sessions == null) throw new IllegalStateException("missing claim SQL session factory");
        return new SqlSessionTemplate(sessions);
    }

    private static <T> T wire(T dao, Object mapper) {
        ReflectionTestUtils.setField(dao, "baseMapper", mapper);
        return dao;
    }

    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    private static String key(int id) { return "00000000-0000-0000-0000-" + String.format("%012d", id); }

    @Intercepts(@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}))
    static final class FailAfterClaimReceipt implements Interceptor {
        final AtomicBoolean enabled = new AtomicBoolean();
        final AtomicBoolean fired = new AtomicBoolean();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            if (enabled.get() && ((MappedStatement) invocation.getArgs()[0]).getId().equals(
                    AgentTaskBountyQuoteMapper.class.getName() + ".completeClaimOperation")) {
                fired.set(true);
                throw new IllegalStateException("injected after real claim receipt update");
            }
            return result;
        }
    }
}
