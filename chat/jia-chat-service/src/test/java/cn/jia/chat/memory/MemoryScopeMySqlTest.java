package cn.jia.chat.memory;

import cn.jia.agent.skill.SkillManagementMySqlFixture;
import cn.jia.chat.dao.impl.ChatMessageDaoImpl;
import cn.jia.chat.mapper.ChatMessageMapper;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
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
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** Production mapper queries against private MySQL with case-insensitive/PAD SPACE source columns. */
class MemoryScopeMySqlTest extends SkillManagementMySqlFixture {
    JdbcTemplate jdbc;
    ChatMessageMapper mapper;
    @BeforeEach void setup() {
        admin.execute("CREATE DATABASE IF NOT EXISTS cyf_memory_scope CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        var ds = source("cyf_memory_scope"); jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS chat_message"); jdbc.execute("DROP TABLE IF EXISTS chat_conversation");
        jdbc.execute("CREATE TABLE chat_conversation (id BIGINT PRIMARY KEY, tenant_id VARCHAR(64), client_id VARCHAR(64), jiacn VARCHAR(64), deleted_at BIGINT)");
        jdbc.execute("CREATE TABLE chat_message (id BIGINT PRIMARY KEY, conversation_id VARCHAR(64), tenant_id VARCHAR(64), client_id VARCHAR(64), jiacn VARCHAR(64), message_type VARCHAR(32), sync_status VARCHAR(32), create_time BIGINT)");
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("owned-memory-scope", new SpringManagedTransactionFactory(), ds));
        config.addMapper(ChatMessageMapper.class);
        mapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)).getMapper(ChatMessageMapper.class);
    }
    void source(long id, String tenant, String client, String owner) {
        jdbc.update("INSERT INTO chat_conversation VALUES (?,?,?,?,NULL)", id, tenant, client, owner);
        jdbc.update("INSERT INTO chat_message VALUES (?,?,?,?,?,'USER','PENDING',200)", id, Long.toString(id), tenant, client, owner);
    }
    ChatMessageDaoImpl dao(String client) {
        var dao = new ChatMessageDaoImpl();
        ReflectionTestUtils.setField(dao, "baseMapper", mapper);
        ReflectionTestUtils.setField(dao, "processScope", new FixedTenantAdmissionPolicy("tenant-a", client));
        return dao;
    }
    @Test void processScopePrecedesLimitAndCursorWithoutStarvation() {
        source(1, "Tenant-a", "client-a", "owner");
        source(2, "tenant-a ", "client-a", "owner");
        source(3, "tenant-a", "Client-a", "owner");
        source(4, "tenant-a", "client-a ", "owner");
        source(5, "other", "client-a", "owner");
        source(6, "tenant-a", "client-a", "owner");
        source(7, "tenant-a", "client-a", "owner");
        var dao = dao("client-a");
        assertEquals(List.of("6"), dao.findPendingConversationIds("PENDING", 1, 0));
        assertEquals(List.of("7"), dao.findPendingConversationIds("PENDING", 1, 6));
        assertEquals(List.of(), dao.findPendingConversationIds("PENDING", 1, 7));
        assertEquals(List.of(new MemoryOwner("client-a", "owner")), dao.findActiveMemoryOwners(100));
    }
    @Test void tenantMaintenanceRetainsExactClientAndOwnerPairs() {
        source(1, "tenant-a", "client-a", "owner"); source(2, "tenant-a", "client-b", "owner");
        source(3, "tenant-a", "client-a", "Owner"); source(4, "tenant-a", "client-a", "owner ");
        source(5, "tenant-a", "client-a", "owner"); source(6, "foreign", "client-a", "owner");
        var owners = dao(null).findActiveMemoryOwners(100);
        assertEquals(4, owners.size());
        assertEquals(Set.of(new MemoryOwner("client-a","owner"), new MemoryOwner("client-b","owner"),
                new MemoryOwner("client-a","Owner"), new MemoryOwner("client-a","owner ")), Set.copyOf(owners));
    }
    @Test void corruptDeletedSystemAndExcludedRowsCannotEnterMemoryDiscovery() {
        source(1, "tenant-a", "client-a", "owner"); source(2, "tenant-a", "client-a", "owner");
        source(3, "tenant-a", "client-a", "owner"); source(4, "tenant-a", "client-a", "owner");
        source(5, "tenant-a", "client-a", "owner"); source(6, "tenant-a", "client-a", "valid");
        jdbc.update("UPDATE chat_message SET jiacn='Owner' WHERE id=1");
        jdbc.update("UPDATE chat_message SET conversation_id='2 ' WHERE id=2");
        jdbc.update("UPDATE chat_conversation SET deleted_at=1 WHERE id=3");
        jdbc.update("UPDATE chat_message SET message_type='SYSTEM' WHERE id=4");
        jdbc.update("UPDATE chat_message SET sync_status='EXCLUDED' WHERE id=5");
        assertEquals(List.of("6"), dao("client-a").findPendingConversationIds("PENDING", 1, 0));
        assertEquals(List.of(new MemoryOwner("client-a", "valid")), dao("client-a").findActiveMemoryOwners(100));
    }
    @Test void summaryWindowExcludesOldRowsAndSnapshotUpdateRemainsExact() {
        source(1, "tenant-a", "client-a", "owner"); source(2, "tenant-a", "client-b", "owner");
        jdbc.update("UPDATE chat_message SET create_time=50 WHERE id=2");
        assertEquals(List.of(new MemoryOwner("client-a", "owner")), dao(null).findActiveMemoryOwners(100));
        assertEquals(0, mapper.markMemorySnapshotSynced("tenant-a", "owner", "Client-a", "1", 1, List.of(1L)));
        assertEquals(1, dao("client-a").markMemorySnapshotSynced("tenant-a", "owner", "client-a", "1", List.of(1L)));
        assertEquals("PENDING", jdbc.queryForObject("SELECT sync_status FROM chat_message WHERE id=2", String.class));
    }
}
