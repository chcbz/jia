package cn.jia.economy.service.impl;

import cn.jia.economy.bounty.FundedBountyLedgerService;
import cn.jia.economy.bounty.FundedBountyRefundCommand;
import cn.jia.economy.bounty.FundedBountyRefundReceipt;
import cn.jia.economy.bounty.FundedBountyReserveCommand;
import cn.jia.economy.bounty.FundedBountyReserveReceipt;
import cn.jia.economy.common.EconomyPrincipalType;
import cn.jia.economy.config.EconomyPreviewGate;
import cn.jia.economy.config.EconomyPreviewProperties;
import cn.jia.economy.mapper.EconomyLedgerMapper;
import cn.jia.economy.service.EconomyPostingCommand;
import cn.jia.economy.service.EconomyPostingResult;
import cn.jia.economy.service.EconomyPostingService;
import cn.jia.economy.service.EconomyPrincipal;
import cn.jia.economy.service.EconomyScope;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Enabled Spring CGLIB + H2 proof for reserve/refund rollback and conservation. */
class FundedBountyLedgerServiceSpringTransactionTest {
    private static final String TENANT = "Tenant-W04";
    private static final String CLIENT = "Client-W04";
    private static final String USER = "jwt-sub-w04";
    private static final long INITIAL = 1_000L;

    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private FundedBountyLedgerService service;
    private ObservingPostingService observing;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(TestConfiguration.class);
        jdbc = context.getBean(JdbcTemplate.class);
        jdbc.execute("DROP ALL OBJECTS");
        createTables();
        service = context.getBean(FundedBountyLedgerService.class);
        observing = context.getBean(ObservingPostingService.class);
        jdbc.update("""
                INSERT INTO economy_account(account_id,owner_type,owner_id,purpose,currency,
                    balance_micro,allow_negative,status,version,tenant_id,client_id,create_time,update_time)
                VALUES('wallet-fixture','USER',?,'AVAILABLE','SILVER',?,0,'ACTIVE',0,?,?,1,1)
                """, USER, INITIAL, TENANT, CLIENT);
    }

    @AfterEach
    void tearDown() {
        if (context != null) context.close();
    }

    @Test
    void enabledSpringCreatesClassProxyAndReserveRefundConserveEveryJournal() {
        assertTrue(AopUtils.isCglibProxy(service), service.getClass().getName());
        FundedBountyReserveReceipt reserve = service.reserve(reserve("task-conserve", key(1), hash(1), 400));
        FundedBountyRefundReceipt refund = service.refund(refund(
                "task-conserve", key(2), hash(2), 400, reserve));

        assertTrue(observing.observedActiveTransaction.get());
        assertEquals(INITIAL, balance("USER", USER, "AVAILABLE"));
        assertEquals(0L, balance("TASK", "task-conserve", "ESCROW"));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT SUM(signed_amount_micro) FROM economy_entry", Long.class));
        assertEquals(2, count("economy_transaction"));
        assertEquals(4, count("economy_entry"));
        assertEquals(400L, jdbc.queryForObject(
                "SELECT refunded_micro FROM economy_escrow", Long.class));
        assertEquals(2L, refund.escrowVersion());
    }

    @Test
    void failureAfterRealPostingRollsBackProvisionedEscrowAccountsJournalAndEscrow() {
        observing.failAfterPost.set(true);

        assertThrows(IllegalStateException.class,
                () -> service.reserve(reserve("task-late-failure", key(3), hash(3), 300)));

        assertTrue(observing.observedActiveTransaction.get());
        assertEquals(INITIAL, balance("USER", USER, "AVAILABLE"));
        assertEquals(1, count("economy_account"));
        assertEquals(0, count("economy_transaction"));
        assertEquals(0, count("economy_entry"));
        assertEquals(0, count("economy_escrow"));
        assertEquals(0, count("economy_escrow_funding_lot"));
    }

    @Test
    void insufficientBalanceLeavesNoPartialAccountJournalOrEscrow() {
        assertThrows(RuntimeException.class,
                () -> service.reserve(reserve("task-insufficient", key(4), hash(4), INITIAL + 1)));

        assertEquals(INITIAL, balance("USER", USER, "AVAILABLE"));
        assertEquals(1, count("economy_account"));
        assertEquals(0, count("economy_transaction"));
        assertEquals(0, count("economy_entry"));
        assertEquals(0, count("economy_escrow"));
        assertEquals(0, count("economy_escrow_funding_lot"));
    }

    @Test
    void parallelSameKeyReturnsOneReceiptAndChangedBodyConflictsWithoutExtraJournal() throws Exception {
        FundedBountyReserveCommand command = reserve("task-parallel", key(5), hash(5), 250);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<FundedBountyReserveReceipt> first = executor.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return service.reserve(command);
            });
            Future<FundedBountyReserveReceipt> second = executor.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return service.reserve(command);
            });
            start.countDown();
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertThrows(RuntimeException.class, () -> service.reserve(
                reserve("task-parallel", key(5), hash(6), 251)));
        assertEquals(1, count("economy_transaction"));
        assertEquals(2, count("economy_entry"));
        assertEquals(INITIAL - 250, balance("USER", USER, "AVAILABLE"));
        assertEquals(250L, balance("TASK", "task-parallel", "ESCROW"));
    }

    private FundedBountyReserveCommand reserve(
            String taskId, String key, byte[] hash, long amount) {
        return new FundedBountyReserveCommand(scope(), principal(), key, hash, taskId, amount);
    }

    private FundedBountyRefundCommand refund(String taskId, String key, byte[] hash,
            long amount, FundedBountyReserveReceipt reserve) {
        return new FundedBountyRefundCommand(scope(), principal(), key, hash, taskId,
                amount, reserve.escrowVersion(), reserve.transactionId());
    }

    private EconomyScope scope() {
        return new EconomyScope(TENANT, CLIENT);
    }

    private EconomyPrincipal principal() {
        return new EconomyPrincipal(EconomyPrincipalType.USER, USER);
    }

    private long balance(String ownerType, String ownerId, String purpose) {
        return jdbc.queryForObject("""
                SELECT balance_micro FROM economy_account
                WHERE tenant_id=? AND client_id=? AND owner_type=? AND owner_id=? AND purpose=?
                """, Long.class, TENANT, CLIENT, ownerType, ownerId, purpose);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static String key(int value) {
        return "00000000-0000-0000-0000-" + String.format("%012d", value);
    }

    private static byte[] hash(int marker) {
        byte[] hash = new byte[32];
        Arrays.fill(hash, (byte) marker);
        return hash;
    }

    private void createTables() {
        jdbc.execute("""
                CREATE TABLE economy_account (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, account_id VARCHAR(100) NOT NULL,
                    owner_type VARCHAR(20) NOT NULL, owner_id VARCHAR(100) NOT NULL,
                    purpose VARCHAR(32) NOT NULL, currency VARCHAR(16) NOT NULL,
                    balance_micro BIGINT NOT NULL DEFAULT 0, allow_negative TINYINT NOT NULL DEFAULT 0,
                    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', version BIGINT NOT NULL DEFAULT 0,
                    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT NOT NULL, update_time BIGINT NOT NULL,
                    UNIQUE(tenant_id,client_id,account_id),
                    UNIQUE(tenant_id,client_id,currency,owner_type,owner_id,purpose))
                """);
        jdbc.execute("""
                CREATE TABLE economy_transaction (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, transaction_id VARCHAR(100) NOT NULL,
                    principal_type VARCHAR(20) NOT NULL, principal_id VARCHAR(100) NOT NULL,
                    idempotency_key VARBINARY(36) NOT NULL, request_hash BINARY(32) NOT NULL,
                    business_type VARCHAR(32) NOT NULL, business_id VARCHAR(100) NOT NULL,
                    currency VARCHAR(16) NOT NULL, status VARCHAR(16) NOT NULL,
                    entry_count INT NOT NULL, debit_total_micro BIGINT NOT NULL,
                    credit_total_micro BIGINT NOT NULL, posted_at BIGINT,
                    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT NOT NULL, update_time BIGINT NOT NULL,
                    UNIQUE(tenant_id,client_id,transaction_id),
                    UNIQUE(tenant_id,client_id,principal_type,principal_id,idempotency_key))
                """);
        jdbc.execute("""
                CREATE TABLE economy_entry (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, entry_id VARCHAR(140) NOT NULL,
                    transaction_id VARCHAR(100) NOT NULL, account_id VARCHAR(100) NOT NULL,
                    entry_sequence INT NOT NULL, signed_amount_micro BIGINT NOT NULL,
                    balance_after_micro BIGINT NOT NULL, currency VARCHAR(16) NOT NULL,
                    status VARCHAR(16) NOT NULL, posted_at BIGINT NOT NULL,
                    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT NOT NULL,
                    UNIQUE(tenant_id,client_id,entry_id),
                    UNIQUE(tenant_id,client_id,transaction_id,entry_sequence))
                """);
        jdbc.execute("""
                CREATE TABLE economy_escrow (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, escrow_id VARCHAR(100) NOT NULL,
                    business_type VARCHAR(32) NOT NULL, business_id VARCHAR(100) NOT NULL,
                    payer_account_id VARCHAR(100) NOT NULL, escrow_account_id VARCHAR(100) NOT NULL,
                    currency VARCHAR(16) NOT NULL, gross_micro BIGINT NOT NULL,
                    captured_micro BIGINT NOT NULL, refunded_micro BIGINT NOT NULL,
                    status VARCHAR(24) NOT NULL, version BIGINT NOT NULL,
                    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT NOT NULL, update_time BIGINT NOT NULL,
                    UNIQUE(tenant_id,client_id,escrow_id),
                    UNIQUE(tenant_id,client_id,business_type,business_id),
                    UNIQUE(tenant_id,client_id,escrow_account_id))
                """);
        jdbc.execute("""
                CREATE TABLE economy_escrow_funding_lot (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, escrow_id VARCHAR(100) NOT NULL,
                    funding_sequence INT NOT NULL, reserve_transaction_id VARCHAR(100) NOT NULL,
                    payer_account_id VARCHAR(100) NOT NULL, amount_micro BIGINT NOT NULL,
                    escrow_gross_after_micro BIGINT NOT NULL, escrow_version_after BIGINT NOT NULL,
                    currency VARCHAR(16) NOT NULL, tenant_id VARCHAR(50) NOT NULL,
                    client_id VARCHAR(50) NOT NULL, created_at BIGINT NOT NULL,
                    UNIQUE(tenant_id,client_id,escrow_id,funding_sequence),
                    UNIQUE(tenant_id,client_id,reserve_transaction_id))
                """);
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TestConfiguration {
        @Bean
        DataSource dataSource() {
            DriverManagerDataSource source = new DriverManagerDataSource();
            source.setDriverClassName("org.h2.Driver");
            source.setUrl("jdbc:h2:mem:w04_ledger_spring;MODE=MYSQL;DB_CLOSE_DELAY=-1;"
                    + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;LOCK_TIMEOUT=10000");
            source.setUsername("sa");
            source.setPassword("");
            return source;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        EconomyLedgerMapper economyLedgerMapper(DataSource dataSource) throws Exception {
            SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
            bean.setDataSource(dataSource);
            org.apache.ibatis.session.Configuration configuration =
                    new org.apache.ibatis.session.Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(EconomyLedgerMapper.class);
            bean.setConfiguration(configuration);
            SqlSessionFactory factory = bean.getObject();
            if (factory == null) throw new IllegalStateException("missing SqlSessionFactory");
            return new SqlSessionTemplate(factory).getMapper(EconomyLedgerMapper.class);
        }

        @Bean
        EconomyPreviewGate previewGate() {
            return new EconomyPreviewGate(new EconomyPreviewProperties(true, true, List.of(
                    new EconomyPreviewProperties.AllowedScope(TENANT, CLIENT))));
        }

        @Bean
        EconomyPostingServiceImpl realPosting(EconomyLedgerMapper mapper,
                PlatformTransactionManager transactionManager, EconomyPreviewGate gate) {
            AtomicInteger ids = new AtomicInteger();
            return new EconomyPostingServiceImpl(mapper, transactionManager, gate,
                    () -> "etx-w04-" + ids.incrementAndGet(),
                    () -> "esc-w04-" + ids.get(),
                    () -> 1_800_000_000_000L + ids.get());
        }

        @Bean
        ObservingPostingService observingPosting(EconomyPostingServiceImpl realPosting) {
            return new ObservingPostingService(realPosting);
        }

        @Bean
        FundedBountyLedgerServiceImpl fundedBountyLedgerService(
                EconomyLedgerMapper mapper, ObservingPostingService posting) {
            return new FundedBountyLedgerServiceImpl(mapper, posting);
        }
    }

    static final class ObservingPostingService implements EconomyPostingService {
        private final EconomyPostingService delegate;
        private final AtomicBoolean observedActiveTransaction = new AtomicBoolean();
        private final AtomicBoolean failAfterPost = new AtomicBoolean();

        ObservingPostingService(EconomyPostingService delegate) {
            this.delegate = delegate;
        }

        @Override
        public EconomyPostingResult post(EconomyPostingCommand command) {
            observedActiveTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            EconomyPostingResult result = delegate.post(command);
            if (failAfterPost.get()) throw new IllegalStateException("forced late failure");
            return result;
        }
    }
}
