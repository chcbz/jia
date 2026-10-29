package cn.jia.oauth;

import cn.jia.oauth.dao.impl.OauthClientDaoImpl;
import cn.jia.oauth.entity.OauthClientEntity;
import cn.jia.oauth.mapper.OauthClientMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/** Actual BaseMapper and exact-lookup SQL, not a mocked registry. */
class OauthClientIdentityMapperTest {
    private JdbcTemplate jdbc;
    private OauthClientDaoImpl clients;

    @BeforeEach void setup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:oauth_identity_" + java.util.UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        jdbc.update("INSERT INTO oauth_client(id,client_id,client_name,client_authentication_methods,"
                + "authorization_grant_types,scopes,client_settings,token_settings,tenant_id) "
                + "VALUES('one','web','Web','client_secret_basic','authorization_code','openid','{}','{}','0'),"
                + "('two','gray','Gray','client_secret_basic','authorization_code','openid','{}','{}','1')");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(OauthClientMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setConfiguration(config); factory.setDataSource(source);
        clients = new OauthClientDaoImpl();
        ReflectionTestUtils.setField(clients, "baseMapper",
                new SqlSessionTemplate(factory.getObject()).getMapper(OauthClientMapper.class));
    }

    @AfterEach void cleanup() { if (jdbc != null) jdbc.execute("DROP ALL OBJECTS"); }

    @Test void oldTenantColumnDoesNotBecomeClientIdentity() {
        assertNull(clients.selectExactByClientId("web").getTenantId());
        assertNull(clients.selectExactByClientId("gray").getTenantId());
        assertNull(clients.selectExactByClientId("WEB"));
        assertNull(clients.selectExactByClientId("web "));
    }

    @Test void globalRegistryCrudAndExactQueriesWorkAfterDroppingTenant() {
        jdbc.execute("ALTER TABLE oauth_client DROP COLUMN tenant_id");
        assertEquals("one", clients.selectExactByClientId("web").getId());
        OauthClientEntity query = new OauthClientEntity(); query.setTenantId("1");
        assertEquals(2, clients.selectByEntity(query).size());
        assertEquals("1", query.getTenantId(), "DAO must not mutate caller's query");
        OauthClientEntity fresh = new OauthClientEntity();
        fresh.setId("three"); fresh.setClientId("new"); fresh.setClientName("New");
        fresh.setClientAuthenticationMethods("client_secret_basic");
        fresh.setAuthorizationGrantTypes("client_credentials"); fresh.setScopes("openid");
        fresh.setClientSettings("{}"); fresh.setTokenSettings("{}"); fresh.setTenantId("1");
        assertEquals(1, clients.insert(fresh));
        fresh.setClientName("Renamed");
        assertEquals(1, clients.updateById(fresh));
        assertEquals("Renamed", clients.selectExactByClientId("new").getClientName());
        assertNull(clients.selectExactByClientId("new").getTenantId());
    }
}
