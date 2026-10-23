package cn.jia.chat.archive.service;
import cn.jia.agent.api.*;
import cn.jia.agent.config.*;
import cn.jia.agent.dao.*;
import cn.jia.agent.dao.impl.*;
import cn.jia.agent.mapper.*;
import cn.jia.agent.position.*;
import cn.jia.agent.skill.*;
import cn.jia.agent.service.*;
import cn.jia.agent.service.impl.*;
import cn.jia.agent.security.*;
import cn.jia.chat.archive.controller.*;
import cn.jia.chat.archive.store.*;
import cn.jia.economy.mapper.EconomySkillMarketplaceMapper;
import cn.jia.economy.entity.skill.SkillProductVersionEntity;
import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.core.security.*;
import cn.jia.user.dao.*;
import cn.jia.user.dao.impl.UserInfoDaoImpl;
import cn.jia.user.mapper.InfoMapper;
import cn.jia.user.security.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import static cn.jia.agent.position.PositionSkillState.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL + production Spring/MyBatis/auth/native HTTP + actual Node Runtime installer/parser.
 * Operator actions use the service boundary. Online channel liveness is an explicit fixture binding,
 * not a real WebSocket/browser/full installed Runtime acceptance claim. No mock DAO or fake business callback. */
class ArchiveNativeHttpMySqlTest {
    static final String TENANT="0",CLIENT="archive-native-client",OWNER="archive-owner";
    static final String AGENT="agt_"+"a".repeat(32),INSTALLATION="rti_"+"b".repeat(32);
    static DataSource source;static Path packageRoot;
    @TempDir Path root;
    final ObjectMapper json=new ObjectMapper();
    Process node;BufferedReader output;BufferedWriter input;JdbcTemplate admin,jdbc;String database;
    Tomcat tomcat;AnnotationConfigWebApplicationContext spring;
    Map<String,Object> exchange(Map<String,Object> request)throws Exception {
        input.write(json.writeValueAsString(request));input.newLine();input.flush();
        var future=CompletableFuture.supplyAsync(()->{try{return output.readLine();}catch(IOException e){throw new UncheckedIOException(e);}});
        String line=future.get(40,TimeUnit.SECONDS);assertNotNull(line,"Node fixture exited; inspect private stderr");
        Map<String,Object> result=json.readValue(line,Map.class);assertNotEquals("FAILED",result.get("op"),"native fixture rejected: "+result.get("code")+" / HTTP "+result.get("status"));return result;
    }
    static byte[] hash(String value)throws Exception{return hash(value.getBytes(StandardCharsets.UTF_8));}
    static byte[] hash(byte[] value)throws Exception{return MessageDigest.getInstance("SHA-256").digest(value);}
    // Test-only second approved skill, generated outside repositories; no production package/catalog mutation.
    byte[] variantPackage(byte[] original,String key)throws Exception{
        var bytes=new ByteArrayOutputStream();
        try(var input=new java.util.zip.ZipInputStream(new ByteArrayInputStream(original));var output=new java.util.zip.ZipOutputStream(bytes)){
            for(var entry=input.getNextEntry();entry!=null;entry=input.getNextEntry()){
                byte[] data=input.readAllBytes();
                if(entry.getName().equals("SKILL.md") || entry.getName().equals("manifest.json"))data=new String(data,StandardCharsets.UTF_8).replace("archive-maintenance",key).getBytes(StandardCharsets.UTF_8);
                output.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));output.write(data);output.closeEntry();
            }
        }return bytes.toByteArray();
    }
    void schema(String resource)throws Exception {
        String ddl=new ClassPathResource(resource).getContentAsString(StandardCharsets.UTF_8);
        for(String sql:ddl.replaceAll("(?m)^--.*$", "").split(";"))if(!sql.isBlank())jdbc.execute(sql);
    }
    @Test void nativeInstallerDraftAutoFailureAndResignationWithActualFilesystem()throws Exception {
        String base=System.getenv("CYF_POSITION_TEST_JDBC");assertNotNull(base);assertTrue(base.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/mysql(?:\\?.*)?"));
        String user=System.getenv("CYF_POSITION_TEST_USER"),password=System.getenv("CYF_POSITION_TEST_PASSWORD");
        admin=new JdbcTemplate(new DriverManagerDataSource(base,user,password));database="aps_http_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE DATABASE "+database);
        source=new DriverManagerDataSource(base.replace("/mysql","/"+database),user,password);jdbc=new JdbcTemplate(source);
        schema("archive-maintenance/native-auth-fixture.sql");schema("db/economy-v0-skill-marketplace.sql");schema("db/agent-position-skills.sql");schema("db/archive-schema.sql");schema("db/archive-maintenance-jobs.sql");
        String authorization="rta1_"+HexFormat.of().formatHex(hash(UUID.randomUUID().toString()));String manifestHash="d".repeat(64);long now=System.currentTimeMillis();
        jdbc.update("INSERT INTO user_info VALUES(7,?,'ACTIVE',2)",OWNER);
        jdbc.update("INSERT INTO agent_persona_binding(id,jiacn,persona_code,agent_id,bound_at,status,tenant_id,client_id,create_time,update_time) VALUES(3,?,'archive-persona',?,?,1,?,?,?,?)",OWNER,AGENT,now,TENANT,CLIENT,now,now);
        jdbc.update("INSERT INTO agent_identity_registry(id,canonical_agent_id,canonical_type,lifecycle_status,owner_jiacn,binding_id,provisioned_at,activated_at,audit_reason,tenant_id,client_id,create_time,update_time) VALUES(2,?,'OPAQUE','ACTIVE',?,3,?,?,'ARCHIVE_ISOLATED_FIXTURE',?,?,?,?)",AGENT,OWNER,now,now,TENANT,CLIENT,now,now);
        jdbc.update("INSERT INTO agent_runtime_v1_installation(id,installation_id,canonical_agent_id,manifest_version,manifest_sha256,enrollment_secret_hash,enrollment_expires_at,enrollment_consumed_at,runtime_authorization_hash,runtime_authorization_issued_at,status,version,tenant_id,client_id,create_time,update_time) VALUES(1,?,?,'1',?,?,?,?,?,?,'ACTIVE',0,?,?,?,?)",INSTALLATION,AGENT,manifestHash,hash("unused"),now+86400000,now,hash(authorization),now,TENANT,CLIENT,now,now);
        packageRoot=Files.createDirectory(root.resolve("packages"));Files.setPosixFilePermissions(packageRoot,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        byte[] zip=Files.readAllBytes(Path.of(System.getenv("CYF_ARCHIVE_SKILL_PACKAGE")));new SkillPackageStore(packageRoot).publish(zip);
        byte[] generalZip=variantPackage(zip,"archive-general");new SkillPackageStore(packageRoot).publish(generalZip);
        jdbc.update("INSERT INTO economy_skill_product(product_id,name,description,status,version,tenant_id,client_id,create_time,update_time) VALUES('archive-package','Archive maintenance','Isolated approved fixture','DRAFT',1,?,?,?,?)",TENANT,CLIENT,now,now);
        startHttp();
        var market=spring.getBean(EconomySkillMarketplaceMapper.class);
        var version=new SkillProductVersionEntity().setProductVersionId("archive-v1").setProductId("archive-package").setVersionSequence(1L).setSkillKey("archive-maintenance").setSkillVersion("1.1.1").setPriceMicro(0L).setPackageSha256(hash(zip)).setPackageSize((long)zip.length).setApprovedPermissionsManifest("[]").setApprovedPermissionsSha256(hash("[]")).setDeploymentRestriction("NONE").setReviewStatus("APPROVED");
        version.setTenantId(TENANT);version.setClientId(CLIENT);version.setCreateTime(now);assertEquals(1,market.insertProductVersion(version));
        var generalVersion=new SkillProductVersionEntity().setProductVersionId("archive-general-v1").setProductId("archive-package").setVersionSequence(2L).setSkillKey("archive-general").setSkillVersion("1.1.1").setPriceMicro(0L).setPackageSha256(hash(generalZip)).setPackageSize((long)generalZip.length).setApprovedPermissionsManifest("[]").setApprovedPermissionsSha256(hash("[]")).setDeploymentRestriction("NONE").setReviewStatus("APPROVED");
        generalVersion.setTenantId(TENANT);generalVersion.setClientId(CLIENT);generalVersion.setCreateTime(now);assertEquals(1,market.insertProductVersion(generalVersion));
        var positions=spring.getBean(PositionSkillService.class);var scope=new PositionAuthority.Scope(TENANT,CLIENT,OWNER);
        for(String p:List.of("MANAGE_POSITIONS","ARCHIVE_WRITE","ARCHIVE_PUBLISH"))jdbc.update("INSERT INTO agent_position_authority VALUES(?,?,?,?,?)",TENANT,CLIENT,OWNER,p,p.equals("MANAGE_POSITIONS")?"positions":"archive:public");
        positions.defineSkill(scope,"skill",new Skill("archive-maintenance",SkillType.POSITION,"archive-v1"));
        positions.defineSkill(scope,"general-skill",new Skill("archive-general",SkillType.GENERAL,"archive-general-v1"));
        positions.configure(scope,"position",new Position("editor","Editor",2,"archive:public",true,List.of("archive-maintenance","archive-general"),0),0);
        String appointment=positions.appoint(scope,"appoint","editor",AGENT).resultId();assertFalse(positions.read(scope).state().installations().values().iterator().next().usable());
        String clientRoot=System.getenv("CYF_ARCHIVE_CLIENT_ROOT");assertNotNull(clientRoot);
        node=new ProcessBuilder("node",Path.of(clientRoot,"conf/codex-ws-agent/test/fixtures/archive-native-http.mjs").toString()).redirectError(root.resolve("node-private.log").toFile()).start();
        output=new BufferedReader(new InputStreamReader(node.getInputStream(),StandardCharsets.UTF_8));input=new BufferedWriter(new OutputStreamWriter(node.getOutputStream(),StandardCharsets.UTF_8));
        String origin="http://127.0.0.1:"+tomcat.getConnector().getLocalPort();
        var session=exchange(Map.of("op","INIT","root",root.toString(),"origin",origin,"authorization",authorization,"manifest",Map.of("runtimeProtocolVersion","v1","manifestVersion","1","installationId",INSTALLATION,"tenantId",TENANT,"clientId",CLIENT,"canonicalAgentId",AGENT,"manifestSha256","sha256:"+manifestHash)));
        Map<String,String> h=(Map<String,String>)session.get("headers");
        // Explicit fixture channel-liveness registration. Session credential and all persistent checks are real.
        jdbc.update("UPDATE agent_runtime SET status='online' WHERE agent_id=?",AGENT);
        var auth=spring.getBean(AgentRuntimeAuthenticationService.class);
        var headers=new AgentRuntimeAuthenticationFilter.SessionHeaders(AGENT,INSTALLATION,"archive-host","archive-boot",Long.parseLong(h.get("X-Agent-Session-Generation")),h.get("Authorization").substring("AgentRuntime ".length()));
        var proof=auth.verify(headers);auth.bind("fixture-channel",proof,()->node.isAlive());
        var installed=exchange(Map.of("op","INSTALL"));assertEquals(2,installed.get("processed"));assertTrue(positions.read(scope).state().installations().values().stream().allMatch(Installation::usable),()->"Installation states: "+positions.read(scope).state().installations().values().stream().map(i->i.state()+"/"+i.errorCode()).toList());
        assertTrue(Files.isRegularFile(root.resolve("codex-home/skills/archive-maintenance/SKILL.md")));
        var service=spring.getBean(ArchiveMaintenanceService.class);var reader=new ArchiveReaderServiceImpl(spring.getBean(JdbcArchiveContentStore.class));
        String sourceA="第一回 初见\n这是仓库外生成的测试文本："+UUID.randomUUID()+"。\n第二回 后续\n第二段。";
        String sourceB="# Opening\nExternal fixture "+UUID.randomUUID()+".\n# Ending\nA separate body.";
        Files.writeString(root.resolve("source-a.txt"),sourceA);Files.writeString(root.resolve("source-b.txt"),sourceB);
        var draft=service.create(scope,"draft",new ArchiveMaintenanceService.Create(appointment,AGENT,"archive-maintenance","data-only-a","Book A","Fixture","source-a.txt","Self-authored",sourceA,ArchiveMaintenanceService.Mode.DRAFT_ONLY));
        assertEquals(1,exchange(Map.of("op","RUN")).get("processed"));assertEquals("DRAFT",service.read(scope,(String)draft.get("jobId")).get("state"));assertTrue(reader.books().items().isEmpty());
        List<Map<String,Object>> published=new ArrayList<>();int n=0;
        for(String text:List.of(sourceA,sourceB)) {String work="data-only-"+(++n);var j=service.create(scope,"publish-"+n,new ArchiveMaintenanceService.Create(appointment,AGENT,"archive-maintenance",work,"Book "+n,"Fixture","external.txt","Self-authored",text,ArchiveMaintenanceService.Mode.AUTO));assertEquals(1,exchange(Map.of("op","RUN")).get("processed"));published.add(service.read(scope,(String)j.get("jobId")));assertEquals("PUBLISHED",published.getLast().get("state"));assertEquals(2,reader.catalog(work).data().activeEdition().chapters().size());}
        var broken=service.create(scope,"broken",new ArchiveMaintenanceService.Create(appointment,AGENT,"archive-maintenance","broken-book","Broken","Fixture","broken.txt","Self-authored","第一回\n第二回\n",ArchiveMaintenanceService.Mode.DRAFT_ONLY));
        var healthy=service.create(scope,"healthy",new ArchiveMaintenanceService.Create(appointment,AGENT,"archive-maintenance","healthy-book","Healthy","Fixture","healthy.txt","Self-authored",sourceA,ArchiveMaintenanceService.Mode.DRAFT_ONLY));
        assertEquals(2,exchange(Map.of("op","RUN")).get("processed"));assertEquals("FAILED",service.read(scope,(String)broken.get("jobId")).get("state"));assertEquals("DRAFT",service.read(scope,(String)healthy.get("jobId")).get("state"));
        assertEquals(2,reader.books().items().size());assertEquals(List.of(401,401,401),exchange(Map.of("op","NEGATIVE","origin",origin)).get("statuses"));
        positions.configure(scope,"shared-position",new Position("reviewer","Reviewer",1,"archive:public",false,List.of("archive-maintenance"),0),0);
        String shared=positions.appoint(scope,"shared-appoint","reviewer",AGENT).resultId();assertEquals(0,exchange(Map.of("op","INSTALL")).get("processed"));
        var pending=service.create(scope,"pending",new ArchiveMaintenanceService.Create(appointment,AGENT,"archive-maintenance","revoked-book","Revoked","Fixture","external.txt","Self-authored",sourceA,ArchiveMaintenanceService.Mode.AUTO));
        positions.resign(scope,"resign",appointment);assertEquals(0,exchange(Map.of("op","RUN")).get("processed"));assertEquals("WAITING",service.read(scope,(String)pending.get("jobId")).get("state"));
        assertEquals(0,exchange(Map.of("op","INSTALL")).get("processed"));assertTrue(Files.exists(root.resolve("codex-home/skills/archive-maintenance")));
        positions.resign(scope,"resign-shared",shared);
        assertEquals(1,exchange(Map.of("op","INSTALL")).get("processed"));assertFalse(Files.exists(root.resolve("codex-home/skills/archive-maintenance")));
        assertEquals(InstallState.REMOVED,positions.read(scope).state().installations().values().stream().filter(i->i.skillKey().equals("archive-maintenance")).findFirst().orElseThrow().state());
        assertTrue(Files.isRegularFile(root.resolve("codex-home/skills/archive-general/SKILL.md")));
        var retained=new PositionInstalledSkills(positions,spring.getBean(AgentIdentityService.class),market,spring.getBean(SkillPackages.class));
        var lookupScope=new SkillCapabilityLookup.Scope(TENANT,CLIENT,OWNER,AGENT);
        assertEquals(List.of("archive-general"),retained.read(lookupScope).stream().map(InstalledSkillDescriptorLookup.Descriptor::skillKey).toList());
        assertEquals(1,retained.entitlements(lookupScope).size());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM economy_skill_order",Integer.class));
        assertEquals("STOPPED",exchange(Map.of("op","STOP")).get("op"));assertTrue(node.waitFor(10,TimeUnit.SECONDS));assertEquals(0,node.exitValue());
    }
    void startHttp()throws Exception {
        Path doc=Files.createDirectory(root.resolve("http"));tomcat=new Tomcat();tomcat.setBaseDir(doc.toString());tomcat.setHostname("127.0.0.1");tomcat.setPort(0);assertTrue(tomcat.getConnector().setProperty("address","127.0.0.1"));
        Context context=tomcat.addContext("",doc.toString());context.setParentClassLoader(getClass().getClassLoader());spring=new AnnotationConfigWebApplicationContext();spring.setServletContext(context.getServletContext());spring.register(HttpConfiguration.class);spring.refresh();
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(spring.getBean(AgentRuntimeAuthenticationService.class)));
        var servlet=Tomcat.addServlet(context,"dispatcher",new DispatcherServlet(spring));servlet.setLoadOnStartup(1);context.addServletMappingDecoded("/","dispatcher");
        FilterDef def=new FilterDef();def.setFilterName("runtime-security");def.setFilter(new DelegatingFilterProxy("springSecurityFilterChain",spring));context.addFilterDef(def);
        FilterMap mapping=new FilterMap();mapping.setFilterName("runtime-security");mapping.addURLPattern("/*");mapping.setDispatcher("REQUEST");context.addFilterMap(mapping);tomcat.start();
    }
    @AfterEach void cleanup()throws Exception {
        if(node!=null && node.isAlive()){node.destroy();if(!node.waitFor(5,TimeUnit.SECONDS)){node.destroyForcibly();node.waitFor(5,TimeUnit.SECONDS);}}
        try {if(tomcat!=null){tomcat.stop();tomcat.destroy();}}finally{if(spring!=null)spring.close();if(admin!=null && database!=null && database.matches("aps_http_[a-f0-9]{32}"))admin.execute("DROP DATABASE "+database);}
    }
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import(AgentRuntimeSecurityConfiguration.class)
    static class HttpConfiguration {
        @Bean DataSource dataSource() { return source; }
        @Bean DataSourceTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true); configuration.setLogImpl(NoLoggingImpl.class);
            for (Class<?> mapper : List.of(AgentRuntimeV1InstallationMapper.class, AgentRuntimeMapper.class,
                    AgentIdentityRegistryMapper.class, AgentIdentityAliasMapper.class, AgentPersonaBindingMapper.class,
                    EconomySkillMarketplaceMapper.class)) configuration.addMapper(mapper);
            GlobalConfig global = new GlobalConfig(); global.setBanner(false);
            global.setIdentifierGenerator(new DefaultIdentifierGenerator());
            MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
            bean.setDataSource(source); bean.setConfiguration(configuration); bean.setGlobalConfig(global);
            // Original production XML registers InfoMapper and its security statements, not test SQL.
            bean.setMapperLocations(new ClassPathResource("cn/jia/user/mapper/InfoMapper.xml"));
            SqlSessionFactory factory = Objects.requireNonNull(bean.getObject());
            assertTrue(factory.getConfiguration().hasStatement(InfoMapper.class.getName() + ".selectSecurityByExactJiacn"),
                    "UR03_PRODUCTION_ACCOUNT_XML_REQUIRED");
            return factory;
        }
        @Bean SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }
        // Register real SqlSessionTemplate proxies as Spring beans, not only MyBatis mapped interfaces.
        // Spring resolves BaseDaoImpl's inherited @Inject even after wire() assigns the field.
        @Bean AgentRuntimeV1InstallationMapper installationMapper(SqlSessionTemplate session) {
            return session.getMapper(AgentRuntimeV1InstallationMapper.class);
        }
        @Bean AgentRuntimeMapper runtimeMapper(SqlSessionTemplate session) {
            return session.getMapper(AgentRuntimeMapper.class);
        }
        @Bean AgentIdentityRegistryMapper registryMapper(SqlSessionTemplate session) {
            return session.getMapper(AgentIdentityRegistryMapper.class);
        }
        @Bean AgentIdentityAliasMapper aliasMapper(SqlSessionTemplate session) {
            return session.getMapper(AgentIdentityAliasMapper.class);
        }
        @Bean AgentPersonaBindingMapper bindingMapper(SqlSessionTemplate session) {
            return session.getMapper(AgentPersonaBindingMapper.class);
        }
        @Bean InfoMapper infoMapper(SqlSessionTemplate session) {
            return session.getMapper(InfoMapper.class);
        }
        @Bean AgentRuntimeV1InstallationDao installations(SqlSessionTemplate session) throws Exception {
            return wire(new AgentRuntimeV1InstallationDaoImpl(), session.getMapper(AgentRuntimeV1InstallationMapper.class));
        }
        @Bean AgentRuntimeDao runtimes(SqlSessionTemplate session) throws Exception {
            return wire(new AgentRuntimeDaoImpl(), session.getMapper(AgentRuntimeMapper.class));
        }
        @Bean AgentIdentityRegistryDao registry(SqlSessionTemplate session) throws Exception {
            return wire(new AgentIdentityRegistryDaoImpl(), session.getMapper(AgentIdentityRegistryMapper.class));
        }
        @Bean AgentIdentityAliasDao aliases(SqlSessionTemplate session) throws Exception {
            return wire(new AgentIdentityAliasDaoImpl(), session.getMapper(AgentIdentityAliasMapper.class));
        }
        @Bean AgentPersonaBindingDao bindings(SqlSessionTemplate session) throws Exception {
            return wire(new AgentPersonaBindingDaoImpl(), session.getMapper(AgentPersonaBindingMapper.class));
        }
        @Bean UserInfoDao users(SqlSessionTemplate session) throws Exception {
            return wire(new UserInfoDaoImpl(), session.getMapper(InfoMapper.class));
        }
        @Bean AgentIdentityService identity(AgentIdentityRegistryDao registry, AgentIdentityAliasDao aliases, AgentPersonaBindingDao bindings) {
            return new AgentIdentityServiceImpl(registry, aliases, bindings);
        }
        @Bean AccountSecurityService accounts(UserInfoDao users) { return new AccountSecurityServiceImpl(users); }
        @Bean AgentTaskEventsGate taskGate() {
            return new AgentTaskEventsGate(new AgentTaskEventsProperties(true,
                    List.of(new AgentTaskEventsProperties.AllowedScope(TENANT, CLIENT))));
        }
        @Bean AgentRuntimeAuthenticationService authentication(AgentRuntimeDao rows, AgentRuntimeV1InstallationDao installations,
                AgentIdentityRegistryDao registry, AgentIdentityService identity, AccountSecurityService accounts, AgentTaskEventsGate gate) {
            return new AgentRuntimeAuthenticationService(rows, installations, registry, identity, accounts, gate);
        }
        @Bean AgentRuntimeV1Service runtime(AgentRuntimeV1InstallationDao installations, AgentIdentityService identity,
                AgentIdentityRegistryDao registry, ObjectProvider<AgentCommandAckService> acks, AgentRuntimeAuthenticationService authentication, ObjectProvider<SkillInstallAckService> skillAcks) {
            return new AgentRuntimeV1ServiceImpl(installations, identity, registry, acks, authentication, skillAcks);
        }
        @Bean AgentRuntimeV1Controller controller(AgentRuntimeV1Service runtime) { return new AgentRuntimeV1Controller(runtime); }
        @Bean SensitiveResponseProperties sensitiveProperties() { return new SensitiveResponseProperties(); }
        @Bean SensitiveResponseBodyAdvice sensitiveAdvice(SensitiveResponseProperties properties) { return new SensitiveResponseBodyAdvice(properties); }

        @Bean EconomySkillMarketplaceMapper market(SqlSessionTemplate session) { return session.getMapper(EconomySkillMarketplaceMapper.class); }
        @Bean JdbcTemplate jdbc(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean PositionSkillStore positionStore(JdbcTemplate jdbc) { return new PositionSkillStore(jdbc); }
        @Bean PositionSkillService positions(PositionSkillStore store,AgentIdentityService ids,EconomySkillMarketplaceMapper market,DataSourceTransactionManager tx) {return new PositionSkillService(store,ids,market,tx);}
        @Bean SkillPackages packages() {return new SkillPackages(packageRoot);}
        @Bean PositionSkillRuntimeService positionRuntime(PositionSkillService p,AgentIdentityService ids,EconomySkillMarketplaceMapper m,SkillPackages packages) {return new PositionSkillRuntimeService(p,ids,m,packages);}
        @Bean PositionSkillRuntimeController positionController(PositionSkillRuntimeService service,AgentRuntimeAuthenticationService auth) {return new PositionSkillRuntimeController(service,auth);}
        @Bean SpringArchiveTransactions archiveTx(DataSourceTransactionManager tx) {return new SpringArchiveTransactions(tx);}
        @Bean JdbcArchiveContentStore content(JdbcTemplate jdbc) {return new JdbcArchiveContentStore(jdbc);}
        @Bean ArchiveMaintenanceStore jobs(JdbcTemplate jdbc) {return new ArchiveMaintenanceStore(jdbc);}
        @Bean ArchiveMaintenanceService archive(PositionSkillService p,AgentIdentityService ids,ArchiveMaintenanceStore jobs,JdbcArchiveContentStore content,SpringArchiveTransactions tx) {return new ArchiveMaintenanceService(p,ids,jobs,content,tx);}
        @Bean ArchiveMaintenanceRuntimeController archiveController(ArchiveMaintenanceService s,AgentRuntimeAuthenticationService auth) {return new ArchiveMaintenanceRuntimeController(s,auth);}
        @Bean cn.jia.core.config.ApiEnvelopeAdvice envelopeAdvice() {return new cn.jia.core.config.ApiEnvelopeAdvice();}
        @Bean cn.jia.core.config.ApiRequestEnvelopeAdvice requestAdvice() {return new cn.jia.core.config.ApiRequestEnvelopeAdvice();}
    }
    private static <T extends BaseDaoImpl<?, ?>> T wire(T dao, Object mapper) throws Exception {
        Field field = BaseDaoImpl.class.getDeclaredField("baseMapper");
        field.setAccessible(true); field.set(dao, mapper); return dao;
    }
}
