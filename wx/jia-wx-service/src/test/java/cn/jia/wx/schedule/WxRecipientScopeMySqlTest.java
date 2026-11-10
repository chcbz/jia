package cn.jia.wx.schedule;

import cn.jia.agent.skill.SkillManagementMySqlFixture;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.wx.dao.impl.MpUserDaoImpl;
import cn.jia.wx.entity.MpUserEntity;
import cn.jia.wx.mapper.MpUserMapper;
import cn.jia.wx.service.impl.MpUserServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Scoped recipient resolution uses real MySQL; it never calls WeChat. */
class WxRecipientScopeMySqlTest extends SkillManagementMySqlFixture {
    JdbcTemplate jdbc; MpUserMapper mapper;
    @BeforeEach void setup() {
        admin.execute("CREATE DATABASE IF NOT EXISTS cyf_wx_recipient CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        var ds=source("cyf_wx_recipient"); jdbc=new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS wx_mp_user");
        jdbc.execute("""
                CREATE TABLE wx_mp_user (id BIGINT PRIMARY KEY, tenant_id VARCHAR(64),client_id VARCHAR(64),
                appid VARCHAR(64),subscribe INT,open_id VARCHAR(64),jiacn VARCHAR(64),subscribe_time BIGINT,
                email VARCHAR(64),sex INT,language VARCHAR(64),nickname VARCHAR(64),head_img_url VARCHAR(64),
                city VARCHAR(64),country VARCHAR(64),province VARCHAR(64),union_id VARCHAR(64),group_id BIGINT,
                subscribe_scene VARCHAR(64),qr_scene VARCHAR(64),qr_scene_str VARCHAR(64),subscribe_items VARCHAR(64),
                status INT,remark VARCHAR(64),create_time BIGINT,update_time BIGINT)
                """);
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("owned-wx-recipient",new SpringManagedTransactionFactory(),ds));
        config.addMapper(MpUserMapper.class);
        mapper=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(MpUserMapper.class);
    }
    void recipient(long id,String tenant,String client,String owner) {
        jdbc.update("INSERT INTO wx_mp_user (id,tenant_id,client_id,jiacn,subscribe,appid,open_id) VALUES (?,?,?,?,1,'app','open')",
                id,tenant,client,owner);
    }
    MpUserDaoImpl dao(String client) {
        var dao=new MpUserDaoImpl();ReflectionTestUtils.setField(dao,"baseMapper",mapper);
        ReflectionTestUtils.setField(dao,"processScope",new FixedTenantAdmissionPolicy("tenant-a",client));return dao;
    }
    MpUserServiceImpl service() {
        var service=new MpUserServiceImpl();ReflectionTestUtils.setField(service,"baseDao",dao(null));return service;
    }
    @Test void unrelatedLowIdsCannotHideExactRecipientBehindLimit() {
        recipient(1,"Tenant-a","client-a","owner");recipient(2,"tenant-a ","client-a","owner");
        recipient(3,"tenant-a","Client-a","owner");recipient(4,"tenant-a","client-a ","owner");
        recipient(5,"tenant-a","client-a","Owner");recipient(6,"tenant-a","client-a","owner ");
        recipient(7,"tenant-a","client-a","owner");
        assertEquals(List.of(7L),dao(null).selectScheduledRecipients("client-a","owner").stream().map(MpUserEntity::getId).toList());
        assertEquals(7L,service().findScheduledRecipient("client-a","owner").getId());
        assertThrows(IllegalArgumentException.class,()->dao("client-b").selectScheduledRecipients("client-a","owner"));
    }
    @Test void ambiguousRecipientIsNotChosenArbitrarilyAndUnsubscribedRowsAreExcluded() {
        recipient(1,"tenant-a","client-a","owner");recipient(2,"tenant-a","client-a","owner");
        assertThrows(IllegalStateException.class,()->service().findScheduledRecipient("client-a","owner"));
        jdbc.update("UPDATE wx_mp_user SET subscribe=0 WHERE id=1");
        assertEquals(2L,service().findScheduledRecipient("client-a","owner").getId());
        jdbc.update("UPDATE wx_mp_user SET subscribe=0 WHERE id=2");
        assertNull(service().findScheduledRecipient("client-a","owner"));
    }
}
