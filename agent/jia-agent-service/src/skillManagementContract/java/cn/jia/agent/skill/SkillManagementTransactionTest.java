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
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.net.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static cn.jia.agent.skill.SkillManagementCodec.*;

/** SQL, escrow, ledger, immutable authorizations and command outbox are real MySQL.
 * Only the already-authenticated identity/session boundary is stubbed explicitly. No paid IO. */
public class SkillManagementTransactionTest {
    @TempDir static Path scratch;
    private static Process mysql;
    private static String baseUrl;
    private static JdbcTemplate admin;
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

    @BeforeAll static void startPrivateMysql() throws Exception {
        String configured=System.getenv("CYF_REVIEW_MYSQLD");
        assertNotNull(configured,"Required CYF_REVIEW_MYSQLD: this suite must FAIL, never skip, if its owned fixture is unavailable");
        Path executable=Path.of(configured).toRealPath();assertTrue(Files.isExecutable(executable));
        Path data=Files.createDirectory(scratch.resolve("data")),log=scratch.resolve("mysql.log");
        String user=System.getProperty("user.name");
        var init=new ProcessBuilder(executable.toString(),"--no-defaults","--initialize-insecure","--user="+user,
                "--datadir="+data,"--basedir="+executable.getParent().getParent()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {assertEquals(0,init.waitFor(),()->readLog(log));}finally {stop(init);}
        int port;
        do {try(var listener=new ServerSocket(0,0,InetAddress.getByName("127.0.0.1"))){port=listener.getLocalPort();}}
        while(Set.of(3306,10018,33060).contains(port));
        mysql=new ProcessBuilder(executable.toString(),"--no-defaults","--user="+user,"--datadir="+data,
            "--basedir="+executable.getParent().getParent(),"--socket="+scratch.resolve("mysql.sock"),"--pid-file="+scratch.resolve("mysql.pid"),
            "--port="+port,"--bind-address=127.0.0.1","--mysqlx=OFF","--skip-log-bin","--innodb-buffer-pool-size=67108864")
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
        baseUrl="jdbc:mysql://127.0.0.1:"+port+"/";
        Class.forName("com.mysql.cj.jdbc.Driver");
        while(true) {
            assertTrue(mysql.isAlive(),()->readLog(log));
            try(var connection=DriverManager.getConnection(url("mysql"),"root","")) {assertTrue(connection.isValid(1));break;}
            catch(SQLException starting){Thread.sleep(100);}
        }
        admin=new JdbcTemplate(source("mysql"));
        System.out.println("CYF_SM_C1_MYSQL owned=true port="+port+" version="+admin.queryForObject("SELECT VERSION()",String.class));
    }
    @AfterAll static void stopPrivateMysql() throws Exception {stop(mysql);mysql=null;admin=null;}
    private static void stop(Process process) throws InterruptedException {if(process!=null&&process.isAlive()){process.destroy();process.waitFor();}}
    private static String readLog(Path log) {try{return Files.readString(log);}catch(Exception e){return e.getClass().getSimpleName();}}
    private static String url(String database){return baseUrl+database+"?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";}
    private static DriverManagerDataSource source(String database){return new DriverManagerDataSource(url(database),"root","");}

    @BeforeEach protected void setup() throws Exception {
        database="cyf_sm_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        var ds=source(database);jdbc=new JdbcTemplate(ds);var manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);
        for(String resource:List.of("db/economy-v0-foundation.sql","db/economy-v0-skill-marketplace.sql","db/economy-v0-skill-application.sql",
                "db/agent-command-transport-schema.sql","db/economy-skill-management-sm-c1.sql","db/agent-skill-execution-v1.sql"))
            new ResourceDatabasePopulator(new ClassPathResource(resource)).execute(ds);
        var config=new org.apache.ibatis.session.Configuration();config.setMapUnderscoreToCamelCase(true);
        for(Class<?> type:List.of(EconomySkillMarketplaceMapper.class,EconomySkillApplicationMapper.class,EconomyLedgerMapper.class,
                EconomySkillManagementMapper.class,SkillExecutionMapper.class,AgentCommandTransportMapper.class,AgentCommandRecoveryMapper.class,AgentSkillInstallRecoveryMapper.class))config.addMapper(type);
        var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);
        var sql=new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        taskAttachments=new SkillExecutionDaoImpl(sql.getMapper(SkillExecutionMapper.class));
        market=sql.getMapper(EconomySkillMarketplaceMapper.class);var app=sql.getMapper(EconomySkillApplicationMapper.class);
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
        var packages=new SkillPackages(packageRoot);sessions=mock(AgentManagedSessionLookup.class);
        when(sessions.isReady(eq("0"),eq(ACTOR.clientId()),eq(AGENT),any())).thenReturn(true);
        when(sessions.dispatch(anyString(),anyString(),anyString(),any(),any())).thenReturn(AgentRawCommandDispatchResult.sent(1,1));
        capabilities=mock(SkillCapabilityLookup.class);when(capabilities.lookup(any(),any())).thenReturn(SkillCapabilityLookup.View.unavailable(SkillCapabilityLookup.State.DISCONNECTED));
        var marketplace=new SkillMarketplaceService(market,app,ledger,posting,gate,versions,runtimes,provider(writer),provider(packages),provider(authentication),provider(sessions),manager);
        service=new SkillManagementService(db,market,app,marketplace,versions,runtimes,installations,authentication,provider(packages),provider(writer),provider(sessions),provider(capabilities),manager);
        var dispatch=new SkillInstallDispatchService(app,market,marketplace,versions,authentication,commands,provider(sessions),manager);dispatch.setManagement(service);
        recovery=new SkillInstallRecoveryService(sql.getMapper(AgentSkillInstallRecoveryMapper.class),new AgentCommandRecoveryDaoImpl(sql.getMapper(AgentCommandRecoveryMapper.class)),gate,dispatch,manager);
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
}
