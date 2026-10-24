package cn.jia.agent.skill;

import cn.jia.agent.dao.*;
import cn.jia.agent.dao.impl.*;
import cn.jia.agent.entity.*;
import cn.jia.agent.hosting.*;
import cn.jia.agent.mapper.*;
import cn.jia.agent.security.*;
import cn.jia.agent.service.*;
import cn.jia.economy.config.*;
import cn.jia.economy.config.skillseed.PlatformSkillPackageCatalog;
import cn.jia.economy.entity.*;
import cn.jia.economy.entity.skill.*;
import cn.jia.economy.mapper.*;
import cn.jia.economy.service.impl.EconomyPostingServiceImpl;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static cn.jia.agent.skill.SkillManagementCodec.*;

/** SQL, escrow, ledger, immutable authorizations and command outbox are real MySQL.
 * The default trusted identity/session fixture can be replaced by persistent proof validation
 * for targeted consumer tests. No paid IO. */
public class SkillManagementTransactionTest extends SkillManagementMySqlFixture {
    protected static final HostingRentHttp.Actor ACTOR=new HostingRentHttp.Actor("buyer","0","sm-client","owner");
    protected static final String AGENT="agt_sm";
    private String database;
    protected JdbcTemplate jdbc;
    protected SkillManagementService service;
    protected EconomySkillMarketplaceMapper market;
    protected EconomySkillManagementMapper db;
    protected SkillInstallCommandWriter writer;
    protected SkillInstallRecoveryService recovery;
    protected AgentRuntimeAuthenticationService authentication;
    protected AgentRuntimeEntity runtime;
    protected AgentManagedSessionLookup sessions;
    protected SkillCapabilityLookup capabilities;
    protected SkillAgentVersions versions;
    protected TransactionTemplate tx;
    private SkillExecutionDao taskAttachments;
    private EconomySkillApplicationMapper app;
    private SkillMarketplaceService marketplace;
    private SkillPackages packages;
    private org.springframework.transaction.PlatformTransactionManager manager;
    protected SqlSessionTemplate sql;
    private AgentSkillInstallRecoveryMapper recoveryMapper;
    private AgentCommandRecoveryDao recoveryDao;
    private SkillInstallDispatchService dispatch;

    @BeforeEach protected void setup() throws Exception {
        database="cyf_sm_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        var ds=source(database);jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);
        for(String resource:List.of("db/economy-v0-foundation.sql","db/economy-v0-skill-marketplace.sql","db/economy-v0-skill-application.sql",
                "db/agent-command-transport-schema.sql","db/economy-skill-management-sm-c1.sql","db/agent-skill-execution-v1.sql"))
            new ResourceDatabasePopulator(new ClassPathResource(resource)).execute(ds);
        var config=new org.apache.ibatis.session.Configuration();config.setMapUnderscoreToCamelCase(true);
        for(Class<?> type:List.of(EconomySkillMarketplaceMapper.class,EconomySkillApplicationMapper.class,EconomyLedgerMapper.class,
                EconomySkillManagementMapper.class,SkillExecutionMapper.class,AgentCommandTransportMapper.class,AgentCommandRecoveryMapper.class,AgentSkillInstallRecoveryMapper.class,AgentOutboxRelayMapper.class))config.addMapper(type);
        var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);
        sql=new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        taskAttachments=new SkillExecutionDaoImpl(sql.getMapper(SkillExecutionMapper.class));
        market=sql.getMapper(EconomySkillMarketplaceMapper.class);app=sql.getMapper(EconomySkillApplicationMapper.class);
        db=sql.getMapper(EconomySkillManagementMapper.class);var ledger=sql.getMapper(EconomyLedgerMapper.class);
        var gate=new EconomyPreviewGate(new EconomyPreviewProperties(true,List.of(new EconomyPreviewProperties.AllowedScope("0",ACTOR.clientId()))));
        var posting=new EconomyPostingServiceImpl(ledger,manager,gate);
        var agents=mock(AgentService.class);var owner=mock(HostingRentOwnerResolver.class);var runtimes=mock(AgentRuntimeDao.class);
        runtime=new AgentRuntimeEntity().setAgentId(AGENT).setOwnerJiacn(ACTOR.ownerJiacn()).setBindingId(7L).setTokenHash("registered").setStatus("online")
                .setRuntimeInstallationId("rti_"+"a".repeat(32)).setRuntimeHostId("host-1").setRuntimeInstanceId("runtime-1").setRuntimeSessionGeneration(1L);
        runtime.setTenantId("0");runtime.setClientId(ACTOR.clientId());
        when(runtimes.findByAgentIdForUpdate(AGENT)).thenReturn(runtime);when(runtimes.findInScope("0",ACTOR.clientId(),AGENT)).thenReturn(runtime);
        when(runtimes.lockInScope("0",ACTOR.clientId(),AGENT)).thenReturn(runtime);
        var installations=mock(AgentRuntimeV1InstallationDao.class);
        var installation=new AgentRuntimeV1InstallationEntity().setInstallationId(runtime.getRuntimeInstallationId()).setCanonicalAgentId(AGENT).setStatus("ACTIVE");
        installation.setTenantId("0");installation.setClientId(ACTOR.clientId());
        when(installations.lock(runtime.getRuntimeInstallationId())).thenReturn(installation);
        authentication=mock(AgentRuntimeAuthenticationService.class);
        when(authentication.currentRegisteredProof("0",ACTOR.clientId(),AGENT)).thenAnswer(i->proof());
        when(authentication.withFence(any(),eq(false),any())).thenAnswer(i->{
            assertEquals(proof(),i.getArgument(0));Supplier<?> action=i.getArgument(2);return tx.execute(s->action.get());
        });
        when(owner.requireOwner(any())).thenAnswer(i->{if(!ACTOR.equals(i.getArgument(0)))throw new HostingRentApplicationException(403,"FORBIDDEN");return ACTOR.ownerJiacn();});
        var dto=new AgentRuntimeDTO();dto.setAgentId(AGENT);dto.setStatus("online");dto.setSystemAgent(false);
        when(agents.requireApiKeyOwnedAgentForUpdate(ACTOR.clientId(),ACTOR.ownerJiacn(),AGENT)).thenReturn(dto);
        when(agents.requireApiKeyOwnedAgent(ACTOR.clientId(),ACTOR.ownerJiacn(),AGENT)).thenReturn(dto);
        versions=new SkillAgentVersions(app,runtimes,provider(agents),owner,manager,true);
        var commands=new AgentCommandTransportDaoImpl(sql.getMapper(AgentCommandTransportMapper.class));writer=spy(new SkillInstallCommandWriter(commands,gate));
        // Provision this fixture's private store explicitly; production never creates it at startup.
        Path packageRoot=Files.createDirectory(scratch.resolve(database+"-packages"),
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
        packages=new SkillPackages(packageRoot);sessions=mock(AgentManagedSessionLookup.class);
        when(sessions.isReady(eq("0"),eq(ACTOR.clientId()),eq(AGENT),any())).thenReturn(true);
        when(sessions.dispatch(anyString(),anyString(),anyString(),any(),any())).thenReturn(AgentRawCommandDispatchResult.sent(1,1));
        capabilities=mock(SkillCapabilityLookup.class);when(capabilities.lookup(any(),any())).thenReturn(SkillCapabilityLookup.View.unavailable(SkillCapabilityLookup.State.DISCONNECTED));
        marketplace=new SkillMarketplaceService(market,app,ledger,posting,gate,versions,runtimes,provider(writer),provider(packages),provider(authentication),provider(sessions),manager);
        service=new SkillManagementService(db,market,app,marketplace,versions,runtimes,installations,authentication,provider(packages),provider(writer),provider(sessions),provider(capabilities),manager);
        dispatch=new SkillInstallDispatchService(app,market,marketplace,versions,authentication,commands,provider(sessions),manager);dispatch.setManagement(service);
        recoveryMapper=sql.getMapper(AgentSkillInstallRecoveryMapper.class);
        recoveryDao=new AgentCommandRecoveryDaoImpl(sql.getMapper(AgentCommandRecoveryMapper.class));
        recovery=new SkillInstallRecoveryService(recoveryMapper,recoveryDao,gate,dispatch,manager);
        for(var p:new PlatformSkillPackageCatalog().products()) {
            market.insertProduct(new SkillProductEntity().setProductId(p.productId()).setSellerType("SYSTEM").setSellerId("SKILL_STORE").setName(p.name()).setDescription(p.description())
                .setStatus("DRAFT").setVersion(1L).setTenantId("0").setClientId(ACTOR.clientId()).setCreateTime(1L).setUpdateTime(1L));
            market.insertProductVersion(new SkillProductVersionEntity().setProductVersionId(p.productVersionId()).setProductId(p.productId()).setVersionSequence(1L)
                .setSkillKey(p.skillKey()).setSkillVersion(p.skillVersion()).setPriceMicro(p.priceMicro()).setPackageSha256(p.packageSha256()).setPackageSize(p.packageSize())
                .setApprovedPermissionsManifest(p.approvedPermissionsManifest()).setApprovedPermissionsSha256(p.approvedPermissionsSha256()).setDeploymentRestriction(p.deploymentRestriction())
                .setReviewStatus("APPROVED").setTenantId("0").setClientId(ACTOR.clientId()).setCreateTime(1L));
            market.publishProductVersion("0",ACTOR.clientId(),p.productId(),p.productVersionId(),1,2);
        }
        ledger.insertAccountIfAbsent(new EconomyAccountEntity().setAccountId("wallet").setOwnerType("USER").setOwnerId(ACTOR.actorId()).setPurpose("AVAILABLE")
            .setCurrency("SILVER").setBalanceMicro(100000000L).setAllowNegative(0).setStatus("ACTIVE").setVersion(0L).setTenantId("0").setClientId(ACTOR.clientId()).setCreateTime(1L).setUpdateTime(1L));
    }
    @AfterEach void cleanup() {if(admin!=null&&database!=null&&database.matches("cyf_sm_[a-f0-9]{32}"))admin.execute("DROP DATABASE `"+database+"`");}
    /** Replace only this test's trusted channel stub with actual persistent proof validation.
     * Account/persona authority remains an explicit fixture; runtime/install rows and locks are SQL. */
    protected void usePersistentAuthentication() throws Exception {
        var ds=Objects.requireNonNull(jdbc.getDataSource());
        // db/schema.sql exists in multiple modules. Select the actual runtime declaration,
        // never whichever unrelated module happens to be first on a test classpath.
        var runtimeDeclarations=new ArrayList<String>();
        var resources=getClass().getClassLoader().getResources("db/schema.sql");
        while(resources.hasMoreElements())try(var in=resources.nextElement().openStream()) {
            String ddl=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            int start=ddl.indexOf("CREATE TABLE IF NOT EXISTS agent_runtime (");
            if(start>=0)runtimeDeclarations.add(ddl.substring(start,ddl.indexOf(';',start)+1));
        }
        assertEquals(1,runtimeDeclarations.size(),"Exactly one production runtime schema declaration required");
        jdbc.execute(runtimeDeclarations.getFirst());
        for(String resource:List.of("db/agent-runtime-session-fence-v1.sql","db/agent-runtime-v1-schema.sql"))
            new ResourceDatabasePopulator(new ClassPathResource(resource)).execute(ds);
        for(Class<?> type:List.of(AgentRuntimeMapper.class,AgentRuntimeV1InstallationMapper.class))sql.getConfiguration().addMapper(type);
        var rows=new AgentRuntimeDaoImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(rows,"baseMapper",sql.getMapper(AgentRuntimeMapper.class));
        var installs=new AgentRuntimeV1InstallationDaoImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(installs,"baseMapper",sql.getMapper(AgentRuntimeV1InstallationMapper.class));
        jdbc.update("INSERT INTO agent_runtime(agent_id,name,owner_jiacn,binding_id,token_hash,status,tenant_id,client_id,runtime_installation_id,runtime_host_id,runtime_instance_id,runtime_session_generation) VALUES (?,? ,?,7,?,'online','0',?,?,?,'runtime-1',1)",
            AGENT,AGENT,ACTOR.ownerJiacn(),"urs1:"+proof().tokenDigest()+":1:1",ACTOR.clientId(),runtime.getRuntimeInstallationId(),"host-1");
        jdbc.update("INSERT INTO agent_runtime_v1_installation(installation_id,canonical_agent_id,manifest_version,manifest_sha256,enrollment_secret_hash,enrollment_expires_at,status,version,tenant_id,client_id) VALUES (?,?,'1',?,?,1,'ACTIVE',0,'0',?)",
            runtime.getRuntimeInstallationId(),AGENT,"a".repeat(64),new byte[32],ACTOR.clientId());
        var identities=mock(AgentIdentityService.class);
        var identity=new AgentIdentityRegistryEntity().setCanonicalAgentId(AGENT).setOwnerJiacn(ACTOR.ownerJiacn()).setBindingId(7L).setLifecycleStatus("ACTIVE");
        identity.setTenantId("0");identity.setClientId(ACTOR.clientId());
        when(identities.requireRegistrationIdentityInScope("0",ACTOR.clientId(),ACTOR.ownerJiacn(),AGENT)).thenReturn(identity);
        when(identities.requireActiveIdentityForBinding("0",ACTOR.clientId(),ACTOR.ownerJiacn(),7L,AGENT)).thenReturn(identity);
        when(identities.requireActiveBinding(identity,null)).thenReturn(new AgentPersonaBindingEntity().setId(7L));
        when(identities.activateForFirstRegistration(identity)).thenReturn(identity);
        var accounts=mock(cn.jia.user.security.AccountSecurityService.class);
        when(accounts.findUniqueByExactJiacn(ACTOR.ownerJiacn())).thenReturn(Optional.of(new cn.jia.user.security.AccountSecuritySnapshot(1,ACTOR.ownerJiacn(),cn.jia.user.security.AccountState.ACTIVE,1)));
        var authority=new AgentRuntimeAuthenticationService(rows,installs,mock(AgentIdentityRegistryDao.class),identities,accounts,
            new cn.jia.agent.config.AgentTaskEventsGate(new cn.jia.agent.config.AgentTaskEventsProperties(true,List.of(new cn.jia.agent.config.AgentTaskEventsProperties.AllowedScope("0",ACTOR.clientId())))));
        var advice=new org.springframework.transaction.interceptor.TransactionInterceptor();
        advice.setTransactionManager(manager);
        advice.setTransactionAttributeSource(new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource());
        var proxy=new org.springframework.aop.framework.ProxyFactory(authority);proxy.setProxyTargetClass(true);proxy.addAdvice(advice);
        authentication=(AgentRuntimeAuthenticationService)proxy.getProxy();
        var agents=mock(AgentService.class);var owner=mock(HostingRentOwnerResolver.class);
        var dto=new AgentRuntimeDTO();dto.setAgentId(AGENT);dto.setSystemAgent(false);dto.setStatus("online");
        when(owner.requireOwner(ACTOR)).thenReturn(ACTOR.ownerJiacn());
        when(agents.requireApiKeyOwnedAgentForUpdate(ACTOR.clientId(),ACTOR.ownerJiacn(),AGENT)).thenReturn(dto);
        when(agents.requireApiKeyOwnedAgent(ACTOR.clientId(),ACTOR.ownerJiacn(),AGENT)).thenReturn(dto);
        versions=new SkillAgentVersions(app,rows,provider(agents),owner,manager,true);
        service=new SkillManagementService(db,market,app,marketplace,versions,rows,installs,authentication,provider(packages),provider(writer),provider(sessions),provider(capabilities),manager);
    }
    /** Immutable snapshot of ALL relevant SQL business rows, including versions and transport leases. */
    protected Map<String,Object> businessSnapshot() {
        Map<String,Object> snapshot=new LinkedHashMap<>();
        for(String table:List.of("economy_account","economy_escrow","economy_escrow_funding_lot","economy_transaction","economy_entry","economy_skill_order","economy_skill_entitlement",
                "economy_skill_installation","economy_skill_actor_root","economy_skill_agent_version","economy_skill_managed_state","economy_skill_change","economy_skill_change_command",
                "economy_skill_management_result","economy_skill_installation_authorization","economy_skill_management_request","economy_skill_management_quote","economy_skill_delivery_binding","economy_skill_result_receipt","agent_command_delivery","agent_outbox_event")) {
            var rows=new ArrayList<Map<String,String>>();
            for(var row:jdbc.queryForList("SELECT * FROM "+table+" ORDER BY 1")) {
                Map<String,String> values=new LinkedHashMap<>();
                row.forEach((key,value)->values.put(key,value instanceof byte[] bytes?HexFormat.of().formatHex(bytes):Objects.toString(value,null)));rows.add(values);
            }
            snapshot.put(table,rows);
        }
        return snapshot;
    }
    protected Map<String,Object> acknowledgement(Map<String,Object> change) {
        var cmd=command(change);var row=db.findCommand("0",ACTOR.clientId(),text(cmd,"commandId"));
        return map("protocolVersion",1,"requestId","req-ack","messageType","command.ack","messageId","ack_"+UUID.randomUUID(),"correlationId",row.getMessageId(),"code","E0","msg","ok",
            "data",map("schemaVersion",2,"skillChangeId",change.get("skillChangeId"),"commandId",cmd.get("commandId"),"attempt",cmd.get("attempt"),"fencingToken",cmd.get("fencingToken"),"deliveryEpoch",cmd.get("deliveryEpoch"),"ackStatus","RECEIVED"));
    }
    @SuppressWarnings("unchecked") static <T> ObjectProvider<T> provider(T value) {ObjectProvider<T> p=mock(ObjectProvider.class);when(p.getIfAvailable()).thenReturn(value);when(p.getObject()).thenReturn(value);return p;}
    protected AgentRuntimeAuthenticationService.Proof proof(){return new AgentRuntimeAuthenticationService.Proof(new AgentRuntimeAuthentication.Scope("0",ACTOR.clientId(),ACTOR.ownerJiacn(),AGENT,"runtime-1"),runtime.getRuntimeInstallationId(),"host-1",1L,"b".repeat(64),1L,1L);}
    protected Map<String,Object> quoteData() {return map("action","INSTALL","agentId",AGENT,"skillKey","repo-test","sourceSkillInstallationId",null,"targetProductVersionId","spv_repo_test_1_0_0",
        "expectedAgentVersion",Long.toString(versions.readOwned(ACTOR,AGENT)),"expectedSkillRevision","0","approvedPermissions",List.of());}
    protected Map<String,Object> creation(Map<String,Object> q,Map<String,Object> request) {var data=new LinkedHashMap<>(request);data.remove("agentId");data.put("quoteId",q.get("quoteId"));data.put("expectedPrice",q.get("price"));return data;}
    protected Map<String,Object> install() {var data=quoteData();var q=service.quote(ACTOR,"q-key",data,false);return service.create(ACTOR,AGENT,"create-key","request-create",creation(q,data),false);}
    protected Map<String,Object> current(Map<String,Object> change){return service.change(ACTOR,AGENT,text(change,"skillChangeId"));}
    protected Map<String,Object> command(Map<String,Object> value){var c=db.findChange("0",ACTOR.clientId(),text(value,"skillChangeId"));var cmd=db.findCommand("0",ACTOR.clientId(),c.getCommandId());return read(cmd.getDataJson().getBytes(java.nio.charset.StandardCharsets.UTF_8),"CommandData");}
    protected Map<String,Object> evidence(Map<String,Object> value,String outcome,String reason) {
        var cmd=command(value);String phase=text(cmd,"phase");
        String prepared="PREPARED".equals(outcome)?hash("cyf.skill.prepared.v1",map("skillChangeId",value.get("skillChangeId"),"attempt",value.get("attempt"),"source",value.get("source"),"target",value.get("target"),"approvedPermissionsSha256",cmd.get("approvedPermissionsSha256"))):null;
        return map("observedCommandId",cmd.get("commandId"),"phase",phase,"outcome",outcome,"actual","APPLIED".equals(outcome)?value.get("target"):null,
            "appliedRevision","APPLIED".equals(outcome)?value.get("skillRevision"):"0","preparedSha256",prepared,"readiness","NOT_EXECUTABLE","reasonCode",reason,"observedAt",System.currentTimeMillis());
    }
    protected Map<String,Object> result(Map<String,Object> value,Map<String,Object> evidence) {
        var cmd=command(value);var data=map("schemaVersion",2,"resultType","SKILL_CHANGE_RESULT","skillChangeId",value.get("skillChangeId"),"commandId",cmd.get("commandId"),
            "agentId",AGENT,"runtimeInstallationId",runtime.getRuntimeInstallationId(),"executionRuntimeInstanceId","runtime-1","attempt",cmd.get("attempt"),
            "fencingToken",cmd.get("fencingToken"),"deliveryEpoch",cmd.get("deliveryEpoch"),"skillRevision",cmd.get("skillRevision"),"evidence",evidence);
        data.put("resultSha256",hash("cyf.skill.result.v2",data));return map("protocolVersion",1,"requestId","req-result","messageType","work.result","messageId","result_"+UUID.randomUUID(),"data",data);
    }
    void report(Map<String,Object> value,String outcome,String reason){service.result(proof(),result(value,evidence(value,outcome,reason)));}
    void catalog(Map<String,Object> value) {
        catalog(value,"EXECUTABLE".equals(object(value.get("target")).get("kind"))?SkillCapabilityLookup.Readiness.READY:SkillCapabilityLookup.Readiness.NOT_EXECUTABLE);
    }
    void catalog(Map<String,Object> value,SkillCapabilityLookup.Readiness readiness) {
        var target=object(value.get("target"));var fact=new SkillCapabilityLookup.RuntimeSkill(text(value,"skillKey"),text(target,"skillVersion"),text(target,"packageSha256"),text(target,"manifestSha256"),"EXECUTABLE".equals(target.get("kind"))?"native-model":null,
            readiness,null,readiness==SkillCapabilityLookup.Readiness.READY?"c".repeat(64):null,null,text(target,"skillInstallationId"),text(target,"productVersionId"),text(target,"kind"),text(value,"skillRevision"),Boolean.TRUE.equals(value.get("desiredEnabled")),System.currentTimeMillis());
        var fence=new AgentManagedSessionLookup.SessionFence(runtime.getRuntimeInstallationId(),"host-1","runtime-1",1L);
        when(capabilities.lookup(any(),any())).thenReturn(new SkillCapabilityLookup.View(SkillCapabilityLookup.State.AVAILABLE,"session-1",fence,new SkillCapabilityLookup.Catalog("1",List.of(fact))));
        service.capabilitiesChanged(proof());
    }
    long wallet(){return jdbc.queryForObject("SELECT balance_micro FROM economy_account WHERE account_id='wallet'",Long.class);}
    int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}

    protected void publishExecutable(String version,long sequence) throws Exception {
        String manifest="""
            {"schemaVersion":1,"skillKey":"pdf-export","skillVersion":"%s","title":"PDF","description":"synthetic approved fixture",
             "runner":"native-model","entrypoint":"pdf.export","parametersSchema":{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object"},
             "inputs":{"minItems":0,"maxItems":0,"acceptedMimeTypes":[]},
             "outputs":{"dataSchema":null,"mimeTypes":["application/pdf"],"multiple":false,"artifactsRequired":true},"requirements":{}}
            """.formatted(version);
        var out=new java.io.ByteArrayOutputStream();
        try(var zip=new java.util.zip.ZipOutputStream(out)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("SKILL.md"));zip.write(("---\nname: pdf-export\ndescription: export pdf\nmetadata:\n  version: \""+version+"\"\n---\nExport PDF.\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("manifest.json"));zip.write(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
        }
        byte[] bytes=out.toByteArray();new SkillPackageStore(scratch.resolve(database+"-packages")).publish(bytes);
        if(sequence==1)market.insertProduct(new SkillProductEntity().setProductId("sp_pdf").setSellerType("SYSTEM").setSellerId("SKILL_STORE").setName("PDF").setDescription("synthetic fixture")
                .setStatus("DRAFT").setVersion(1L).setTenantId("0").setClientId(ACTOR.clientId()).setCreateTime(1L).setUpdateTime(1L));
        market.insertProductVersion(new SkillProductVersionEntity().setProductVersionId("spv_pdf_"+sequence).setProductId("sp_pdf").setVersionSequence(sequence)
            .setSkillKey("pdf-export").setSkillVersion(version).setPriceMicro(5_000_000L).setPackageSha256(SkillPackageStore.sha256(bytes)).setPackageSize((long)bytes.length)
            .setApprovedPermissionsManifest("[]").setApprovedPermissionsSha256(SkillPackageStore.sha256("[]".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .setDeploymentRestriction("NONE").setReviewStatus("APPROVED").setTenantId("0").setClientId(ACTOR.clientId()).setCreateTime(1L));
        assertEquals(1,market.publishProductVersion("0",ACTOR.clientId(),"sp_pdf","spv_pdf_"+sequence,sequence,System.currentTimeMillis()));
    }
    protected Map<String,Object> packageChange(String action,String source,String revision,String target) {
        var request=map("action",action,"agentId",AGENT,"skillKey","pdf-export","sourceSkillInstallationId",source,"targetProductVersionId",target,
            "expectedAgentVersion",Long.toString(versions.readOwned(ACTOR,AGENT)),"expectedSkillRevision",revision,"approvedPermissions",List.of());
        var q=service.quote(ACTOR,"q-"+UUID.randomUUID(),request,false);return service.create(ACTOR,AGENT,"c-"+UUID.randomUUID(),"request",creation(q,request),false);
    }
    protected Map<String,Object> policyChange(String action,Map<String,Object> c) {
        return service.create(ACTOR,AGENT,"p-"+UUID.randomUUID(),"request",map("action",action,"skillKey",c.get("skillKey"),"sourceSkillInstallationId",object(c.get("target")).get("skillInstallationId"),
            "expectedAgentVersion",Long.toString(versions.readOwned(ACTOR,AGENT)),"expectedSkillRevision",c.get("skillRevision")),false);
    }
    void finishInstall(Map<String,Object> c) {report(c,"PREPARED",null);report(current(c),"APPLIED",null);catalog(current(c));}
    protected SkillCapabilityLookup.Scope scope(){return new SkillCapabilityLookup.Scope("0",ACTOR.clientId(),ACTOR.ownerJiacn(),AGENT);}
    @Test void coveredUpgradePreservesExactOldTaskReferenceAndClosesAdmissionOnlyAtActivation() throws Exception {
        publishExecutable("1.0.0",1);var first=packageChange("INSTALL",null,"0","spv_pdf_1");finishInstall(first);first=current(first);
        String old=text(object(first.get("target")),"skillInstallationId");
        var frozen=service.admit(scope(),"pdf-export",old,()->service.taskBinding("0",ACTOR.clientId(),"exec-old","grant-old",1,AGENT,old));
        publishExecutable("2.0.0",2);
        var base=first;
        var missing=assertThrows(SkillMarketplaceException.class,()->packageChange("UPGRADE",old,text(base,"skillRevision"),"spv_pdf_2"));assertEquals("SKILL_UPGRADE_POLICY_UNSET",missing.code());
        jdbc.update("INSERT INTO economy_skill_upgrade_policy VALUES (?,?,?,?,?)","0",ACTOR.clientId(),"spv_pdf_1","spv_pdf_2","COVERED");
        var upgrade=packageChange("UPGRADE",old,text(first,"skillRevision"),"spv_pdf_2");assertNull(upgrade.get("orderId"));assertEquals("NONE",upgrade.get("fundingStatus"));
        assertEquals("old-active",service.admit(scope(),"pdf-export",old,()->"old-active"));
        report(upgrade,"FAILED","DEPENDENCY_MISSING");upgrade=current(upgrade);
        assertEquals("still-old",service.admit(scope(),"pdf-export",old,()->"still-old"));
        upgrade=service.retry(ACTOR,AGENT,text(upgrade,"skillChangeId"),"upgrade-retry","retry-request",map("expectedChangeRevision",upgrade.get("changeRevision")));
        report(upgrade,"PREPARED",null);upgrade=current(upgrade);
        assertThrows(SkillMarketplaceException.class,()->service.admit(scope(),"pdf-export",old,()->"never"));
        report(upgrade,"APPLIED",null);catalog(current(upgrade));upgrade=current(upgrade);assertEquals("SUCCEEDED",upgrade.get("status"));
        String newest=text(object(upgrade.get("target")),"skillInstallationId");assertNotEquals(old,newest);
        assertEquals(old,frozen.get("skillInstallationId"));assertEquals("spv_pdf_1",frozen.get("productVersionId"));
        assertEquals(2,service.descriptors(scope()).size()); // old exact executable remains recoverable
        assertEquals("new",service.admit(scope(),"pdf-export",newest,()->"new"));assertEquals(1,count("economy_skill_order"));assertEquals(95_000_000L,wallet());
    }
    /** Convert a private successful fixture into an exact historical installation plus adoption
     * projection. No new protocol command/result is invented by the migration. */
    private Map<String,Object> adoptedFixture() throws Exception {
        publishExecutable("1.0.0",1);var change=packageChange("INSTALL",null,"0","spv_pdf_1");finishInstall(change);change=current(change);
        String installation=text(object(change.get("target")),"skillInstallationId");
        var auth=db.findAuthorization("0",ACTOR.clientId(),installation);
        byte[] receiptHash=java.util.HexFormat.of().parseHex("d".repeat(64));
        app.insertResult("0",ACTOR.clientId(),"historical-receipt",installation,receiptHash,"ACTIVE",1L);
        String local="e".repeat(64),migration="adopt_"+hash("cyf.skill.adoption.identity.v1",map("tenantId","0","clientId",ACTOR.clientId(),"skillInstallationId",installation,"localEvidenceSha256",local));
        var proof=map("schemaVersion",1,"migrationId",migration,"actorId",ACTOR.actorId(),"ownerJiacn",ACTOR.ownerJiacn(),"agentId",AGENT,"skillKey","pdf-export",
                "runtimeInstallationId",runtime.getRuntimeInstallationId(),"runtimeHostId","host-1","receiptMessageId","historical-receipt","receiptSha256",HexFormat.of().formatHex(receiptHash),
                "package",object(change.get("target")),"approvedPermissionsSha256",auth.getPermissionsSha256(),"localEvidenceSha256",local);
        proof.put("evidenceSha256",hash("cyf.skill.adoption.v1",proof));
        jdbc.update("UPDATE economy_skill_installation_authorization SET change_id=?,adoption_evidence_json=? WHERE installation_id=?",migration,json(proof),installation);
        jdbc.update("DELETE FROM economy_skill_change_command");jdbc.update("DELETE FROM economy_skill_change");jdbc.update("DELETE FROM economy_skill_management_result");
        jdbc.update("UPDATE economy_skill_installation SET schema_version=1");
        return change;
    }
    @Test void exactHistoricalAdoptionSupportsDescriptorsAdmissionFrozenBindingAndDisableWithoutInventedApplied() throws Exception {
        var adopted=adoptedFixture();String id=text(object(adopted.get("target")),"skillInstallationId");catalog(adopted);
        assertEquals(0,count("economy_skill_change"));assertEquals(0,count("economy_skill_change_command"));assertEquals(0,count("economy_skill_management_result"));
        assertEquals(1,service.descriptors(scope()).size());
        var auth=db.findAuthorization("0",ACTOR.clientId(),id);
        assertTrue(Boolean.TRUE.equals(tx.execute(s->service.qualifies(scope(),auth))));
        assertEquals(id,service.admit(scope(),"pdf-export",id,()->service.taskBinding("0",ACTOR.clientId(),"exec-adopted","grant-adopted",1,AGENT,id)).get("skillInstallationId"));
        var listed=service.skills(ACTOR,AGENT,false);assertEquals(1,((List<?>)listed.get("items")).size());
        var item=object(((List<?>)listed.get("items")).getFirst());assertNull(item.get("latestChange"));assertTrue(((List<?>)item.get("allowedActions")).contains("DISABLE"));
        assertEquals(1,count("economy_skill_order"));assertEquals(1,count("economy_skill_entitlement"));assertEquals(95_000_000L,wallet());
        assertTrue(service.descriptors(new SkillCapabilityLookup.Scope("0",ACTOR.clientId(),"wrong-owner",AGENT)).isEmpty());
        assertTrue(service.descriptors(new SkillCapabilityLookup.Scope("0","other-client",ACTOR.ownerJiacn(),AGENT)).isEmpty());
    }
    @Test void historicalAdoptionRejectsChangedReceiptDigestPackageOrLocalProofWithoutFinancialSideEffects() throws Exception {
        var adopted=adoptedFixture();catalog(adopted);String id=text(object(adopted.get("target")),"skillInstallationId");
        var original=jdbc.queryForObject("SELECT adoption_evidence_json FROM economy_skill_installation_authorization",String.class);
        var beforeWallet=wallet();
        jdbc.update("UPDATE economy_skill_result_receipt SET request_hash=?",new byte[32]);
        assertTrue(service.descriptors(scope()).isEmpty());
        assertFalse(Boolean.TRUE.equals(tx.execute(s->service.qualifies(scope(),db.findAuthorization("0",ACTOR.clientId(),id)))));
        jdbc.update("UPDATE economy_skill_result_receipt SET request_hash=?",HexFormat.of().parseHex("d".repeat(64)));
        assertEquals(1,service.descriptors(scope()).size());
        for(String key:List.of("ownerJiacn","agentId","skillKey","receiptSha256","localEvidenceSha256","approvedPermissionsSha256")) {
            var forged=read(original.getBytes(java.nio.charset.StandardCharsets.UTF_8),null);forged.put(key,"f".repeat(64));
            jdbc.update("UPDATE economy_skill_installation_authorization SET adoption_evidence_json=?",json(forged));
            assertTrue(service.descriptors(scope()).isEmpty(),key);
        }
        jdbc.update("UPDATE economy_skill_installation_authorization SET adoption_evidence_json=?",original);
        jdbc.update("UPDATE economy_skill_installation SET package_sha256=?",new byte[32]);assertTrue(service.descriptors(scope()).isEmpty());
        assertEquals(beforeWallet,wallet());assertEquals(1,count("economy_skill_order"));assertEquals(0,count("economy_skill_change_command"));
    }
    @Test void historicalActiveWithoutExplicitAdoptionNeverQualifiesOrCreatesNewPurchase() throws Exception {
        var adopted=adoptedFixture();catalog(adopted);String id=text(object(adopted.get("target")),"skillInstallationId");
        jdbc.update("UPDATE economy_skill_installation_authorization SET adoption_evidence_json=NULL");
        assertTrue(service.descriptors(scope()).isEmpty());
        assertFalse(Boolean.TRUE.equals(tx.execute(s->service.qualifies(scope(),db.findAuthorization("0",ACTOR.clientId(),id)))));
        assertThrows(SkillMarketplaceException.class,()->service.admit(scope(),"pdf-export",id,()->"never"));
        assertEquals(0,count("economy_skill_change_command"));assertEquals(1,count("economy_skill_order"));
    }
    @Test void explicitPaidUpgradeReservesAndCapturesFullApprovedTargetPriceOnlyOnce() throws Exception {
        publishExecutable("1.0.0",1);var first=packageChange("INSTALL",null,"0","spv_pdf_1");finishInstall(first);first=current(first);
        publishExecutable("2.0.0",2);
        jdbc.update("INSERT INTO economy_skill_upgrade_policy VALUES (?,?,?,?,?)","0",ACTOR.clientId(),"spv_pdf_1","spv_pdf_2","PAID");
        var upgrade=packageChange("UPGRADE",text(object(first.get("target")),"skillInstallationId"),text(first,"skillRevision"),"spv_pdf_2");
        assertNotNull(upgrade.get("orderId"));assertNotEquals(first.get("orderId"),upgrade.get("orderId"));
        assertEquals("5000000",object(upgrade.get("price")).get("amountMinor"));assertEquals("HELD",upgrade.get("fundingStatus"));
        assertEquals(90_000_000L,wallet());report(upgrade,"PREPARED",null);upgrade=current(upgrade);
        var applied=result(upgrade,evidence(upgrade,"APPLIED",null));service.result(proof(),applied);service.result(proof(),applied);
        catalog(current(upgrade));assertEquals("SUCCEEDED",current(upgrade).get("status"));assertEquals("CAPTURED",current(upgrade).get("fundingStatus"));
        assertEquals(2,count("economy_skill_order"));assertEquals(90_000_000L,wallet());
    }
    @Test void notAllowedUpgradeCannotCreateQuoteOrderOrInstallation() throws Exception {
        publishExecutable("1.0.0",1);var first=packageChange("INSTALL",null,"0","spv_pdf_1");finishInstall(first);first=current(first);
        publishExecutable("2.0.0",2);jdbc.update("INSERT INTO economy_skill_upgrade_policy VALUES (?,?,?,?,?)","0",ACTOR.clientId(),"spv_pdf_1","spv_pdf_2","NOT_ALLOWED");
        String source=text(object(first.get("target")),"skillInstallationId"),revision=text(first,"skillRevision");
        assertEquals("SKILL_UPGRADE_NOT_ALLOWED",assertThrows(SkillMarketplaceException.class,()->packageChange("UPGRADE",source,revision,"spv_pdf_2")).code());
        assertEquals(1,count("economy_skill_order"));assertEquals(1,count("economy_skill_installation"));assertEquals(1,count("economy_skill_management_quote"));
        assertEquals(95_000_000L,wallet());assertEquals("still-active",service.admit(scope(),"pdf-export",source,()->"still-active"));
    }
    @Test void authorizedLateOldAttemptIsAuditedWithoutChangingNewAttemptOrRefunding() {
        var first=install();var oldResult=result(first,evidence(first,"FAILED","DEPENDENCY_MISSING"));
        service.result(proof(),oldResult);var failed=current(first);
        var retry=service.retry(ACTOR,AGENT,text(failed,"skillChangeId"),"retry-late","retry",map("expectedChangeRevision",failed.get("changeRevision")));
        var late=new LinkedHashMap<>(oldResult);late.put("messageId","late-old-attempt");
        var receipt=service.result(proof(),late);assertEquals("IGNORED_STALE",object(receipt.get("data")).get("receiptStatus"));
        assertEquals(retry,current(retry));assertEquals(70_000_000L,wallet());assertEquals(1,count("economy_skill_order"));
        assertEquals(2,count("economy_skill_installation"));
    }
    @Test void disableClosesApiAdmissionImmediatelyAndEnableRetryRetainsOriginalInstallation() throws Exception {
        publishExecutable("1.0.0",1);var first=packageChange("INSTALL",null,"0","spv_pdf_1");finishInstall(first);first=current(first);
        String installation=text(object(first.get("target")),"skillInstallationId");
        when(sessions.isReady(anyString(),anyString(),anyString(),any())).thenReturn(false);
        var disable=policyChange("DISABLE",first);assertEquals("SUCCEEDED",disable.get("status"));assertEquals(false,disable.get("runtimeSynced"));
        assertThrows(SkillMarketplaceException.class,()->service.admit(scope(),"pdf-export",installation,()->"never"));
        var offline=disable;assertEquals("SKILL_AGENT_OFFLINE",assertThrows(SkillMarketplaceException.class,()->policyChange("ENABLE",offline)).code());
        when(sessions.isReady(anyString(),anyString(),anyString(),any())).thenReturn(true);
        var enable=policyChange("ENABLE",disable);report(enable,"FAILED","DEPENDENCY_MISSING");enable=current(enable);
        var retry=service.retry(ACTOR,AGENT,text(enable,"skillChangeId"),"enable-retry","retry",map("expectedChangeRevision",enable.get("changeRevision")));
        assertEquals(installation,object(retry.get("target")).get("skillInstallationId"));assertEquals(1,count("economy_skill_installation"));assertEquals("POLICY",command(retry).get("phase"));
        report(retry,"APPLIED",null);catalog(current(retry));assertEquals("yes",service.admit(scope(),"pdf-export",installation,()->"yes"));
    }

    @Test void disabledPolicySynchronizesDespiteMissingRunnerDependenciesWithoutRevokingOldTaskBinding() throws Exception {
        publishExecutable("1.0.0",1);var first=packageChange("INSTALL",null,"0","spv_pdf_1");finishInstall(first);first=current(first);
        String installation=text(object(first.get("target")),"skillInstallationId");
        var frozen=service.admit(scope(),"pdf-export",installation,()->service.taskBinding("0",ACTOR.clientId(),"old-execution","old-grant",1,AGENT,installation));
        var disable=policyChange("DISABLE",first);report(disable,"APPLIED",null);
        catalog(current(disable),SkillCapabilityLookup.Readiness.DEPENDENCY_MISSING);disable=current(disable);
        assertEquals("SUCCEEDED",disable.get("status"));assertEquals(true,disable.get("runtimeSynced"));assertEquals(false,disable.get("desiredEnabled"));
        assertThrows(SkillMarketplaceException.class,()->service.admit(scope(),"pdf-export",installation,()->"never"));
        assertEquals(installation,frozen.get("skillInstallationId"));assertEquals(1,service.descriptors(scope()).size());
    }

    @Test void exactTaskAttachmentCommitsAndRollsBackWithTheRealAdmissionTransaction() throws Exception {
        publishExecutable("1.0.0",1);var first=packageChange("INSTALL",null,"0","spv_pdf_1");finishInstall(first);first=current(first);
        String installation=text(object(first.get("target")),"skillInstallationId");var target=object(first.get("target"));
        var snapshot=map("skill",map("installationId",installation,"packageSha256",target.get("packageSha256")),"taskGrant",map("id","grant","version",1));
        String frozen=json(snapshot),digest=HexFormat.of().formatHex(SkillPackageStore.sha256(frozen.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var insert=(java.util.function.Consumer<String>)(execution->{
            var binding=service.taskBinding("0",ACTOR.clientId(),execution,"grant",1,AGENT,installation);
            taskAttachments.insertReady(new SkillExecutionEntity().setTenantId("0").setClientId(ACTOR.clientId()).setOwnerJiacn(ACTOR.ownerJiacn())
                .setExecutionId(execution).setSkillKey("pdf-export").setSkillVersion("1.0.0").setPackageSha256(text(target,"packageSha256"))
                .setManifestSha256(text(target,"manifestSha256")).setSnapshotJson(frozen).setSnapshotSha256(digest).setTaskSkillBindingJson(json(binding)).setCreatedAt(1L));
        });
        assertThrows(IllegalStateException.class,()->service.admit(scope(),"pdf-export",installation,()->{insert.accept("exec-rolled-back");throw new IllegalStateException("synthetic task authorization failure");}));
        assertNull(taskAttachments.find("0",ACTOR.clientId(),ACTOR.ownerJiacn(),"exec-rolled-back"));
        service.admit(scope(),"pdf-export",installation,()->{insert.accept("exec-pinned");return null;});
        var row=taskAttachments.find("0",ACTOR.clientId(),ACTOR.ownerJiacn(),"exec-pinned");assertNotNull(row);
        assertEquals(digest,row.getSnapshotSha256());assertEquals(installation,read(row.getTaskSkillBindingJson().getBytes(java.nio.charset.StandardCharsets.UTF_8),"TaskSkillBinding").get("skillInstallationId"));
        policyChange("DISABLE",first);assertEquals(row.getTaskSkillBindingJson(),taskAttachments.find("0",ACTOR.clientId(),ACTOR.ownerJiacn(),"exec-pinned").getTaskSkillBindingJson());
        assertThrows(SkillMarketplaceException.class,()->service.admit(scope(),"pdf-export",installation,()->{insert.accept("exec-denied");return null;}));
        assertNull(taskAttachments.find("0",ACTOR.clientId(),ACTOR.ownerJiacn(),"exec-denied"));
        // This exercises actual admission + attachment SQL; full task-grant ACL is a separate PWE consumer test.
    }
    @Test void taskBindingIncrementalMigrationPreservesHistoricalSnapshotAndOnlyAttachesOnce() {
        jdbc.execute("ALTER TABLE agent_skill_execution DROP COLUMN task_skill_binding_json");
        jdbc.update("INSERT INTO agent_skill_execution(tenant_id,client_id,owner_jiacn,execution_id,skill_key,skill_version,package_sha256,manifest_sha256,snapshot_json,snapshot_sha256,phase,effects_json,version,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,'READY','{}',0,1,1)",
            "0",ACTOR.clientId(),ACTOR.ownerJiacn(),"historical","pdf-export","1.0.0","a".repeat(64),"b".repeat(64),"{\"frozen\":true}","c".repeat(64));
        new ResourceDatabasePopulator(new ClassPathResource("db/agent-skill-management-sm-c1.sql")).execute(source(database));
        var before=taskAttachments.find("0",ACTOR.clientId(),ACTOR.ownerJiacn(),"historical");assertNull(before.getTaskSkillBindingJson());
        assertEquals(0,taskAttachments.bindTaskSkill("0",ACTOR.clientId(),ACTOR.ownerJiacn(),"historical","d".repeat(64),0,"{}",2));
        assertEquals(1,taskAttachments.bindTaskSkill("0",ACTOR.clientId(),ACTOR.ownerJiacn(),"historical","c".repeat(64),0,"{}",2));
        assertEquals(0,taskAttachments.bindTaskSkill("0",ACTOR.clientId(),ACTOR.ownerJiacn(),"historical","c".repeat(64),1,"{\"different\":true}",3));
        var after=taskAttachments.find("0",ACTOR.clientId(),ACTOR.ownerJiacn(),"historical");assertEquals(before.getSnapshotJson(),after.getSnapshotJson());assertEquals(before.getSnapshotSha256(),after.getSnapshotSha256());
    }

    @Test void offlineFirstInstallFlowsThroughRealOutboxPrepareActivateAppliedAndCurrentCatalog() {
        when(sessions.isReady(anyString(),anyString(),anyString(),any())).thenReturn(false);
        var c=install();assertEquals("WAITING_AGENT",c.get("status"));assertEquals(70_000_000L,wallet());
        assertEquals(1,count("economy_skill_order"));assertEquals(1,count("agent_outbox_event"));
        var pkg=service.packageBytes(proof(),text(object(c.get("target")),"skillInstallationId"));assertTrue(pkg.length>0);
        when(sessions.isReady(anyString(),anyString(),anyString(),any())).thenReturn(true);recovery.recover();
        verify(sessions).dispatch(eq("0"),eq(ACTOR.clientId()),eq(AGENT),any(),any());
        report(c,"PREPARED",null);c=current(c);assertEquals("ACTIVATING",c.get("status"));assertEquals("ACTIVATE",command(c).get("phase"));
        assertEquals(2,count("agent_outbox_event"));
        var applied=result(c,evidence(c,"APPLIED",null));var receipt=service.result(proof(),applied);assertEquals(receipt,service.result(proof(),applied));
        c=current(c);assertEquals("VERIFYING",c.get("status"));assertEquals("CAPTURED",c.get("fundingStatus"));
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM economy_skill_order",String.class));
        assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT status FROM economy_skill_installation",String.class));
        catalog(c);c=current(c);assertEquals("SUCCEEDED",c.get("status"));assertEquals(true,c.get("runtimeSynced"));
        var list=service.skills(ACTOR,AGENT,false);validate("SkillList",list);
        String installationId=text(object(c.get("target")),"skillInstallationId");
        // Instruction packages are discoverable but never executable task admission.
        assertThrows(SkillMarketplaceException.class,()->service.admit(new SkillCapabilityLookup.Scope("0",ACTOR.clientId(),ACTOR.ownerJiacn(),AGENT),"repo-test",installationId,()->"not-called"));
    }
    @Test void determinatePrepareFailureRetainsOriginalMoneyAndRetryGetsNewCandidateAndFence() {
        var c=install();var first=c;report(c,"FAILED","DEPENDENCY_MISSING");c=current(c);assertEquals("FAILED_RETRYABLE",c.get("status"));assertEquals(70_000_000L,wallet());
        var action=map("expectedChangeRevision",c.get("changeRevision"));var retried=service.retry(ACTOR,AGENT,text(c,"skillChangeId"),"retry-key","retry-request",action);
        assertEquals(retried,service.retry(ACTOR,AGENT,text(c,"skillChangeId"),"retry-key","retry-request",action));
        String changeId=text(c,"skillChangeId");var changedAction=map("expectedChangeRevision",retried.get("changeRevision"));
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(SkillMarketplaceException.class,
            ()->service.retry(ACTOR,AGENT,changeId,"retry-key","request",changedAction)).code());
        assertEquals("SKILL_STATE_CONFLICT",assertThrows(SkillMarketplaceException.class,
            ()->service.retry(ACTOR,AGENT,changeId,"new-retry-key","request",action)).code());
        assertEquals(first.get("orderId"),retried.get("orderId"));assertEquals(2,retried.get("attempt"));assertNotEquals(object(first.get("target")).get("skillInstallationId"),object(retried.get("target")).get("skillInstallationId"));
        assertEquals(1,count("economy_skill_order"));assertEquals(2,count("economy_skill_installation"));assertEquals(70_000_000L,wallet());
    }
    @Test void unknownNeverRefundsOrRetriesAndReadReconcileDoesNotInstall() {
        var c=install();report(c,"UNKNOWN","RECEIPT_UNKNOWN");c=current(c);assertEquals("RECOVERY_REQUIRED",c.get("status"));assertEquals("HELD",c.get("fundingStatus"));
        var action=map("expectedChangeRevision",c.get("changeRevision"));String id=text(c,"skillChangeId");
        assertThrows(SkillMarketplaceException.class,()->service.retry(ACTOR,AGENT,id,"retry-key","request",action));
        var reconciled=service.reconcile(ACTOR,AGENT,id,"read-1","request",action);
        assertEquals(reconciled,service.reconcile(ACTOR,AGENT,id,"read-1","replay-request",action));
        var changedAction=map("expectedChangeRevision",Long.toString(Long.parseLong(text(c,"changeRevision"))+1));
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(SkillMarketplaceException.class,
            ()->service.reconcile(ACTOR,AGENT,id,"read-1","request",changedAction)).code());
        assertEquals("SKILL_STATE_CONFLICT",assertThrows(SkillMarketplaceException.class,
            ()->service.reconcile(ACTOR,AGENT,id,"new-read-key","request",changedAction)).code());
        service.reconcile(ACTOR,AGENT,id,"read-2","request",action);
        assertEquals(1,count("economy_skill_installation"));assertEquals(2,count("agent_outbox_event"));assertEquals(70_000_000L,wallet());
    }
    @Test void rejectedAuthorizationRefundsOnceAndRefundedOrderNeverRevives() {
        var c=install();var message=result(c,evidence(c,"FAILED","ENTITLEMENT_TERMINATED"));validate("Result",message);
        service.result(proof(),message);service.result(proof(),message);
        c=current(c);assertEquals("FAILED_FINAL",c.get("status"));assertEquals("REFUNDED",c.get("fundingStatus"));assertEquals(100_000_000L,wallet());
        var action=map("expectedChangeRevision",c.get("changeRevision"));String id=text(c,"skillChangeId");
        assertThrows(SkillMarketplaceException.class,()->service.retry(ACTOR,AGENT,id,"retry","request",action));
        var list=service.skills(ACTOR,AGENT,false);
        var row=((List<?>)list.get("items")).stream().map(SkillManagementCodec::object).filter(v->"repo-test".equals(v.get("skillKey"))).findFirst().orElseThrow();
        assertTrue(((List<?>)row.get("allowedActions")).contains("INSTALL")); // confirmed failed managed facts are not unknown legacy evidence
    }
    @Test void outboxFailureRollsBackMoneyOrderInstallationAndIdempotencyAsOneTransaction() {
        var data=quoteData();var quote=service.quote(ACTOR,"quote",data,false);
        doThrow(new IllegalStateException("synthetic outbox failure")).when(writer).writeManagement(any(),any(),any(),any());
        assertThrows(IllegalStateException.class,()->service.create(ACTOR,AGENT,"create","request",creation(quote,data),false));
        assertEquals(100_000_000L,wallet());for(String table:List.of("economy_skill_order","economy_skill_change","economy_skill_installation","agent_outbox_event"))assertEquals(0,count(table),table);
        assertEquals(1,count("economy_skill_management_request")); // only quote accepted
    }
    @Test void simultaneousDuplicateCreateHasOneFinancialAndOutboxEffect() throws Exception {
        var data=quoteData();var quote=service.quote(ACTOR,"quote",data,false);var body=creation(quote,data);
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {
            Callable<Map<String,Object>> call=()->{start.await();return service.create(ACTOR,AGENT,"same-key","same-request",body,false);};
            var a=pool.submit(call);var b=pool.submit(call);start.countDown();assertEquals(a.get().get("skillChangeId"),b.get().get("skillChangeId"));
        }finally {pool.shutdownNow();}
        assertEquals(70_000_000L,wallet());assertEquals(1,count("economy_skill_order"));assertEquals(1,count("agent_outbox_event"));
    }
    @Test void acceptedQuoteAndCreateReplayPreserveCasAndIdempotencyAndOwnerBoundaries() {
        var data=quoteData();var quote=service.quote(ACTOR,"quote",data,false);var body=creation(quote,data);
        var accepted=service.create(ACTOR,AGENT,"accepted","first-request",body,false);
        assertEquals(accepted,service.create(ACTOR,AGENT,"accepted","replay-request",body,false));
        assertEquals(quote,service.quote(ACTOR,"quote",data,false)); // Original expectedAgentVersion is now stale.
        var alteredQuote=new LinkedHashMap<>(data);alteredQuote.put("expectedAgentVersion",Long.toString(versions.readOwned(ACTOR,AGENT)));
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(SkillMarketplaceException.class,()->service.quote(ACTOR,"quote",alteredQuote,false)).code());
        assertEquals("SKILL_STATE_CONFLICT",assertThrows(SkillMarketplaceException.class,()->service.quote(ACTOR,"new-quote-key",data,false)).code());
        assertEquals("SKILL_STATE_CONFLICT",assertThrows(SkillMarketplaceException.class,
            ()->service.create(ACTOR,AGENT,"new-key","request",body,false)).code());
        var altered=new LinkedHashMap<>(body);altered.put("expectedAgentVersion",Long.toString(versions.readOwned(ACTOR,AGENT)));
        assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(SkillMarketplaceException.class,
            ()->service.create(ACTOR,AGENT,"accepted","request",altered,false)).code());
        assertThrows(RuntimeException.class,()->service.create(new HostingRentHttp.Actor(ACTOR.actorId(),"0",ACTOR.clientId(),"foreign-owner"),
            AGENT,"accepted","request",body,false));
        assertEquals(70_000_000L,wallet());assertEquals(1,count("economy_skill_order"));assertEquals(1,count("agent_outbox_event"));
    }
    @Test void terminalReasonCannotRefundUnknownPreparation() {
        var c=install();var unknown=result(c,evidence(c,"UNKNOWN","ENTITLEMENT_TERMINATED"));validate("Result",unknown);
        service.result(proof(),unknown);c=current(c);assertEquals("RECOVERY_REQUIRED",c.get("status"));
        assertEquals("HELD",c.get("fundingStatus"));assertEquals(false,c.get("retryAllowed"));assertEquals(70_000_000L,wallet());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM economy_skill_order WHERE status='REFUNDED'",Integer.class));
    }
    @Test void terminalReasonCannotRefundUnprovenActivation() {
        var c=install();report(c,"PREPARED",null);c=current(c);assertEquals("ACTIVATING",c.get("status"));
        var failed=result(c,evidence(c,"FAILED","ENTITLEMENT_TERMINATED"));validate("Result",failed);
        service.result(proof(),failed);c=current(c);assertEquals("RECOVERY_REQUIRED",c.get("status"));
        assertEquals("HELD",c.get("fundingStatus"));assertEquals(false,c.get("retryAllowed"));assertEquals(70_000_000L,wallet());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM economy_skill_order WHERE status='REFUNDED'",Integer.class));
    }
    @Test void getQueriesDoNotCreateVersionStateOrCommandsAndCrossOwnerCannotRead() {
        var list=service.skills(ACTOR,AGENT,false);assertFalse(((List<?>)list.get("items")).isEmpty());
        for(String table:List.of("economy_skill_agent_version","economy_skill_managed_state","agent_outbox_event"))assertEquals(0,count(table));
        assertThrows(RuntimeException.class,()->service.skills(new HostingRentHttp.Actor("other","0",ACTOR.clientId(),"other-owner"),AGENT,false));
    }
    @Test void nativeRecoveryClaimAndFinishCasRejectWrongLeaseAndCaseChangedScope() {
        install();long now=System.currentTimeMillis();var d=recoveryMapper.candidates(now,now-60000).getFirst();
        tx.executeWithoutResult(status->{
            assertEquals(1,recoveryMapper.claimDelivery(d,"lease-A",now+60000,now));
            assertEquals(0,recoveryMapper.claimDelivery(d,"lease-B",now+60000,now));
            var current=recoveryDao.lockDelivery("0",ACTOR.clientId(),d.getId());
            assertEquals(0,recoveryMapper.finishDelivery(current,"lease-a","SENT",null,"wrong lease",now));
            current.setClientId(ACTOR.clientId().toUpperCase(Locale.ROOT));
            assertEquals(0,recoveryMapper.finishDelivery(current,"lease-A","SENT",null,"wrong client",now));
            current.setClientId(ACTOR.clientId());current.setOwnerJiacn(ACTOR.ownerJiacn().toUpperCase(Locale.ROOT));
            assertEquals(0,recoveryMapper.finishDelivery(current,"lease-A","SENT",null,"wrong owner",now));
            current.setOwnerJiacn(ACTOR.ownerJiacn());current.setCommandId(current.getCommandId().toUpperCase(Locale.ROOT));
            assertEquals(0,recoveryMapper.finishDelivery(current,"lease-A","SENT",null,"wrong command",now));
            var o=recoveryDao.lockActiveOutboxes("0",ACTOR.clientId(),d.getId(),d.getActiveMessageId()).getFirst();
            assertEquals(1,recoveryMapper.claimOutbox(o,"lease-A",now+60000,now));
            assertEquals(0,recoveryMapper.claimOutbox(o,"lease-B",now+60000,now));
            o=recoveryDao.lockActiveOutboxes("0",ACTOR.clientId(),d.getId(),d.getActiveMessageId()).getFirst();
            assertEquals(0,recoveryMapper.finishOutbox(o,"lease-a","SENT",null,"wrong lease",now));
            o.setClientId(ACTOR.clientId().toUpperCase(Locale.ROOT));
            assertEquals(0,recoveryMapper.finishOutbox(o,"lease-A","SENT",null,"wrong scope",now));
        });
        assertEquals("lease-A",jdbc.queryForObject("SELECT lease_owner FROM agent_command_delivery",String.class));
        assertEquals("lease-A",jdbc.queryForObject("SELECT lease_owner FROM agent_outbox_event",String.class));
        assertTrue(recoveryMapper.candidates(now,now-60000).isEmpty());
    }
    @Test void nativeRecoveryRespectsLiveLeaseThenReclaimsExpiredLeaseWithoutMintingWireOrRabbitWork() {
        var c=install();byte[] wire=jdbc.queryForObject("SELECT wire_payload FROM agent_outbox_event",byte[].class);
        long now=System.currentTimeMillis();var d=recoveryMapper.candidates(now,now-60000).getFirst();
        jdbc.update("UPDATE agent_command_delivery SET status='CONSUMED',lease_owner='prior',lease_until=?,update_time=?",now+60000,now-70000);
        jdbc.update("UPDATE agent_outbox_event SET status='CLAIMED',lease_owner='prior',lease_until=?,update_time=?",now+60000,now-70000);
        var before=businessSnapshot();recovery.recover(d,now);assertEquals(before,businessSnapshot());
        verify(sessions,never()).dispatch(anyString(),anyString(),anyString(),any(),any());
        var relay=sql.getMapper(AgentOutboxRelayMapper.class);
        assertTrue(relay.selectDueCandidates(now+120000,25).isEmpty());assertTrue(relay.selectCorruptCandidates(now+120000,25).isEmpty());assertTrue(relay.selectStaleCandidates(now+120000,25).isEmpty());
        jdbc.update("UPDATE agent_command_delivery SET lease_until=?",now-1);jdbc.update("UPDATE agent_outbox_event SET lease_until=?",now-1);
        when(sessions.dispatch(anyString(),anyString(),anyString(),any(),any())).thenAnswer(call->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertArrayEquals(wire,call.getArgument(4));return AgentRawCommandDispatchResult.sent(1,1);
        });
        recovery.recover(d,now);
        assertEquals("SENT",jdbc.queryForObject("SELECT status FROM agent_command_delivery",String.class));
        assertEquals("SENT",jdbc.queryForObject("SELECT status FROM agent_outbox_event",String.class));
        assertEquals("NONE",jdbc.queryForObject("SELECT publisher_confirm_status FROM agent_outbox_event",String.class));
        assertNull(jdbc.queryForObject("SELECT confirmed_at FROM agent_outbox_event",Long.class));
        assertArrayEquals(wire,jdbc.queryForObject("SELECT wire_payload FROM agent_outbox_event",byte[].class));
        assertEquals(1,count("economy_skill_order"));assertEquals(1,count("economy_skill_change_command"));assertEquals(70_000_000L,wallet());
        assertEquals(c.get("attempt"),current(c).get("attempt"));
    }
    @Test void nativeRecoveryOutboxClaimCasLossRollsBackDeliveryLease() {
        install();long now=System.currentTimeMillis();var hint=recoveryMapper.candidates(now,now-60000).getFirst();
        var before=businessSnapshot();var failing=spy(recoveryMapper);
        doReturn(0).when(failing).claimOutbox(any(),anyString(),anyLong(),anyLong());
        var gate=new EconomyPreviewGate(new EconomyPreviewProperties(true,List.of(new EconomyPreviewProperties.AllowedScope("0",ACTOR.clientId()))));
        var fixture=new SkillInstallRecoveryService(failing,recoveryDao,gate,dispatch,manager);
        assertThrows(SkillMarketplaceException.class,()->fixture.recover(hint,now));
        assertEquals(before,businessSnapshot());verify(sessions,never()).dispatch(anyString(),anyString(),anyString(),any(),any());
    }
    @Test void nativeAckRacingSenderCannotLetOldCompletionOverwriteReclaimedLease() {
        var c=install();long now=System.currentTimeMillis();var hint=recoveryMapper.candidates(now,now-60000).getFirst();
        var afterReclaim=new java.util.concurrent.atomic.AtomicReference<Map<String,Object>>();
        when(sessions.dispatch(anyString(),anyString(),anyString(),any(),any())).thenAnswer(call->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            service.acknowledge(proof(),acknowledgement(c));
            tx.executeWithoutResult(status->{
                long reclaim=now+120000;
                var d=recoveryDao.lockDelivery("0",ACTOR.clientId(),hint.getId());
                var o=recoveryDao.lockActiveOutboxes("0",ACTOR.clientId(),d.getId(),d.getActiveMessageId()).getFirst();
                assertEquals(1,recoveryMapper.claimDelivery(d,"new-owner",reclaim+60000,reclaim));
                assertEquals(1,recoveryMapper.claimOutbox(o,"new-owner",reclaim+60000,reclaim));
            });
            afterReclaim.set(businessSnapshot());return AgentRawCommandDispatchResult.sent(1,1);
        });
        recovery.recover(hint,now);assertNotNull(afterReclaim.get());assertEquals(afterReclaim.get(),businessSnapshot());
        assertEquals("RECEIVED",jdbc.queryForObject("SELECT state FROM economy_skill_change_command",String.class));
        assertEquals("new-owner",jdbc.queryForObject("SELECT lease_owner FROM agent_outbox_event",String.class));
    }
    @Test void preparedResultRacingSendCompletionPreservesNewPhaseAndImmutableOriginalWire() {
        var c=install();long now=System.currentTimeMillis();var hint=recoveryMapper.candidates(now,now-60000).getFirst();
        byte[] original=jdbc.queryForObject("SELECT wire_payload FROM agent_outbox_event",byte[].class);
        when(sessions.dispatch(anyString(),anyString(),anyString(),any(),any())).thenAnswer(call->{
            service.acknowledge(proof(),acknowledgement(c));report(c,"PREPARED",null);return AgentRawCommandDispatchResult.sent(1,1);
        });
        recovery.recover(hint,now);
        assertEquals("ACTIVATING",current(c).get("status"));assertEquals("ACTIVATE",command(current(c)).get("phase"));
        assertEquals(2,count("agent_outbox_event"));assertEquals(1,count("economy_skill_order"));assertEquals(70_000_000L,wallet());
        assertArrayEquals(original,jdbc.queryForObject("SELECT wire_payload FROM agent_outbox_event WHERE command_id=?",byte[].class,hint.getCommandId()));
        assertEquals("RESULT",jdbc.queryForObject("SELECT state FROM economy_skill_change_command WHERE command_id=?",String.class,hint.getCommandId()));
    }
    @Test void realMysqlGeneratedGuardsRetainExpressionsAndNullUniqueness() {
        SkillTransportStorageAssertions.generatedGuards(jdbc,true,"0",ACTOR.clientId(),AGENT);
    }
    @Test void realMysqlAuditTriggersRejectUpdateAndDeleteWithoutChangingEvidence() {
        SkillTransportStorageAssertions.immutableAudit(jdbc,"0",ACTOR.clientId(),AGENT);
    }

}
