package cn.jia.user.dao.impl;

import cn.jia.user.entity.UserEntity;
import cn.jia.user.entity.UserVO;
import cn.jia.user.mapper.InfoMapper;
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

/** Actual ORM/XML regression against legacy-present and removed identity-scope/point columns. */
class UserPointProjectionMapperTest {
    private JdbcTemplate jdbc;
    private UserInfoDaoImpl users;

    @BeforeEach void setup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:user_points_" + java.util.UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE point_account(tenant_id VARCHAR(50),jiacn VARCHAR(50),balance INT,"
                + "PRIMARY KEY(tenant_id,jiacn))");
        jdbc.execute("CREATE TABLE user_business_membership(tenant_id VARCHAR(50),client_id VARCHAR(50),"
                + "user_id BIGINT,create_time BIGINT,PRIMARY KEY(tenant_id,client_id,user_id))");
        jdbc.update("INSERT INTO user_business_membership VALUES('0','web',1,1),('0','',2,1),('1','gray',1,1)");
        jdbc.update("INSERT INTO user_info(id,jiacn,point) VALUES(1,'alice',999),(2,'bob',999)");
        jdbc.update("INSERT INTO point_account VALUES('0','alice',90),('1','alice',7)");
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(InfoMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setConfiguration(configuration); factory.setDataSource(source);
        users = new UserInfoDaoImpl();
        ReflectionTestUtils.setField(users, "baseMapper", new SqlSessionTemplate(factory.getObject()).getMapper(InfoMapper.class));
    }

    @AfterEach void cleanup() { if (jdbc != null) jdbc.execute("DROP ALL OBJECTS"); }

    @Test void listAndSearchFilterByBusinessBalanceNotStaleUserColumn() {
        UserVO list = new UserVO(); list.setPoint(7);
        assertEquals("alice", users.selectForList(list, "1").getFirst().getJiacn());
        assertEquals(7, users.selectForList(list, "1").getFirst().getPoint());
        assertTrue(users.selectForList(list, "0").isEmpty());
        UserEntity search = new UserEntity(); search.setPoint(7);
        assertEquals("alice", users.searchByExample(search, "1").getFirst().getJiacn());
        search.setPoint(0);
        assertEquals("bob", users.searchByExample(search, "1").getFirst().getJiacn());
        assertThrows(IllegalArgumentException.class, () -> users.searchByExample(search));
        assertThrows(IllegalArgumentException.class, () -> users.selectByEntity(search));
    }

    @Test void identityPersistenceAndPointSearchWorkAfterDroppingLegacyColumns() {
        jdbc.execute("ALTER TABLE user_info DROP COLUMN point");
        jdbc.execute("ALTER TABLE user_info DROP COLUMN tenant_id");
        jdbc.execute("ALTER TABLE user_info DROP COLUMN client_id");
        UserEntity user = new UserEntity(); user.setJiacn("carol"); user.setPoint(1234); user.setTenantId("1"); user.setClientId("ignored");
        assertEquals(1, users.insert(user));
        UserEntity update = new UserEntity(); update.setId(user.getId()); update.setNickname("Carol");
        update.setPoint(5678); update.setTenantId("1"); update.setClientId("ignored");
        assertEquals(1, users.updateById(update));
        assertNull(users.selectByJiacn("carol").getPoint());
        UserEntity query = new UserEntity(); query.setPoint(7);
        assertEquals("alice", users.searchByExample(query, "1").getFirst().getJiacn());
        UserVO list = new UserVO(); list.setPoint(7);
        assertEquals("alice", users.selectForList(list, "1").getFirst().getJiacn());
    }

    @Test void ordinaryIdentityReadsNeverHydrateLegacyPoint() {
        assertNull(users.selectByJiacn("alice").getPoint());
        UserEntity query = new UserEntity(); query.setJiacn("alice");
        assertNull(users.searchByExample(query).getFirst().getPoint());
    }

    @Test void pointProjectionDoesNotDropExactIdentityOrMembershipFilters() {
        UserVO exact = new UserVO(); exact.setJiacn("bob");
        assertEquals(1, users.selectForList(exact, "1").size());
        assertEquals("bob", users.selectForList(exact, "1").getFirst().getJiacn());
        UserVO membership = new UserVO(); membership.setOrgId(99L);
        assertTrue(users.selectForList(membership, "1").isEmpty());
        jdbc.update("INSERT INTO user_org_rel(user_id,org_id) VALUES(1,99)");
        assertEquals("alice", users.selectForList(membership, "1").getFirst().getJiacn());
    }
    @Test void scopeFiltersUseBusinessMembershipAfterIdentityColumnsAreRemoved() {
        jdbc.execute("ALTER TABLE user_info DROP COLUMN tenant_id");
        jdbc.execute("ALTER TABLE user_info DROP COLUMN client_id");
        UserVO query = new UserVO(); query.setTenantId("0"); query.setClientId("web");
        assertEquals("alice", users.selectForList(query, "0").getFirst().getJiacn());
        assertEquals("0", query.getTenantId());
        assertEquals("web", query.getClientId());
        query.setClientId("gray");
        assertTrue(users.selectForList(query, "0").isEmpty());
        query.setTenantId("1");
        assertEquals("alice", users.selectForList(query, "1").getFirst().getJiacn());
        query.setClientId("");
        assertTrue(users.selectForList(query, "1").isEmpty());
        query.setClientId(null); query.setTenantId("0");
        assertEquals(2, users.selectForList(query, "0").size());
        UserEntity generic = new UserEntity(); generic.setTenantId("0"); generic.setClientId("web");
        assertEquals("alice", users.selectByEntity(generic).getFirst().getJiacn());
        assertNull(users.selectByJiacn("alice").getTenantId());
        assertNull(users.selectByJiacn("alice").getClientId());
    }

}
