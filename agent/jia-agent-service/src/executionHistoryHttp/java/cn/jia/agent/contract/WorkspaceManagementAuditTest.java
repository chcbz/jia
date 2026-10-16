package cn.jia.agent.contract;

import cn.jia.agent.api.*;
import cn.jia.agent.config.*;
import cn.jia.agent.dao.*;
import cn.jia.agent.dao.impl.*;
import cn.jia.agent.entity.*;
import cn.jia.agent.exception.PersonalWorkspaceException;
import cn.jia.agent.mapper.*;
import cn.jia.agent.service.*;
import cn.jia.agent.service.impl.*;
import cn.jia.chat.service.WorkspaceConversationAccessService;
import cn.jia.oauth.config.ResourceServerConfig;
import cn.jia.user.security.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.*;
import jakarta.servlet.*;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.DirectFieldAccessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** Test-only private MySQL + real production resource SecurityFilterChains + real HTTP/controllers/services/DAOs.
 * Synthetic account directory/conversation access port; no OAuth login, Provider, production data or deployment. */
class WorkspaceManagementAuditTest {
    @TempDir Path scratch;
    static String jdbcUrl;
    static Path privateStore;
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final PersonalWorkspaceService.Scope S = new PersonalWorkspaceService.Scope("0", "client-a", "owner-a");
    static final PersonalWorkspaceTaskLinkService.Scope TS = new PersonalWorkspaceTaskLinkService.Scope("0", "client-a", "owner-a");
    static final PersonalWorkspaceConversationLinkService.Scope CS = new PersonalWorkspaceConversationLinkService.Scope("0", "client-a", "owner-a");
    static volatile CountDownLatch claimReads;
    static final List<Map<String,Object>> observations = new ArrayList<>();
    static void observed(String name, String kind, Object actual) {
        observations.add(Map.of("name",name,"kind",kind,"actual",actual));
    }
    @Configuration @EnableWebMvc @EnableWebSecurity @EnableTransactionManagement(proxyTargetClass=true)
    @Import({ResourceServerConfig.class, cn.jia.core.config.ApiEnvelopeAdvice.class, cn.jia.core.config.ApiRequestEnvelopeAdvice.class, cn.jia.core.config.ApiEnvelopeJsonConfig.class})
    public static class App {
        @Bean static BeanPostProcessor fixtureResourcePaths() { return new BeanPostProcessor() {
            public Object postProcessBeforeInitialization(Object bean, String name) {
                if (name.equals("oauthResourceProperties")) new DirectFieldAccessor(bean).setPropertyValue("uris",List.of("/agent/**"));
                return bean;
            }
        }; }
        @Bean AccountSecurityService accounts() { return new AccountSecurityService() {
            public Optional<AccountSecuritySnapshot> findByUserId(long id) {
                return id==1 ? Optional.of(new AccountSecuritySnapshot(1,"owner-a",AccountState.ACTIVE,0)) :
                    id==2 ? Optional.of(new AccountSecuritySnapshot(2,"owner-b",AccountState.ACTIVE,0)) : Optional.empty();
            }
            public Optional<AccountSecuritySnapshot> findUniqueByExactJiacn(String s) { return findByUserId("owner-a".equals(s)?1:"owner-b".equals(s)?2:0); }
            public int revokeAllSessions(long id,long epoch) { throw new AssertionError("Account mutation forbidden"); }
        }; }
        @Bean DataSource dataSource() { return new DriverManagerDataSource(jdbcUrl,"root",""); }
        @Bean DataSourceTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean SqlSessionTemplate sql(DataSource ds) throws Exception {
            var c=new MybatisConfiguration();c.setMapUnderscoreToCamelCase(true);
            for(Class<?> type:List.of(PersonalWorkspaceFileMapper.class,PersonalWorkspaceVersionMapper.class,PersonalWorkspaceOperationMapper.class,
                    PersonalWorkspaceTaskFileLinkMapper.class,PersonalWorkspaceConversationFileLinkMapper.class,
                    PersonalWorkspaceExecutionMapper.class,PersonalWorkspaceExecutionInputMapper.class,PersonalWorkspaceExecutionOutputMapper.class)) c.addMapper(type);
            var g=new GlobalConfig();g.setIdentifierGenerator(new DefaultIdentifierGenerator());
            var f=new MybatisSqlSessionFactoryBean();f.setDataSource(ds);f.setConfiguration(c);f.setGlobalConfig(g);
            return new SqlSessionTemplate(Objects.requireNonNull(f.getObject()));
        }
        @Bean PersonalWorkspaceDao files(SqlSessionTemplate s) {
            PersonalWorkspaceDao real = new PersonalWorkspaceDaoImpl(s.getMapper(PersonalWorkspaceFileMapper.class),s.getMapper(PersonalWorkspaceVersionMapper.class),s.getMapper(PersonalWorkspaceOperationMapper.class));
            return (PersonalWorkspaceDao) java.lang.reflect.Proxy.newProxyInstance(PersonalWorkspaceDao.class.getClassLoader(),new Class[]{PersonalWorkspaceDao.class},(p,m,a)-> {
                Object value;try {value=m.invoke(real,a);}catch(InvocationTargetException e){throw e.getCause();}
                CountDownLatch barrier=claimReads;
                if(barrier!=null && m.getName().equals("findOperationByIdempotency") && a[4].equals("same-concurrent-key") && value==null && barrier.getCount()>0){barrier.countDown();barrier.await();}
                return value;
            });
        }
        @Bean PersonalWorkspaceTaskLinkDao taskDao(SqlSessionTemplate s) { return new PersonalWorkspaceTaskLinkDaoImpl(s.getMapper(PersonalWorkspaceTaskFileLinkMapper.class)); }
        @Bean PersonalWorkspaceConversationLinkDao conversationDao(SqlSessionTemplate s) { return new PersonalWorkspaceConversationLinkDaoImpl(s.getMapper(PersonalWorkspaceConversationFileLinkMapper.class)); }
        @Bean PersonalWorkspaceExecutionDao executionDao(SqlSessionTemplate s) { return new PersonalWorkspaceExecutionDaoImpl(s.getMapper(PersonalWorkspaceExecutionMapper.class),s.getMapper(PersonalWorkspaceExecutionInputMapper.class),s.getMapper(PersonalWorkspaceExecutionOutputMapper.class)); }
        @Bean PersonalWorkspaceStorage storage() { return new FileSystemPersonalWorkspaceStorage(privateStore,1024*1024,Set.of("text/plain")); }
        @Bean PersonalWorkspaceOperationClaimTx claims(PersonalWorkspaceDao d) { return new PersonalWorkspaceOperationClaimTx(d); }
        @Bean PersonalWorkspaceWriteService writes(PersonalWorkspaceDao d,PersonalWorkspaceOperationClaimTx claims) { return new PersonalWorkspaceWriteService(d,claims); }
        @Bean PersonalWorkspaceService workspace(PersonalWorkspaceDao d,PersonalWorkspaceStorage s,PersonalWorkspaceWriteService w,PersonalWorkspaceTaskLinkDao t,PersonalWorkspaceExecutionDao e) { return new PersonalWorkspaceServiceImpl(d,s,w); }
        @Bean PersonalWorkspaceTaskLinkService taskLinks(PersonalWorkspaceTaskLinkDao d) { return new PersonalWorkspaceTaskLinkServiceImpl(d); }
        @Bean PersonalWorkspaceConversationLinkService conversationLinks(PersonalWorkspaceConversationLinkDao d) {
            WorkspaceConversationAccessService access=(scope,id)-> {
                if(!scope.ownerJiacn().equals("owner-a")||!scope.clientId().equals("client-a")||!id.startsWith("conversation-")) throw new IllegalArgumentException("Synthetic conversation unavailable");
                return new WorkspaceConversationAccessService.ConversationView(id,"USER","owner-a",null,List.of(),1,1);
            };
            return new PersonalWorkspaceConversationLinkServiceImpl(d,access);
        }
        @Bean PersonalWorkspaceController controller(PersonalWorkspaceService s) { return new PersonalWorkspaceController(s); }
        @Bean PersonalWorkspaceTaskFileLinkController tasks(PersonalWorkspaceTaskLinkService s) { return new PersonalWorkspaceTaskFileLinkController(s); }
        @Bean PersonalWorkspaceConversationFileLinkController conversations(PersonalWorkspaceConversationLinkService s) { return new PersonalWorkspaceConversationFileLinkController(s); }
        @Bean StandardServletMultipartResolver multipartResolver() { return new StandardServletMultipartResolver(); }
    }

    @Test void remainingBoundariesOnPrivateMySqlAndRealResourceSecurity() throws Exception {
        observations.clear();
        for (Class<?> type : List.of(org.springframework.http.HttpHeaders.class, org.apache.catalina.startup.Tomcat.class,
                cn.jia.core.config.ApiEnvelopeAdvice.class)) {
            observed("runtime-origin-"+type.getName(),"dependency-evidence",Map.of("origin",type.getProtectionDomain().getCodeSource().getLocation().toString(),"version",String.valueOf(type.getPackage().getImplementationVersion())));
        }
        String mysql="/home/isp/apps/mysql/bin/mysqld";
        Path data=Files.createDirectory(scratch.resolve("mysql-data")), log=scratch.resolve("mysql.log");
        int port; try(var socket=new ServerSocket(0,0,InetAddress.getLoopbackAddress())) { port=socket.getLocalPort(); }
        String user=System.getProperty("user.name"), basedir=Path.of(mysql).getParent().getParent().toString();
        Process init=new ProcessBuilder(mysql,"--no-defaults","--initialize-insecure","--user="+user,"--datadir="+data,"--basedir="+basedir).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertEquals(0,init.waitFor(),"MySQL fixture initialization");
        Process database=null;Tomcat tomcat=null;AnnotationConfigWebApplicationContext app=null;
        try {
            database=new ProcessBuilder(mysql,"--no-defaults","--user="+user,"--datadir="+data,"--basedir="+basedir,
                "--socket="+scratch.resolve("mysql.sock"),"--pid-file="+scratch.resolve("mysql.pid"),"--port="+port,
                "--bind-address=127.0.0.1","--mysqlx=OFF","--skip-log-bin","--innodb-buffer-pool-size=67108864").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
            String base="jdbc:mysql://127.0.0.1:"+port+"/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
            Class.forName("com.mysql.cj.jdbc.Driver");Connection admin=null;
            while(admin==null) { assertTrue(database.isAlive(),"Owned MySQL exited");try { admin=DriverManager.getConnection(base,"root",""); } catch(SQLException e) { Thread.sleep(200); } }
            try(Connection db=admin) {
                assertTrue(db.getMetaData().getDatabaseProductVersion().startsWith("8."));
                try(var st=db.createStatement()) { st.execute("CREATE DATABASE workspace_audit CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin"); }
                jdbcUrl=base.replace("/?","/workspace_audit?");privateStore=Files.createDirectory(scratch.resolve("store"));
                var jdbc=new JdbcTemplate(new DriverManagerDataSource(jdbcUrl,"root",""));
                new PersonalWorkspaceSchemaInitializer(jdbc).afterPropertiesSet();
                new PersonalWorkspaceTaskLinkSchemaInitializer(jdbc).afterPropertiesSet();
                new PersonalWorkspaceConversationLinkSchemaInitializer(jdbc).afterPropertiesSet();
                // Provision the current execution catalog from production migrations. The base
                // initializer alone currently references protocol_version before installing v3;
                // that pre-existing cold-bootstrap defect is not patched by this workspace task.
                var schema = new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator();
                for (String migration : List.of("v1_11-executions", "v1_13-task-execution",
                        "v1_13-task-publication", "v1_14-conversation-execution", "v1_14-conversation-output",
                        "v1_15-conversation-lease", "v1_16-conversation-provider-start")) {
                    schema.addScript(new org.springframework.core.io.ClassPathResource("db/agent-personal-workspace-"+migration+".sql"));
                }
                schema.execute(Objects.requireNonNull(jdbc.getDataSource()));
                new ControlledImageFollowupV3SchemaInitializer(jdbc).afterPropertiesSet();
                new PersonalWorkspaceExecutionSchemaInitializer(jdbc).afterPropertiesSet();
                jdbc.execute("CREATE TABLE agent_task_meta (task_id VARCHAR(100) PRIMARY KEY,tenant_id VARCHAR(50),client_id VARCHAR(50),owner_jiacn VARCHAR(50)) ENGINE=InnoDB");
                for(int i=0;i<105;i++) jdbc.update("INSERT INTO agent_task_meta VALUES (?,'0','client-a','owner-a')","task-"+i);
                tomcat=new Tomcat();tomcat.setBaseDir(scratch.resolve("tomcat").toString());tomcat.setPort(0);tomcat.getConnector().setProperty("address","127.0.0.1");
                Context context=tomcat.addContext("",Files.createDirectory(scratch.resolve("webroot")).toString());context.setParentClassLoader(getClass().getClassLoader());
                app=new AnnotationConfigWebApplicationContext();app.setServletContext(context.getServletContext());app.register(App.class);app.refresh();
                var servlet=Tomcat.addServlet(context,"dispatcher",new DispatcherServlet(app));servlet.setLoadOnStartup(1);servlet.setMultipartConfigElement(new MultipartConfigElement(scratch.toString()));context.addServletMappingDecoded("/","dispatcher");
                FilterDef requestIds=new FilterDef();requestIds.setFilterName("requestId");requestIds.setFilter(new cn.jia.core.filter.RequestIdFilter());context.addFilterDef(requestIds);
                FilterMap idsMap=new FilterMap();idsMap.setFilterName("requestId");idsMap.addURLPattern("/*");idsMap.setDispatcher(DispatcherType.REQUEST.name());context.addFilterMap(idsMap);
                FilterDef filter=new FilterDef();filter.setFilterName("security");filter.setFilter(new DelegatingFilterProxy("springSecurityFilterChain",app));context.addFilterDef(filter);
                FilterMap mapping=new FilterMap();mapping.setFilterName("security");mapping.addURLPattern("/*");mapping.setDispatcher(DispatcherType.REQUEST.name());context.addFilterMap(mapping);
                tomcat.start();String origin="http://127.0.0.1:"+tomcat.getConnector().getLocalPort();
                var client=HttpClient.newHttpClient();String owner=token(app,1,"client-a",0), other=token(app,2,"client-a",0), foreignClient=token(app,1,"client-b",0);
                var service=app.getBean(PersonalWorkspaceService.class);var files=app.getBean(PersonalWorkspaceDao.class);
                var task=app.getBean(PersonalWorkspaceTaskLinkService.class);var conversation=app.getBean(PersonalWorkspaceConversationLinkService.class);
                var upload=service.create(S,upload("audit-first","version-one"));String id=upload.file().fileId();String path="/agent/personal-workspace/files/"+id;
                // Both authenticating production chains and real JWT/account epoch validator are installed.
                for(String route:List.of("/agent/tasks/task-0/file-links","/agent/conversations/conversation-0/file-links")) {
                    for(String body:List.of("{",q(Map.of("fileId",id,"version","1","role","INPUT")))) assertEquals(401,send(client,origin+route,"POST",null,body,Map.of()).statusCode());
                    assertEquals(400,send(client,origin+route,"POST",owner,q(Map.of("fileId",id,"version","1","role","INPUT","owner","owner-b")),Map.of("Idempotency-Key","reject-owner")).statusCode());
                }
                assertEquals(401,send(client,origin+path,"GET",token(app,1,"client-a",9),null,Map.of()).statusCode());
                assertEquals(401,send(client,origin+path,"GET",owner.substring(0,owner.lastIndexOf('.')+1)+"invalid",null,Map.of()).statusCode());
                assertEquals(200,send(client,origin+path,"GET",owner,null,Map.of()).statusCode());
                observed("production-resource-filter-and-epoch-validation","normal",Map.of("anonymousMalformedAndValid",401,"staleEpoch",401,"forgedSignature",401,"authenticatedInvalidBody",400));
                for(String suffix:List.of("","/usage","/versions","/versions/1/content","/versions/1/preview?view=parts","/versions/1/preview/parts/content")) {
                    for(String bearer:List.of(other,foreignClient)) assertEquals(404,send(client,origin+path+suffix,"GET",bearer,null,Map.of()).statusCode(),suffix);
                }
                assertEquals(404,send(client,origin+"/agent/personal-workspace/operations/"+upload.operation().operationId(),"GET",other,null,Map.of()).statusCode());
                assertEquals(404,send(client,origin+path,"PATCH",other,q(Map.of("displayName","foreign")),Map.of("If-Match",tag(id,1),"Idempotency-Key","foreign-write")).statusCode());
                observed("http-owner-client-isolation-content-preview-usage-operation","normal",true);
                // Download exact bytes over real HTTP before the browser exercises download-to-disk.
                assertEquals("version-one",send(client,origin+path+"/versions/1/content","GET",owner,null,Map.of()).body());
                for(String action:List.of("rename","trash","restore")) {
                    var body=action.equals("rename")?q(Map.of("displayName","renamed")):action.equals("trash")?q(Map.of("impactRevision","1","acknowledgeExistingReferences",true)):"{}";
                    var route=action.equals("rename")?path:path+"/"+action;
                    assertEquals(412,send(client,origin+route,action.equals("rename")?"PATCH":"POST",owner,body,Map.of("Idempotency-Key","weak-"+action,"If-Match","W/"+tag(id,1))).statusCode());
                }
                observed("weak-representation-etag-is-not-business-precondition","normal",412);
                service.appendVersion(S,id,upload("append-2","version-two"),1);
                assertEquals(2,service.get(S,id).file().metadataRevision());
                observed("append-invalidates-old-confirmation","regression-pass",2);
                for(int n=3;n<=101;n++) service.appendVersion(S,id,upload("append-"+n,"version-"+n),n-1);
                assertEquals(100,service.get(S,id).versions().size());
                var p1=service.versionsPage(S,id,null);assertEquals(100,p1.items().size());assertTrue(p1.hasMore());
                service.appendVersion(S,id,upload("append-102","version-102"),101);
                var p2=service.versionsPage(S,id,p1.nextCursor());assertEquals(List.of(1),p2.items().stream().map(PersonalWorkspaceViews.VersionView::version).toList());assertFalse(p2.hasMore());
                var second=service.create(S,upload("second","browser-download-original"));
                assertThrows(PersonalWorkspaceException.class,()->service.versionsPage(S,second.file().fileId(),p1.nextCursor()));
                assertEquals(100,body(send(client,origin+path+"/versions","GET",owner,null,Map.of())).path("items").size());
                observed("101-versions-keyset-and-concurrent-append","normal",Map.of("detailTruncated",100,"firstPage",100,"secondPage",1,"newLatest",102));
                // Exact references inserted through real transaction services, not SQL result stubs.
                for(int n=0;n<101;n++) task.create(TS,"task-"+n,new PersonalWorkspaceTaskLinkService.CreateCommand(id,1,"INPUT","task-key-"+n));
                conversation.create(CS,"conversation-0",new PersonalWorkspaceConversationLinkService.CreateCommand(id,1,"REFERENCE","conversation-key"));
                var usage=service.usage(S,id);assertEquals(101,usage.counts().taskReferences());assertEquals(1,usage.counts().conversationReferences());
                assertEquals(101,jdbc.queryForObject("SELECT COUNT(*) FROM agent_personal_workspace_task_file_link WHERE file_id=?",Integer.class,id));
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_personal_workspace_conversation_file_link WHERE file_id=?",Integer.class,id));
                JsonNode wireUsage=body(send(client,origin+path+"/usage","GET",owner,null,Map.of()));assertEquals("101",wireUsage.path("counts").path("taskReferences").asText());assertEquals("1",wireUsage.path("counts").path("conversationReferences").asText());assertEquals(id,wireUsage.path("fileId").asText());
                observed("usage-exact-task-conversation-counts","regression-pass",wireUsage.toString());
                // Extra pagination boundary uses a separate client scope, so browser inventory is unchanged.
                var pagingScope=new PersonalWorkspaceService.Scope("0","paging-client","owner-a");
                assertEquals(0,service.list(pagingScope,new PersonalWorkspaceService.ListQuery(null,null,null,null)).items().size());
                for(int n=0;n<51;n++) service.create(pagingScope,upload("page-"+n,"page"));
                var list1=service.list(pagingScope,new PersonalWorkspaceService.ListQuery(null,"TEXT","ACTIVE",null));assertEquals(50,list1.items().size());assertNotNull(list1.nextCursor());
                var list2=service.list(pagingScope,new PersonalWorkspaceService.ListQuery(null,"TEXT","ACTIVE",list1.nextCursor()));assertEquals(1,list2.items().size());assertNull(list2.nextCursor());
                assertEquals(0,service.list(pagingScope,new PersonalWorkspaceService.ListQuery("no-such-name",null,"ACTIVE",null)).items().size());
                assertEquals(0,service.list(pagingScope,new PersonalWorkspaceService.ListQuery(null,null,"TRASHED",null)).items().size());
                observed("mysql-file-list-0-50-51-search-filter","normal",Map.of("first",50,"last",1,"absentSearch",0,"trash",0));
                // Synthetic execution metadata only: no execution service/Provider is called.
                assertEquals(0,service.usage(S,id).counts().activeInputGrants());
                for(int n=0;n<101;n++) {
                    jdbc.update("INSERT INTO agent_personal_workspace_execution (execution_id,owner_jiacn,task_id,run_id,target_agent_id,instruction,output_content_mime_type,execution_state,grant_revision,idempotency_key,request_hash,created_at,tenant_id,client_id) VALUES (?,'owner-a',?,?,'fixture-agent','synthetic','text/plain','QUEUED',1,?,?,1,'0','client-a')","audit-exec-"+n,"audit-task-"+n,"audit-run-"+n,"audit-exec-key-"+n,"a".repeat(64));
                    jdbc.update("INSERT INTO agent_personal_workspace_execution_input (input_ref,execution_id,owner_jiacn,file_id,file_version,original_filename,content_mime_type,byte_length,content_hash,storage_uri,grant_state,created_at,tenant_id,client_id) SELECT ?,?,'owner-a',file_id,version,original_filename,content_mime_type,byte_length,content_hash,storage_uri,'ACTIVE',1,'0','client-a' FROM agent_personal_workspace_file_version WHERE file_id=? AND version=1","audit-input-"+n,"audit-exec-"+n,id);
                    if(n==0)assertEquals(1,service.usage(S,id).counts().activeInputGrants());
                    if(n==99)assertEquals(100,service.usage(S,id).counts().activeInputGrants());
                }
                assertEquals(101,jdbc.queryForObject("SELECT COUNT(*) FROM agent_personal_workspace_execution_input WHERE file_id=?",Integer.class,id));
                assertEquals(101,service.usage(S,id).counts().activeInputGrants());
                jdbc.update("UPDATE agent_personal_workspace_execution SET execution_state='OUTPUT_COMMITTED' WHERE execution_id='audit-exec-0'");
                assertEquals(101,service.usage(S,id).counts().activeInputGrants());
                jdbc.update("UPDATE agent_personal_workspace_execution_input SET grant_state='REVOKED',revoked_at=2 WHERE execution_id='audit-exec-1'");
                assertEquals(100,service.usage(S,id).counts().activeInputGrants());
                observed("usage-grants-not-execution-state","regression-pass",Map.of("persisted",101,"reportedAt101",101,"afterTerminalAndRevocation",100));
                // Hold a completed statement result while another transaction changes the reference and revision.
                var isolated=service.create(S,upload("usage-race","usage-race"));String raceId=isolated.file().fileId();
                var readFile=new CountDownLatch(1);var continueRead=new CountDownLatch(1);
                PersonalWorkspaceDao delayed=(PersonalWorkspaceDao)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PersonalWorkspaceDao.class},(p,m,a)-> {
                    Object result;try { result=m.invoke(files,a); } catch(InvocationTargetException e) { throw e.getCause(); }
                    if(m.getName().equals("findUsage")&&a[3].equals(raceId)) { readFile.countDown();continueRead.await(); }return result;
                });
                var racingService=new PersonalWorkspaceServiceImpl(delayed,app.getBean(PersonalWorkspaceStorage.class),app.getBean(PersonalWorkspaceWriteService.class));
                try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
                    var read=pool.submit(()->racingService.usage(S,raceId));readFile.await();
                    try {task.create(TS,"task-102",new PersonalWorkspaceTaskLinkService.CreateCommand(raceId,1,"INPUT","usage-race-link"));} finally {continueRead.countDown();}
                    var mixed=read.get();assertEquals(1,mixed.impactRevision());assertEquals(0,mixed.counts().taskReferences());assertEquals(2,service.get(S,raceId).file().metadataRevision());
                    observed("usage-coherent-statement-snapshot","regression-pass",Map.of("reportedRevision",1,"actualRevision",2,"reportedNewReference",0));
                    assertThrows(PersonalWorkspaceException.class,()->service.trash(S,raceId,mixed.impactRevision(),true,tag(raceId,mixed.impactRevision()),new PersonalWorkspaceService.Idempotency("stale-confirmation")));
                }
                // Both concurrent appenders use the same old version; one must commit and one conflict.
                var concurrent=service.create(S,upload("concurrent","base"));String cid=concurrent.file().fileId();
                List<String> appendResults=race(()->append(service,cid,"concurrent-a"),()->append(service,cid,"concurrent-b"));
                assertEquals(1,Collections.frequency(appendResults,"COMMITTED"));assertEquals(1,Collections.frequency(appendResults,"VERSION_CONFLICT"));assertEquals(2,service.get(S,cid).file().latestVersion());
                observed("mysql-concurrent-append-cas","normal",appendResults);
                // Task root -> file row lock vs trash file lock. Both legal serial outcomes preserve invariants.
                var lockFile=service.create(S,upload("lock-race","lock-race"));String lid=lockFile.file().fileId();
                List<String> lockResults=race(()->attempt(()->task.create(TS,"task-103",new PersonalWorkspaceTaskLinkService.CreateCommand(lid,1,"INPUT","lock-link"))),
                    ()->attempt(()->service.trash(S,lid,1,true,tag(lid,1),new PersonalWorkspaceService.Idempotency("lock-trash"))));
                assertEquals(1,Collections.frequency(lockResults,"COMMITTED"));
                assertTrue(lockResults.contains("METADATA_CHANGED")||lockResults.contains("NOT_FOUND"));
                observed("mysql-task-link-versus-trash-lock-order","normal",lockResults);
                // Same-key race may report processing to the loser, but cannot create a second file.
                long before=jdbc.queryForObject("SELECT COUNT(*) FROM agent_personal_workspace_file",Long.class);
                claimReads=new CountDownLatch(2);
                List<String> keys=race(()->attempt(()->service.create(S,upload("same-concurrent-key","same-bytes"))),()->attempt(()->service.create(S,upload("same-concurrent-key","same-bytes"))));
                assertEquals(before+1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_personal_workspace_file",Long.class));
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_personal_workspace_operation WHERE idempotency_key='same-concurrent-key'",Integer.class));
                claimReads=null;
                assertTrue(keys.contains("COMMITTED"));
                assertTrue(keys.stream().allMatch(k->k.equals("COMMITTED")||k.equals("PROCESSING")));
                observed("mysql-same-key-race-fresh-transaction","regression-pass",keys);
                // All seven claim callers share the same scope/hash replay contract.
                var claimService=app.getBean(PersonalWorkspaceWriteService.class);
                var claimScope=new PersonalWorkspaceWriteService.Scope("0","client-a","owner-a");
                for(String operationType:List.of("CREATE","APPEND","RENAME","TRASH","RESTORE","ARCHIVE_ASSET","ARCHIVE_TEXT")) {
                    String claimKey="claim-path-"+operationType;
                    var first=claimService.claim(claimScope,operationType,claimKey,"a".repeat(64));
                    var replay=claimService.claim(claimScope,operationType,claimKey,"a".repeat(64));
                    assertTrue(first.claimed());assertFalse(replay.claimed());
                    assertEquals(first.operation().getOperationId(),replay.operation().getOperationId());
                    var conflict=assertThrows(PersonalWorkspaceException.class,()->claimService.claim(claimScope,operationType,claimKey,"b".repeat(64)));
                    assertEquals(PersonalWorkspaceException.Reason.IDEMPOTENCY_CONFLICT,conflict.getReason());
                }
                observed("seven-operation-claim-replay-and-conflict","regression-pass",7);
                // Strong-CAS recycle/restore and old byte snapshots are exercised on a dedicated synthetic file.
                String browserId=second.file().fileId();String browserPath="/agent/personal-workspace/files/"+browserId;
                var rawHeaders=send(client,origin+browserPath,"GET",owner,null,Map.of());observed("raw-file-headers","f13-evidence",rawHeaders.headers().map());
                var tr=send(client,origin+browserPath+"/trash","POST",owner,q(Map.of("impactRevision","1","acknowledgeExistingReferences",true)),Map.of("Idempotency-Key","strong-trash","If-Match",tag(browserId,1)));assertEquals(200,tr.statusCode());
                assertEquals("browser-download-original",send(client,origin+browserPath+"/versions/1/content","GET",owner,null,Map.of()).body());
                assertEquals(200,send(client,origin+browserPath+"/restore","POST",owner,"{}",Map.of("Idempotency-Key","strong-restore","If-Match",tag(browserId,2))).statusCode());
                observed("strong-cas-trash-restore-preserve-bytes","normal",true);
                Files.writeString(Path.of(System.getenv("CYF_WORKSPACE_EVIDENCE")).resolve("mysql-http-prefix-observations.json"),JSON.writeValueAsString(observations));
                String browserScript=System.getenv("CYF_WORKSPACE_BROWSER_SCRIPT");
                if(browserScript!=null) {
                    var command=new ProcessBuilder("node",browserScript);command.environment().put("CYF_WORKSPACE_ORIGIN",origin);
                    command.environment().put("CYF_WORKSPACE_TOKENS",JSON.writeValueAsString(Map.of("ownerA",owner,"ownerB",other)));
                    command.environment().put("CYF_WORKSPACE_FILES",JSON.writeValueAsString(Map.of("history",id,"download",browserId)));
                    Path browserLog=Path.of(System.getenv("CYF_WORKSPACE_EVIDENCE")).resolve("browser.log");command.redirectErrorStream(true).redirectOutput(browserLog.toFile());
                    Process child=command.start();try {assertEquals(0,child.waitFor(),"Browser diagnostic: "+browserLog);} finally {if(child.isAlive())child.destroy();}
                    observed("browser-diagnostic-completed","normal",true);
                }
                Path evidence=Path.of(System.getenv("CYF_WORKSPACE_EVIDENCE"));
                Files.writeString(evidence.resolve("mysql-http-observations.json"),JSON.writeValueAsString(Map.of("productionTouched",false,"database",db.getMetaData().getDatabaseProductVersion(),"security","production ResourceServerConfig and AccountSecurityJwtValidator; synthetic directory/conversation port", "observations",observations)));
            }
        } finally {
            try {if(tomcat!=null){tomcat.stop();tomcat.destroy();}} finally {try {if(app!=null)app.close();} finally {if(database!=null){database.destroy();database.waitFor();}}}
            if(Files.exists(log)) Files.copy(log,Path.of(System.getenv("CYF_WORKSPACE_EVIDENCE")).resolve("private-mysql.log"),StandardCopyOption.REPLACE_EXISTING);
        }
    }
    static PersonalWorkspaceService.UploadCommand upload(String key,String text) {return new PersonalWorkspaceService.UploadCommand(new PersonalWorkspaceService.Idempotency(key),"audit "+key,"audit.txt","text/plain",text.getBytes(StandardCharsets.UTF_8));}
    static String tag(String id,long revision){return "\""+id+":"+revision+"\"";}
    static String q(Map<String,Object> data){return JSON.writeValueAsString(Map.of("protocolVersion",1,"requestId","audit-"+UUID.randomUUID(),"data",data));}
    static JsonNode body(HttpResponse<String> r){assertEquals(200,r.statusCode(),r.body());var root=JSON.readTree(r.body());assertEquals(1,root.path("protocolVersion").asInt());assertFalse(root.path("requestId").asText().isBlank());assertEquals("E0",root.path("code").asText());assertEquals(r.headers().firstValue("X-Request-Id").orElseThrow(),root.path("requestId").asText());return root.path("data");}
    static HttpResponse<String> send(HttpClient c,String url,String method,String token,String body,Map<String,String> headers)throws Exception {
        var b=HttpRequest.newBuilder(URI.create(url)).method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        if(token!=null)b.header("Authorization","Bearer "+token);if(body!=null)b.header("Content-Type","application/json");headers.forEach(b::header);
        return c.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    @SuppressWarnings("unchecked") static String token(AnnotationConfigWebApplicationContext app,long uid,String client,long epoch)throws Exception {
        var source=(JWKSource<SecurityContext>)app.getBean("jwkSource");var keys=source.get(new JWKSelector(new JWKMatcher.Builder().keyType(KeyType.RSA).build()),null);var key=(RSAKey)keys.getFirst();
        var claims=new JWTClaimsSet.Builder().subject("synthetic-user").claim("token_kind","user").claim("uid",Long.toString(uid)).claim("username","fixture")
            .claim("jiacn",uid==1?"owner-a":"owner-b").claim("client_id",client).claim("auth_epoch",epoch).issueTime(java.util.Date.from(Instant.now())).expirationTime(java.util.Date.from(Instant.now().plusSeconds(3600))).build();
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),claims);jwt.sign(new RSASSASigner(key));return jwt.serialize();
    }
    static String append(PersonalWorkspaceService s,String id,String key){return attempt(()->s.appendVersion(S,id,upload(key,key),1));}
    static String attempt(Supplier<?> action){try{action.get();return "COMMITTED";}catch(org.springframework.dao.DuplicateKeyException e){return "DUPLICATE_KEY_LEAK";}catch(PersonalWorkspaceException e){return e.getReason().name();}catch(PersonalWorkspaceTaskLinkService.Failure e){return e.getReason().name();}}
    static List<String> race(Supplier<String> a,Supplier<String>b)throws Exception {try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var ready=new CountDownLatch(2);var start=new CountDownLatch(1);var fa=pool.submit(()->{ready.countDown();start.await();return a.get();});var fb=pool.submit(()->{ready.countDown();start.await();return b.get();});ready.await();start.countDown();return List.of(fa.get(),fb.get());}}
}
