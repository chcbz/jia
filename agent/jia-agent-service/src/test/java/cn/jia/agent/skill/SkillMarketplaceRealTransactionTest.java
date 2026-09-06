package cn.jia.agent.skill;

import cn.jia.agent.config.*;
import cn.jia.agent.dao.*;
import cn.jia.agent.dao.impl.AgentCommandTransportDaoImpl;
import cn.jia.agent.entity.*;
import cn.jia.agent.mapper.AgentCommandTransportMapper;
import cn.jia.agent.hosting.*;
import cn.jia.agent.service.*;
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
import org.mybatis.spring.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real SQL/REQUIRED transactions, actual W02 posting and actual command/outbox writer.
 * No sockets, broker, runner, or provisioning. Runtime/owner/key adapters are explicit trusted fixtures.
 * Paid terminal selectors require W06's centralized CAPTURE_SKILL/REFUND_SKILL primitive integration. */
class SkillMarketplaceRealTransactionTest {
    private static final HostingRentHttp.Actor ACTOR=new HostingRentHttp.Actor("payer-sub","Tenant-A","Client-A");
    private static final String AGENT="agt_00000000000000000000000000000001";
    private JdbcTemplate jdbc;
    private EconomySkillMarketplaceMapper market;
    private EconomySkillApplicationMapper app;
    private SkillMarketplaceService service;
    private SkillInstallResultService results;
    private SkillAgentVersions versions;
    private AgentRuntimeEntity runtime;
    private OauthApiKeyEntity key;
    private TransactionTemplate tx;
    private SkillManagedCredentials credentials;
    private EconomySkillCredentialMapper credentialMapper;
    private ApiKeyService keys;
    private AgentManagedSessionLookup sessions;
    private SkillInstallDispatchService dispatch;
    private EconomyLedgerMapper ledger;


    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:w09_"+UUID.randomUUID()+";MODE=MYSQL;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE;LOCK_TIMEOUT=10000","sa","");
        jdbc=new JdbcTemplate(ds); var manager=new DataSourceTransactionManager(ds); tx=new TransactionTemplate(manager);
        for(String resource:List.of("db/economy-v0-foundation.sql","db/economy-v0-skill-marketplace.sql","db/economy-v0-skill-application.sql","db/agent-command-transport-schema.sql")) schema(resource);
        var config=new org.apache.ibatis.session.Configuration();config.setMapUnderscoreToCamelCase(true);
        for(Class<?> type:List.of(EconomySkillMarketplaceMapper.class,EconomySkillApplicationMapper.class,EconomySkillCredentialMapper.class,EconomyLedgerMapper.class,AgentCommandTransportMapper.class)) config.addMapper(type);
        var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);
        var sql=new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        market=spy(sql.getMapper(EconomySkillMarketplaceMapper.class));app=sql.getMapper(EconomySkillApplicationMapper.class);
        ledger=sql.getMapper(EconomyLedgerMapper.class);
        var gate=new EconomyPreviewGate(new EconomyPreviewProperties(true,List.of(new EconomyPreviewProperties.AllowedScope(ACTOR.tenantId(),ACTOR.clientId()))));
        var posting=new EconomyPostingServiceImpl(ledger,manager,gate);
        var agents=mock(AgentService.class); var owner=mock(HostingRentOwnerResolver.class); var runtimes=mock(AgentRuntimeDao.class);
        runtime=new AgentRuntimeEntity().setAgentId(AGENT).setOwnerJiacn(ACTOR.tenantId()).setBindingId(7L).setTokenHash("registration-1").setStatus("online");
        runtime.setClientId(ACTOR.clientId());
        when(runtimes.findByAgentIdForUpdate(AGENT)).thenAnswer(i->runtime);
        when(owner.requireOwner(ACTOR)).thenReturn(ACTOR.tenantId());
        var dto=new AgentRuntimeDTO();dto.setAgentId(AGENT);dto.setStatus("online");dto.setSystemAgent(false);
        when(agents.requireApiKeyOwnedAgentForUpdate(ACTOR.clientId(),ACTOR.tenantId(),AGENT)).thenReturn(dto);
        versions=new SkillAgentVersions(app,runtimes,provider(agents),owner,manager,true);
        var transport=mock(AgentRabbitSafetyGate.class);
        when(transport.state()).thenReturn(AgentRabbitActivationState.DISPATCH_CANARY);
        when(transport.commandOutboxEnabled()).thenReturn(true);when(transport.rabbitDispatchEnabled()).thenReturn(true);
        when(transport.allowsDispatch(ACTOR.tenantId(),ACTOR.clientId())).thenReturn(true);
        var commands=new AgentCommandTransportDaoImpl(sql.getMapper(AgentCommandTransportMapper.class));
        AgentCommandTransportWriter writer=new AgentCommandTransportWriterImpl(commands,transport,agents,mock(AgentTaskCollaborationAccessService.class),manager);
        var packages=new SkillPackages(); keys=mock(ApiKeyService.class);
        key=new OauthApiKeyEntity();key.setId("key-1");key.setJiacn(ACTOR.tenantId());key.setClientId(ACTOR.clientId());key.setTenantId(ACTOR.tenantId());key.setStatus(1);
        when(keys.get("key-1")).thenReturn(key);
        credentialMapper=spy(sql.getMapper(EconomySkillCredentialMapper.class));
        doReturn(List.of()).when(credentialMapper).hostingCandidates(anyString(),anyString(),anyString());
        credentialMapper.insert("sc_1","key-1",AGENT,7L,ACTOR.tenantId(),ACTOR.clientId(),1L);
        credentials=new SkillManagedCredentials(credentialMapper,app,mock(EconomyHostingRentMapper.class),versions,runtimes,provider(keys),gate,manager,true,false);
        sessions=mock(AgentManagedSessionLookup.class);
        when(sessions.isReady(eq(ACTOR.tenantId()),eq(ACTOR.clientId()),eq(AGENT),eq("key-1"),any())).thenReturn(true);
        service=new SkillMarketplaceService(market,app,ledger,posting,gate,versions,runtimes,provider(writer),provider(transport),provider(packages),provider(keys),credentials,provider(sessions),manager);
        dispatch=new SkillInstallDispatchService(app,market,service,versions,runtimes,commands,provider(sessions),manager);
        results=new SkillInstallResultService(market,app,commands,service,versions,runtimes,provider(packages),manager);
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
    @Test void paidPurchaseInstallsCapturesAndReplaysOriginalReceipt() {
        var body=purchaseBody("spv_repo_test_1_0_0");String idem=uuid();var receipt=service.purchase(ACTOR,idem,body,false);
        assertEquals("FUNDS_HELD",receipt.get("status")); assertEquals(70000000L,wallet());
        assertEquals(1,count("agent_command_delivery"));assertEquals(1,count("agent_outbox_event"));assertEquals(1,count("economy_skill_entitlement"));
        assertEquals("INSTALLING",service.order(ACTOR,(String)receipt.get("orderId")).get("status"));
        var i=installation(); sent(i);assertEquals(i.getPackageSize().longValue(),results.packageBytes(key,i.getInstallationId()).length);
        var success=result(i,"SUCCEEDED",null);results.accept(ACTOR.tenantId(),ACTOR.clientId(),AGENT,"key-1",success);
        results.accept(ACTOR.tenantId(),ACTOR.clientId(),AGENT,"key-1",success);
        assertEquals("ACTIVE",service.order(ACTOR,i.getOrderId()).get("status"));assertEquals(70000000L,wallet());
        assertEquals(2,count("economy_transaction"));assertEquals(0L,jdbc.queryForObject("SELECT SUM(signed_amount_micro) FROM economy_entry",Long.class));
        assertEquals(receipt,service.purchase(ACTOR,idem,body,false));
        assertEquals("ACTIVE",service.entitlements(ACTOR,AGENT).getFirst().get("status"));
        assertThrows(SkillMarketplaceException.class,()->results.packageBytes(key,i.getInstallationId()));
    }
    @Test void confirmedPreactivationFailureRefundsOriginalOrderExactlyOnce() {
        service.purchase(ACTOR,uuid(),purchaseBody("spv_repo_test_1_0_0"),false);var i=installation();sent(i);
        var failure=result(i,"FAILED","SKILL_PACKAGE_DIGEST_MISMATCH");
        results.accept(ACTOR.tenantId(),ACTOR.clientId(),AGENT,"key-1",failure);results.accept(ACTOR.tenantId(),ACTOR.clientId(),AGENT,"key-1",failure);
        assertEquals(100000000L,wallet());assertEquals(2,count("economy_transaction"));
        assertEquals("REFUNDED",service.order(ACTOR,i.getOrderId()).get("status"));
        assertEquals("FAILED",service.entitlements(ACTOR,AGENT).getFirst().get("status"));
        assertThrows(SkillMarketplaceException.class,()->results.accept(ACTOR.tenantId(),ACTOR.clientId(),AGENT,"key-1",result(i,"SUCCEEDED",null)));
        assertEquals(100000000L,wallet());
    }
    @Test void unknownNeverRefundsAndLaterSuccessCanCapture() {
        service.purchase(ACTOR,uuid(),purchaseBody("spv_repo_test_1_0_0"),false);var i=installation();sent(i);
        results.accept(ACTOR.tenantId(),ACTOR.clientId(),AGENT,"key-1",result(i,"FAILED","SKILL_INSTALL_IO_FAILED"));
        assertEquals(70000000L,wallet());assertEquals(1,count("economy_transaction"));
        assertEquals("INSTALLING",service.order(ACTOR,i.getOrderId()).get("status"));
        results.accept(ACTOR.tenantId(),ACTOR.clientId(),AGENT,"key-1",result(i,"SUCCEEDED",null));
        assertEquals(2,count("economy_transaction"));assertEquals("ACTIVE",service.order(ACTOR,i.getOrderId()).get("status"));
    }
    @Test void freeSkillHasDurableInstallationAndNoInventedMoneyReferences() {
        service.purchase(ACTOR,uuid(),purchaseBody("spv_repo_inspector_1_0_0"),false);var i=installation();sent(i);
        results.accept(ACTOR.tenantId(),ACTOR.clientId(),AGENT,"key-1",result(i,"SUCCEEDED",null));
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
        assertThrows(SkillMarketplaceException.class,()->service.purchase(new HostingRentHttp.Actor(ACTOR.actorId(),"tenant-a",ACTOR.clientId()),uuid(),body,false));
        assertEquals(0,count("economy_transaction"));assertEquals(0,count("agent_command_delivery"));
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
        var foreign=new OauthApiKeyEntity();foreign.setId("other-key");foreign.setTenantId(ACTOR.tenantId());foreign.setJiacn(ACTOR.tenantId());foreign.setClientId(ACTOR.clientId());foreign.setStatus(1);
        assertThrows(SkillMarketplaceException.class,()->results.packageBytes(foreign,i.getInstallationId()));
        assertThrows(SkillMarketplaceException.class,()->results.accept(ACTOR.tenantId(),ACTOR.clientId(),"other-agent","key-1",result(i,"SUCCEEDED",null)));
        jdbc.update("UPDATE agent_command_delivery SET active_attempt=2");
        assertThrows(SkillMarketplaceException.class,()->results.packageBytes(key,i.getInstallationId()));
        assertThrows(SkillMarketplaceException.class,()->results.accept(ACTOR.tenantId(),ACTOR.clientId(),AGENT,"key-1",result(i,"SUCCEEDED",null)));
        assertEquals("INSTALLING",service.order(ACTOR,i.getOrderId()).get("status"));
    }
    @Test void fundingLookupUsesOnlyPersistedInstalledActiveExactAgentSkills() {
        var lookup=new InstalledSkillEntitlementLookup(market,app,versions,service);
        var requirement=new cn.jia.agent.entity.funding.AgentSkillRequirementDTO();requirement.setSkillKey("repo-test");requirement.setVersionRange(">=1.0.0");
        var actor=new cn.jia.agent.service.funding.FundedBountyActor(ACTOR.tenantId(),ACTOR.clientId(),ACTOR.actorId());
        service.purchase(ACTOR,uuid(),purchaseBody("spv_repo_test_1_0_0"),false);var i=installation();sent(i);
        assertFalse(lookup.lookup(actor,AGENT,List.of(requirement)).allRequirementsMatched());
        results.accept(ACTOR.tenantId(),ACTOR.clientId(),AGENT,"key-1",result(i,"SUCCEEDED",null));
        assertTrue(lookup.lookup(actor,AGENT,List.of(requirement)).allRequirementsMatched());
        doReturn(List.of()).when(market).selectEntitlementsByAgent(ACTOR.tenantId(),ACTOR.clientId(),AGENT);
        assertFalse(lookup.lookup(actor,AGENT,List.of(requirement)).allRequirementsMatched());
    }
    @Test void sameKeyReconnectCanDownloadAndReconcileDurableOldResult() {
        service.purchase(ACTOR,uuid(),purchaseBody("spv_repo_test_1_0_0"),false);var i=installation();sent(i);
        var original=result(i,"SUCCEEDED",null);original.put("runtimeInstanceId","original-runtime");
        runtime.setTokenHash("reconnected-generation");versions.observe(runtime);
        assertTrue(results.packageBytes(key,i.getInstallationId()).length>0);
        results.accept(ACTOR.tenantId(),ACTOR.clientId(),AGENT,"key-1",original);
        assertEquals("ACTIVE",service.order(ACTOR,i.getOrderId()).get("status"));assertEquals(2,count("economy_transaction"));
        assertThrows(SkillMarketplaceException.class,()->results.accept(ACTOR.tenantId(),ACTOR.clientId(),AGENT,"foreign-key",original));
    }
    @Test void nonRentalCredentialSecretOnceExplicitRotationAndRegistrationRequiredBeforeCharging() {
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
        assertEquals("SKILL_AGENT_REGISTRATION_REQUIRED",assertThrows(SkillMarketplaceException.class,()->purchaseBody("spv_repo_test_1_0_0")).code());
        when(sessions.isReady(eq(ACTOR.tenantId()),eq(ACTOR.clientId()),eq(AGENT),eq(created.get().getId()),any())).thenReturn(true);
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
        when(sessions.dispatch(eq(ACTOR.tenantId()),eq(ACTOR.clientId()),eq(AGENT),eq("key-1"),any(),any())).thenAnswer(x->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return AgentRawCommandDispatchResult.sent(1,1);
        });
        assertEquals(AgentRawCommandDispatchResult.Status.SENT,dispatch.dispatch(ACTOR.tenantId(),ACTOR.clientId(),i.getOrderId(),AGENT,i.getCommandId(),wire).status());
        byte[] tampered=wire.clone();tampered[0]='[';
        assertThrows(SkillMarketplaceException.class,()->dispatch.dispatch(ACTOR.tenantId(),ACTOR.clientId(),i.getOrderId(),AGENT,i.getCommandId(),tampered));
        verify(sessions,times(1)).dispatch(anyString(),anyString(),anyString(),anyString(),any(),any());
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
        String h2=new ClassPathResource(resource).getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("(?m)^--.*$","")
                .replaceAll("(?is)\\)\\s*ENGINE=InnoDB\\s+DEFAULT\\s+CHARSET=utf8mb4"
                        + "(?:\\s+COLLATE\\s*=\\s*[a-z0-9_]+)?"
                        + "(?:\\s+COMMENT='(?:''|[^'])*')?\\s*;",");")
                .replaceAll("(?i)\\s+COLLATE\\s+[a-z0-9_]+","")
                .replaceAll("(?i)\\s+CHARACTER SET\\s+[a-z0-9_]+","");
        new ResourceDatabasePopulator(new ByteArrayResource(h2.getBytes(StandardCharsets.UTF_8)))
                .execute(Objects.requireNonNull(jdbc.getDataSource()));
    }
    @SuppressWarnings("unchecked") static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p=mock(ObjectProvider.class);when(p.getIfAvailable()).thenReturn(value);when(p.getObject()).thenReturn(value);return p;
    }
}
