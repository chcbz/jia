package cn.jia.agent.skill;

import cn.jia.agent.dao.impl.AgentCommandTransportDaoImpl;
import cn.jia.agent.dao.impl.AgentCommandRecoveryDaoImpl;
import cn.jia.agent.entity.*;
import cn.jia.agent.mapper.*;
import cn.jia.agent.service.impl.AgentCommandCanonicalCodec;
import cn.jia.economy.config.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real private MySQL delivery/outbox schema, native writer and lease/CAS. Dispatch permission
 * is separately covered by real marketplace transactions; this fixture never represents an order. */
@EnabledIfEnvironmentVariable(named="CYF_SKILL_MYSQL_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/.*")
class SkillInstallTransportMySqlTest {
    private JdbcTemplate admin,jdbc;
    private String db;
    private TransactionTemplate tx;
    private AgentSkillInstallRecoveryMapper mapper;
    private AgentCommandTransportMapper commands;
    private SkillInstallCommandWriter writer;
    private SkillInstallRecoveryService recovery;
    private SkillInstallDispatchService dispatch;
    private AgentCommandDraft draft;
    @BeforeEach void setup() throws Exception {
        if(!"true".equals(System.getenv("CYF_SKILL_MYSQL_ISOLATED"))) throw new IllegalStateException("Exclusive fixture required");
        String url=System.getenv("CYF_SKILL_MYSQL_URL");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,"root",""));db="skill_ws_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE "+db+" CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        var ds=new DriverManagerDataSource(url.replace("/?","/"+db+"?"),"root","");jdbc=new JdbcTemplate(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/agent-command-transport-schema.sql")).execute(ds);
        jdbc.execute("CREATE TABLE economy_skill_installation (tenant_id VARCHAR(50),client_id VARCHAR(50),command_id VARCHAR(100),status VARCHAR(32))");
        var cfg=new org.apache.ibatis.session.Configuration();cfg.setMapUnderscoreToCamelCase(true);
        for(Class<?> c:List.of(AgentCommandTransportMapper.class,AgentCommandRecoveryMapper.class,AgentSkillInstallRecoveryMapper.class,AgentOutboxRelayMapper.class)) cfg.addMapper(c);
        var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(cfg);var sql=new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        commands=sql.getMapper(AgentCommandTransportMapper.class);mapper=sql.getMapper(AgentSkillInstallRecoveryMapper.class);
        var manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);
        var preview=new EconomyPreviewGate(new EconomyPreviewProperties(true,List.of(new EconomyPreviewProperties.AllowedScope("0","Client-A"))));
        writer=new SkillInstallCommandWriter(new AgentCommandTransportDaoImpl(commands),preview);dispatch=mock(SkillInstallDispatchService.class);
        recovery=new SkillInstallRecoveryService(mapper,new AgentCommandRecoveryDaoImpl(sql.getMapper(AgentCommandRecoveryMapper.class)),preview,dispatch,manager);
        long now=System.currentTimeMillis();String install="si_fixture",order="so_fixture";
        draft=new AgentCommandDraft(1,AgentCommandCanonicalCodec.skillInstallCommandId("0","Client-A","Owner-A",install),order,install,
                "0","Client-A","Owner-A",order,null,"agt_fixture","SKILL_INSTALL",now,now+3600000,
                new AgentSkillInstallPayload(order,install,"pv_fixture","repo-test","1.0.0","100","sha256:"+"a".repeat(64),"/internal/agent/skill-installations/"+install+"/package"));
    }
    @AfterEach void cleanup() { if(admin!=null && db!=null) admin.execute("DROP DATABASE "+db); }
    void pending() {
        tx.execute(s->{writer.write(draft);jdbc.update("INSERT INTO economy_skill_installation VALUES (?,?,?,'INSTALLING')","0","Client-A",draft.commandId());return null;});
    }
    @Test void sqlWriterAndDrainerKeepBytesAndNeverClaimBrokerConfirmation() {
        pending();byte[] wire=jdbc.queryForObject("SELECT wire_payload FROM agent_outbox_event",byte[].class);
        when(dispatch.dispatch(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),any())).thenAnswer(call->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("Owner-A",call.getArgument(2));assertEquals("Client-A",call.getArgument(1));
            assertArrayEquals(wire,call.getArgument(6));return AgentRawCommandDispatchResult.sent(1,1);
        });
        recovery.recover();assertEquals("SENT",jdbc.queryForObject("SELECT status FROM agent_command_delivery",String.class));
        assertEquals("SENT",jdbc.queryForObject("SELECT status FROM agent_outbox_event",String.class));
        assertEquals("NONE",jdbc.queryForObject("SELECT publisher_confirm_status FROM agent_outbox_event",String.class));
        assertNull(jdbc.queryForObject("SELECT confirmed_at FROM agent_outbox_event",Long.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_consumer_inbox",Integer.class));
    }
    @Test void claimCasCannotOverwriteLeaseOrCrossCaseScope() {
        pending();long now=System.currentTimeMillis();var d=mapper.candidates(now,now-60000).getFirst();
        assertEquals(1,mapper.claimDelivery(d,"owner-1",now+60000,now));
        assertEquals(0,mapper.claimDelivery(d,"owner-2",now+60000,now));
        d.setVersion(d.getVersion()+1).setStatus("CONSUMED");
        assertEquals(0,mapper.finishDelivery(d,"owner-2","SENT",null,"wrong lease",now));
        d.setClientId("client-a");assertEquals(0,mapper.finishDelivery(d,"owner-1","SENT",null,"wrong scope",now));
        assertEquals("owner-1",jdbc.queryForObject("SELECT lease_owner FROM agent_command_delivery",String.class));
    }
    @Test void failedOutboxWriteRollsBackOriginalDelivery() {
        assertThrows(RuntimeException.class,()->tx.execute(s->{writer.write(draft);throw new IllegalStateException("abort order");}));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_command_delivery",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_outbox_event",Integer.class));
        assertThrows(SkillMarketplaceException.class,()->writer.write(draft));
    }
    @Test void rabbitDiscoveryCannotClaimNativeWsCommands() {
        // Only this private fixture installs the V2 discovery schema. Native WS rows
        // retain NULL protocol/scope and must not be claimed by the Rabbit lane.
        var migrationSource=new DriverManagerDataSource(System.getenv("CYF_SKILL_MYSQL_URL").replace("/?","/"+db+"?"),"root","");
        new ResourceDatabasePopulator(new ClassPathResource("db/agent-outbox-relay-v2-manual-20260925.sql")).execute(migrationSource);
        pending();long now=System.currentTimeMillis();
        // Mapper statement text is exercised against real MySQL, including REGEXP guards.
        var cfg=new org.apache.ibatis.session.Configuration();cfg.setMapUnderscoreToCamelCase(true);cfg.addMapper(AgentOutboxRelayMapper.class);
        var ds=new DriverManagerDataSource(System.getenv("CYF_SKILL_MYSQL_URL").replace("/?","/"+db+"?"),"root","");
        var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(cfg);
        try { var relay=new SqlSessionTemplate(Objects.requireNonNull(factory.getObject())).getMapper(AgentOutboxRelayMapper.class);
            var scope=new cn.jia.core.tenant.WorkerScope(cn.jia.core.tenant.TenantId.of("0"),
                    cn.jia.core.tenant.DataDomainId.of("cyf"),"pool-a","deployment-a",7,11,13,"agent-command",17);
            var scopedRelay=new cn.jia.agent.dao.impl.AgentOutboxRelayDaoImpl(relay);
            assertTrue(scopedRelay.selectDueCandidates(scope,now,25).isEmpty());
            assertTrue(scopedRelay.selectCorruptCandidates(scope,now,25).isEmpty());
            assertTrue(scopedRelay.selectStaleCandidates(scope,now,25).isEmpty());
        } catch(Exception e) { throw new RuntimeException(e); }
    }
}
