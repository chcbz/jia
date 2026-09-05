package cn.jia.agent.service.funding;

import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.dao.impl.AgentTaskMetaDaoImpl;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.mapper.AgentTaskFundingMapper;
import cn.jia.agent.mapper.AgentTaskMetaMapper;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentTaskAggregationService;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.impl.AgentLegacyTaskCompatibilityService;
import cn.jia.agent.service.impl.AgentTaskMutationTransactionImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Real task-root/funding-row lock regression for the preview-off legacy bypass race. */
class FundedBountyLegacyGuardRaceRealTransactionTest {
    private static final String TENANT = "Tenant-A";
    private static final String CLIENT = "Client-A";
    private static final String TASK = "task-funded-race";
    private static final String AGENT = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private AgentTaskFundingMapper fundingMapper;
    private PersistedFundedBountyLegacyGuard guard;
    private AgentIdentityService identityService;
    private AgentTaskMemberDao memberDao;
    private AgentTaskWorkItemDao workItemDao;
    private AgentTaskEventWriter eventWriter;
    private AgentLegacyTaskCompatibilityService compatibility;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:w04_guard_race;MODE=MYSQL;DB_CLOSE_DELAY=-1;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;LOCK_TIMEOUT=10000");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
        createRootAndLegacyTables();

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentTaskMetaMapper.class);
        configuration.addMapper(AgentTaskFundingMapper.class);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(dataSource);
        bean.setConfiguration(configuration);
        bean.setGlobalConfig(globalConfig);
        SqlSessionFactory factory = bean.getObject();
        if (factory == null) throw new IllegalStateException("missing SqlSessionFactory");
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        fundingMapper = template.getMapper(AgentTaskFundingMapper.class);
        AgentTaskMetaDaoImpl metaDao = new AgentTaskMetaDaoImpl();
        Field baseMapper = cn.jia.common.dao.BaseDaoImpl.class.getDeclaredField("baseMapper");
        baseMapper.setAccessible(true);
        baseMapper.set(metaDao, template.getMapper(AgentTaskMetaMapper.class));

        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        AgentTaskMutationTransaction mutation = new AgentTaskMutationTransactionImpl(metaDao, transactionManager);
        guard = new PersistedFundedBountyLegacyGuard(fundingMapper, dataSource);
        identityService = mock(AgentIdentityService.class);
        memberDao = mock(AgentTaskMemberDao.class);
        workItemDao = mock(AgentTaskWorkItemDao.class);
        eventWriter = mock(AgentTaskEventWriter.class);
        compatibility = new AgentLegacyTaskCompatibilityService(metaDao, memberDao, workItemDao,
                mock(AgentTaskAggregationService.class), identityService, mutation, eventWriter);
        Field guardField = AgentLegacyTaskCompatibilityService.class
                .getDeclaredField("fundedBountyLegacyGuard");
        guardField.setAccessible(true);
        guardField.set(compatibility, guard);
        insertRoot();
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    void noFundingTableLeavesUnfundedLegacyInstallationUntouched() {
        jdbc.execute("DROP TABLE IF EXISTS agent_task_funding");

        guard.requireAssignmentAllowed(TENANT, CLIENT, TASK, false, 1, false);
        guard.requireAssignmentAllowed(TENANT, CLIENT, TASK, true, 1, false);
        guard.requireLifecycleAllowed(TENANT, CLIENT, TASK, false);
    }

    @Test
    void previewServiceAbsentStillBlocksManualAutoAndLifecycleForPersistedFunding() {
        createFundingTable();
        insertHeldFunding();

        assertEquals("QUOTE_REQUIRED", assertThrows(FundedBountyException.class,
                () -> guard.requireAssignmentAllowed(TENANT, CLIENT, TASK,
                        false, 1, false)).code());
        assertEquals("FUNDED_TEAM_NOT_SUPPORTED", assertThrows(FundedBountyException.class,
                () -> guard.requireAssignmentAllowed(TENANT, CLIENT, TASK,
                        true, 1, false)).code());
        assertEquals("QUOTE_REQUIRED", assertThrows(FundedBountyException.class,
                () -> guard.requireLifecycleAllowed(TENANT, CLIENT, TASK, false)).code());
    }

    @Test
    void fundingCommittedAfterUnlockedPrecheckIsRejectedUnderRootLockBeforeIdentityOrMutation() {
        createFundingTable();
        guard.requireAssignmentAllowed(TENANT, CLIENT, TASK, false, 1, false);
        insertHeldFunding(); // Represents funded create committing before legacy root acquisition.

        FundedBountyException failure = assertThrows(FundedBountyException.class, () ->
                compatibility.assignResolved(TENANT, CLIENT, TASK, List.of(AGENT), false,
                        new AgentLegacyTaskCompatibilityService.AssignmentPrecommitValidator() {
                            @Override
                            public void beforeIdentityLock(
                                    AgentTaskMetaEntity lockedTask, List<String> canonicalAgentIds) {
                                guard.requireAssignmentAllowed(TENANT, CLIENT, lockedTask.getTaskId(),
                                        false, canonicalAgentIds.size(), true);
                            }

                            @Override
                            public void validate(
                                    AgentTaskMetaEntity lockedTask, List<String> canonicalAgentIds) {
                                throw new AssertionError("must not pass locked funded check");
                            }
                        }));

        assertEquals("QUOTE_REQUIRED", failure.code());
        verifyNoInteractions(identityService, memberDao, workItemDao, eventWriter);
        assertEquals(0, count("agent_task_member"));
        assertEquals(0, count("agent_task_work_item"));
        assertEquals("open", jdbc.queryForObject(
                "SELECT reward_status FROM agent_task_meta WHERE task_id=?", String.class, TASK));
    }

    @Test
    void reportLockedHookRejectsCommittedFundingBeforeCanonicalIdentityLock() {
        createFundingTable();
        insertHeldFunding();

        FundedBountyException failure = assertThrows(FundedBountyException.class, () ->
                compatibility.reportResolved(TENANT, CLIENT, TASK, AGENT, "running", null));

        assertEquals("QUOTE_REQUIRED", failure.code());
        verifyNoInteractions(identityService, memberDao, workItemDao, eventWriter);
    }

    private void createRootAndLegacyTables() {
        jdbc.execute("""
                CREATE TABLE agent_task_meta(
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, task_id VARCHAR(100) NOT NULL,
                    reward_status VARCHAR(32) NOT NULL, required_abilities VARCHAR(2000), reward INT,
                    assigned_agent_id VARCHAR(100), collaboration_mode VARCHAR(16) NOT NULL,
                    risk_level VARCHAR(16) NOT NULL, max_agents INT NOT NULL, review_required BOOLEAN NOT NULL,
                    coordinator_agent_id VARCHAR(100), assigned_at BIGINT, started_at BIGINT,
                    completed_at BIGINT, failure_reason VARCHAR(500), archived_at BIGINT,
                    task_version BIGINT NOT NULL, current_event_version BIGINT NOT NULL,
                    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT NOT NULL, update_time BIGINT NOT NULL,
                    UNIQUE(tenant_id,client_id,task_id))
                """);
        jdbc.execute("CREATE TABLE agent_task_member(id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "task_id VARCHAR(100), agent_id VARCHAR(100), tenant_id VARCHAR(50), client_id VARCHAR(50))");
        jdbc.execute("CREATE TABLE agent_task_work_item(id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "work_item_id VARCHAR(100), task_id VARCHAR(100), tenant_id VARCHAR(50), client_id VARCHAR(50))");
    }

    private void createFundingTable() {
        jdbc.execute("""
                CREATE TABLE agent_task_funding(
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, task_id VARCHAR(100) NOT NULL,
                    funding_mode VARCHAR(32) NOT NULL, funding_status VARCHAR(24) NOT NULL,
                    payer_principal_type VARCHAR(20) NOT NULL, payer_principal_id VARCHAR(100) NOT NULL,
                    settlement_policy VARCHAR(32) NOT NULL, gross_bounty_amount_micro BIGINT NOT NULL,
                    remaining_micro BIGINT NOT NULL, escrow_id VARCHAR(100), escrow_version BIGINT,
                    reserve_transaction_id VARCHAR(100), required_skill_requirements CLOB NOT NULL,
                    cancel_idempotency_key VARBINARY(36), cancel_request_hash BINARY(32),
                    refund_transaction_id VARCHAR(100), cancel_refunded_micro BIGINT,
                    cancel_task_version BIGINT, refunded_at BIGINT, version BIGINT NOT NULL,
                    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT NOT NULL, update_time BIGINT NOT NULL,
                    UNIQUE(tenant_id,client_id,task_id))
                """);
    }

    private void insertRoot() {
        jdbc.update("""
                INSERT INTO agent_task_meta(task_id,reward_status,required_abilities,reward,
                    collaboration_mode,risk_level,max_agents,review_required,task_version,
                    current_event_version,tenant_id,client_id,create_time,update_time)
                VALUES(?,'open','[]',1,'single','low',1,FALSE,0,0,?,?,1,1)
                """, TASK, TENANT, CLIENT);
    }

    private void insertHeldFunding() {
        jdbc.update("""
                INSERT INTO agent_task_funding(task_id,funding_mode,funding_status,
                    payer_principal_type,payer_principal_id,settlement_policy,
                    gross_bounty_amount_micro,remaining_micro,escrow_id,escrow_version,
                    reserve_transaction_id,required_skill_requirements,version,
                    tenant_id,client_id,create_time,update_time)
                VALUES(?,'FUNDED_SINGLE_AGENT','FUNDS_HELD','USER','jwt-sub',
                    'GROSS_INCLUSIVE',100,100,'esc-1',1,'etx-reserve','[]',1,?,?,1,1)
                """, TASK, TENANT, CLIENT);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
