package cn.jia.agent.mapper;

import cn.jia.agent.dao.AgentCommandRecoveryDao;
import cn.jia.agent.dao.impl.AgentCommandRecoveryDaoImpl;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.WorkerScope;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Executes production recovery discovery SQL and proves adjacent lanes cannot consume a batch. */
class AgentCommandRecoveryScopedDiscoveryTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final WorkerScope SCOPE = new WorkerScope(
            TenantId.of("1"), DataDomainId.of("cyf"), "pool-a", "deployment-a",
            7, 11, 13, "agent-command", 17);

    private JdbcTemplate jdbc;
    private AgentCommandRecoveryDao dao;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:recovery_scope_" + UUID.randomUUID()
                        + ";MODE=MYSQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE agent_command_delivery(
                  id BIGINT PRIMARY KEY, protocol_version INT, data_domain_id VARCHAR(64),
                  execution_pool_id VARCHAR(100), admission_epoch BIGINT,
                  producer_deployment_id VARCHAR(100), module_id VARCHAR(64), migration_epoch BIGINT,
                  tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                  target_agent_id VARCHAR(100) NOT NULL, status VARCHAR(32) NOT NULL,
                  next_retry_at BIGINT, expires_at BIGINT NOT NULL, update_time BIGINT)
                """);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentCommandRecoveryMapper.class);
        GlobalConfig global = new GlobalConfig();
        global.setIdentifierGenerator(new DefaultIdentifierGenerator());
        global.setBanner(false);
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(source);
        bean.setConfiguration(configuration);
        bean.setGlobalConfig(global);
        SqlSessionFactory factory = bean.getObject();
        dao = new AgentCommandRecoveryDaoImpl(
                new SqlSessionTemplate(factory).getMapper(AgentCommandRecoveryMapper.class));
    }

    @Test
    void v2DueReconnectAndCursorIgnoreAdjacentTenantDomainPoolAndProvenance() {
        insert(1, 2, "2", "cyf", "pool-a", 13L, "deployment-a", "agent-command", 17L,
                "client-a", "agent-a");
        insert(2, 2, "1", "other", "pool-a", 13L, "deployment-a", "agent-command", 17L,
                "client-a", "agent-a");
        insert(3, 2, "1", "cyf", "pool-b", 13L, "deployment-a", "agent-command", 17L,
                "client-a", "agent-a");
        insert(4, 2, "1", "cyf", "pool-a", 14L, "deployment-a", "agent-command", 17L,
                "client-a", "agent-a");
        insert(5, 2, "1", "cyf", "pool-a", 13L, "deployment-b", "agent-command", 17L,
                "client-a", "agent-a");
        insert(6, 2, "1", "cyf", "pool-a", 13L, "deployment-a", "other-module", 17L,
                "client-a", "agent-a");
        insert(7, 2, "1", "cyf", "pool-a", 13L, "deployment-a", "agent-command", 18L,
                "client-a", "agent-a");
        insert(8, 2, "1", "cyf", "pool-a", 13L, "deployment-a", "agent-command", 17L,
                "client-a", "agent-a");
        insert(9, 2, "1", "cyf", "pool-a", 13L, "deployment-a", "agent-command", 17L,
                "client-b", "agent-b");
        insert(10, 2, "1", "cyf", "pool-a", 13L, "deployment-a", "agent-command", 17L,
                "client-c", "agent-c", "RETRY");

        assertEquals(List.of(8L), ids(dao.findV2DueCandidates(SCOPE, NOW, NOW - 30_000, 0, 1)));
        assertEquals(List.of(9L), ids(dao.findV2DueCandidates(SCOPE, NOW, NOW - 30_000, 8, 1)));
        assertEquals(List.of(10L), ids(dao.findV2DueCandidates(
                SCOPE, NOW, NOW - 30_000, 9, 10)));
        assertEquals(List.of(8L), ids(dao.findV2ReconnectCandidates(
                SCOPE, "client-a", "agent-a", NOW, 0, 1)));
        assertEquals(List.of(), ids(dao.findV2ReconnectCandidates(
                SCOPE, "client-a", "agent-b", NOW, 0, 10)));
    }

    @Test
    void legacyDiscoveryAllowsOnlyTenantZeroWithNoV2Provenance() {
        insert(1, null, "1", null, null, null, null, null, null,
                "client-a", "agent-a");
        insert(2, 2, "0", "cyf", "pool-a", 13L, "deployment-a", "agent-command", 17L,
                "client-a", "agent-a");
        insert(3, null, "0", "cyf", null, null, null, null, null,
                "client-a", "agent-a");
        insert(4, null, "0", null, null, null, null, null, null,
                "client-a", "agent-a");
        insert(5, 1, "0", null, null, null, null, null, null,
                "client-a", "agent-a");
        insert(6, null, "0", null, null, null, null, null, null,
                "client-a", "agent-a", "RETRY");

        assertEquals(List.of(4L, 5L), ids(
                dao.findLegacyDueCandidates(NOW, NOW - 30_000, 0, 10)));
        assertEquals(List.of(4L, 5L), ids(dao.findLegacyReconnectCandidates(
                "0", "client-a", "agent-a", NOW, 0, 10)));
        assertEquals(List.of(), ids(dao.findLegacyReconnectCandidates(
                "1", "client-a", "agent-a", NOW, 0, 10)));
    }

    private void insert(long id, Integer protocol, String tenant, String domain, String pool,
            Long admissionEpoch, String producerDeployment, String module, Long migrationEpoch,
            String client, String target) {
        insert(id, protocol, tenant, domain, pool, admissionEpoch, producerDeployment, module,
                migrationEpoch, client, target, "WAITING_AGENT");
    }

    private void insert(long id, Integer protocol, String tenant, String domain, String pool,
            Long admissionEpoch, String producerDeployment, String module, Long migrationEpoch,
            String client, String target, String status) {
        jdbc.update("""
                INSERT INTO agent_command_delivery(
                  id,protocol_version,data_domain_id,execution_pool_id,admission_epoch,
                  producer_deployment_id,module_id,migration_epoch,tenant_id,client_id,
                  target_agent_id,status,next_retry_at,expires_at,update_time)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, protocol, domain, pool, admissionEpoch, producerDeployment, module,
                migrationEpoch, tenant, client, target, status, NOW - 1,
                NOW + 60_000, NOW - 60_000);
    }

    private static List<Long> ids(List<cn.jia.agent.entity.AgentWaitingCommandCandidate> rows) {
        return rows.stream().map(cn.jia.agent.entity.AgentWaitingCommandCandidate::deliveryId)
                .toList();
    }
}
