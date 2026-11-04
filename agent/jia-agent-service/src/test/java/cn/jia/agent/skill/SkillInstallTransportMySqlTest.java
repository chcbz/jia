package cn.jia.agent.skill;
import cn.jia.agent.entity.skill.SkillChangeCommandEntity;
import cn.jia.agent.entity.skill.SkillChangeEntity;

import cn.jia.core.security.FixedTenantAdmissionPolicy;
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
    private cn.jia.agent.entity.skill.SkillChangeEntity change;
    private cn.jia.agent.entity.skill.SkillChangeCommandEntity command;
    private Map<String,Object> data;
    private byte[] wire;
    @BeforeEach void setup() throws Exception {
        if(!"true".equals(System.getenv("CYF_SKILL_MYSQL_ISOLATED"))) throw new IllegalStateException("Exclusive fixture required");
        String url=System.getenv("CYF_SKILL_MYSQL_URL");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,"root",""));db="skill_ws_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE "+db+" CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        var ds=new DriverManagerDataSource(url.replace("/?","/"+db+"?"),"root","");jdbc=new JdbcTemplate(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/agent-command-transport-schema.sql")).execute(ds);
        String management = new ClassPathResource("db/economy-skill-management-sm-c1.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8).replaceAll("(?m)^--.*$", "");
        for (String statement : management.split(";")) {
            if (List.of("agent_skill_change_command", "agent_skill_change", "agent_skill_managed_state")
                    .stream().anyMatch(table -> statement.strip().startsWith("CREATE TABLE " + table + " (")))
                jdbc.execute(statement);
        }
        var cfg=new org.apache.ibatis.session.Configuration();cfg.setMapUnderscoreToCamelCase(true);
        for(Class<?> c:List.of(AgentCommandTransportMapper.class,AgentCommandRecoveryMapper.class,AgentSkillInstallRecoveryMapper.class,AgentOutboxRelayMapper.class)) cfg.addMapper(c);
        var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(cfg);var sql=new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        commands=sql.getMapper(AgentCommandTransportMapper.class);mapper=sql.getMapper(AgentSkillInstallRecoveryMapper.class);
        var manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);
        var preview=new EconomyPreviewGate(new EconomyPreviewProperties(true,List.of(new EconomyPreviewProperties.AllowedScope("0","Client-A"))));
        writer=new SkillInstallCommandWriter(new AgentCommandTransportDaoImpl(commands));dispatch=mock(SkillInstallDispatchService.class);
        recovery=new SkillInstallRecoveryService(mapper,new AgentCommandRecoveryDaoImpl(sql.getMapper(AgentCommandRecoveryMapper.class)),dispatch,manager,new FixedTenantAdmissionPolicy("0", null));
        long now=System.currentTimeMillis();
        change=new cn.jia.agent.entity.skill.SkillChangeEntity().setChangeId("change-fixture")
                .setAgentId("agt_fixture").setOwnerJiacn("Owner-A");
        change.setTenantId("0");change.setClientId("Client-A");
        command=new cn.jia.agent.entity.skill.SkillChangeCommandEntity()
                .setCommandId("command-fixture").setMessageId("message-fixture");
        var target=new LinkedHashMap<String,Object>();
        target.putAll(Map.of("skillInstallationId","si_fixture","productVersionId","pv_fixture",
                "skillVersion","1.0.0","packageSha256","a".repeat(64),"byteLength","100","kind","INSTRUCTION"));
        target.put("manifestSha256",null);
        data=new LinkedHashMap<>();
        data.putAll(Map.of("schemaVersion",2,"commandId",command.getCommandId(),"skillChangeId",change.getChangeId(),
                "action","INSTALL","tenantId","0","clientId","Client-A","ownerJiacn","Owner-A",
                "agentId","agt_fixture","runtimeInstallationId","runtime","skillKey","repo-test"));
        data.putAll(Map.of("attempt",1,"fencingToken","1","deliveryEpoch","1","skillRevision","1",
                "target",target,"approvedPermissionsSha256","b".repeat(64),"desiredEnabled",true,
                "issuedAt",now,"commandSha256","c".repeat(64)));
        data.put("source",null);data.put("phase","PREPARE");data.put("commandType","SKILL_INSTALL");
        data.put("downloadPath","/internal/agent/skill-installations/si_fixture/package");
        wire=SkillManagementCodec.bytes(Map.of("protocolVersion",1,"requestId","request-fixture",
                "messageType","command.dispatch","messageId",command.getMessageId(),"data",data));

    }
    @AfterEach void cleanup() { if(admin!=null && db!=null) admin.execute("DROP DATABASE "+db); }
    void pending() {
        tx.execute(s->{
            writer.writeManagement(change,wire,command,data);
            jdbc.update("INSERT INTO agent_skill_managed_state(tenant_id,client_id,agent_id,skill_key,skill_revision) VALUES('0','Client-A','agt_fixture','repo-test',1)");
            jdbc.update("INSERT INTO agent_skill_change(tenant_id,client_id,change_id,agent_id,skill_key,actor_id,owner_jiacn,version,dto_json,permissions_json,runtime_installation_id,runtime_host_id,created_at,updated_at) VALUES('0','Client-A','change-fixture','agt_fixture','repo-test','actor','Owner-A',1,'{}','[]','runtime','host',1,1)");
            jdbc.update("INSERT INTO agent_skill_change_command(tenant_id,client_id,command_id,change_id,request_id,message_id,data_json,state) VALUES('0','Client-A',?,'change-fixture','request-fixture','message-fixture',?,'PENDING')", command.getCommandId(), SkillManagementCodec.json(data));
            return null;
        });
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
        pending();long now=System.currentTimeMillis();var d=mapper.candidates("0",null,now,now-60000).getFirst();
        assertEquals(1,mapper.claimDelivery(d,"owner-1",now+60000,now));
        assertEquals(0,mapper.claimDelivery(d,"owner-2",now+60000,now));
        d.setVersion(d.getVersion()+1).setStatus("CONSUMED");
        assertEquals(0,mapper.finishDelivery(d,"owner-2","SENT",null,"wrong lease",now));
        d.setClientId("client-a");assertEquals(0,mapper.finishDelivery(d,"owner-1","SENT",null,"wrong scope",now));
        assertEquals("owner-1",jdbc.queryForObject("SELECT lease_owner FROM agent_command_delivery",String.class));
    }
    @Test void failedOutboxWriteRollsBackOriginalDelivery() {
        assertThrows(RuntimeException.class,()->tx.execute(s->{writer.writeManagement(change,wire,command,data);throw new IllegalStateException("abort order");}));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_command_delivery",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_outbox_event",Integer.class));
        assertThrows(SkillMarketplaceException.class,()->writer.writeManagement(change,wire,command,data));
    }
    @Test void rabbitDiscoveryCannotClaimNativeWsCommands() {
        pending();long now=System.currentTimeMillis();
        // Mapper statement text is exercised against real MySQL, including REGEXP guards.
        var cfg=new org.apache.ibatis.session.Configuration();cfg.setMapUnderscoreToCamelCase(true);cfg.addMapper(AgentOutboxRelayMapper.class);
        var ds=new DriverManagerDataSource(System.getenv("CYF_SKILL_MYSQL_URL").replace("/?","/"+db+"?"),"root","");
        var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(cfg);
        try { var relay=new SqlSessionTemplate(Objects.requireNonNull(factory.getObject())).getMapper(AgentOutboxRelayMapper.class);
            assertTrue(relay.selectDueCandidates("0", now,25).isEmpty());assertTrue(relay.selectCorruptCandidates("0", now,25).isEmpty());assertTrue(relay.selectStaleCandidates("0", now,25).isEmpty());
        } catch(Exception e) { throw new RuntimeException(e); }
    }
}
