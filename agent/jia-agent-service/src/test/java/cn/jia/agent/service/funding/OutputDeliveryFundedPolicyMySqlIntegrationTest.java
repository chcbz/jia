package cn.jia.agent.service.funding;

import cn.jia.agent.config.OutputDeliveryLeaseSchemaInitializer;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.impl.AgentTaskEventDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskMetaDaoImpl;
import cn.jia.agent.entity.AgentTaskCreateDTO;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.AgentTaskDeliveryRequirementsDTO;
import cn.jia.agent.entity.funding.AgentModelPreferenceDTO;
import cn.jia.agent.entity.funding.AgentTaskClaimRequestDTO;
import cn.jia.agent.entity.funding.AgentTaskFundingCompleteDTO;
import cn.jia.agent.entity.funding.AgentTaskQuoteRequestDTO;
import cn.jia.agent.mapper.AgentTaskBountyQuoteMapper;
import cn.jia.agent.mapper.AgentTaskEventMapper;
import cn.jia.agent.mapper.AgentTaskFundingMapper;
import cn.jia.agent.mapper.AgentTaskMetaMapper;
import cn.jia.agent.mapper.AgentTaskSettlementMapper;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentTaskEventAfterCommitPublisher;
import cn.jia.agent.service.AgentTaskEventBroker;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.impl.AgentCommandTransportCapture;
import cn.jia.agent.service.impl.AgentLegacyTaskCompatibilityService;
import cn.jia.agent.service.impl.AgentTaskEventWriterImpl;
import cn.jia.agent.service.impl.AgentTaskMutationTransactionImpl;
import cn.jia.economy.bounty.FundedBountyCaptureService;
import cn.jia.economy.config.EconomyPreviewGate;
import cn.jia.economy.config.EconomyPreviewProperties;
import cn.jia.economy.mapper.EconomyLedgerMapper;
import cn.jia.economy.service.EconomyScope;
import cn.jia.economy.service.impl.EconomyPostingServiceImpl;
import cn.jia.economy.service.impl.FundedBountyLedgerServiceImpl;
import cn.jia.task.mapper.TaskItemMapper;
import cn.jia.task.mapper.TaskPlanMapper;
import cn.jia.task.service.TaskService;
import cn.jia.task.service.impl.TaskItemDaoImpl;
import cn.jia.task.service.impl.TaskPlanDaoImpl;
import cn.jia.task.service.impl.TaskServiceImpl;
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
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Actual MySQL proof that policy 1 cannot enter the preview funded-bounty lifecycle. */
@EnabledIfEnvironmentVariable(named = "OD01_MYSQL_URL", matches = ".+")
class OutputDeliveryFundedPolicyMySqlIntegrationTest {
    private static final String TENANT = "Tenant-OD07";
    private static final String CLIENT = "Client-OD07";
    private static final String USER = "jwt-sub-od07";
    private static final String AGENT = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final FundedBountyActor ACTOR = new FundedBountyActor(TENANT, CLIENT, USER);

    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String database;
    private PlatformTransactionManager transactionManager;
    private SqlSessionTemplate template;
    private AgentTaskMutationTransaction mutations;
    private FundedBountyServiceImpl funding;

    @BeforeEach
    void setUp() throws Exception {
        String baseUrl = requiredEnvironment("OD01_MYSQL_URL");
        String username = environment("OD01_MYSQL_USER", "root");
        String password = environment("OD01_MYSQL_PASSWORD", "");
        admin = new JdbcTemplate(dataSource(baseUrl, username, password));
        String version = admin.queryForObject("SELECT VERSION()", String.class);
        assertTrue(version != null && version.startsWith("8.0."), version);
        database = "cyf_od07_funded_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE `" + database
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        DataSource source = dataSource(urlForDatabase(baseUrl, database), username, password);
        jdbc = new JdbcTemplate(source);
        transactionManager = new DataSourceTransactionManager(source);
        installSchema(source);
        new OutputDeliveryLeaseSchemaInitializer(jdbc).afterPropertiesSet();
        template = mappers(source);
        funding = fundingService();
        seedWallet();
    }

    @AfterEach
    void tearDown() {
        if (admin != null && database != null) {
            assertTrue(database.matches("cyf_od07_funded_[0-9a-f]{32}"));
            admin.execute("DROP DATABASE IF EXISTS `" + database + "`");
        }
    }

    @Test
    void policy1FundedCreateRejectsBeforeTaskFundingLedgerOrEventWrites() {
        DatabaseState before = state();
        AgentTaskCreateDTO request = fundedRequest("policy1-rejected");
        request.setDeliveryRequirements(deliveryRequirements());

        FundedBountyException failure = assertThrows(FundedBountyException.class, () ->
                funding.create(ACTOR, key(1), FundedBountyRequestDigest.create(request), request));

        assertEquals("FUNDED_TASK_CONFLICT", failure.code());
        assertEquals("Funded delivery-policy tasks are not supported", failure.getMessage());
        assertEquals(before, state());
    }

    @Test
    void policy1QuoteClaimAndSettlementRejectBeforeTaskFundingOrEventMutation() {
        AgentTaskCreateDTO request = fundedRequest("guard-source");
        AgentTaskDTO created = funding.create(ACTOR, key(2),
                FundedBountyRequestDigest.create(request), request);
        jdbc.update("UPDATE agent_task_meta SET delivery_policy_version=1 WHERE task_id=?",
                created.getId());
        DatabaseState before = state();

        AgentIdentityService identities = mock(AgentIdentityService.class);
        AgentRuntimeDao runtimes = mock(AgentRuntimeDao.class);
        AgentLegacyTaskCompatibilityService assignments = mock(AgentLegacyTaskCompatibilityService.class);
        AgentCommandTransportCapture transport = mock(AgentCommandTransportCapture.class);
        FundedBountySkillEntitlementLookup skills = mock(FundedBountySkillEntitlementLookup.class);
        FundedBountyQuoteClaimServiceImpl quotes = new FundedBountyQuoteClaimServiceImpl(
                template.getMapper(AgentTaskBountyQuoteMapper.class),
                template.getMapper(AgentTaskFundingMapper.class), mutations,
                identities, runtimes, assignments, transport, skills, transactionManager);

        AgentTaskQuoteRequestDTO quote = quoteRequest();
        assertPolicyConflict("FUNDED_BOUNTY_CONFLICT", () -> quotes.quote(ACTOR, key(3),
                FundedBountyRequestDigest.quote(created.getId(), quote), created.getId(), quote));
        assertEquals(before, state());

        AgentTaskClaimRequestDTO claim = claimRequest();
        assertPolicyConflict("FUNDED_BOUNTY_CONFLICT", () -> quotes.claim(ACTOR, key(4),
                FundedBountyRequestDigest.claim(created.getId(), claim), created.getId(), claim));
        assertEquals(before, state());
        verifyNoInteractions(identities, runtimes, assignments, transport, skills);

        FundedBountyCaptureService capture = mock(FundedBountyCaptureService.class);
        AgentTaskEventWriter events = mock(AgentTaskEventWriter.class);
        FundedBountySettlementServiceImpl settlements = new FundedBountySettlementServiceImpl(
                template.getMapper(AgentTaskFundingMapper.class),
                template.getMapper(AgentTaskBountyQuoteMapper.class),
                template.getMapper(AgentTaskSettlementMapper.class),
                template.getMapper(AgentTaskMetaMapper.class), mutations, events, capture,
                transactionManager);
        assertPolicyConflict("FUNDED_BOUNTY_CONFLICT", () -> settlements.complete(ACTOR, key(5), created.getId(),
                new AgentTaskFundingCompleteDTO("0", "1")));
        assertEquals(before, state());
        verify(capture).requirePreviewScope(new EconomyScope(TENANT, CLIENT));
        verify(capture, never()).capture(any());
        verifyNoInteractions(events);
    }

    @Test
    void policy0FundedCreateStillCommitsTaskFundingLedgerAndEvent() {
        AgentTaskCreateDTO request = fundedRequest("policy0-positive");

        AgentTaskDTO created = funding.create(ACTOR, key(6),
                FundedBountyRequestDigest.create(request), request);

        assertEquals("FUNDS_HELD", created.getFunding().getStatus());
        assertEquals(600L, wallet());
        assertEquals(1, count("task_plan"));
        assertEquals(1, count("task_item"));
        assertEquals(1, count("agent_task_meta"));
        assertEquals(1, count("agent_task_funding"));
        assertEquals(1, count("agent_task_funding_operation"));
        assertEquals(1, count("economy_transaction"));
        assertEquals(2, count("economy_entry"));
        assertEquals(1, count("economy_escrow"));
        assertEquals(1, count("economy_escrow_funding_lot"));
        assertEquals(1, count("agent_task_event"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT delivery_policy_version FROM agent_task_meta WHERE task_id=?",
                Integer.class, created.getId()));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT task_version FROM agent_task_meta WHERE task_id=?", Long.class, created.getId()));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT current_event_version FROM agent_task_meta WHERE task_id=?", Long.class, created.getId()));
    }

    private FundedBountyServiceImpl fundingService() {
        AgentTaskMetaDaoImpl meta = new AgentTaskMetaDaoImpl();
        ReflectionTestUtils.setField(meta, "baseMapper", template.getMapper(AgentTaskMetaMapper.class));
        mutations = new AgentTaskMutationTransactionImpl(meta, transactionManager);
        AgentTaskEventDaoImpl eventDao = new AgentTaskEventDaoImpl();
        ReflectionTestUtils.setField(eventDao, "baseMapper", template.getMapper(AgentTaskEventMapper.class));
        AgentTaskEventWriter eventWriter = new AgentTaskEventWriterImpl(eventDao, transactionManager,
                new AgentTaskEventAfterCommitPublisher(new AgentTaskEventBroker(), transactionManager));

        TaskPlanDaoImpl plans = new TaskPlanDaoImpl();
        ReflectionTestUtils.setField(plans, "baseMapper", template.getMapper(TaskPlanMapper.class));
        TaskItemDaoImpl items = new TaskItemDaoImpl();
        ReflectionTestUtils.setField(items, "baseMapper", template.getMapper(TaskItemMapper.class));
        TaskServiceImpl taskService = new TaskServiceImpl();
        ReflectionTestUtils.setField(taskService, "baseDao", plans);
        ReflectionTestUtils.setField(taskService, "taskItemDao", items);
        StaticListableBeanFactory tasks = new StaticListableBeanFactory();
        tasks.addBean("taskService", taskService);

        EconomyLedgerMapper economy = template.getMapper(EconomyLedgerMapper.class);
        EconomyPostingServiceImpl posting = new EconomyPostingServiceImpl(economy, transactionManager,
                new EconomyPreviewGate(new EconomyPreviewProperties(true, true,
                        List.of(new EconomyPreviewProperties.AllowedScope(TENANT, CLIENT)))));
        return new FundedBountyServiceImpl(template.getMapper(AgentTaskFundingMapper.class), meta,
                mutations, eventWriter, tasks.getBeanProvider(TaskService.class),
                new FundedBountyLedgerServiceImpl(economy, posting), transactionManager);
    }

    private SqlSessionTemplate mappers(DataSource source) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        for (Class<?> mapper : List.of(AgentTaskMetaMapper.class, AgentTaskFundingMapper.class,
                AgentTaskEventMapper.class, AgentTaskBountyQuoteMapper.class,
                AgentTaskSettlementMapper.class, TaskPlanMapper.class, TaskItemMapper.class,
                EconomyLedgerMapper.class)) {
            configuration.addMapper(mapper);
        }
        GlobalConfig global = new GlobalConfig();
        global.setIdentifierGenerator(new DefaultIdentifierGenerator());
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTransactionFactory(new SpringManagedTransactionFactory());
        factory.setConfiguration(configuration);
        factory.setGlobalConfig(global);
        SqlSessionFactory built = factory.getObject();
        if (built == null) throw new IllegalStateException("missing SqlSessionFactory");
        return new SqlSessionTemplate(built);
    }

    private void installSchema(DataSource source) throws Exception {
        String sql = new ClassPathResource("w04/funded-integration-h2.sql")
                .getContentAsString(StandardCharsets.UTF_8)
                .replace("CLOB", "LONGTEXT")
                .replace("delivery_requirement_json VARCHAR(4000)",
                        "delivery_requirement_json JSON");
        new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)))
                .execute(source);
    }

    private void seedWallet() {
        jdbc.update("""
                INSERT INTO economy_account(account_id,owner_type,owner_id,purpose,currency,
                    balance_micro,allow_negative,status,version,tenant_id,client_id,create_time,update_time)
                VALUES('wallet-fixture','USER',?,'AVAILABLE','SILVER',1000,0,'ACTIVE',0,?,?,1,1)
                """, USER, TENANT, CLIENT);
    }

    private DatabaseState state() {
        return new DatabaseState(wallet(), count("task_plan"), count("task_item"),
                count("agent_task_meta"), count("agent_task_funding"),
                count("agent_task_funding_operation"), count("economy_transaction"),
                count("economy_entry"), count("economy_escrow"),
                count("economy_escrow_funding_lot"), count("agent_task_event"),
                jdbc.queryForObject("SELECT COALESCE(SUM(task_version),0) FROM agent_task_meta", Long.class),
                jdbc.queryForObject("SELECT COALESCE(SUM(current_event_version),0) FROM agent_task_meta", Long.class),
                jdbc.queryForObject("SELECT COALESCE(SUM(version),0) FROM agent_task_funding", Long.class));
    }

    private long wallet() {
        return jdbc.queryForObject("""
                SELECT balance_micro FROM economy_account
                WHERE owner_type='USER' AND owner_id=? AND tenant_id=? AND client_id=?
                """, Long.class, USER, TENANT, CLIENT);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static void assertPolicyConflict(String expectedCode, ThrowingOperation operation) {
        FundedBountyException failure = assertThrows(FundedBountyException.class, operation::run);
        assertEquals(expectedCode, failure.code());
        assertEquals("Funded delivery-policy tasks are not supported", failure.getMessage());
    }

    private static AgentTaskCreateDTO fundedRequest(String title) {
        AgentTaskCreateDTO request = new AgentTaskCreateDTO();
        request.setTitle(title);
        request.setDescription("actual MySQL policy guard fixture");
        request.setRequiredAbilities(List.of("test"));
        request.setReward(1);
        request.setGrossBountyAmountMicro("400");
        request.setSettlementPolicy("GROSS_INCLUSIVE");
        request.setRequiredSkillRequirements(List.of());
        return request;
    }

    private static AgentTaskDeliveryRequirementsDTO deliveryRequirements() {
        AgentTaskDeliveryRequirementsDTO delivery = new AgentTaskDeliveryRequirementsDTO();
        delivery.setMode("files");
        delivery.setMinFiles(1);
        delivery.setRequiredNames(List.of("result.md"));
        delivery.setInstructions("return the requested result");
        delivery.setMaxReviewRevisions(1);
        return delivery;
    }

    private static AgentTaskQuoteRequestDTO quoteRequest() {
        AgentModelPreferenceDTO model = new AgentModelPreferenceDTO();
        model.setProvider(FundedBountyPreviewPriceBook.PROVIDER);
        model.setModel(FundedBountyPreviewPriceBook.MODEL);
        AgentTaskQuoteRequestDTO request = new AgentTaskQuoteRequestDTO();
        request.setAgentId(AGENT);
        request.setModelPreference(model);
        request.setContextRevision("0");
        request.setMinimumAcceptedPayoutMicro("1");
        return request;
    }

    private static AgentTaskClaimRequestDTO claimRequest() {
        AgentTaskClaimRequestDTO request = new AgentTaskClaimRequestDTO();
        request.setAgentId(AGENT);
        request.setQuoteId("q_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        request.setTaskVersion("0");
        request.setAllowQueue(false);
        return request;
    }

    private static DriverManagerDataSource dataSource(String url, String username, String password) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(url);
        source.setUsername(username);
        source.setPassword(password);
        return source;
    }

    private static String urlForDatabase(String baseUrl, String database) {
        int query = baseUrl.indexOf('?');
        String parameters = query < 0 ? "" : baseUrl.substring(query);
        String withoutQuery = query < 0 ? baseUrl : baseUrl.substring(0, query);
        int slash = withoutQuery.indexOf('/', "jdbc:mysql://".length());
        String server = slash < 0 ? withoutQuery : withoutQuery.substring(0, slash);
        return server + "/" + database + parameters;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }

    private static String key(int value) {
        return "00000000-0000-0000-0000-" + String.format("%012d", value);
    }

    private record DatabaseState(long wallet, int taskPlans, int taskItems, int taskRoots,
            int fundingRows, int fundingOperations, int economyTransactions, int economyEntries,
            int escrows, int fundingLots, int taskEvents, long taskVersions,
            long eventVersions, long fundingVersions) { }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run();
    }
}
