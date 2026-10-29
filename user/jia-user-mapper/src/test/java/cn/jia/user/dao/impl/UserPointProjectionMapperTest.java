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

/** Actual ORM/XML regression against both legacy-present and removed point column. */
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
    }

    @Test void identityPersistenceAndPointSearchWorkAfterDroppingLegacyPoint() {
        jdbc.execute("ALTER TABLE user_info DROP COLUMN point");
        UserEntity user = new UserEntity(); user.setJiacn("carol"); user.setPoint(1234);
        assertEquals(1, users.insert(user));
        UserEntity update = new UserEntity(); update.setId(user.getId()); update.setNickname("Carol");
        update.setPoint(5678);
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
}
