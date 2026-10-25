package cn.jia.user.api;

import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.user.dao.UserInfoDao;
import cn.jia.user.dao.impl.UserInfoDaoImpl;
import cn.jia.user.entity.CurrentUserIdentityDTO;
import cn.jia.user.mapper.InfoMapper;
import cn.jia.user.service.impl.UserServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserOwnIdentityRealMapperTest {
    private JdbcTemplate jdbc;
    private UserController controller;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:user_own_identity;MODE=MYSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("""
                CREATE TABLE user_info (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    username VARCHAR(100), password VARCHAR(200), openid VARCHAR(100),
                    jiacn VARCHAR_IGNORECASE(50), phone VARCHAR(100), email VARCHAR(100),
                    nickname VARCHAR(100), avatar VARCHAR(500), remark VARCHAR(500),
                    tenant_id VARCHAR(50), client_id VARCHAR(50)
                )
                """);

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(InfoMapper.class);
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(source);
        factoryBean.setConfiguration(configuration);
        SqlSessionFactory factory = factoryBean.getObject();
        UserInfoDao dao = new UserInfoDaoImpl();
        ReflectionTestUtils.setField(dao, "baseMapper",
                new SqlSessionTemplate(factory).getMapper(InfoMapper.class));
        UserServiceImpl service = new UserServiceImpl();
        ReflectionTestUtils.setField(service, "baseDao", dao);
        controller = new UserController();
        ReflectionTestUtils.setField(controller, "userService", service);
    }

    @AfterEach
    void tearDown() {
        EsContextHolder.clearContext();
        jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    void globalRowHydratesOnlyMinimalOwnIdentityUsingTrustedAdmittedScope() {
        jdbc.update("""
                INSERT INTO user_info
                    (username, password, openid, jiacn, phone, email, nickname, avatar,
                     remark, tenant_id, client_id)
                VALUES ('alice', 'secret-hash', 'openid-a', 'owner-a', '15500000000',
                        'private@example.test', 'Alice', '/avatar/a.png', 'private remark',
                        '0', 'legacy-client')
                """);
        context("1", "cyf-local", "owner-a");

        JsonResult<?> result = assertInstanceOf(JsonResult.class, controller.userInfo());
        CurrentUserIdentityDTO identity = assertInstanceOf(CurrentUserIdentityDTO.class, result.getData());

        assertEquals("alice", identity.username());
        assertEquals("openid-a", identity.openid());
        assertEquals("owner-a", identity.jiacn());
        assertEquals("Alice", identity.nickname());
        assertEquals("/avatar/a.png", identity.avatar());
        assertEquals("1", identity.tenantId());
        assertEquals("cyf-local", identity.clientId());
        assertEquals(8, CurrentUserIdentityDTO.class.getRecordComponents().length);
    }

    @Test
    void byteExactForeignSystemInvalidAndAmbiguousIdentitiesFailClosed() {
        jdbc.update("INSERT INTO user_info(username, jiacn) VALUES ('alice', 'owner-a')");

        context("1", "cyf-local", "OWNER-A");
        assertThrows(EsRuntimeException.class, controller::userInfo);
        context("1", "cyf-local", "foreign-owner");
        assertThrows(EsRuntimeException.class, controller::userInfo);
        context("1", "cyf-local", "0");
        assertThrows(EsRuntimeException.class, controller::userInfo);
        context("1", "cyf-local ", "owner-a");
        assertThrows(EsRuntimeException.class, controller::userInfo);
        context("1", "cyf-local\u00a0", "owner-a");
        assertThrows(EsRuntimeException.class, controller::userInfo);
        context("1", "cyf-local", "owner-a\ud800");
        assertThrows(EsRuntimeException.class, controller::userInfo);
        context("", "cyf-local", "owner-a");
        assertThrows(EsRuntimeException.class, controller::userInfo);

        jdbc.update("INSERT INTO user_info(username, jiacn) VALUES ('duplicate', 'owner-a')");
        context("1", "cyf-local", "owner-a");
        assertThrows(IllegalStateException.class, controller::userInfo);
    }

    @Test
    void selectedProjectionDoesNotHydrateSensitiveGlobalColumns() {
        jdbc.update("""
                INSERT INTO user_info(username, password, jiacn, phone, email, remark)
                VALUES ('alice', 'secret-hash', 'owner-a', '15500000000',
                        'private@example.test', 'private remark')
                """);
        UserServiceImpl service = (UserServiceImpl) ReflectionTestUtils.getField(controller, "userService");
        var projected = service.findOwnIdentityByExactJiacn("owner-a");

        assertEquals("alice", projected.getUsername());
        assertNull(projected.getPassword());
        assertNull(projected.getPhone());
        assertNull(projected.getEmail());
        assertNull(projected.getRemark());
    }

    private static void context(String tenantId, String clientId, String jiacn) {
        EsContext context = new EsContext();
        context.setTenantId(tenantId);
        context.setClientId(clientId);
        context.setJiacn(jiacn);
        EsContextHolder.setContext(context);
    }
}
