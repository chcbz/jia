package cn.jia.agent.service.funding;

import cn.jia.agent.dao.impl.AgentTaskMetaDaoImpl;
import cn.jia.agent.mapper.*;
import cn.jia.agent.service.impl.AgentTaskMutationTransactionImpl;
import cn.jia.economy.common.*;
import cn.jia.economy.config.*;
import cn.jia.economy.exception.EconomyPostingException;
import cn.jia.economy.mapper.EconomyLedgerMapper;
import cn.jia.economy.service.*;
import cn.jia.economy.service.impl.EconomyPostingServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.*;
import org.springframework.core.io.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Fixed historical rows, real scoped SQL/root transactions; no retired create/claim fixture. */
class FundedBountyHistoryReadTest {
    private static final FundedBountyActor ACTOR = new FundedBountyActor("0", "Client-W04", "Owner-W04", "jwt-sub-w04");
    private static final String AGENT = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final long GROSS = 1000L;
    private final String taskId = "history-task";
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private PlatformTransactionManager tm;
    private FundedBountyServiceImpl funding;
    private FundedBountySettlementServiceImpl settlement;

    @BeforeEach void setup() throws Exception {
        context = new AnnotationConfigApplicationContext(Configuration.class);
        DataSource source = context.getBean(DataSource.class);
        jdbc = new JdbcTemplate(source); tm = context.getBean(PlatformTransactionManager.class);
        new ResourceDatabasePopulator(new ClassPathResource("w04/funded-integration-h2.sql")).execute(source);
        String ddl = new ClassPathResource("db/agent-task-bounty-settlement-v0.sql")
                .getContentAsString(StandardCharsets.UTF_8)
                .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin", "");
        new ResourceDatabasePopulator(new ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8))).execute(source);
        SqlSessionTemplate sql = context.getBean(SqlSessionTemplate.class);
        AgentTaskMetaDaoImpl roots = new AgentTaskMetaDaoImpl();
        ReflectionTestUtils.setField(roots, "baseMapper", sql.getMapper(AgentTaskMetaMapper.class));
        funding = new FundedBountyServiceImpl(sql.getMapper(AgentTaskFundingMapper.class));
        settlement = new FundedBountySettlementServiceImpl(sql.getMapper(AgentTaskFundingMapper.class),
                sql.getMapper(AgentTaskSettlementMapper.class), new AgentTaskMutationTransactionImpl(roots, tm));
        jdbc.update("""
            INSERT INTO agent_task_meta(task_id,owner_jiacn,reward_status,collaboration_mode,risk_level,
                max_agents,review_required,task_version,current_event_version,tenant_id,client_id,create_time,update_time)
            VALUES(?,?,'assigned','single','low',1,false,1,0,?,?,1,1)
            """, taskId, ACTOR.ownerJiacn(), ACTOR.tenantId(), ACTOR.clientId());
        jdbc.update("""
            INSERT INTO agent_task_funding(task_id,funding_mode,funding_status,payer_principal_type,payer_principal_id,
                settlement_policy,gross_bounty_amount_wen,remaining_wen,escrow_id,escrow_version,reserve_transaction_id,
                required_skill_requirements,version,tenant_id,client_id,owner_jiacn,create_time,update_time)
            VALUES(?,'FUNDED_SINGLE_AGENT','FUNDS_HELD','USER',?,'GROSS_INCLUSIVE',?,?,'history-escrow',1,'history-reserve','[]',1,?,?,?,1,1)
            """, taskId, ACTOR.userId(), GROSS, GROSS, ACTOR.tenantId(), ACTOR.clientId(), ACTOR.ownerJiacn());
        // Fixed historical escrow plus an empty payer wallet. Reads must not mutate either.
        jdbc.update("""
            INSERT INTO economy_account(account_id,owner_type,owner_id,purpose,currency,balance_wen,allow_negative,status,
                version,tenant_id,client_id,create_time,update_time)
            VALUES('history-escrow','TASK',?,'ESCROW','WEN',?,0,'ACTIVE',0,?,?,1,1),
                  ('history-wallet','USER',?,'AVAILABLE','WEN',0,0,'ACTIVE',0,?,?,1,1)
            """, taskId, GROSS, ACTOR.tenantId(), ACTOR.clientId(), ACTOR.userId(), ACTOR.tenantId(), ACTOR.clientId());
    }

    @AfterEach void close() {
        if (context != null) { try { if (jdbc != null) jdbc.execute("DROP ALL OBJECTS"); } finally { context.close(); } }
    }

    @Test void heldHistoryRemainsReadableWithoutPreviewOrCaptureServiceAndWritesNothing() {
        assertNull(context.getEnvironment().getProperty("economy.preview.enabled"));
        assertFalse(FundedBountyServiceImpl.class.isAnnotationPresent(org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class));
        assertFalse(FundedBountySettlementServiceImpl.class.isAnnotationPresent(org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class));
        var before = snapshot();
        for (int i = 0; i < 2; i++) {
            var result = settlement.read(ACTOR, taskId);
            assertEquals("FUNDS_HELD", result.status()); assertNull(result.settlement()); assertNull(result.cancellation());
            assertEquals(Long.toString(GROSS), result.remainingWen());
            assertEquals("FUNDS_HELD", funding.findFunding("0", ACTOR.clientId(), taskId).getStatus());
            assertEquals(List.of(), funding.requiredSkills("0", ACTOR.clientId(), taskId));
        }
        assertEquals(before, snapshot());
    }

    @Test void settledHistoryPreservesOriginalAmountsAndReceiptWithoutRepricing() {
        settledFixture(); var before = snapshot();
        var result = settlement.read(ACTOR, taskId);
        assertEquals("SETTLED", result.status()); assertEquals("0", result.remainingWen());
        assertEquals("3", result.settlement().actualComputeWen());
        assertEquals("2", result.settlement().platformFeeWen());
        assertEquals("995", result.settlement().agentPayoutWen());
        assertEquals(List.of("old-compute", "old-fee", "old-payout"), result.settlement().transactionIds());
        assertEquals(before, snapshot());
    }

    @Test void refundedHistoryPreservesOriginalRefundWithoutASecondPosting() {
        jdbc.update("UPDATE agent_task_funding SET funding_status='REFUNDED',remaining_wen=0,refund_transaction_id='old-refund',cancel_refunded_wen=?,cancel_task_version=2,version=2,escrow_version=2,refunded_at=10", GROSS);
        var before = snapshot(); var result = settlement.read(ACTOR, taskId);
        assertEquals("REFUNDED", result.status()); assertNull(result.settlement());
        assertEquals(Long.toString(GROSS), result.cancellation().refundedWen());
        assertEquals("old-refund", result.cancellation().refundTransactionId());
        assertEquals(before, snapshot());
    }

    @Test void wrongOwnerPayerClientAndMissingTaskCannotReadFinancialHistory() {
        var before = snapshot();
        for (var actor : List.of(new FundedBountyActor("0", ACTOR.clientId(), "other-owner", ACTOR.userId()),
                new FundedBountyActor("0", ACTOR.clientId(), ACTOR.ownerJiacn(), "other-payer"),
                new FundedBountyActor("0", "other-client", ACTOR.ownerJiacn(), ACTOR.userId()))) {
            assertEquals(HttpStatus.NOT_FOUND, assertThrows(FundedBountyException.class, () -> settlement.read(actor, taskId)).status());
        }
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(FundedBountyException.class, () -> settlement.read(ACTOR, "missing")).status());
        assertEquals(before, snapshot());
    }

    @Test void inconsistentHistoricalReceiptIsNotRepairedOrCharged() {
        settledFixture(); jdbc.update("UPDATE agent_task_bounty_settlement SET escrow_version=99");
        var before = snapshot();
        assertEquals("FUNDED_BOUNTY_UNAVAILABLE", assertThrows(FundedBountyException.class, () -> settlement.read(ACTOR, taskId)).code());
        assertEquals(before, snapshot());
    }

    @Test void corruptHistoricalSkillRequirementsAreNotSilentlyConvertedToEmpty() {
        jdbc.update("UPDATE agent_task_funding SET required_skill_requirements='not-json'");
        assertThrows(FundedBountyException.class, () -> funding.requiredSkills("0", ACTOR.clientId(), taskId));
        assertEquals(0, count("economy_transaction"));
    }

    @Test
    void sharedSkillCaptureAndRefundConserveReplayAndRollBackUsingRealPosting() {
        EconomyPostingService posting = context.getBean(EconomyPostingService.class);
        EconomyScope scope = new EconomyScope(ACTOR.tenantId(), ACTOR.clientId());
        EconomyPrincipal actor = new EconomyPrincipal(EconomyPrincipalType.USER, ACTOR.userId());
        EconomyAccountKey payer = new EconomyAccountKey("WEN", EconomyAccountOwnerType.USER, ACTOR.userId(), EconomyAccountPurpose.AVAILABLE);
        EconomyAccountKey store = new EconomyAccountKey("WEN", EconomyAccountOwnerType.SYSTEM, "SKILL_STORE", EconomyAccountPurpose.SKILL_STORE);
        jdbc.update("""
                INSERT INTO economy_account(account_id,owner_type,owner_id,purpose,currency,balance_wen,
                  allow_negative,status,version,tenant_id,client_id,create_time,update_time)
                VALUES('skill-store-fixture','SYSTEM','SKILL_STORE','SKILL_STORE','WEN',0,0,'ACTIVE',0,?,?,1,1)
                """, ACTOR.tenantId(), ACTOR.clientId());
        for (int index = 0; index < 2; index++) {
            boolean capture = index == 0;
            String order = "skill-order-" + index;
            // Test account/funds fixture only; actual reserve and settlement are production posting.
            jdbc.update("UPDATE economy_account SET balance_wen=10 WHERE owner_type='USER' AND purpose='AVAILABLE'");
            jdbc.update("""
                    INSERT INTO economy_account(account_id,owner_type,owner_id,purpose,currency,balance_wen,
                      allow_negative,status,version,tenant_id,client_id,create_time,update_time)
                    VALUES(?,'ORDER',?,'ESCROW','WEN',0,0,'ACTIVE',0,?,?,1,1)
                    """, order + "-escrow", order, ACTOR.tenantId(), ACTOR.clientId());
            EconomyAccountKey escrow = new EconomyAccountKey("WEN", EconomyAccountOwnerType.ORDER, order, EconomyAccountPurpose.ESCROW);
            EconomyPostingResult reserve = posting.post(new EconomyPostingCommand(scope, actor, key(10 + index), new byte[32],
                    EconomyJournalType.RESERVE_SKILL, order,
                    List.of(new EconomyPostingLine(payer, -10), new EconomyPostingLine(escrow, 10)),
                    new EconomyEscrowFunding(EconomyEscrowType.SKILL_ORDER, payer, escrow, 10, null)));
            EconomyAccountKey destination = capture ? store : payer;
            EconomyJournalType type = capture ? EconomyJournalType.CAPTURE_SKILL : EconomyJournalType.REFUND_SKILL;
            EconomyPostingCommand command = new EconomyPostingCommand(scope, actor, key(20 + index), new byte[32], type,
                    order, List.of(new EconomyPostingLine(escrow, -10), new EconomyPostingLine(destination, 10)), null,
                    new EconomyEscrowSettlement(EconomyEscrowType.SKILL_ORDER, escrow, destination, 10, 1, reserve.transactionId()));
            int journals = count("economy_transaction");
            EconomyPostingCommand stale = new EconomyPostingCommand(scope, actor, key(30 + index), new byte[32], type,
                    order, command.lines(), null, new EconomyEscrowSettlement(EconomyEscrowType.SKILL_ORDER,
                    escrow, destination, 10, 2, reserve.transactionId()));
            assertEquals(EconomyPostingException.Reason.ESCROW_CONFLICT,
                    assertThrows(EconomyPostingException.class, () -> posting.post(stale)).reason());
            EconomyPostingCommand wrongReserve = new EconomyPostingCommand(scope, actor, key(30 + index), new byte[32], type,
                    order, command.lines(), null, new EconomyEscrowSettlement(EconomyEscrowType.SKILL_ORDER,
                    escrow, destination, 10, 1, "etx_wrong-reserve"));
            assertEquals(EconomyPostingException.Reason.ESCROW_CONFLICT,
                    assertThrows(EconomyPostingException.class, () -> posting.post(wrongReserve)).reason());
            assertEquals(journals, count("economy_transaction"));
            new TransactionTemplate(tm).executeWithoutResult(status -> {
                posting.post(command);
                assertEquals(capture ? "CAPTURED" : "REFUNDED", jdbc.queryForObject(
                        "SELECT status FROM economy_escrow WHERE business_id=?", String.class, order));
                status.setRollbackOnly();
            });
            assertEquals(journals, count("economy_transaction"));
            assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM economy_escrow WHERE business_id=?", String.class, order));
            assertEquals(1L, jdbc.queryForObject("SELECT version FROM economy_escrow WHERE business_id=?", Long.class, order));
            EconomyPostingResult result = posting.post(command);
            assertEquals(result, posting.post(command));
            byte[] different = new byte[32]; different[0] = 1;
            EconomyPostingCommand conflicting = new EconomyPostingCommand(scope, actor, command.idempotencyKey(), different,
                    type, order, command.lines(), null, command.escrowSettlement());
            assertEquals(EconomyPostingException.Reason.IDEMPOTENCY_CONFLICT,
                    assertThrows(EconomyPostingException.class, () -> posting.post(conflicting)).reason());
            assertEquals(journals + 1, count("economy_transaction"));
            assertEquals(0L, jdbc.queryForObject("SELECT SUM(signed_amount_wen) FROM economy_entry", Long.class));
            assertEquals(GROSS + 10L * (index + 1), jdbc.queryForObject("SELECT SUM(balance_wen) FROM economy_account", Long.class));
            assertEquals(0L, jdbc.queryForObject("SELECT balance_wen FROM economy_account WHERE account_id=?", Long.class, order + "-escrow"));
            assertEquals(2L, jdbc.queryForObject("SELECT version FROM economy_escrow WHERE business_id=?", Long.class, order));
            assertEquals(10L, jdbc.queryForObject("SELECT " + (capture ? "captured_wen" : "refunded_wen")
                    + " FROM economy_escrow WHERE business_id=?", Long.class, order));
        }
        assertEquals(10L, jdbc.queryForObject("SELECT balance_wen FROM economy_account WHERE account_id='skill-store-fixture'", Long.class));
        assertEquals(10L, jdbc.queryForObject("SELECT balance_wen FROM economy_account WHERE owner_type='USER' AND purpose='AVAILABLE'", Long.class));
    }


    @Test
    void sharedCaptureTemplatesStillRejectWrongCounterpartyAndImbalancedLinesBeforeAnyJournal() {
        var before = snapshot();
        EconomyPostingService posting = context.getBean(EconomyPostingService.class);
        EconomyAccountKey escrow = new EconomyAccountKey("WEN", EconomyAccountOwnerType.TASK, taskId, EconomyAccountPurpose.ESCROW);
        EconomyAccountKey wrong = new EconomyAccountKey("WEN", EconomyAccountOwnerType.USER, ACTOR.userId(), EconomyAccountPurpose.AVAILABLE);
        String reserve = jdbc.queryForObject("SELECT reserve_transaction_id FROM agent_task_funding", String.class);
        for (EconomyJournalType type : List.of(EconomyJournalType.CAPTURE_COMPUTE, EconomyJournalType.CAPTURE_FEE, EconomyJournalType.PAY_AGENT)) {
            EconomyPostingException failure = assertThrows(EconomyPostingException.class, () -> posting.post(
                    new EconomyPostingCommand(new EconomyScope(ACTOR.tenantId(), ACTOR.clientId()),
                            new EconomyPrincipal(EconomyPrincipalType.USER, ACTOR.userId()), key(9), new byte[32], type, taskId,
                            List.of(new EconomyPostingLine(escrow, -1), new EconomyPostingLine(wrong, 1)), null,
                            new EconomyEscrowSettlement(EconomyEscrowType.BOUNTY, escrow, wrong, 1, 1, reserve))));
            assertEquals(EconomyPostingException.Reason.INVALID_COMMAND, failure.reason());
        }
        EconomyAccountKey model = new EconomyAccountKey("WEN", EconomyAccountOwnerType.SYSTEM, "MODEL_COST", EconomyAccountPurpose.MODEL_COST);
        EconomyPostingException imbalance = assertThrows(EconomyPostingException.class, () -> posting.post(
                new EconomyPostingCommand(new EconomyScope(ACTOR.tenantId(), ACTOR.clientId()),
                        new EconomyPrincipal(EconomyPrincipalType.USER, ACTOR.userId()), key(9), new byte[32],
                        EconomyJournalType.CAPTURE_COMPUTE, taskId,
                        List.of(new EconomyPostingLine(escrow, -2), new EconomyPostingLine(model, 1)), null,
                        new EconomyEscrowSettlement(EconomyEscrowType.BOUNTY, escrow, model, 1, 1, reserve))));
        assertEquals(EconomyPostingException.Reason.IMBALANCED_TRANSACTION, imbalance.reason());
        assertEquals(before, snapshot());
    }


    private void settledFixture() {
        jdbc.update("UPDATE agent_task_funding SET funding_status='SETTLED',remaining_wen=0,version=2,escrow_version=4");
        jdbc.update("""
            INSERT INTO agent_task_bounty_settlement(tenant_id,client_id,principal_type,principal_id,idempotency_key,
                request_hash,task_id,quote_id,agent_id,escrow_id,status,gross_wen,actual_compute_wen,platform_fee_wen,
                agent_payout_wen,refunded_wen,task_version,funding_version,escrow_version,settled_at,transaction_ids)
            VALUES(?,?,'USER',?,?,?,?,'old-quote',?,'history-escrow','SETTLED',1000,3,2,995,0,2,2,4,10,?)
            """, ACTOR.tenantId(), ACTOR.clientId(), ACTOR.userId(), key(1).getBytes(StandardCharsets.US_ASCII),
                new byte[32], taskId, AGENT, "[\"old-compute\",\"old-fee\",\"old-payout\"]");
    }

    private Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String table : List.of("agent_task_meta", "agent_task_funding", "agent_task_bounty_settlement", "economy_account", "economy_transaction", "economy_entry")) {
            result.put(table, jdbc.query("SELECT * FROM " + table + " ORDER BY id", (rs, row) -> {
                List<String> values = new ArrayList<>();
                for (int column = 1; column <= rs.getMetaData().getColumnCount(); column++) values.add(rs.getString(column));
                return values;
            }));
        }
        return result;
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private static String key(int n) { return String.format("00000000-0000-0000-0000-%012d", n); }

    @org.springframework.context.annotation.Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Configuration {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:funded_history_" + UUID.randomUUID()
                    + ";MODE=MYSQL;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE", "sa", "");
        }
        @Bean PlatformTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean SqlSessionTemplate sql(DataSource source) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
            for (Class<?> mapper : List.of(AgentTaskMetaMapper.class, AgentTaskFundingMapper.class, AgentTaskSettlementMapper.class, EconomyLedgerMapper.class)) configuration.addMapper(mapper);
            GlobalConfig global = new GlobalConfig(); global.setIdentifierGenerator(new DefaultIdentifierGenerator());
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source);
            factory.setConfiguration(configuration); factory.setGlobalConfig(global);
            return new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        }
        @Bean EconomyPostingServiceImpl posting(SqlSessionTemplate sql, PlatformTransactionManager tm) {
            return new EconomyPostingServiceImpl(sql.getMapper(EconomyLedgerMapper.class), tm,
                    new EconomyPreviewGate(new EconomyPreviewProperties(true, true,
                        List.of(new EconomyPreviewProperties.AllowedScope(ACTOR.tenantId(), ACTOR.clientId())))));
        }
    }
}
