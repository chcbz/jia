package cn.jia.agent.contract;

import cn.jia.agent.api.PersonalWorkspaceExecutionController;
import cn.jia.agent.config.PersonalWorkspaceExecutionProperties;
import cn.jia.agent.dao.*;
import cn.jia.agent.dao.impl.PersonalWorkspaceExecutionDaoImpl;
import cn.jia.agent.mapper.*;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.service.PersonalWorkspaceStorage;
import cn.jia.agent.service.impl.PersonalWorkspaceExecutionServiceImpl;
import cn.jia.agent.service.impl.PersonalWorkspaceWriteService;
import jakarta.servlet.DispatcherType;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** No mocked controller/service/DAO/HTTP/SQL responses; unrelated write paths fail closed. */
class ExecutionHistoryHttpContractTest {
    @TempDir Path scratch;
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final AtomicInteger QUERIES = new AtomicInteger();
    static final AtomicInteger FORBIDDEN = new AtomicInteger();
    static String jdbcUrl, dbPassword;
    static byte[] signingKey;

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @EnableTransactionManagement(proxyTargetClass = true)
    public static class App {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource(jdbcUrl, "history_reader", dbPassword);
        }
        @Bean DataSourceTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean SqlSessionTemplate sql(DataSource ds) {
            var config = new org.apache.ibatis.session.Configuration(new org.apache.ibatis.mapping.Environment(
                    "isolated-mysql", new SpringManagedTransactionFactory(), ds));
            config.setMapUnderscoreToCamelCase(true);
            config.addInterceptor(new ReadOnlyQueries());
            config.addMapper(PersonalWorkspaceExecutionMapper.class);
            config.addMapper(PersonalWorkspaceExecutionInputMapper.class);
            config.addMapper(PersonalWorkspaceExecutionOutputMapper.class);
            return new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        }
        @Bean PersonalWorkspaceExecutionDao executionDao(SqlSessionTemplate sql) {
            return new PersonalWorkspaceExecutionDaoImpl(sql.getMapper(PersonalWorkspaceExecutionMapper.class),
                    sql.getMapper(PersonalWorkspaceExecutionInputMapper.class), sql.getMapper(PersonalWorkspaceExecutionOutputMapper.class));
        }
        @Bean PersonalWorkspaceExecutionService executionService(PersonalWorkspaceExecutionDao dao) {
            var workspace = forbidden(PersonalWorkspaceDao.class);
            var links = forbidden(PersonalWorkspaceTaskLinkDao.class);
            PersonalWorkspaceStorage storage = new PersonalWorkspaceStorage() {
                public long maxContentBytes() { return 1024; }
                public StoredObject store(Scope s, byte[] c, String m) { throw forbiddenCall(); }
                public StoredContent read(Scope s, String u, String h, long b, String m) { throw forbiddenCall(); }
            };
            return new PersonalWorkspaceExecutionServiceImpl(dao, workspace, links,
                    forbidden(AgentRuntimeDao.class), storage, new PersonalWorkspaceWriteService(workspace, links),
                    new PersonalWorkspaceExecutionProperties());
        }
        @Bean PersonalWorkspaceExecutionController controller(PersonalWorkspaceExecutionService service) {
            return new PersonalWorkspaceExecutionController(service);
        }
        @Bean JwtDecoder jwtDecoder() {
            var decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(signingKey, "HmacSHA256"))
                    .macAlgorithm(MacAlgorithm.HS256).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("cyf-history-fixture"));
            return decoder;
        }
        @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
            return http.csrf(csrf -> csrf.disable()).authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {})).build();
        }
    }

    @Intercepts({
        @Signature(type=Executor.class, method="query", args={MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type=Executor.class, method="update", args={MappedStatement.class, Object.class})
    })
    public static class ReadOnlyQueries implements Interceptor {
        public Object intercept(Invocation invocation) throws Throwable {
            if (invocation.getMethod().getName().equals("update")) throw forbiddenCall();
            QUERIES.incrementAndGet();
            return invocation.proceed();
        }
    }
    static IllegalStateException forbiddenCall() { FORBIDDEN.incrementAndGet(); return new IllegalStateException("Unexpected side effect/collaborator"); }
    @SuppressWarnings("unchecked") static <T> T forbidden(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p,m,a) -> { throw forbiddenCall(); });
    }

    @Test void realHttpMySqlAndFrontendShareOneContract() throws Exception {
        QUERIES.set(0); FORBIDDEN.set(0);
        Path contract = Path.of(required("CYF_HISTORY_CONTRACT"));
        JsonNode fixture = JSON.readTree(Files.readString(contract));
        String fixtureHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(contract)));
        String mysql = required("CYF_HISTORY_MYSQLD");
        Path data = Files.createDirectory(scratch.resolve("mysql-data"));
        Path mysqlLog = scratch.resolve("mysql.log");
        int port; try (var socket = new ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) { port=socket.getLocalPort(); }
        String user = System.getProperty("user.name");
        var init = new ProcessBuilder(mysql, "--no-defaults", "--initialize-insecure", "--user="+user,
                "--datadir="+data, "--basedir="+Path.of(mysql).getParent().getParent())
                .redirectErrorStream(true).redirectOutput(mysqlLog.toFile()).start();
        assertEquals(0, init.waitFor(), "isolated mysql initialization: "+mysqlLog);
        Process database = null; Tomcat tomcat = null; AnnotationConfigWebApplicationContext app = null;
        try {
            database = new ProcessBuilder(mysql, "--no-defaults", "--user="+user, "--datadir="+data,
                    "--basedir="+Path.of(mysql).getParent().getParent(), "--socket="+scratch.resolve("mysql.sock"),
                    "--pid-file="+scratch.resolve("mysql.pid"), "--port="+port, "--bind-address=127.0.0.1",
                    "--mysqlx=OFF", "--skip-log-bin", "--innodb-buffer-pool-size=67108864")
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(mysqlLog.toFile())).start();
            String base = "jdbc:mysql://127.0.0.1:"+port+"/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
            Class.forName("com.mysql.cj.jdbc.Driver");
            Connection admin = null;
            while (admin == null) {
                assertTrue(database.isAlive(), "owned MySQL exited: "+mysqlLog);
                try { admin = DriverManager.getConnection(base, "root", ""); }
                catch (SQLException notReady) { Thread.sleep(200); }
            }
            try (Connection adminConnection = admin) {
                assertTrue(admin.getMetaData().getDatabaseProductVersion().startsWith("8."));
                try (var statement=admin.createStatement()) {
                    statement.execute("CREATE DATABASE history_contract CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
                    statement.execute("USE history_contract");
                    statement.execute("CREATE TABLE agent_personal_workspace_execution (tenant_id VARCHAR(50), client_id VARCHAR(50), owner_jiacn VARCHAR(50), execution_id VARCHAR(100) PRIMARY KEY, target_agent_id VARCHAR(100), execution_state VARCHAR(40), output_content_mime_type VARCHAR(127), created_at BIGINT, instruction TEXT, task_id VARCHAR(100), run_id VARCHAR(100), execution_mode VARCHAR(40), work_item_id VARCHAR(100), conversation_id VARCHAR(100), failure_code VARCHAR(40), failure_message VARCHAR(255), grant_revision BIGINT, idempotency_key VARCHAR(100), revoked_at BIGINT, failed_at BIGINT)");
                }
                for (String id : List.of("first-page", "last-page")) {
                    for (JsonNode row : example(fixture,id).path("response").path("body").path("items")) seed(admin,row,"0","client-a","owner-a",null);
                }
                JsonNode model=example(fixture,"last-page").path("response").path("body").path("items").get(0);
                seed(admin, model, "1", "client-a", "owner-a", "foreign-tenant");
                seed(admin, model, "0", "foreign-client", "owner-a", "foreign-client");
                seed(admin, model, "0", "client-a", "foreign-owner", "foreign-owner");
                seed(admin, model, "0", "client-a", "owner-broken", "broken");
                try (var s=admin.createStatement()) { s.executeUpdate("UPDATE agent_personal_workspace_execution SET target_agent_id=' invalid ' WHERE execution_id='broken'"); }
                dbPassword = UUID.randomUUID().toString().replace("-", "");
                try (var s=admin.createStatement()) {
                    s.execute("CREATE USER 'history_reader'@'127.0.0.1' IDENTIFIED BY '"+dbPassword+"'");
                    s.execute("GRANT SELECT ON history_contract.* TO 'history_reader'@'127.0.0.1'");
                }
                jdbcUrl=base.replace("/?", "/history_contract?");
                signingKey=new byte[32];new SecureRandom().nextBytes(signingKey);
                var before=snapshot(admin);
                tomcat = new Tomcat(); tomcat.setBaseDir(scratch.resolve("tomcat").toString()); tomcat.setPort(0);
                tomcat.getConnector().setProperty("address","127.0.0.1");
                Context context=tomcat.addContext("",Files.createDirectory(scratch.resolve("webroot")).toString());
                context.setParentClassLoader(getClass().getClassLoader());
                app=new AnnotationConfigWebApplicationContext(); app.setServletContext(context.getServletContext()); app.register(App.class); app.refresh();
                var servlet=Tomcat.addServlet(context,"dispatcher",new DispatcherServlet(app)); servlet.setLoadOnStartup(1);
                context.addServletMappingDecoded("/","dispatcher");
                FilterDef filter=new FilterDef();filter.setFilterName("security");filter.setFilter(new DelegatingFilterProxy("springSecurityFilterChain",app));context.addFilterDef(filter);
                FilterMap mapping=new FilterMap();mapping.setFilterName("security");mapping.addURLPattern("/*");mapping.setDispatcher(DispatcherType.REQUEST.name());context.addFilterMap(mapping);
                tomcat.start();String origin="http://127.0.0.1:"+tomcat.getConnector().getLocalPort();
                var client=HttpClient.newHttpClient();String route="/agent/personal-workspace/executions";
                String token=token("owner-a","client-a");
                Map<String,String> tokens=new LinkedHashMap<>();tokens.put("ownerA",token);tokens.put("ownerB",token("owner-b","client-a"));
                tokens.put("ownerCase",token("OWNER-A","client-a"));tokens.put("clientOther",token("owner-a","client-b"));
                tokens.put("clientCase",token("owner-a","CLIENT-A"));tokens.put("broken",token("owner-broken","client-a"));
                for (JsonNode c: fixture.path("cases")) {
                    String identity=c.path("id").asText();String bearer=identity.equals("empty")?tokens.get("ownerB"):identity.equals("unavailable")?tokens.get("broken"):token;
                    int queriesBefore=QUERIES.get();var response=get(client,origin+route+query(c.path("request")),bearer);
                    assertEquals(c.path("response").path("status").asInt(),response.statusCode(),identity);
                    assertEquals(c.path("response").path("body"),JSON.readTree(response.body()),identity);
                    assertEquals("private, no-store",response.headers().firstValue("Cache-Control").orElse(""));
                    if(identity.equals("unpaired-cursor"))assertEquals(queriesBefore,QUERIES.get(),"malformed request reached SQL");
                }
                int count=QUERIES.get();
                assertEquals(401,get(client,origin+route,null).statusCode());
                assertEquals(401,get(client,origin+route,token.substring(0,token.lastIndexOf('.')+1)+"invalid").statusCode());
                assertEquals(400,get(client,origin+route,token(null,"client-a")).statusCode());
                assertEquals(count,QUERIES.get(),"unauthorized requests reached database");
                var node=new ProcessBuilder(required("CYF_HISTORY_NODE"),required("CYF_HISTORY_NODE_SCRIPT"));
                node.environment().put("CYF_HISTORY_ORIGIN",origin);node.environment().put("CYF_HISTORY_TOKENS",JSON.writeValueAsString(tokens));
                Path nodeLog=scratch.resolve("frontend-http.jsonl");node.redirectErrorStream(true).redirectOutput(nodeLog.toFile());
                var child=node.start();int exit;try { exit=child.waitFor(); } finally { if(child.isAlive()) child.destroy(); }
                String output=Files.readString(nodeLog);assertEquals(0,exit,"frontend HTTP diagnostic:\n"+output);
                assertTrue(output.contains("\"status\":\"PASS\""));System.out.println("FRONTEND_HTTP "+output.strip());
                assertEquals(before,snapshot(admin),"read path changed persisted rows");assertEquals(0,FORBIDDEN.get());
                assertTrue(QUERIES.get()>0,"no real mapper queries executed");
                Path result=Path.of(required("CYF_HISTORY_RESULT"));
                Files.writeString(result,JSON.writeValueAsString(Map.of("status","PASS","database","isolated MySQL 8.0.21","realMapperQueries",QUERIES.get(),"forbiddenCalls",FORBIDDEN.get(),"rowSnapshotUnchanged",true,"fixtureSha256",fixtureHash,"frontendOutput",output.strip(),"productionTouched",false)));
            }
        } finally {
            try { if(tomcat!=null){tomcat.stop();tomcat.destroy();} }
            finally { try {if(app!=null)app.close();} finally {if(database!=null){database.destroy();database.waitFor();}} }
        }
    }
    static void seed(Connection db,JsonNode row,String tenant,String client,String owner,String replacement) throws SQLException {
        try(var p=db.prepareStatement("INSERT INTO agent_personal_workspace_execution (tenant_id,client_id,owner_jiacn,execution_id,target_agent_id,execution_state,output_content_mime_type,created_at,instruction) VALUES (?,?,?,?,?,?,?,?,?)")) {
            p.setString(1,tenant);p.setString(2,client);p.setString(3,owner);p.setString(4,replacement==null?row.path("executionId").asText():replacement);
            p.setString(5,row.path("targetAgentId").asText());p.setString(6,row.path("state").asText());p.setString(7,row.path("outputContentMimeType").asText());p.setLong(8,row.path("createdAt").asLong());p.setString(9,"synthetic-private-body-must-not-leak");p.executeUpdate();
        }
    }
    static String snapshot(Connection db)throws SQLException {try(var s=db.createStatement();var r=s.executeQuery("SELECT * FROM agent_personal_workspace_execution ORDER BY execution_id")){StringBuilder b=new StringBuilder();while(r.next())for(int i=1;i<=r.getMetaData().getColumnCount();i++)b.append(r.getString(i)).append('\u0000');return b.toString();}}
    static JsonNode example(JsonNode fixture,String id){for(JsonNode c:fixture.path("cases"))if(c.path("id").asText().equals(id))return c;throw new IllegalArgumentException(id);}
    static String query(JsonNode params){List<String> values=new ArrayList<>();params.properties().forEach(e->values.add(e.getKey()+"="+java.net.URLEncoder.encode(e.getValue().asText(),StandardCharsets.UTF_8)));return "?"+String.join("&",values);}
    static HttpResponse<String> get(HttpClient client,String url,String token)throws Exception{var request=HttpRequest.newBuilder(URI.create(url)).GET();if(token!=null)request.header("Authorization","Bearer "+token);return client.send(request.build(),HttpResponse.BodyHandlers.ofString());}
    static String required(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalStateException("Missing "+key);return value;}
    static String token(String owner,String client)throws Exception{
        Map<String,Object> claims=new LinkedHashMap<>();claims.put("sub","synthetic");claims.put("iss","cyf-history-fixture");claims.put("iat",Instant.now().getEpochSecond());claims.put("exp",Instant.now().plusSeconds(3600).getEpochSecond());claims.put("client_id",client);if(owner!=null)claims.put("jiacn",owner);
        var encoder=Base64.getUrlEncoder().withoutPadding();String body=encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8))+"."+encoder.encodeToString(JSON.writeValueAsBytes(claims));
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(signingKey,"HmacSHA256"));return body+"."+encoder.encodeToString(mac.doFinal(body.getBytes(StandardCharsets.US_ASCII)));
    }
}
