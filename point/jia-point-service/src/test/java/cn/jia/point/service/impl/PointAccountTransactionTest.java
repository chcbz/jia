package cn.jia.point.service.impl;

import cn.jia.point.dao.PointGiftUsageDao;
import cn.jia.point.dao.PointRecordDao;
import cn.jia.point.dao.impl.PointAccountDaoImpl;
import cn.jia.point.entity.PointGiftUsageEntity;
import cn.jia.point.entity.PointRecordEntity;
import cn.jia.point.mapper.PointAccountMapper;
import cn.jia.point.service.GiftService;
import cn.jia.point.service.PointAccountService;
import cn.jia.point.service.PointService;
import cn.jia.user.entity.UserEntity;
import cn.jia.user.service.UserService;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real account mapper + Spring transactions; ledger/order collaborators use JDBC in the same transaction. */
class PointAccountTransactionTest {
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private PointAccountService accounts;
    private PointRecordDao ledger;
    private UserService users;

    @BeforeEach void setup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:point_" + java.util.UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        jdbc.execute("CREATE TABLE point_account(tenant_id VARCHAR(50),jiacn VARCHAR(50),balance INT,"
                + "version BIGINT,create_time BIGINT,update_time BIGINT,PRIMARY KEY(tenant_id,jiacn))");
        jdbc.execute("CREATE TABLE point_record(tenant_id VARCHAR(50),jiacn VARCHAR(50),chg INT,remain INT)");
        jdbc.execute("CREATE TABLE gift_order(id BIGINT PRIMARY KEY,status INT)");
        jdbc.update("INSERT INTO point_account VALUES('0','alice',90,0,1,1),('1','alice',7,0,1,1)");
        var configuration = new Configuration();
        configuration.addMapper(PointAccountMapper.class);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source); factory.setConfiguration(configuration);
        var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(PointAccountMapper.class);
        accounts = proxy(new PointAccountServiceImpl(new PointAccountDaoImpl(mapper), new cn.jia.core.security.FixedTenantAdmissionPolicy("1", null)));
        users = mock(UserService.class);
        when(users.findOwnIdentityByExactJiacn("alice")).thenReturn(new UserEntity());
        ledger = mock(PointRecordDao.class);
        when(ledger.insert(any())).thenAnswer(call -> {
            PointRecordEntity row = call.getArgument(0);
            return jdbc.update("INSERT INTO point_record VALUES(?,?,?,?)", row.getTenantId(),
                    row.getJiacn(), row.getChg(), row.getRemain());
        });
    }

    @org.junit.jupiter.api.AfterEach void cleanup() {
        if (jdbc != null) jdbc.execute("DROP ALL OBJECTS");
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactions);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true); proxy.addAdvice(interceptor);
        return (T) proxy.getProxy();
    }

    private PointService points() {
        return proxy(new PointServiceImpl(null, null, ledger, users, accounts));
    }

    @Test void successfulCreditCommitsAccountAndLedgerWithoutTouchingProduction() {
        assertEquals(10, points().add("alice", 3, 1).getRemain());
        assertEquals(10, accounts.balance("alice"));
        assertEquals(90, jdbc.queryForObject("SELECT balance FROM point_account WHERE tenant_id='0'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM point_record WHERE tenant_id='1'", Integer.class));
    }

    @Test void ledgerFailureRollsBackRealAccountUpdate() {
        doReturn(0).when(ledger).insert(any());
        assertThrows(IllegalStateException.class, () -> points().add("alice", 3, 1));
        assertEquals(7, accounts.balance("alice"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM point_record", Integer.class));
    }

    @Test void standaloneMutationIsRejectedByMandatoryPropagation() {
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                () -> accounts.changeAndGet("alice", 1));
        assertEquals(7, accounts.balance("alice"));
    }

    @Test void failedRefundStatusRollsBackBalanceAndAlreadyInsertedLedger() {
        jdbc.update("INSERT INTO gift_order VALUES(1,1)");
        PointGiftUsageDao orders = mock(PointGiftUsageDao.class);
        when(orders.selectScoped("1", 1L, true)).thenAnswer(call -> {
            int status = jdbc.queryForObject("SELECT status FROM gift_order WHERE id=1 FOR UPDATE", Integer.class);
            PointGiftUsageEntity usage = new PointGiftUsageEntity();
            usage.setId(1L); usage.setJiacn("alice"); usage.setPoint(4); usage.setStatus(status);
            return usage;
        });
        doReturn(0).when(orders).updateScoped(eq("1"), any());
        GiftServiceImpl target = new GiftServiceImpl();
        ReflectionTestUtils.setField(target, "pointGiftUsageDao", orders);
        ReflectionTestUtils.setField(target, "pointRecordDao", ledger);
        ReflectionTestUtils.setField(target, "accounts", accounts);
        ReflectionTestUtils.setField(target, "userService", users);
        GiftService service = proxy(target);
        assertThrows(IllegalStateException.class, () -> service.usageCancel(1L));
        assertEquals(7, accounts.balance("alice"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM point_record", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT status FROM gift_order", Integer.class));
    }
}
