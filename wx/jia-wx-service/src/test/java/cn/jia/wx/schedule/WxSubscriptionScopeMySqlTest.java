package cn.jia.wx.schedule;

import cn.jia.agent.skill.SkillManagementMySqlFixture;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.kefu.dao.impl.KefuMsgSubscribeDaoImpl;
import cn.jia.kefu.entity.KefuMsgSubscribeEntity;
import cn.jia.kefu.mapper.KefuMsgSubscribeMapper;
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

/** Actual discovery SQL, including source columns with case-insensitive/PAD SPACE collation. */
class WxSubscriptionScopeMySqlTest extends SkillManagementMySqlFixture {
    JdbcTemplate jdbc;
    KefuMsgSubscribeMapper mapper;
    @BeforeEach void setup() {
        admin.execute("CREATE DATABASE IF NOT EXISTS cyf_wx_scope CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        var ds = source("cyf_wx_scope"); jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS kefu_msg_subscribe");
        jdbc.execute("""
                CREATE TABLE kefu_msg_subscribe (id BIGINT PRIMARY KEY, tenant_id VARCHAR(64),
                client_id VARCHAR(64), jiacn VARCHAR(64), type_code VARCHAR(32), status INT,
                wx_rx_flag INT, sms_rx_flag INT, create_time BIGINT, update_time BIGINT)
                """);
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("owned-wx-scope", new SpringManagedTransactionFactory(), ds));
        config.addMapper(KefuMsgSubscribeMapper.class);
        mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(KefuMsgSubscribeMapper.class);
    }
    void subscription(long id, String tenant, String client, String owner) {
        jdbc.update("INSERT INTO kefu_msg_subscribe VALUES (?,?,?,?, 'vote',1,1,0,0,0)", id,tenant,client,owner);
    }
    KefuMsgSubscribeDaoImpl dao(String client) {
        var dao = new KefuMsgSubscribeDaoImpl();
        ReflectionTestUtils.setField(dao,"baseMapper",mapper);
        ReflectionTestUtils.setField(dao,"processScope",new FixedTenantAdmissionPolicy("tenant-a",client));
        return dao;
    }
    @Test void scopePrecedesCursorAndLimit() {
        subscription(1,"Tenant-a","client-a","owner");
        subscription(2,"tenant-a ","client-a","owner");
        subscription(3,"tenant-a","Client-a","owner");
        subscription(4,"tenant-a","client-a ","owner");
        subscription(5,"tenant-a","client-a","owner");
        subscription(6,"tenant-a","client-a","owner");
        var dao = dao("client-a");
        assertEquals(List.of(5L),dao.findScheduledRecipients("vote",0,1).stream().map(KefuMsgSubscribeEntity::getId).toList());
        assertEquals(List.of(6L),dao.findScheduledRecipients("vote",5,1).stream().map(KefuMsgSubscribeEntity::getId).toList());
        assertTrue(dao.findScheduledRecipients("vote",6,1).isEmpty());
        assertEquals(4,dao(null).findScheduledRecipients("vote",0,10).size());
    }
    @Test void disabledUnsubscribedOrDifferentKindCannotBeDiscovered() {
        for (int i=1;i<=5;i++) subscription(i,"tenant-a","client-a","owner");
        jdbc.update("UPDATE kefu_msg_subscribe SET status=0 WHERE id=1");
        jdbc.update("UPDATE kefu_msg_subscribe SET wx_rx_flag=0 WHERE id=2");
        jdbc.update("UPDATE kefu_msg_subscribe SET type_code='phrase' WHERE id=3");
        jdbc.update("UPDATE kefu_msg_subscribe SET jiacn=NULL WHERE id=4");
        assertEquals(List.of(5L),dao("client-a").findScheduledRecipients("vote",0,10).stream().map(KefuMsgSubscribeEntity::getId).toList());
    }
    @Test void dispatchRechecksFrozenOwnerAndSubscriptionNotOnlyItsId() {
        subscription(1,"tenant-a","client-a","owner"); var dao = dao(null);
        assertNotNull(dao.findScheduledRecipient("client-a",1,"vote","owner"));
        assertNull(dao.findScheduledRecipient("client-a",1,"vote","Owner"));
        assertNull(dao.findScheduledRecipient("client-a",1,"vote","owner "));
        assertNull(dao.findScheduledRecipient("client-b",1,"vote","owner"));
        assertNull(dao.findScheduledRecipient("client-a",1,"phrase","owner"));
        assertThrows(IllegalArgumentException.class, () -> dao("client-b").findScheduledRecipient("client-a",1,"vote","owner"));
        jdbc.update("UPDATE kefu_msg_subscribe SET status=0 WHERE id=1");
        assertNull(dao.findScheduledRecipient("client-a",1,"vote","owner"));
    }
}
