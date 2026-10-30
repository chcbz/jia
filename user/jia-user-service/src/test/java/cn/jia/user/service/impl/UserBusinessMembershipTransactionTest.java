package cn.jia.user.service.impl;

import cn.jia.user.dao.UserBusinessMembershipDao;
import cn.jia.user.dao.UserInfoDao;
import cn.jia.user.dao.impl.UserBusinessMembershipDaoImpl;
import cn.jia.user.mapper.UserBusinessMembershipMapper;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real membership mapper/Spring transactions; identity DAO collaborator uses the same JDBC transaction. */
class UserBusinessMembershipTransactionTest {
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private UserBusinessMembershipDao memberships;
    private UserBusinessMembership service;

    @BeforeEach void setup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:membership_" + java.util.UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        jdbc.execute("CREATE TABLE user_info(id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE user_business_membership(tenant_id VARCHAR(50),client_id VARCHAR(50),"
                + "user_id BIGINT,create_time BIGINT,PRIMARY KEY(tenant_id,client_id,user_id))");
        jdbc.update("INSERT INTO user_info VALUES(7),(8)");
        jdbc.update("INSERT INTO user_business_membership VALUES('0','web',7,1),('1','gray',7,1),('0','web',8,1)");
        var config = new Configuration(); config.addMapper(UserBusinessMembershipMapper.class);
        var factory = new SqlSessionFactoryBean(); factory.setConfiguration(config); factory.setDataSource(source);
        memberships = spy(new UserBusinessMembershipDaoImpl(
                new SqlSessionTemplate(factory.getObject()).getMapper(UserBusinessMembershipMapper.class)));
        service = proxy(new UserBusinessMembership(memberships, new cn.jia.core.security.FixedTenantAdmissionPolicy("0", null)));
    }

    @AfterEach void cleanup() { if (jdbc != null) jdbc.execute("DROP ALL OBJECTS"); }

    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        var interceptor = new TransactionInterceptor(); interceptor.setTransactionManager(transactions);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(target); factory.setProxyTargetClass(true); factory.addAdvice(interceptor);
        return (T) factory.getProxy();
    }

    private UserServiceImpl users() {
        var identity = mock(UserInfoDao.class);
        when(identity.deleteById(7L)).thenAnswer(call -> jdbc.update("DELETE FROM user_info WHERE id=7"));
        var target = new UserServiceImpl();
        ReflectionTestUtils.setField(target, "baseDao", identity);
        ReflectionTestUtils.setField(target, "businessMembership", service);
        return proxy(target);
    }

    @Test void standaloneWritesRejectMissingIdentityTransaction() {
        assertThrows(IllegalTransactionStateException.class, () -> service.ensure(7L, "new"));
        assertThrows(IllegalTransactionStateException.class, () -> service.removeForDeletedIdentity(7L));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM user_business_membership", Integer.class));
    }

    @Test void globalIdentityDeleteRemovesOnlyItsAffiliationsAcrossEnvironments() {
        assertTrue(users().delete(7L));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_info", Integer.class));
        assertEquals(8L, jdbc.queryForObject("SELECT user_id FROM user_business_membership", Long.class));
    }

    @Test void cleanupFailureRollsBackIdentityAndAlreadyDeletedAffiliations() {
        doAnswer(call -> {
            call.callRealMethod();
            throw new IllegalStateException("fixture failure after actual SQL");
        }).when(memberships).deleteByUserId(7L);
        assertThrows(IllegalStateException.class, () -> users().delete(7L));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM user_info", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM user_business_membership", Integer.class));
    }

    @Test void failedRegistrationRollsBackBothIdentityAndMembership() {
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions).execute(status -> {
            jdbc.update("INSERT INTO user_info VALUES(9)");
            service.ensure(9L, "web");
            throw new IllegalStateException("fixture failed downstream write");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_info WHERE id=9", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_business_membership WHERE user_id=9", Integer.class));
    }
}
