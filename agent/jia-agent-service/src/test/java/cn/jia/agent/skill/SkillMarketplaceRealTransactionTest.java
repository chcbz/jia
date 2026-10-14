package cn.jia.agent.skill;

import cn.jia.agent.config.*;
import cn.jia.agent.dao.*;
import cn.jia.agent.dao.impl.AgentCommandTransportDaoImpl;
import cn.jia.agent.entity.*;
import cn.jia.agent.mapper.AgentCommandTransportMapper;
import cn.jia.agent.mapper.AgentCommandRecoveryMapper;
import cn.jia.agent.mapper.AgentSkillInstallRecoveryMapper;
import cn.jia.agent.dao.impl.AgentCommandRecoveryDaoImpl;
import cn.jia.agent.hosting.*;
import cn.jia.agent.service.*;
import cn.jia.agent.security.AgentRuntimeAuthentication;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.impl.AgentCommandTransportWriterImpl;
import cn.jia.economy.config.*;
import cn.jia.economy.config.skillseed.PlatformSkillPackageCatalog;
import cn.jia.economy.entity.*;
import cn.jia.economy.entity.skill.*;
import cn.jia.economy.mapper.*;
import cn.jia.economy.service.impl.EconomyPostingServiceImpl;
import cn.jia.oauth.entity.OauthApiKeyEntity;
import cn.jia.oauth.service.ApiKeyService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import org.mybatis.spring.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real SQL/REQUIRED transactions, actual W02 posting and actual command/outbox writer.
 * No sockets, broker, runner, or provisioning. Runtime/owner/key adapters are explicit trusted fixtures.
 * Paid terminal selectors require W06's centralized CAPTURE_SKILL/REFUND_SKILL primitive integration. */
public class SkillMarketplaceRealTransactionTest {
    private static final HostingRentHttp.Actor ACTOR=new HostingRentHttp.Actor("payer-sub","0","Client-A","Tenant-A");
    private static final String AGENT="agt_00000000000000000000000000000001";
    private static final String TRANSPORT_SCHEMA="db/agent-command-transport-schema.sql";
    private static final String MYSQL_AUDIT_UPDATE_TRIGGER="""
            CREATE TRIGGER trg_command_operation_audit_no_update
            BEFORE UPDATE ON agent_command_operation_audit
            FOR EACH ROW
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'D09: operation audit rows are immutable after insert';
            """.strip();
    private static final String MYSQL_AUDIT_DELETE_TRIGGER="""
            CREATE TRIGGER trg_command_operation_audit_no_delete
            BEFORE DELETE ON agent_command_operation_audit
            FOR EACH ROW
            SIGNAL SQLSTATE '45000'
                SET MESSAGE_TEXT = 'D09: physical delete of operation audit rows is forbidden';
            """.strip();
    private static final String H2_AUDIT_UPDATE_TRIGGER="""
            CREATE TRIGGER trg_command_operation_audit_no_update
            BEFORE UPDATE ON agent_command_operation_audit
            FOR EACH ROW
            CALL "cn.jia.agent.skill.SkillAuditMutationRejectTrigger";
            """.strip();
    private static final String H2_AUDIT_DELETE_TRIGGER="""
            CREATE TRIGGER trg_command_operation_audit_no_delete
            BEFORE DELETE ON agent_command_operation_audit
            FOR EACH ROW
            CALL "cn.jia.agent.skill.SkillAuditMutationRejectTrigger";
            """.strip();
    private static final String MYSQL_DISPOSITION_GUARD=
            "disposition_guard           TINYINT GENERATED ALWAYS AS (IF(outcome_state='PENDING',1,NULL)) STORED";
    private static final String H2_DISPOSITION_GUARD=
            "disposition_guard           TINYINT GENERATED ALWAYS AS (CASE WHEN outcome_state='PENDING' THEN 1 ELSE NULL END)";
    private static final String MYSQL_REDRIVE_GUARD=
            "redrive_guard               TINYINT GENERATED ALWAYS AS (IF(settlement_state IN ('SOURCE_REQUEUED','NOT_ACQUIRED'),NULL,1)) STORED";
    private static final String H2_REDRIVE_GUARD=
            "redrive_guard               TINYINT GENERATED ALWAYS AS (CASE WHEN settlement_state IN ('SOURCE_REQUEUED','NOT_ACQUIRED') THEN NULL ELSE 1 END)";
    @TempDir Path packageRoot;
    private Connection schemaConnection;
    private JdbcTemplate jdbc;
    private EconomySkillMarketplaceMapper market;
    private EconomySkillApplicationMapper app;
    private SkillMarketplaceService service;
    protected SkillInstallResultService results;
    private SkillInstallRecoveryService recovery;
    private AgentSkillInstallRecoveryMapper recoveryMapper;
    private AgentCommandTransportDao commandsForResults;
    private org.springframework.transaction.PlatformTransactionManager transactionManagerForResults;
    protected AgentRuntimeAuthenticationService authentication;
    private SkillAgentVersions versions;
    protected AgentRuntimeEntity runtime;
    private OauthApiKeyEntity key;
    private TransactionTemplate tx;
    private SkillManagedCredentials credentials;
    private EconomySkillCredentialMapper credentialMapper;
    private ApiKeyService keys;
    private AgentManagedSessionLookup sessions;
    private AgentService agents;
    private HostingRentOwnerResolver owner;
    private SkillInstallDispatchService dispatch;
    private EconomyLedgerMapper ledger;
    private SkillInstallAckService nativeAcks;
    private AgentCommandRecoveryDao ackCommands;


    @BeforeEach void setup() throws Exception {
        var ds=fixtureDataSource();
        jdbc=new JdbcTemplate(ds); var manager=new DataSourceTransactionManager(ds); tx=new TransactionTemplate(manager);
        // H2 2.4.240 constant-IN expressions retain their DDL SessionLocal comparator.
        // Keep only that schema session alive; business transactions still use independent connections.
        schemaConnection=ds.getConnection();
        for(String resource:List.of("db/economy-v0-foundation.sql","db/economy-v0-skill-marketplace.sql","db/economy-v0-skill-application.sql","db/agent-command-transport-schema.sql")) schema(resource);
        var config=new org.apache.ibatis.session.Configuration();config.setMapUnderscoreToCamelCase(true);
        if(!mysqlFixture()) config.addInterceptor(new SkillExecutionMapperRealTransactionTest.MySqlBinaryCastAdapter());
        for(Class<?> type:List.of(EconomySkillMarketplaceMapper.class,EconomySkillApplicationMapper.class,EconomySkillCredentialMapper.class,EconomyLedgerMapper.class,AgentCommandTransportMapper.class,AgentCommandRecoveryMapper.class,AgentSkillInstallRecoveryMapper.class)) config.addMapper(type);
        var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);
        var sql=new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        market=spy(sql.getMapper(EconomySkillMarketplaceMapper.class));app=sql.getMapper(EconomySkillApplicationMapper.class);
        ledger=sql.getMapper(EconomyLedgerMapper.class);
        var gate=new EconomyPreviewGate(new EconomyPreviewProperties(true,List.of(new EconomyPreviewProperties.AllowedScope(ACTOR.tenantId(),ACTOR.clientId()))));
        var posting=new EconomyPostingServiceImpl(ledger,manager,gate);
        agents=mock(AgentService.class); owner=mock(HostingRentOwnerResolver.class); var runtimes=mock(AgentRuntimeDao.class);
        runtime=new AgentRuntimeEntity().setAgentId(AGENT).setOwnerJiacn(ACTOR.ownerJiacn()).setBindingId(7L).setTokenHash("registration-1").setStatus("online");
        runtime.setTenantId(ACTOR.tenantId());
        runtime.setClientId(ACTOR.clientId());
        runtime.setRuntimeInstallationId("rti_"+"a".repeat(32));
        runtime.setRuntimeHostId("host-1");runtime.setRuntimeInstanceId("runtime-1");runtime.setRuntimeSessionGeneration(1L);
        when(runtimes.findByAgentIdForUpdate(AGENT)).thenAnswer(i->runtime);
        when(runtimes.findInScope(ACTOR.tenantId(),ACTOR.clientId(),AGENT)).thenAnswer(i->runtime);
        authentication=mock(AgentRuntimeAuthenticationService.class);
        when(authentication.currentRegisteredProof(ACTOR.tenantId(),ACTOR.clientId(),AGENT)).thenAnswer(i->proof());
        // Explicit trusted transport adapter only. SQL, REQUIRED nesting, money and outbox are real.
        // Persistent fence/auth concurrency is separately exercised by AgentRuntimePersistentSessionFenceTest.
        when(authentication.withFence(any(),eq(false),any())).thenAnswer(i->{
            AgentRuntimeAuthenticationService.Proof supplied=i.getArgument(0);
            if(!proof().equals(supplied)) throw new SkillMarketplaceException(403,"SKILL_DELIVERY_FENCED");
            java.util.function.Supplier<?> action=i.getArgument(2);
            return tx.execute(status->action.get());
        });
        when(owner.requireOwner(any())).thenAnswer(inv -> {
            HostingRentHttp.Actor requested = inv.getArgument(0);
            if (!ACTOR.equals(requested)) throw new cn.jia.agent.hosting.HostingRentApplicationException(
                    403, "HOSTING_RENT_OWNER_UNPROVEN");
            return ACTOR.ownerJiacn();
        });
        var dto=new AgentRuntimeDTO();dto.setAgentId(AGENT);dto.setStatus("online");dto.setSystemAgent(false);
        when(agents.requireApiKeyOwnedAgentForUpdate(ACTOR.clientId(),ACTOR.ownerJiacn(),AGENT)).thenReturn(dto);
        versions=new SkillAgentVersions(app,runtimes,provider(agents),owner,manager,true);
        var commands=new AgentCommandTransportDaoImpl(sql.getMapper(AgentCommandTransportMapper.class));
        var writer=new SkillInstallCommandWriter(commands,gate);
        var packages=new SkillPackages(packageRoot); keys=mock(ApiKeyService.class);
        key=new OauthApiKeyEntity();key.setId("key-1");key.setJiacn(ACTOR.ownerJiacn());key.setClientId(ACTOR.clientId());key.setTenantId(ACTOR.tenantId());key.setStatus(1);
        when(keys.get("key-1")).thenReturn(key);
        credentialMapper=spy(sql.getMapper(EconomySkillCredentialMapper.class));
        doReturn(List.of()).when(credentialMapper).hostingCandidates(anyString(),anyString(),anyString());
        credentialMapper.insert("sc_1","key-1",AGENT,7L,ACTOR.tenantId(),ACTOR.clientId(),1L);
        credentials=new SkillManagedCredentials(credentialMapper,app,mock(EconomyHostingRentMapper.class),versions,runtimes,provider(keys),gate,manager,true,false);
        sessions=mock(AgentManagedSessionLookup.class);
        when(sessions.isReady(eq(ACTOR.tenantId()),eq(ACTOR.clientId()),eq(AGENT),any())).thenReturn(true);
        service=new SkillMarketplaceService(market,app,ledger,posting,gate,versions,runtimes,provider(writer),provider(packages),provider(authentication),provider(sessions),manager);
        dispatch=new SkillInstallDispatchService(app,market,service,versions,authentication,commands,provider(sessions),manager);
        recoveryMapper=sql.getMapper(AgentSkillInstallRecoveryMapper.class);
        recovery=new SkillInstallRecoveryService(recoveryMapper,new AgentCommandRecoveryDaoImpl(sql.getMapper(AgentCommandRecoveryMapper.class)),gate,dispatch,manager);
        ackCommands=spy(new AgentCommandRecoveryDaoImpl(sql.getMapper(AgentCommandRecoveryMapper.class)));
        nativeAcks=new SkillInstallAckService(app,market,ackCommands,recoveryMapper,authentication,gate,service,manager);
        commandsForResults=commands;transactionManagerForResults=manager;
        results=new SkillInstallResultService(market,app,commands,service,versions,authentication,provider(packages),manager);
        for(var p:new PlatformSkillPackageCatalog().products()) {
            market.insertProduct(new SkillProductEntity().setProductId(p.productId()).setSellerType("SYSTEM").setSellerId("SKILL_STORE")
                    .setName(p.name()).setDescription(p.description()).setStatus("DRAFT").setVersion(1L)
                    .setTenantId(ACTOR.tenantId()).setClientId(ACTOR.clientId()).setCreateTime(1L).setUpdateTime(1L));
            market.insertProductVersion(new SkillProductVersionEntity().setProductVersionId(p.productVersionId()).setProductId(p.productId())
                    .setVersionSequence(1L).setSkillKey(p.skillKey()).setSkillVersion(p.skillVersion()).setPriceMicro(p.priceMicro())
                    .setPackageSha256(p.packageSha256()).setPackageSize(p.packageSize()).setApprovedPermissionsManifest(p.approvedPermissionsManifest())
                    .setApprovedPermissionsSha256(p.approvedPermissionsSha256()).setDeploymentRestriction(p.deploymentRestriction()).setReviewStatus("APPROVED")
                    .setTenantId(ACTOR.tenantId()).setClientId(ACTOR.clientId()).setCreateTime(1L));
            market.publishProductVersion(ACTOR.tenantId(),ACTOR.clientId(),p.productId(),p.productVersionId(),1,2);
        }
        ledger.insertAccountIfAbsent(new EconomyAccountEntity().setAccountId("wallet").setOwnerType("USER").setOwnerId(ACTOR.actorId()).setPurpose("AVAILABLE")
                .setCurrency("SILVER").setBalanceMicro(100000000L).setAllowNegative(0).setStatus("ACTIVE").setVersion(0L)
                .setTenantId(ACTOR.tenantId()).setClientId(ACTOR.clientId()).setCreateTime(1L).setUpdateTime(1L));
    }
    @AfterEach void tearDown() throws SQLException {
        try {
            if(jdbc!=null && !mysqlFixture()) jdbc.execute("DROP ALL OBJECTS"); // This fixture's UUID-owned H2 only.
        } finally {
            if(schemaConnection!=null) schemaConnection.close();
            cleanupFixture();
        }
    }
    protected DriverManagerDataSource fixtureDataSource() {
        return new DriverManagerDataSource("jdbc:h2:mem:w09_"+UUID.randomUUID()+";MODE=MYSQL;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE;LOCK_TIMEOUT=10000","sa","");
    }
    protected boolean mysqlFixture() { return false; }
    protected void cleanupFixture() { }

    protected Map<String,Object> acceptNativeResult(AgentRuntimeAuthenticationService.Proof proof,Map<String,Object> body) {
        var wire=new LinkedHashMap<>(body);wire.put("tenantId",proof.scope().tenantId());wire.put("clientId",proof.scope().clientId());
        wire.put("hostId",proof.hostId());wire.put("sessionGeneration",proof.sessionGeneration());
        return results.acceptNative(proof,wire);
    }
    private AgentCommandAckResult nativeAck(SkillInstallationEntity i,String status,Long deliveryVersion) {
        var generic=mock(AgentCommandAckService.class);
        var boundary=new cn.jia.agent.service.impl.AgentRuntimeV1ServiceImpl(mock(AgentRuntimeV1InstallationDao.class),
                mock(AgentIdentityService.class),mock(AgentIdentityRegistryDao.class),provider(generic),authentication,provider(nativeAcks));
        when(authentication.verify(any())).thenAnswer(call->{
            cn.jia.agent.security.AgentRuntimeAuthenticationFilter.SessionHeaders h=call.getArgument(0);
            if(h.sessionGeneration()!=proof().sessionGeneration() || !h.installationId().equals(proof().installationId())
                    || !h.runtimeInstanceId().equals(proof().scope().runtimeInstanceId())) throw new IllegalArgumentException("STALE_NATIVE_FENCE");
            return proof();
        });
        var p=proof();
        var request=new AgentRuntimeV1AckRequest(i.getMessageId(),i.getOrderId(),i.getCommandId(),i.getOrderId(),null,
                ACTOR.tenantId(),ACTOR.clientId(),AGENT,null,null,status,p.installationId(),p.hostId(),p.scope().runtimeInstanceId(),p.sessionGeneration(),deliveryVersion);
        var result=boundary.acknowledge("rts1_fixture_only",i.getMessageId(),request,System.currentTimeMillis());
        verifyNoInteractions(generic); return result;
    }
    private void drainNative() {
        when(sessions.dispatch(anyString(),anyString(),anyString(),any(),any())).thenReturn(AgentRawCommandDispatchResult.sent(1,1));
        recovery.recover();
    }
    @Test void nativeSkillAckReceivedStartedDownloadAndRealResultActivateWithoutBroker() {
        service.purchase(ACTOR,uuid(),purchaseBody("spv_repo_test_1_0_0"),false);var i=installation();drainNative();
        byte[] wire=jdbc.queryForObject("SELECT wire_payload FROM agent_outbox_event",byte[].class);
        var received=nativeAck(i,"RECEIVED",null);assertEquals(AgentCommandAckResult.Kind.ADVANCED,received.kind());
        assertEquals(AgentCommandAckResult.Kind.PRIOR,nativeAck(i,"RECEIVED",received.deliveryVersion()).kind());
        var started=nativeAck(i,"STARTED",received.deliveryVersion());
        assertEquals(i.getPackageSize().longValue(),results.packageBytes(proof(),i.getInstallationId()).length);
        assertEquals("INSTALLING",service.order(ACTOR,i.getOrderId()).get("status"));
        nativeAck(i,"SUCCEEDED",started.deliveryVersion());
        assertEquals("INSTALLING",service.order(ACTOR,i.getOrderId()).get("status")); // ACK is not installation proof.
        var body=result(i,"SUCCEEDED",null);
        body.put("sourceAgentId",AGENT);body.put("agentId",AGENT);body.put("runtimeInstanceId","original-executing-boot");
        var receipt=acceptNativeResult(proof(),body);
        assertEquals("ACCEPTED",receipt.get("receiptStatus"));assertEquals("ACTIVE",service.order(ACTOR,i.getOrderId()).get("status"));
        assertEquals("ACTIVE",service.entitlements(ACTOR,AGENT).getFirst().get("status"));
        runtime.setRuntimeSessionGeneration(2L).setRuntimeInstanceId("authorized-reconnect");
        assertEquals(receipt,acceptNativeResult(proof(),body));
        var unknown=new LinkedHashMap<>(body);unknown.put("unknownBusiness",1);
        assertThrows(RuntimeException.class,()->acceptNativeResult(proof(),unknown));
        assertArrayEquals(wire,jdbc.queryForObject("SELECT wire_payload FROM agent_outbox_event",byte[].class));
        assertEquals("NONE",jdbc.queryForObject("SELECT publisher_confirm_status FROM agent_outbox_event",String.class));
        assertNull(jdbc.queryForObject("SELECT confirmed_at FROM agent_outbox_event",Long.class));
        assertEquals(0,count("agent_consumer_inbox"));
    }
    @Test void nativeSkillAckRacingSenderSettlesOnlyOriginalLeaseAndCannotBeOverwritten() {
        service.purchase(ACTOR,uuid(),purchaseBody("spv_repo_test_1_0_0"),false);var i=installation();
        when(sessions.dispatch(anyString(),anyString(),anyString(),any(),any())).thenAnswer(call->{
            assertEquals("CONSUMED",jdbc.queryForObject("SELECT status FROM agent_command_delivery",String.class));
            nativeAck(i,"RECEIVED",null);nativeAck(i,"STARTED",null);
            assertTrue(results.packageBytes(proof(),i.getInstallationId()).length>0);
            return AgentRawCommandDispatchResult.sent(1,1);
        });
        recovery.recover();assertEquals("STARTED",jdbc.queryForObject("SELECT status FROM agent_command_delivery",String.class));
        assertEquals("SENT",jdbc.queryForObject("SELECT status FROM agent_outbox_event",String.class));
        assertNull(jdbc.queryForObject("SELECT lease_owner FROM agent_outbox_event",String.class));
        assertEquals("NONE",jdbc.queryForObject("SELECT publisher_confirm_status FROM agent_outbox_event",String.class));
    }
    @Test void nativeSkillAckRejectsWrongMessageOrderAgentAndStaleProofWithoutMutation() {
        service.purchase(ACTOR,uuid(),purchaseBody("spv_repo_test_1_0_0"),false);var i=installation();drainNative();
        long version=jdbc.queryForObject("SELECT version FROM agent_command_delivery",Long.class);
        for(String field:List.of("message","order","agent")) {
            var a=new AgentCommandAck(ACTOR.tenantId(),ACTOR.clientId(),field.equals("agent")?"foreign":AGENT,
                    "ack_fixture",field.equals("message")?"foreign":i.getMessageId(),i.getCommandId(),field.equals("order")?"foreign":i.getOrderId(),null,"RECEIVED",System.currentTimeMillis());
            assertThrows(RuntimeException.class,()->nativeAcks.acknowledgeIfSkill(proof(),a,System.currentTimeMillis()));
        }
        var stale=proof();runtime.setRuntimeSessionGeneration(2L);
        var a=new AgentCommandAck(ACTOR.tenantId(),ACTOR.clientId(),AGENT,"ack_fixture",i.getMessageId(),i.getCommandId(),i.getOrderId(),null,"RECEIVED",System.currentTimeMillis());
        assertThrows(RuntimeException.class,()->nativeAcks.acknowledgeIfSkill(stale,a,System.currentTimeMillis()));
        assertEquals(version,jdbc.queryForObject("SELECT version FROM agent_command_delivery",Long.class));
        assertEquals("SENT",jdbc.queryForObject("SELECT status FROM agent_command_delivery",String.class));
    }
    @Test void nativeSkillAckRejectsSkipAndCasLossRollsBackRacingOutbox() {
        service.purchase(ACTOR,uuid(),purchaseBody("spv_repo_test_1_0_0"),false);var i=installation();drainNative();
        assertThrows(RuntimeException.class,()->nativeAck(i,"STARTED",null));
        doReturn(0).when(ackCommands).advanceAck(any(),anyString(),any(),anyLong());
        when(sessions.dispatch(anyString(),anyString(),anyString(),any(),any())).thenAnswer(call->{
            assertThrows(RuntimeException.class,()->nativeAck(i,"RECEIVED",null));
            assertEquals("CLAIMED",jdbc.queryForObject("SELECT status FROM agent_outbox_event",String.class));
            return AgentRawCommandDispatchResult.sent(1,1);
        });
        jdbc.update("UPDATE agent_command_delivery SET update_time=?,next_retry_at=NULL",System.currentTimeMillis()-70000);
        recovery.recover();assertEquals("SENT",jdbc.queryForObject("SELECT status FROM agent_command_delivery",String.class));
    }
    @Test void transportGeneratedGuardAdapterRejectsDefinitionDrift() throws Exception {
        String source=new ClassPathResource(TRANSPORT_SCHEMA).getContentAsString(StandardCharsets.UTF_8);
        String adapted=adaptTransportGeneratedColumns(TRANSPORT_SCHEMA,source);
        assertEquals(source,adapted.replace(H2_DISPOSITION_GUARD,MYSQL_DISPOSITION_GUARD)
                .replace(H2_REDRIVE_GUARD,MYSQL_REDRIVE_GUARD));
        assertEquals(source,adaptTransportGeneratedColumns("db/unrelated.sql",source));
        assertThrows(IllegalStateException.class,()->adaptTransportGeneratedColumns(TRANSPORT_SCHEMA,
                source.replace(MYSQL_DISPOSITION_GUARD,"disposition_guard TINYINT")));
        assertThrows(IllegalStateException.class,()->adaptTransportGeneratedColumns(TRANSPORT_SCHEMA,
                source+"\n"+MYSQL_REDRIVE_GUARD));
    }
    @Test void transportGeneratedGuardsPreserveExpressionsAndNullUniqueness() {
        long delivery=0;
        for(String outcome:List.of("PENDING","SUCCEEDED","FAILED")) {
            for(String settlement:List.of("PENDING","SOURCE_ACKED","SOURCE_REQUEUED","NOT_ACQUIRED","UNKNOWN")) {
                long group=++delivery;
                String operation="guard-"+group;
                insertRedriveGuardRow(operation,group,outcome,settlement);
                Map<String,Object> row=jdbc.queryForMap(
                        "SELECT disposition_guard,redrive_guard FROM agent_command_redrive_operation WHERE operation_id=?",operation);
                if(outcome.equals("PENDING")) assertEquals(1,((Number)row.get("disposition_guard")).intValue());
                else assertNull(row.get("disposition_guard"));
                boolean released=Set.of("SOURCE_REQUEUED","NOT_ACQUIRED").contains(settlement);
                if(released) {
                    assertNull(row.get("redrive_guard"));
                    insertRedriveGuardRow(operation+"-null",group,outcome,settlement);
                    insertRedriveGuardRow(operation+"-active",group,"PENDING","PENDING");
                    DataAccessException update=assertThrows(DataAccessException.class,()->jdbc.update(
                            "UPDATE agent_command_redrive_operation SET settlement_state='PENDING' WHERE operation_id=?",operation));
                    assertEquals("23505",((SQLException)update.getMostSpecificCause()).getSQLState());
                    assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM agent_command_redrive_operation "
                            +"WHERE delivery_id=? AND redrive_guard IS NULL",Integer.class,group));
                } else {
                    assertEquals(1,((Number)row.get("redrive_guard")).intValue());
                    DataAccessException duplicate=assertThrows(DataAccessException.class,
                            ()->insertRedriveGuardRow(operation+"-duplicate",group,outcome,settlement));
                    assertEquals("23505",((SQLException)duplicate.getMostSpecificCause()).getSQLState());
                }
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_command_redrive_operation "
                        +"WHERE delivery_id=? AND redrive_guard=1",Integer.class,group));
            }
        }
    }
    private void insertRedriveGuardRow(String operation,long delivery,String outcome,String settlement) {
        jdbc.update("""
                INSERT INTO agent_command_redrive_operation
                  (operation_id,delivery_id,task_id,target_agent_id,command_id,source_event_id,
                   source_message_id,source_attempt,wire_hash,requester_id,reason,ticket_reference,
                   outcome_state,settlement_state,requested_at,tenant_id,client_id,create_time,update_time)
                VALUES(?,?,'guard-task',?,'guard-command','guard-event','guard-message',1,?,
                       'requester','fixture guard check','ticket-guard',?,?,1,?,?,1,1)
                """,operation,delivery,AGENT,new byte[32],outcome,settlement,ACTOR.tenantId(),ACTOR.clientId());
    }
    @Test void operationAuditRowsRejectUpdateAndDeleteAndRemainUnchanged() {
        jdbc.update("""
                INSERT INTO agent_command_operation_audit
                  (operation_id,phase,operation_type,tenant_id,client_id,task_id,target_agent_id,
                   source_message_id,delivery_id,requester_id,reason,ticket_reference,requested_at,
                   outcome,created_by,created_at)
                VALUES('audit-op-1','REQUEST','BROKER_REDRIVE',?,?,?,?,'message-1',1,
                       'requester','fixture audit reason','ticket-1',1,'REQUESTED','creator',1)
                """,ACTOR.tenantId(),ACTOR.clientId(),"task-1",AGENT);
        Map<String,Object> before=jdbc.queryForMap(
                "SELECT * FROM agent_command_operation_audit WHERE operation_id='audit-op-1'");

        DataAccessException update=assertThrows(DataAccessException.class,()->jdbc.update(
                "UPDATE agent_command_operation_audit SET outcome='FAILED' WHERE operation_id='audit-op-1'"));
        assertTrue(update.getMostSpecificCause().getMessage().contains(
                "D09: operation audit rows are immutable after insert"));
        DataAccessException delete=assertThrows(DataAccessException.class,()->jdbc.update(
                "DELETE FROM agent_command_operation_audit WHERE operation_id='audit-op-1'"));
        assertTrue(delete.getMostSpecificCause().getMessage().contains(
                "D09: physical delete of operation audit rows is forbidden"));

        assertEquals(before,jdbc.queryForMap(
                "SELECT * FROM agent_command_operation_audit WHERE operation_id='audit-op-1'"));
        assertEquals(1,jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_command_operation_audit WHERE operation_id='audit-op-1'",Integer.class));
    }
    @Test void dynamicallyStoredApprovedPackageUsesRealPurchaseAndInstallTransactions() throws Exception {
        byte[] manifest="""
                {"schemaVersion":1,"skillKey":"pdf-export","skillVersion":"1.0.0","title":"PDF","description":"export",
                 "runner":"native-model","entrypoint":"pdf.export",
                 "parametersSchema":{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object"},
                 "inputs":{"minItems":0,"maxItems":0,"acceptedMimeTypes":[]},
                 "outputs":{"dataSchema":null,"mimeTypes":["application/pdf"],"multiple":false,"artifactsRequired":true},
                 "requirements":{}}
                """.getBytes(StandardCharsets.UTF_8);
        var buffer=new java.io.ByteArrayOutputStream();
        try(var zip=new java.util.zip.ZipOutputStream(buffer)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("SKILL.md"));
            zip.write("---\nname: pdf-export\ndescription: export pdf\nmetadata:\n  version: \"1.0.0\"\n---\nExport a PDF.\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();zip.putNextEntry(new java.util.zip.ZipEntry("manifest.json"));zip.write(manifest);zip.closeEntry();
        }
        byte[] packageBytes=buffer.toByteArray();new SkillPackageStore(packageRoot).publish(packageBytes);
        var seed=new PlatformSkillPackageCatalog().products().getFirst();
        market.insertProduct(new SkillProductEntity().setProductId("sp_dynamic_pdf").setSellerType("SYSTEM").setSellerId("SKILL_STORE")
                .setName("Dynamic PDF").setDescription("not classpath").setStatus("DRAFT").setVersion(1L)
                .setTenantId(ACTOR.tenantId()).setClientId(ACTOR.clientId()).setCreateTime(1L).setUpdateTime(1L));
        market.insertProductVersion(new SkillProductVersionEntity().setProductVersionId("spv_dynamic_pdf_1").setProductId("sp_dynamic_pdf")
                .setVersionSequence(1L).setSkillKey("pdf-export").setSkillVersion("1.0.0").setPriceMicro(0L)
                .setPackageSha256(SkillPackageStore.sha256(packageBytes)).setPackageSize((long)packageBytes.length)
                .setApprovedPermissionsManifest(seed.approvedPermissionsManifest()).setApprovedPermissionsSha256(seed.approvedPermissionsSha256())
                .setDeploymentRestriction("NONE").setReviewStatus("APPROVED").setTenantId(ACTOR.tenantId()).setClientId(ACTOR.clientId()).setCreateTime(1L));
        assertEquals(1,market.publishProductVersion(ACTOR.tenantId(),ACTOR.clientId(),"sp_dynamic_pdf","spv_dynamic_pdf_1",1,2));
        var body=purchaseBody("spv_dynamic_pdf_1");String idem=uuid();var receipt=service.purchase(ACTOR,idem,body,false);
        var i=installation();sent(i);assertArrayEquals(packageBytes,results.packageBytes(proof(),i.getInstallationId()));
        // Exact approved lookup is scope-isolated and does not depend on the product's current pointer.
        assertNull(market.selectApprovedProductVersion(ACTOR.tenantId(),"other-client","spv_dynamic_pdf_1"));
        jdbc.update("UPDATE economy_skill_product SET current_product_version_id='a-later-version',version=3 WHERE product_id='sp_dynamic_pdf'");
        assertArrayEquals(packageBytes,results.packageBytes(proof(),i.getInstallationId()));
        var success=result(i,"SUCCEEDED",null);
        assertEquals("SKILL_MANIFEST_BINDING_MISMATCH",assertThrows(SkillMarketplaceException.class,()->results.accept(proof(),success)).code());
        assertEquals(0,count("economy_skill_result_receipt"));assertEquals("INSTALLING",installation().getStatus());
        success.put("manifestSha256",HexFormat.of().formatHex(SkillPackageStore.sha256(manifest)));
        results.accept(proof(),success);assertEquals(1,count("economy_skill_result_receipt"));
        assertEquals(1,app.successfulReceipts(ACTOR.tenantId(),ACTOR.clientId(),i.getInstallationId()));
        assertEquals(0,app.successfulReceipts(ACTOR.tenantId(),ACTOR.clientId(),i.getCommandId()));
        assertEquals(0,app.successfulReceipts(ACTOR.tenantId(),"client-a",i.getInstallationId()));
        assertEquals(0,app.successfulReceipts(ACTOR.tenantId(),ACTOR.clientId(),i.getInstallationId().toUpperCase(Locale.ROOT)));
        var identities=mock(AgentIdentityService.class);
        when(identities.requireCanonicalAgentIdInScope(ACTOR.tenantId(),ACTOR.clientId(),ACTOR.ownerJiacn(),AGENT)).thenReturn(AGENT);
        var descriptors=new InstalledSkillDescriptorService(identities,market,app,provider(new SkillPackages(packageRoot)));
        var descriptor=descriptors.read(new SkillCapabilityLookup.Scope(ACTOR.tenantId(),ACTOR.clientId(),ACTOR.ownerJiacn(),AGENT)).skills().getFirst();
        assertTrue(descriptor.verified()); assertEquals(success.get("manifestSha256"),descriptor.manifestSha256());
        assertEquals("pdf-export",descriptor.skillKey());
        var tampered=new LinkedHashMap<>(success);tampered.put("manifestSha256","0".repeat(64));
        assertThrows(SkillMarketplaceException.class,()->results.accept(proof(),tampered));
        // New service/process with cold package cache still replays the immutable receipt after object retirement.
        java.nio.file.Files.delete(packageRoot.resolve(HexFormat.of().formatHex(SkillPackageStore.sha256(packageBytes))+".zip"));
        var cold=new SkillInstallResultService(market,app,commandsForResults,service,versions,authentication,
                provider(new SkillPackages(packageRoot)),transactionManagerForResults);
        assertEquals(results.accept(proof(),success),cold.accept(proof(),success));
        assertEquals("ACTIVE",service.order(ACTOR,i.getOrderId()).get("status"));
        assertEquals(receipt,service.purchase(ACTOR,idem,body,false));assertEquals(100000000L,wallet());
    }
    @Test void paidPurchaseInstallsCapturesAndReplaysOriginalReceipt() {
        var body=purchaseBody("spv_repo_test_1_0_0");String idem=uuid();var receipt=service.purchase(ACTOR,idem,body,false);
        assertEquals("FUNDS_HELD",receipt.get("status")); assertEquals(70000000L,wallet());
        assertEquals(1,count("agent_command_delivery"));assertEquals(1,count("agent_outbox_event"));assertEquals(1,count("economy_skill_entitlement"));
        assertEquals("INSTALLING",service.order(ACTOR,(String)receipt.get("orderId")).get("status"));
        var i=installation(); sent(i);assertEquals(i.getPackageSize().longValue(),results.packageBytes(proof(),i.getInstallationId()).length);
        var success=result(i,"SUCCEEDED",null);results.accept(proof(),success);
        results.accept(proof(),success);
        assertEquals("ACTIVE",service.order(ACTOR,i.getOrderId()).get("status"));assertEquals(70000000L,wallet());
        assertEquals(2,count("economy_transaction"));assertEquals(0L,jdbc.queryForObject("SELECT SUM(signed_amount_micro) FROM economy_entry",Long.class));
        assertEquals(receipt,service.purchase(ACTOR,idem,body,false));
        assertEquals("ACTIVE",service.entitlements(ACTOR,AGENT).getFirst().get("status"));
        assertThrows(SkillMarketplaceException.class,()->results.packageBytes(proof(),i.getInstallationId()));
    }
    @Test void nativeWsPurchaseDrainsOriginalCommittedBytesWithoutRabbitAndActivatesOnlyFromTrustedResult() {
        var body=purchaseBody("spv_repo_test_1_0_0");service.purchase(ACTOR,uuid(),body,false);var i=installation();
        assertTrue(service.available(ACTOR));assertFalse(service.available(new HostingRentHttp.Actor("payer-sub","0","client-a","Tenant-A")));
        byte[] original=jdbc.queryForObject("SELECT wire_payload FROM agent_outbox_event",byte[].class);
        assertEquals(SkillInstallCommandWriter.DESTINATION,jdbc.queryForObject("SELECT destination FROM agent_outbox_event",String.class));
        when(sessions.dispatch(eq(ACTOR.tenantId()),eq(ACTOR.clientId()),eq(AGENT),any(),any())).thenAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertArrayEquals(original,call.getArgument(4));
            assertEquals("CONSUMED",jdbc.queryForObject("SELECT status FROM agent_command_delivery",String.class));
            assertEquals("CLAIMED",jdbc.queryForObject("SELECT status FROM agent_outbox_event",String.class));
            return AgentRawCommandDispatchResult.sent(1,1);
        });
        recovery.recover();
        assertEquals("SENT",jdbc.queryForObject("SELECT status FROM agent_command_delivery",String.class));
        assertEquals("SENT",jdbc.queryForObject("SELECT status FROM agent_outbox_event",String.class));
        assertEquals("NONE",jdbc.queryForObject("SELECT publisher_confirm_status FROM agent_outbox_event",String.class));
        assertEquals(0,count("agent_consumer_inbox"));
        assertEquals("INSTALLING",service.order(ACTOR,i.getOrderId()).get("status"));
        results.accept(proof(),result(i,"SUCCEEDED",null));
        assertEquals("ACTIVE",service.order(ACTOR,i.getOrderId()).get("status"));
        assertTrue(recoveryMapper.candidates(System.currentTimeMillis()+120000,System.currentTimeMillis()+60000).isEmpty());
    }
    @Test void nativeWsUnknownSendRecoversSameWireAndNeverRefundsOrMintsAnotherCommand() {
        service.purchase(ACTOR,uuid(),purchaseBody("spv_repo_test_1_0_0"),false);var i=installation();
        byte[] wire=jdbc.queryForObject("SELECT wire_payload FROM agent_outbox_event",byte[].class);
        long reserved=wallet();
        when(sessions.dispatch(anyString(),anyString(),anyString(),any(),any())).thenThrow(new IllegalStateException("uncertain send"));
        recovery.recover();assertEquals("RETRY",jdbc.queryForObject("SELECT status FROM agent_command_delivery",String.class));
        assertEquals("INSTALLING",service.order(ACTOR,i.getOrderId()).get("status"));assertEquals(reserved,wallet());
        jdbc.update("UPDATE agent_command_delivery SET update_time=?,next_retry_at=?",System.currentTimeMillis()-70000,System.currentTimeMillis()-1);
        jdbc.update("UPDATE agent_outbox_event SET update_time=?,next_retry_at=?",System.currentTimeMillis()-70000,System.currentTimeMillis()-1);
        doAnswer(call->{
            assertArrayEquals(wire,call.getArgument(4));return AgentRawCommandDispatchResult.sent(1,1);
        }).when(sessions).dispatch(anyString(),anyString(),anyString(),any(),any());
        recovery.recover();assertEquals("SENT",jdbc.queryForObject("SELECT status FROM agent_command_delivery",String.class));
        assertEquals(i.getMessageId(),jdbc.queryForObject("SELECT active_message_id FROM agent_command_delivery",String.class));
        assertEquals(1,jdbc.queryForObject("SELECT active_attempt FROM agent_command_delivery",Integer.class));
        assertEquals(1,count("agent_outbox_event"));assertEquals(1,count("economy_skill_installation"));
        assertArrayEquals(wire,jdbc.queryForObject("SELECT wire_payload FROM agent_outbox_event",byte[].class));
        assertEquals("INSTALLING",service.order(ACTOR,i.getOrderId()).get("status"));assertEquals(reserved,wallet());
    }
    @Test void nativeWsInFlightLeasePreventsDuplicateAndReclaimsOnlyExpiredLease() {
        service.purchase(ACTOR,uuid(),purchaseBody("spv_repo_test_1_0_0"),false);
        long now=System.currentTimeMillis();
        jdbc.update("UPDATE agent_command_delivery SET status='CONSUMED',lease_owner='other',lease_until=?,update_time=?",now+60000,now-70000);
        jdbc.update("UPDATE agent_outbox_event SET status='CLAIMED',lease_owner='other',lease_until=?,update_time=?",now+60000,now-70000);
        recovery.recover();verify(sessions,never()).dispatch(anyString(),anyString(),anyString(),any(),any());
        jdbc.update("UPDATE agent_command_delivery SET lease_until=?",now-1);jdbc.update("UPDATE agent_outbox_event SET lease_until=?",now-1);
        when(sessions.dispatch(anyString(),anyString(),anyString(),any(),any())).thenReturn(AgentRawCommandDispatchResult.sent(1,1));
        recovery.recover();assertEquals("SENT",jdbc.queryForObject("SELECT status FROM agent_command_delivery",String.class));
        verify(sessions,times(1)).dispatch(anyString(),anyString(),anyString(),any(),any());
    }
    @Test void persistedSkillWireAndResultReplayKeepTransportAndInstallationIdentitiesDistinct() {
        var body=purchaseBody("spv_repo_test_1_0_0");String idem=uuid();
        var receipt=service.purchase(ACTOR,idem,body,false);var i=installation();
        assertEquals(i.getMessageId(),i.getRequestId());
        assertNotEquals(i.getInstallationId(),i.getRequestId());
        byte[] wire=jdbc.queryForObject("SELECT wire_payload FROM agent_outbox_event WHERE command_id=?",byte[].class,i.getCommandId());
        var envelope=tools.jackson.databind.json.JsonMapper.builder().build().readTree(wire);
        assertArrayEquals(cn.jia.agent.service.impl.AgentCommandCanonicalCodec.sha256(wire),
                jdbc.queryForObject("SELECT wire_payload_hash FROM agent_outbox_event WHERE command_id=?",byte[].class,i.getCommandId()));
        assertEquals(i.getMessageId(),envelope.get("messageId").asString());
        assertEquals(envelope.get("messageId"),envelope.get("requestId"));
        assertEquals(i.getInstallationId(),envelope.get("installationId").asString());
        assertEquals(envelope.get("installationId"),envelope.get("payload").get("installationId"));
        assertEquals(i.getOrderId(),envelope.get("orderId").asString());
        assertEquals(i.getCommandId(),envelope.get("commandId").asString());
        assertEquals(i.getAttempt().intValue(),envelope.get("attempt").asInt());
        assertEquals(i.getFencingToken().toString(),envelope.get("fencingToken").asString());
        assertEquals(i.getDeliveryEpoch().toString(),envelope.get("deliveryEpoch").asString());
        assertEquals(i.getMessageId(),jdbc.queryForObject("SELECT message_id FROM agent_outbox_event WHERE command_id=?",String.class,i.getCommandId()));
        assertEquals(i.getMessageId(),jdbc.queryForObject("SELECT active_message_id FROM agent_command_delivery WHERE command_id=?",String.class,i.getCommandId()));
        assertEquals(receipt,service.purchase(ACTOR,idem,body,false));
        assertArrayEquals(wire,jdbc.queryForObject("SELECT wire_payload FROM agent_outbox_event WHERE command_id=?",byte[].class,i.getCommandId()));
        assertEquals(1,count("economy_skill_installation"));assertEquals(1,count("agent_outbox_event"));
        assertEquals(1,count("agent_command_delivery"));
        sent(i);var success=result(i,"SUCCEEDED",null);
        assertNotEquals(i.getMessageId(),success.get("messageId"));
        var wrongInstallation=new LinkedHashMap<>(success);wrongInstallation.put("installationId",i.getMessageId());
        assertThrows(SkillMarketplaceException.class,()->results.accept(proof(),wrongInstallation));
        var wrongAttempt=new LinkedHashMap<>(success);wrongAttempt.put("attempt",i.getAttempt()+1);
        assertThrows(SkillMarketplaceException.class,()->results.accept(proof(),wrongAttempt));
        assertEquals(0,count("economy_skill_result_receipt"));assertEquals("INSTALLING",installation().getStatus());
        var accepted=results.accept(proof(),success);
        assertEquals(success.get("messageId"),accepted.get("correlationId"));
        assertEquals(i.getInstallationId(),accepted.get("installationId"));
        assertEquals(accepted,results.accept(proof(),success));
        assertEquals(1,count("economy_skill_result_receipt"));assertEquals("SUCCEEDED",installation().getStatus());
        assertEquals(i.getInstallationId(),installation().getInstallationId());
        assertEquals(i.getMessageId(),installation().getRequestId());
        assertArrayEquals(wire,jdbc.queryForObject("SELECT wire_payload FROM agent_outbox_event WHERE command_id=?",byte[].class,i.getCommandId()));
    }
    @Test void confirmedPreactivationFailureRefundsOriginalOrderExactlyOnce() {
        service.purchase(ACTOR,uuid(),purchaseBody("spv_repo_test_1_0_0"),false);var i=installation();sent(i);
        var failure=result(i,"FAILED","SKILL_PACKAGE_DIGEST_MISMATCH");
        results.accept(proof(),failure);results.accept(proof(),failure);
        assertEquals(100000000L,wallet());assertEquals(2,count("economy_transaction"));
        assertEquals("REFUNDED",service.order(ACTOR,i.getOrderId()).get("status"));
        assertEquals("FAILED",service.entitlements(ACTOR,AGENT).getFirst().get("status"));
        assertThrows(SkillMarketplaceException.class,()->results.accept(proof(),result(i,"SUCCEEDED",null)));
        assertEquals(100000000L,wallet());
    }
    @Test void unknownNeverRefundsAndLaterSuccessCanCapture() {
        service.purchase(ACTOR,uuid(),purchaseBody("spv_repo_test_1_0_0"),false);var i=installation();sent(i);
        results.accept(proof(),result(i,"FAILED","SKILL_INSTALL_IO_FAILED"));
        assertEquals(70000000L,wallet());assertEquals(1,count("economy_transaction"));
        assertEquals("INSTALLING",service.order(ACTOR,i.getOrderId()).get("status"));
        results.accept(proof(),result(i,"SUCCEEDED",null));
        assertEquals(2,count("economy_transaction"));assertEquals("ACTIVE",service.order(ACTOR,i.getOrderId()).get("status"));
    }
    @Test void freeSkillHasDurableInstallationAndNoInventedMoneyReferences() {
        service.purchase(ACTOR,uuid(),purchaseBody("spv_repo_inspector_1_0_0"),false);var i=installation();sent(i);
        results.accept(proof(),result(i,"SUCCEEDED",null));
        assertEquals(0,count("economy_transaction"));assertEquals(0,count("economy_escrow"));assertEquals(100000000L,wallet());
        var o=app.order(ACTOR.tenantId(),ACTOR.clientId(),i.getOrderId());assertNull(o.getEscrowId());assertNull(o.getReserveTransactionId());assertNull(o.getCaptureTransactionId());
        assertEquals("ACTIVE",o.getStatus());
    }
    @Test void insufficientOrFailedPendingInsertRollsBackFundsCommandVersionAndAllBusinessRows() {
        var body=purchaseBody("spv_repo_test_1_0_0");jdbc.update("UPDATE economy_account SET balance_micro=1 WHERE account_id='wallet'");
        assertThrows(RuntimeException.class,()->service.purchase(ACTOR,uuid(),body,false));
        assertEquals(1L,wallet());assertEquals(0,count("economy_transaction"));assertEquals(0,count("economy_skill_order"));assertEquals(0,count("agent_outbox_event"));
        jdbc.update("UPDATE economy_account SET balance_micro=100000000 WHERE account_id='wallet'");
        doThrow(new IllegalStateException("fixture pending insert failure")).when(market).insertEntitlement(any());
        assertThrows(RuntimeException.class,()->service.purchase(ACTOR,uuid(),body,false));
        assertEquals(100000000L,wallet());assertEquals(0,count("economy_transaction"));assertEquals(0,count("economy_skill_order"));assertEquals(0,count("agent_command_delivery"));
        assertEquals("1",Long.toString(versions.requireOwned(ACTOR,AGENT,null,false)));
    }
    @Test void staleVersionTamperedPermissionAdminAndScopeCannotReserve() {
        var body=purchaseBody("spv_repo_test_1_0_0");
        var tampered=new HashMap<>(body);tampered.put("approvedPermissions","[\"write\"]");
        assertThrows(SkillMarketplaceException.class,()->service.purchase(ACTOR,uuid(),tampered,false));
        runtime.setTokenHash("registration-2");versions.observe(runtime);
        assertThrows(SkillMarketplaceException.class,()->service.purchase(ACTOR,uuid(),body,false));
        assertThrows(SkillMarketplaceException.class,()->service.quote(ACTOR,uuid(),Map.of("targetAgentId",AGENT,"productVersionId","spv_deploy_runner_1_0_0","expectedAgentVersion","2"),false));
        // Runtime exact owner denial now precedes the hosting owner resolver; still 403,
        // and no funds/escrow/installation/command may be reserved by this different scope.
        var denied=assertThrows(SkillMarketplaceException.class,
                ()->service.purchase(new HostingRentHttp.Actor(ACTOR.actorId(),"0",ACTOR.clientId(),"tenant-a"),uuid(),body,false));
        assertEquals(403,denied.status());assertEquals("AGENT_FORBIDDEN",denied.code());
        assertNoPurchaseSideEffects();
    }
    @Test void exactRuntimeProofNeverBypassesHostingOwnerRejectionOrChangesWalletAndVersion() {
        var body=purchaseBody("spv_repo_test_1_0_0");
        long version=versions.requireOwned(ACTOR,AGENT,null,false);
        when(owner.requireOwner(ACTOR)).thenThrow(new cn.jia.agent.hosting.HostingRentApplicationException(403,"HOSTING_RENT_OWNER_UNPROVEN"));
        var denied=assertThrows(cn.jia.agent.hosting.HostingRentApplicationException.class,()->service.purchase(ACTOR,uuid(),body,false));
        assertEquals(403,denied.status());assertEquals("HOSTING_RENT_OWNER_UNPROVEN",denied.code());
        assertNoPurchaseSideEffects();assertEquals(version,jdbc.queryForObject("SELECT version FROM economy_skill_agent_version",Long.class));
    }
    @Test void exactRuntimeProofNeverBypassesRealHostingRentAdmissionOrConsumesVersion() {
        var body=purchaseBody("spv_repo_test_1_0_0");long version=versions.requireOwned(ACTOR,AGENT,null,false);
        var rentMapper=mock(EconomyHostingRentMapper.class);
        var lease=new EconomyHostingLeaseEntity().setStatus("ACTIVE").setPaidThrough(1L);
        when(rentMapper.selectLatestLease(ACTOR.tenantId(),ACTOR.clientId(),AGENT)).thenReturn(lease);
        var rentAdmission=new cn.jia.agent.hosting.HostingRentWorkAdmission(rentMapper,true);
        doAnswer(call->{rentAdmission.requireNewWork(call.getArgument(0),call.getArgument(1),call.getArgument(2));return null;})
                .when(agents).requireHostingNewWork(ACTOR.tenantId(),ACTOR.clientId(),AGENT);
        var overdue=assertThrows(cn.jia.agent.hosting.HostingRentApplicationException.class,()->service.purchase(ACTOR,uuid(),body,false));
        assertEquals(409,overdue.status());assertEquals("HOSTING_RENT_RENEWAL_REQUIRED",overdue.code());assertNoPurchaseSideEffects();
        lease.setStatus("SUSPENDED");
        var suspended=assertThrows(cn.jia.agent.hosting.HostingRentApplicationException.class,()->service.purchase(ACTOR,uuid(),body,false));
        assertEquals(409,suspended.status());assertEquals("HOSTING_RENT_NOT_ACTIVE",suspended.code());assertNoPurchaseSideEffects();
        assertEquals(version,versions.requireOwned(ACTOR,AGENT,null,false));
    }
    private void assertNoPurchaseSideEffects() {
        assertEquals(100000000L,wallet());
        for(String table:List.of("economy_transaction","economy_escrow","economy_skill_order","economy_skill_order_receipt",
                "economy_skill_installation","economy_skill_entitlement","economy_skill_delivery_binding",
                "agent_command_delivery","agent_outbox_event")) assertEquals(0,count(table),table);
    }
    @Test void concurrentSameKeyHasOneReserveOneDeliveryAndImmutableReceipt() throws Exception {
        var body=purchaseBody("spv_repo_test_1_0_0");String idem=uuid();var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Map<String,Object>> work=()->{start.await();return service.purchase(ACTOR,idem,body,false);};
            var a=pool.submit(work);var b=pool.submit(work);start.countDown();assertEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
        assertEquals(1,count("economy_transaction"));assertEquals(1,count("agent_command_delivery"));assertEquals(70000000L,wallet());
    }
    @Test void foreignAgentKeyOrStaleDeliveryCannotDownloadOrSettle() {
        service.purchase(ACTOR,uuid(),purchaseBody("spv_repo_inspector_1_0_0"),false);var i=installation();sent(i);
        var foreign=foreignProof("other-agent","rti_"+"b".repeat(32));
        assertThrows(SkillMarketplaceException.class,()->results.packageBytes(foreign,i.getInstallationId()));
        assertThrows(SkillMarketplaceException.class,()->results.accept(foreign,result(i,"SUCCEEDED",null)));
        jdbc.update("UPDATE agent_command_delivery SET active_attempt=2");
        assertThrows(SkillMarketplaceException.class,()->results.packageBytes(proof(),i.getInstallationId()));
        assertThrows(SkillMarketplaceException.class,()->results.accept(proof(),result(i,"SUCCEEDED",null)));
        assertEquals("INSTALLING",service.order(ACTOR,i.getOrderId()).get("status"));
    }
    @Test void fundingLookupUsesOnlyPersistedInstalledActiveExactAgentSkills() {
        var lookup=new InstalledSkillEntitlementLookup(market,app,versions,service);
        var requirement=new cn.jia.agent.entity.funding.AgentSkillRequirementDTO();requirement.setSkillKey("repo-test");requirement.setVersionRange(">=1.0.0");
        var actor=new cn.jia.agent.service.funding.FundedBountyActor(ACTOR.tenantId(),ACTOR.clientId(),ACTOR.ownerJiacn(), ACTOR.actorId());
        service.purchase(ACTOR,uuid(),purchaseBody("spv_repo_test_1_0_0"),false);var i=installation();sent(i);
        assertFalse(lookup.lookup(actor,AGENT,List.of(requirement)).allRequirementsMatched());
        results.accept(proof(),result(i,"SUCCEEDED",null));
        assertTrue(lookup.lookup(actor,AGENT,List.of(requirement)).allRequirementsMatched());
        doReturn(List.of()).when(market).selectEntitlementsByAgent(ACTOR.tenantId(),ACTOR.clientId(),AGENT);
        assertFalse(lookup.lookup(actor,AGENT,List.of(requirement)).allRequirementsMatched());
    }
    @Test void sameInstallationHostReconnectCanDownloadAndReconcileDurableOldResult() {
        service.purchase(ACTOR,uuid(),purchaseBody("spv_repo_test_1_0_0"),false);var i=installation();sent(i);
        var original=result(i,"SUCCEEDED",null);original.put("runtimeInstanceId","original-runtime");
        var originalProof=proof();
        runtime.setTokenHash("reconnected-generation").setRuntimeInstanceId("runtime-2").setRuntimeSessionGeneration(2L);versions.observe(runtime);
        assertThrows(SkillMarketplaceException.class,()->results.packageBytes(originalProof,i.getInstallationId()));
        var binding=app.deliveryBinding(ACTOR.tenantId(),ACTOR.clientId(),i.getInstallationId());
        assertEquals(runtime.getRuntimeInstallationId(),binding.getRuntimeInstallationId());
        assertEquals(runtime.getRuntimeHostId(),binding.getRuntimeHostId());
        assertNull(binding.getApiKeyId());assertNull(binding.getRegistrationHash());
        assertTrue(results.packageBytes(proof(),i.getInstallationId()).length>0);
        results.accept(proof(),original);
        assertEquals("ACTIVE",service.order(ACTOR,i.getOrderId()).get("status"));assertEquals(2,count("economy_transaction"));
        assertThrows(SkillMarketplaceException.class,()->results.accept(foreignProof(AGENT,"rti_"+"b".repeat(32)),original));
    }
    @Test void legacyCredentialRotationDoesNotAuthorizeRuntimeAdmission() {
        when(keys.update(any())).thenAnswer(x->x.getArgument(0));
        var created=new java.util.concurrent.atomic.AtomicReference<OauthApiKeyEntity>();
        when(keys.create(any())).thenAnswer(x->{OauthApiKeyEntity k=x.getArgument(0);k.setId(uuid());created.set(k);return k;});
        when(keys.get(argThat(id->!"key-1".equals(id)))).thenAnswer(x->created.get());
        String idem=uuid();long version=versions.requireOwned(ACTOR,AGENT,null,false);
        var response=credentials.rotate(ACTOR,AGENT,idem,version);
        assertEquals("CREATED",response.get("status"));assertEquals(Long.toString(version+1),response.get("version"));
        assertTrue(((String)response.get("apiKey")).startsWith("cdx_"));assertEquals(0,count("economy_transaction"));
        assertEquals(0,key.getStatus());assertEquals(2,count("economy_skill_managed_credential"));
        assertEquals("SKILL_CREDENTIAL_SECRET_NOT_REPLAYABLE",assertThrows(SkillMarketplaceException.class,()->credentials.rotate(ACTOR,AGENT,idem,version)).code());
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(SkillMarketplaceException.class,()->credentials.rotate(ACTOR,AGENT,idem,version+1)).code());
        when(sessions.isReady(eq(ACTOR.tenantId()),eq(ACTOR.clientId()),eq(AGENT),any())).thenReturn(false);
        assertEquals("SKILL_AGENT_REGISTRATION_REQUIRED",assertThrows(SkillMarketplaceException.class,()->purchaseBody("spv_repo_test_1_0_0")).code());
        when(sessions.isReady(eq(ACTOR.tenantId()),eq(ACTOR.clientId()),eq(AGENT),any())).thenReturn(true);
        runtime.setTokenHash("new-key-registration");versions.observe(runtime);
        var body=purchaseBody("spv_repo_test_1_0_0");service.purchase(ACTOR,uuid(),body,false);
        assertEquals("SKILL_INSTALLATION_PENDING",assertThrows(SkillMarketplaceException.class,()->credentials.rotate(ACTOR,AGENT,uuid(),versions.requireOwned(ACTOR,AGENT,null,false))).code());
        assertEquals(1,count("economy_transaction"));
    }
    @Test void sameApiKeyCannotBeBoundToTwoAgentsAndCredentialReceiptContainsNoSecret() {
        assertThrows(RuntimeException.class,()->credentialMapper.insert("sc_other","key-1","other-agent",8L,ACTOR.tenantId(),ACTOR.clientId(),2L));
        assertEquals(1,count("economy_skill_managed_credential"));
        var columns=jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE LOWER(table_name)='economy_skill_credential_operation'",String.class);
        assertFalse(columns.stream().anyMatch(c->c.equalsIgnoreCase("api_key") || c.equalsIgnoreCase("secret")));
    }
    @Test void skillDispatchUsesDurableOrderNotTaskAclAndIoRunsAfterCommit() {
        service.purchase(ACTOR,uuid(),purchaseBody("spv_repo_test_1_0_0"),false);var i=installation();sent(i);
        byte[] wire=jdbc.queryForObject("SELECT wire_payload FROM agent_outbox_event",byte[].class);
        when(sessions.dispatch(eq(ACTOR.tenantId()),eq(ACTOR.clientId()),eq(AGENT),any(),any())).thenAnswer(x->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return AgentRawCommandDispatchResult.sent(1,1);
        });
        assertEquals(AgentRawCommandDispatchResult.Status.SENT,dispatch.dispatch(ACTOR.tenantId(),ACTOR.clientId(),ACTOR.ownerJiacn(), i.getOrderId(),AGENT,i.getCommandId(),wire).status());
        byte[] tampered=wire.clone();tampered[0]='[';
        assertThrows(SkillMarketplaceException.class,()->dispatch.dispatch(ACTOR.tenantId(),ACTOR.clientId(),ACTOR.ownerJiacn(), i.getOrderId(),AGENT,i.getCommandId(),tampered));
        verify(sessions,times(1)).dispatch(anyString(),anyString(),anyString(),any(),any());
    }
    @Test void unmappedHistoryBlocksOnlyItsDeliveryWithoutRechargingOrReplaying() {
        var body=purchaseBody("spv_repo_test_1_0_0");String idem=uuid();
        var receipt=service.purchase(ACTOR,idem,body,false);var i=installation();sent(i);
        // Isolated fixture mutation represents an existing, unmigrated legacy record.
        jdbc.update("UPDATE economy_skill_delivery_binding SET canonical_agent_id=NULL,runtime_installation_id=NULL,runtime_host_id=NULL,api_key_id='historical-key',registration_hash=?",new byte[32]);
        assertEquals("SKILL_RUNTIME_MAPPING_REQUIRED",assertThrows(SkillMarketplaceException.class,
                ()->results.packageBytes(proof(),i.getInstallationId())).code());
        assertEquals("SKILL_RUNTIME_MAPPING_REQUIRED",assertThrows(SkillMarketplaceException.class,
                ()->results.accept(proof(),result(i,"SUCCEEDED",null))).code());
        assertEquals(receipt,service.purchase(ACTOR,idem,body,false));
        assertEquals(1,count("economy_transaction"));assertEquals(1,count("agent_command_delivery"));
        assertEquals(70000000L,wallet());assertEquals(0,count("economy_skill_result_receipt"));
    }
    private AgentRuntimeAuthenticationService.Proof proof() {
        return new AgentRuntimeAuthenticationService.Proof(new AgentRuntimeAuthentication.Scope(
                ACTOR.tenantId(),ACTOR.clientId(),ACTOR.ownerJiacn(),AGENT,runtime.getRuntimeInstanceId()),
                runtime.getRuntimeInstallationId(),runtime.getRuntimeHostId(),runtime.getRuntimeSessionGeneration(),
                java.util.HexFormat.of().formatHex(cn.jia.agent.service.impl.AgentCommandCanonicalCodec.sha256(
                        runtime.getTokenHash().getBytes(StandardCharsets.UTF_8))),1L,1L);
    }
    private AgentRuntimeAuthenticationService.Proof foreignProof(String agent,String installation) {
        var current=proof();return new AgentRuntimeAuthenticationService.Proof(new AgentRuntimeAuthentication.Scope(
                ACTOR.tenantId(),ACTOR.clientId(),ACTOR.ownerJiacn(),agent,current.scope().runtimeInstanceId()),
                installation,current.hostId(),current.sessionGeneration(),current.tokenDigest(),1L,1L);
    }
    private Map<String,String> purchaseBody(String product) {
        String version=Long.toString(versions.requireOwned(ACTOR,AGENT,null,false));
        var quote=service.quote(ACTOR,uuid(),Map.of("productVersionId",product,"targetAgentId",AGENT,"expectedAgentVersion",version),false);
        return Map.of("quoteId",(String)quote.get("quoteId"),"productVersionId",product,"targetAgentId",AGENT,"expectedAgentVersion",version,
                "expectedPriceMicro",(String)quote.get("priceMicro"),"approvedPermissions","[]");
    }
    private SkillInstallationEntity installation() { return app.installation(ACTOR.tenantId(),ACTOR.clientId(),jdbc.queryForObject("SELECT installation_id FROM economy_skill_installation",String.class)); }
    private void sent(SkillInstallationEntity i) { jdbc.update("UPDATE agent_command_delivery SET status='STARTED' WHERE command_id=?",i.getCommandId()); }
    private Map<String,Object> result(SkillInstallationEntity i,String status,String failure) {
        Map<String,Object> b=new LinkedHashMap<>();b.put("schemaVersion",1);b.put("messageType","work.result");b.put("messageId",uuid());b.put("resultType","SKILL_INSTALL_RESULT");
        b.put("commandId",i.getCommandId());b.put("attempt",i.getAttempt());b.put("fencingToken",i.getFencingToken().toString());b.put("deliveryEpoch",i.getDeliveryEpoch().toString());
        b.put("orderId",i.getOrderId());b.put("installationId",i.getInstallationId());b.put("targetAgentId",i.getTargetAgentId());b.put("productVersionId",i.getProductVersionId());
        b.put("status",status);b.put("packageDigest",SkillMarketplaceService.digest(i.getPackageSha256()));b.put("skillKey",i.getSkillKey());b.put("skillVersion",i.getSkillVersion());
        b.put("installedAt",status.equals("SUCCEEDED")?Long.toString(System.currentTimeMillis()):null);b.put("failureCode",failure);return b;
    }
    private long wallet() { return jdbc.queryForObject("SELECT balance_micro FROM economy_account WHERE account_id='wallet'",Long.class); }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
    private static String uuid() { return UUID.randomUUID().toString(); }
    private void schema(String resource) throws Exception {
        if(mysqlFixture()) {
            new ResourceDatabasePopulator(new ClassPathResource(resource)).populate(schemaConnection); return;
        }
        String h2=new ClassPathResource(resource).getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("(?m)^--.*$","")
                .replaceAll("(?is)\\)\\s*ENGINE=InnoDB\\s+DEFAULT\\s+CHARSET=utf8mb4"
                        + "(?:\\s+COLLATE\\s*=\\s*[a-z0-9_]+)?"
                        + "(?:\\s+COMMENT='(?:''|[^'])*')?\\s*;",");")
                .replaceAll("(?i)\\s+COLLATE\\s+[a-z0-9_]+","")
                .replaceAll("(?i)\\s+CHARACTER SET\\s+[a-z0-9_]+","");
        h2=adaptTransportGeneratedColumns(resource,h2);
        h2=adaptTransportAuditTriggers(resource,h2);
        new ResourceDatabasePopulator(new ByteArrayResource(h2.getBytes(StandardCharsets.UTF_8)))
                .populate(Objects.requireNonNull(schemaConnection));
    }
    private static String adaptTransportGeneratedColumns(String resource,String sql) {
        if(!TRANSPORT_SCHEMA.equals(resource)) return sql;
        if(occurrences(sql,"GENERATED ALWAYS AS")!=2
                || occurrences(sql,MYSQL_DISPOSITION_GUARD)!=1 || occurrences(sql,MYSQL_REDRIVE_GUARD)!=1) {
            throw new IllegalStateException("D09 fixture requires two exact generated guard definitions");
        }
        // CASE preserves IF's branches and NULL values; H2 generated columns have no STORED suffix.
        // Retain the original indexes, including NULL-distinct uk_redrive_operation_guard.
        return sql.replace(MYSQL_DISPOSITION_GUARD,H2_DISPOSITION_GUARD)
                .replace(MYSQL_REDRIVE_GUARD,H2_REDRIVE_GUARD);
    }
    private static String adaptTransportAuditTriggers(String resource,String sql) {
        if(!TRANSPORT_SCHEMA.equals(resource)) return sql;
        if(occurrences(sql,"CREATE TRIGGER ")!=2 || occurrences(sql,"DROP TRIGGER IF EXISTS ")!=2
                || occurrences(sql,MYSQL_AUDIT_UPDATE_TRIGGER)!=1
                || occurrences(sql,MYSQL_AUDIT_DELETE_TRIGGER)!=1) {
            throw new IllegalStateException("D09 fixture requires two exact immutable audit trigger definitions");
        }
        String adapted=sql.replace(MYSQL_AUDIT_UPDATE_TRIGGER,H2_AUDIT_UPDATE_TRIGGER)
                .replace(MYSQL_AUDIT_DELETE_TRIGGER,H2_AUDIT_DELETE_TRIGGER);
        if(occurrences(adapted,"CREATE TRIGGER ")!=2
                || occurrences(adapted,"CALL \"cn.jia.agent.skill.SkillAuditMutationRejectTrigger\"")!=2
                || adapted.contains("SIGNAL SQLSTATE")) {
            throw new IllegalStateException("D09 fixture audit trigger adaptation drifted");
        }
        return adapted;
    }
    private static int occurrences(String value,String token) {
        int count=0;
        for(int index=0;(index=value.indexOf(token,index))>=0;index+=token.length()) count++;
        return count;
    }
    @SuppressWarnings("unchecked") static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p=mock(ObjectProvider.class);when(p.getIfAvailable()).thenReturn(value);when(p.getObject()).thenReturn(value);when(p.getIfUnique()).thenReturn(value);return p;
    }
}
