package cn.jia.agent.acceptance.ur06;

import cn.jia.agent.api.AgentRuntimeV1Controller;
import cn.jia.agent.event.AgentEventPublisher;
import cn.jia.agent.config.*;
import cn.jia.agent.dao.*;
import cn.jia.agent.dao.impl.*;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentTaskInvitePayload;
import cn.jia.agent.mapper.*;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.AgentCommandAckService;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentRuntimeV1Service;
import cn.jia.agent.service.impl.AgentCommandAckServiceImpl;
import cn.jia.agent.service.impl.AgentCommandCanonicalCodec;
import cn.jia.agent.service.impl.AgentIdentityServiceImpl;
import cn.jia.agent.service.impl.AgentRuntimeV1ServiceImpl;
import cn.jia.core.security.SensitiveResponseBodyAdvice;
import cn.jia.core.security.SensitiveResponseProperties;
import cn.jia.user.dao.UserInfoDao;
import cn.jia.user.dao.impl.UserInfoDaoImpl;
import cn.jia.user.mapper.InfoMapper;
import cn.jia.user.security.AccountSecurityService;
import cn.jia.user.security.AccountSecurityServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import cn.jia.agent.entity.*;
import cn.jia.agent.service.*;
import cn.jia.agent.service.impl.*;
import cn.jia.agent.cache.AgentPersonaCatalogCache;
import cn.jia.agent.hosting.*;
import cn.jia.agent.skill.SkillAgentVersions;
import cn.jia.economy.mapper.*;
import cn.jia.chat.config.AgentRuntimeHandshakeInterceptor;
import cn.jia.chat.handler.AgentWebSocketHandler;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.dao.impl.ChatMessageDaoImpl;
import cn.jia.chat.mapper.ChatMessageMapper;
import cn.jia.chat.service.ChatConversationEventBroker;
import cn.jia.agent.api.AgentWorkItemReassignmentController;
import cn.jia.agent.api.AgentWorkItemRuntimeResultController;
import cn.jia.task.service.TaskService;
import cn.jia.oauth.service.ApiKeyService;
import cn.jia.user.service.UserService;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.core.io.FileSystemResource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.sql.CallableStatement;

/** Fixture-only original Spring/controller/DAO/native WS assembly on private MySQL.
 * No application auto-configuration, alternate auth, fake DAO or credential preseed.
 * Child stdout is a parent-owned private pipe, NOT an artifact/report/log destination. */
public final class Ur06EnrollMysqlFixture {
    static final String TENANT = "0", CLIENT = "ur06-client", OWNER = "ur06-owner", PREFIX = "UR06_PIPE ";
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String[] AGENTS = java.util.stream.IntStream.range(1, 9).mapToObj(i -> "agt_" + Integer.toHexString(i).repeat(32)).toArray(String[]::new);
    static final String[] INSTALLATIONS = java.util.stream.IntStream.range(1, 9).mapToObj(i -> "rti_" + Integer.toHexString(i).repeat(32)).toArray(String[]::new);
    private static DataSource activeDataSource;
    private static Path activeRoot;
    private static PrintStream protocol;
    private static AgentWebSocketHandler nativeHandler;
    private static String bootStage = "BOOT_INPUT";
    private static volatile String cutInstallation;
    private static final CountDownLatch heldResponse = new CountDownLatch(1);
    private static final AtomicBoolean cutUsed = new AtomicBoolean();
    private static final List<Map<String, Object>> HTTP = new CopyOnWriteArrayList<>();
        private Ur06EnrollMysqlFixture() { }
    static void require(boolean value, String code) { if (!value) throw new FixtureFailure(code); }
    static String text(JsonNode node, String key) {
        JsonNode f = node == null ? null : node.get(key);
        require(f != null && f.isTextual() && !f.asText().isBlank(), "TEXT_REQUIRED"); return f.asText();
    }
    static long integer(JsonNode node, String key) {
        JsonNode f = node == null ? null : node.get(key);
        require(f != null && f.isIntegralNumber() && f.canConvertToLong(), "INTEGER_REQUIRED"); return f.longValue();
    }
    static byte[] sha256(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception ignored) { throw new FixtureFailure("DIGEST_UNAVAILABLE"); }
    }
    static void emit(Map<String, ?> value) { protocol.println(PREFIX + JSON.writeValueAsString(value)); protocol.flush(); }
    public static void main(String[] args) {
        protocol = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream())); System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        java.util.logging.LogManager.getLogManager().reset();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            emit(Map.of("stage", "JAVA_BOOT", "pid", ProcessHandle.current().pid()));
            JsonNode init = JSON.readTree(in.readLine()); require("INIT".equals(text(init, "op")), "INIT_REQUIRED");
            activeRoot = privateRoot(Path.of(text(init, "root")));
            activeDataSource = new GuardedDataSource(DatabaseSpec.from(init.get("database")));
            JdbcTemplate jdbc = new JdbcTemplate(activeDataSource);
            bootStage = "BOOT_SCHEMA";
            if (init.get("initialize").asBoolean()) initialize(jdbc, Path.of(text(init, "apiRoot")));
            else require(jdbc.queryForObject("SELECT COUNT(*) FROM agent_runtime WHERE agent_id='ur06-legacy'", Integer.class) == 1,
                    "NO_RESEED_RESTART_REQUIRED");
            bootStage = "BOOT_HTTP";
            try (HttpFixture http = new HttpFixture(activeRoot)) {
                http.start(); emit(Map.of("stage", "JAVA_READY", "pid", ProcessHandle.current().pid(), "port", http.port()));
                String line;
                while ((line = in.readLine()) != null) {
                    JsonNode r = JSON.readTree(line); String op = text(r, "op");
                    if (op.equals("STOP")) { heldResponse.countDown(); http.close(); emit(Map.of("stage", "STOPPED")); return; }
                    if (op.equals("CREATE")) {
                        int i = Math.toIntExact(integer(r, "agentIndex")); require(i >= 0 && i < 8, "INDEX_REQUIRED");
                        JsonNode m = r.get("manifest"); require(AGENTS[i].equals(text(m, "canonicalAgentId")) && INSTALLATIONS[i].equals(text(m, "installationId")), "MANIFEST_SUBJECT_REQUIRED");
                        long now = System.currentTimeMillis(), expires = Math.addExact(now, integer(r, "expiresInMillis"));
                        AgentRuntimeV1InstallationView view = http.spring.getBean(AgentRuntimeV1Service.class).create(TENANT, CLIENT, OWNER,
                            new AgentRuntimeV1InstallationRequest(INSTALLATIONS[i], AGENTS[i], text(m, "manifestVersion"), text(m, "manifestSha256").substring(7), text(r, "secretSha256"), expires), now);
                        require("PENDING".equals(view.status()), "SERVICE_PENDING_REQUIRED");
                        emit(Map.of("stage", "CREATED", "expiresAt", expires, "version", 0, "pending", true));
                    } else if (op.equals("SNAPSHOT")) emit(snapshot(jdbc));
                    else if (op.equals("ARM_CUT")) { cutInstallation = INSTALLATIONS[Math.toIntExact(integer(r, "agentIndex"))]; emit(Map.of("stage", "CUT_ARMED")); }
                    else if (op.equals("REPEAT_SCHEMA")) {
                        String before = catalog(jdbc); List<Map<String, Object>> rows = legacy(jdbc);
                        new AgentSchemaInitializer(jdbc).afterPropertiesSet();
                        new AgentRuntimeV1SchemaInitializer(jdbc).afterPropertiesSet();
                        new AgentRuntimeSessionFenceSchemaInitializer(jdbc).afterPropertiesSet();
                        new AgentCommandTransportSchemaInitializer(jdbc).afterPropertiesSet();
                        require(before.equals(catalog(jdbc)) && rows.equals(legacy(jdbc)), "REPEAT_CATALOG_ROWS_REQUIRED");
                        emit(Map.of("stage", "SCHEMA_REPEATED", "unchanged", true));
                    } else if (op.equals("SEED_ACK")) {
                        int i = Math.toIntExact(integer(r, "agentIndex")); require(i >= 0 && i < 2, "INDEX_REQUIRED");
                        Map<String, Object> command = new TransactionTemplate(new DataSourceTransactionManager(activeDataSource)).execute(tx -> seedAck(jdbc, i));
                        emit(Map.of("stage", "ACK_SEEDED", "command", command));
                    } else if (op.equals("MUTATE_SOURCE")) {
                        int i = Math.toIntExact(integer(r, "agentIndex")); String variant = text(r, "variant");
                        require(i >= 0 && i < 2, "INDEX_REQUIRED");
                        switch (variant) {
                            case "HASH" -> jdbc.update("UPDATE agent_outbox_event SET wire_payload_hash=? WHERE id=?", new byte[32], i + 1);
                            case "LEASE" -> jdbc.update("UPDATE agent_consumer_inbox SET lease_owner='ur06-invalid',lease_until=? WHERE id=?", System.currentTimeMillis() + 60000, i + 1);
                            case "MESSAGE" -> jdbc.update("UPDATE agent_command_delivery SET active_message_id='ur06-conflict' WHERE id=?", i + 1);
                            default -> throw new FixtureFailure("MUTATION_ALLOWLIST_REQUIRED");
                        }
                        emit(Map.of("stage", "SOURCE_MUTATED"));
                    } else if (op.equals("REVOKE")) {
                        int i = Math.toIntExact(integer(r, "agentIndex")); require(i >= 0 && i < 2, "INDEX_REQUIRED");
                        http.spring.getBean(AgentRuntimeV1Service.class).revoke(TENANT, CLIENT, OWNER, INSTALLATIONS[i], System.currentTimeMillis());
                        emit(Map.of("stage", "REVOKED"));
                    } else throw new FixtureFailure("OPERATION_ALLOWLIST_REQUIRED");
                }
                throw new FixtureFailure("EXPLICIT_STOP_REQUIRED");
            }
        } catch (Throwable failure) {
            Map<String, Object> safe = new LinkedHashMap<>(safeFailure(failure)); safe.put("stage", "ERROR"); safe.put("at", bootStage);
            emit(safe); System.exit(1);
        }
    }
    private static void initialize(JdbcTemplate jdbc, Path api) throws Exception {
        require(api.isAbsolute() && api.toRealPath().equals(api), "API_CANONICAL_REQUIRED");
        // Original module baseline resources (no ambiguous shared db/schema.sql lookup).
        for (String path : List.of("agent/jia-agent-mapper/src/main/resources/db/schema.sql",
                "user/jia-user-mapper/src/test/resources/db/schema.sql",
                "user/jia-user-mapper/src/main/resources/db/account-security-foundation-upgrade.sql")) {
            Path resource = api.resolve(path); require(resource.toRealPath().equals(resource), "DDL_ALIAS_FORBIDDEN");
            new ResourceDatabasePopulator(new FileSystemResource(resource)).execute(activeDataSource);
        }
        // Latest original initializer owns corrected tenant-0 identity/constraints/persona
        // projection. Do NOT apply obsolete owner-as-tenant A02 migration to this fresh DB.
        new AgentSchemaInitializer(jdbc).afterPropertiesSet();
        jdbc.update("INSERT INTO agent_runtime(agent_id,name,status,tenant_id,client_id,create_time,update_time) VALUES('ur06-legacy','UR06 legacy','offline','0',?,17,17)", CLIENT);
        new AgentRuntimeV1SchemaInitializer(jdbc).afterPropertiesSet();
        new AgentRuntimeSessionFenceSchemaInitializer(jdbc).afterPropertiesSet();
        new AgentCommandTransportSchemaInitializer(jdbc).afterPropertiesSet();
        require(legacy(jdbc).size() == 1, "LEGACY_KEY_REQUIRED");
        require(jdbc.queryForObject("SELECT COUNT(*) FROM agent_runtime WHERE agent_id='ur06-legacy' AND runtime_installation_id IS NULL AND runtime_host_id IS NULL AND runtime_instance_id IS NULL AND runtime_session_generation IS NULL AND create_time=17 AND update_time=17", Integer.class) == 1,
            "NULL_OWNERSHIP_PRESERVED_REQUIRED");
        new TransactionTemplate(new DataSourceTransactionManager(activeDataSource)).executeWithoutResult(tx -> {
            jdbc.update("INSERT INTO user_info(id,jiacn,account_state,auth_epoch,client_id,tenant_id) VALUES(700,?,'ACTIVE',2,?,'0')", OWNER, CLIENT);
            long now = System.currentTimeMillis();
            for (int i = 0; i < 8; i++) {
                long id = 1000 + i;
                jdbc.update("INSERT INTO agent_persona(id,persona_code,name,rank_no,abilities,active,system_agent,tenant_id,client_id) VALUES(?,?,?,?,?,1,0,'0',?)", id, "ur06-persona-"+i, "UR06 persona "+i, 200+i, "[]", CLIENT);
                jdbc.update("INSERT INTO agent_persona_binding(id,jiacn,persona_code,agent_id,bound_at,status,tenant_id,client_id,create_time,update_time) VALUES(?,?,?,?,?,1,'0',?,?,?)", id, OWNER, "ur06-persona-"+i, AGENTS[i], now, CLIENT, now, now);
                jdbc.update("INSERT INTO agent_identity_registry(id,canonical_agent_id,canonical_type,lifecycle_status,owner_jiacn,binding_id,provisioned_at,activated_at,audit_reason,tenant_id,client_id,create_time,update_time) VALUES(?,?,'OPAQUE','ACTIVE',?,?,?,?,'UR06_SYNTHETIC','0',?,?,?)", id, AGENTS[i], OWNER, id, now, now, CLIENT, now, now);
            }
        });
        // Installation table remains EMPTY until the original proxied create operation.
        require(jdbc.queryForObject("SELECT COUNT(*) FROM agent_runtime_v1_installation", Integer.class) == 0, "INSTALLATION_PRESEED_FORBIDDEN");
    }
    static List<Map<String, Object>> legacy(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT * FROM agent_runtime WHERE agent_id='ur06-legacy'");
    }
    static String catalog(JdbcTemplate jdbc) {
        var columns = jdbc.queryForList("SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT,COLLATION_NAME,EXTRA FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME,ORDINAL_POSITION");
        var indexes = jdbc.queryForList("SELECT TABLE_NAME,INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME FROM information_schema.statistics WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX");
        var tables=jdbc.queryForList("SELECT TABLE_NAME,ENGINE,TABLE_COLLATION FROM information_schema.tables WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME");
        var checks=jdbc.queryForList("SELECT tc.TABLE_NAME,tc.CONSTRAINT_NAME,tc.ENFORCED,cc.CHECK_CLAUSE FROM information_schema.table_constraints tc JOIN information_schema.check_constraints cc ON cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME WHERE tc.CONSTRAINT_SCHEMA=DATABASE() AND tc.CONSTRAINT_TYPE='CHECK' ORDER BY tc.TABLE_NAME,tc.CONSTRAINT_NAME");
        var triggers=jdbc.queryForList("SELECT TRIGGER_NAME,EVENT_OBJECT_TABLE,ACTION_STATEMENT FROM information_schema.triggers WHERE TRIGGER_SCHEMA=DATABASE() ORDER BY TRIGGER_NAME");
        return JSON.writeValueAsString(List.of(columns, indexes, tables, checks, triggers));
    }
    static Map<String, Object> snapshot(JdbcTemplate jdbc) {
        Map<String, Object> s = new LinkedHashMap<>(); s.put("stage", "SNAPSHOT"); s.put("http", List.copyOf(HTTP));
        s.put("installations", jdbc.queryForList("SELECT installation_id,status,version,enrollment_consumed_at IS NOT NULL AS consumed,runtime_authorization_issued_at IS NOT NULL AS issued,runtime_authorization_hash IS NOT NULL AS authorized FROM agent_runtime_v1_installation ORDER BY installation_id"));
        s.put("deliveries", jdbc.queryForList("SELECT status,version FROM agent_command_delivery ORDER BY id"));
        s.put("generations", jdbc.queryForList("SELECT runtime_session_generation FROM agent_runtime WHERE agent_id IN (?,?) ORDER BY agent_id", AGENTS[0], AGENTS[1]));
        return s;
    }
    private static Map<String, Object> seedAck(JdbcTemplate jdbc, int i) {
        long now = System.currentTimeMillis(); String task = "ur06-task-"+i, message = "ur06-message-"+i, event = "ur06-event-"+i;
        String command = AgentCommandCanonicalCodec.taskInviteCommandId(TENANT, CLIENT, OWNER, task, AGENTS[i]);
        AgentTaskInvitePayload payload = new AgentTaskInvitePayload("task_briefing", "宋江首领已完成悬赏分派，请按职责协作推进。",
            "阅读悬赏任务，确认自己的职责；如需协助，优先参考协作名册中的好汉能力并回报下一步计划。", "UR06 synthetic ACK only", List.of(), AGENTS[i], List.of(AGENTS[i]), "coordinator",
            "回报执行计划、风险和协助诉求；Protocol v1 使用 work.progress，完成后使用 work.result，旧客户端由兼容层处理。", "juyiting");
        AgentCommandDraft draft = new AgentCommandDraft(1, command, task, event, TENANT, CLIENT, OWNER, task, null, AGENTS[i], "TASK_INVITE", now, now+AgentCommandCanonicalCodec.TASK_INVITE_TTL_MILLIS, null, payload);
        byte[] business = AgentCommandCanonicalCodec.businessBytes(draft), wire = AgentCommandCanonicalCodec.wireBytes(draft, message, 1);
        var route = AgentRabbitTopologyManifest.canonical().defaultCommandPublishRoute(); long id=i+1;
        jdbc.update("INSERT INTO agent_command_delivery(id,owner_jiacn,command_id,task_id,work_item_id,target_agent_id,command_type,command_payload,command_payload_hash,status,attempt_count,active_message_id,active_attempt,expires_at,version,tenant_id,client_id,create_time,update_time) VALUES(?,?,?,?,NULL,?,'TASK_INVITE',?,?,'SENT',1,?,1,?,7,'0',?,?,?)", id, OWNER, command, task, AGENTS[i], business, AgentCommandCanonicalCodec.sha256(business), message, draft.expiresAt(), CLIENT, now, now);
        jdbc.update("INSERT INTO agent_outbox_event(id,event_id,message_id,command_id,delivery_id,aggregate_type,aggregate_id,destination,routing_key,wire_payload,wire_payload_hash,status,attempt_count,active_attempt,expires_at,publisher_confirm_status,confirmed_at,mandatory_return_status,published_at,version,tenant_id,client_id,create_time,update_time) VALUES(?,?,?,?,?,'task',?,?,?,?,?,'PUBLISHED',1,1,?,'ACK',?,'NOT_RETURNED',?,0,'0',?,?,?)", id,event,message,command,id,task,route.destination(),route.routingKey(),wire,AgentCommandCanonicalCodec.sha256(wire),draft.expiresAt(),now,now,CLIENT,now,now);
        jdbc.update("INSERT INTO agent_consumer_inbox(id,consumer_name,message_id,event_id,command_id,delivery_id,wire_payload,wire_payload_hash,status,result_status,attempt_count,active_attempt,expires_at,processed_at,version,tenant_id,client_id,create_time,update_time) VALUES(?,'agent-command-dispatch-v1',?,?,?,?,?,?,'PROCESSED','SENT',1,1,?,?,0,'0',?,?,?)", id,message,event,command,id,wire,AgentCommandCanonicalCodec.sha256(wire),draft.expiresAt(),now,CLIENT,now,now);
        Map<String,Object> context = new LinkedHashMap<>(); context.put("commandId",command); context.put("taskId",task); context.put("workItemId",null); context.put("messageId",message); context.put("correlationId",message); context.put("expiresAt",java.time.Instant.ofEpochMilli(draft.expiresAt()).toString());
        return context;
    }
    private static final class ResponseCut implements Filter {
        @Override public void doFilter(ServletRequest raw, ServletResponse response, FilterChain chain) throws IOException, ServletException {
            // Spring 7 has only the (request, int) constructor. Its local bytecode
            // maps nonpositive limits to null: 0 observes without truncation or a new gate.
            var req = new ContentCachingRequestWrapper((HttpServletRequest) raw, 0);
            var res = new ContentCachingResponseWrapper((HttpServletResponse) response);
            try {
                chain.doFilter(req,res);
                String path=req.getRequestURI();
                if (path.endsWith("/enroll") || path.endsWith("/session") || path.endsWith("/acks") || path.endsWith("/result-commit")) {
                    byte[] requestBytes=req.getContentAsByteArray(), responseBytes=res.getContentAsByteArray();
                    JsonNode body=requestBytes.length==0 ? null : JSON.readTree(requestBytes);
                    // Servlet/container failures may legitimately have no JSON response.
                    // Observe status without replacing that response or swallowing JSON failures.
                    JsonNode output=responseBytes.length==0 || res.getContentType()==null
                        || !res.getContentType().toLowerCase(Locale.ROOT).contains("json")
                        ? null : JSON.readTree(responseBytes);
                    String category=path.endsWith("/enroll") ? "ENROLL" : path.endsWith("/session") ? "SESSION" : path.endsWith("/acks") ? "ACK" : "NATIVE";
                    Map<String,Object> receipt=new LinkedHashMap<>(); receipt.put("category",category); receipt.put("status",res.getStatus());
                    receipt.put("noStore", "private, no-store".equals(res.getHeader("Cache-Control")));
                    if (body!=null && body.get("installationId")!=null) {
                        String id=body.get("installationId").asText(); if (Arrays.asList(INSTALLATIONS).contains(id)) receipt.put("installationId",id);
                    }
                    JsonNode data=output==null ? null : output.get("data");
                    if (category.equals("ENROLL") && res.getStatus()==200) {
                        JsonNode view=data==null ? null : data.get("installation");
                        require(data!=null && data.size()==2 && data.has("runtimeAuthorization") && view!=null && view.size()==9
                            && !view.has("runtimeAuthorization") && !view.has("sessionToken") && !view.has("enrollmentSecret"), "NESTED_WIRE_REQUIRED");
                        receipt.put("nested",true);
                    }
                    if (category.equals("ACK") && data!=null && data.get("deliveryVersion")!=null) {
                        receipt.put("deliveryVersion", data.get("deliveryVersion").longValue());
                        if (body!=null && body.get("deliveryVersion")!=null) receipt.put("lastConfirmedVersion", body.get("deliveryVersion").isNull() ? null : body.get("deliveryVersion").longValue());
                        for (String key : List.of("kind","status")) if (data.get(key)!=null && Set.of("ADVANCED","PRIOR","RECEIVED","STARTED","SUCCEEDED").contains(data.get(key).asText())) receipt.put(key,data.get(key).asText());
                    }
                    if (!receipt.containsKey("installationId")) {
                        String id=req.getHeader("X-Agent-Installation-Id");
                        if (Arrays.asList(INSTALLATIONS).contains(id)) receipt.put("installationId",id);
                    }
                    HTTP.add(receipt);
                    if (category.equals("ENROLL") && res.getStatus()==200 && cutInstallation!=null && cutInstallation.equals(receipt.get("installationId")) && cutUsed.compareAndSet(false,true)) {
                        require(new JdbcTemplate(activeDataSource).queryForObject("SELECT COUNT(*) FROM agent_runtime_v1_installation WHERE installation_id=? AND status='ACTIVE' AND enrollment_consumed_at IS NOT NULL AND version=1",Integer.class,cutInstallation)==1,"COMMITTED_BEFORE_CUT_REQUIRED");
                        emit(Map.of("stage","ENROLL_COMMIT_HELD","committed",true)); heldResponse.await();
                    }
                }
                res.copyBodyToResponse();
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new ServletException("UR06_CUT_INTERRUPTED"); }
        }
    }
    private static final class HttpFixture implements AutoCloseable {
        final AnnotationConfigWebApplicationContext spring = new AnnotationConfigWebApplicationContext();
        private final Tomcat tomcat = new Tomcat(); private boolean closed;
        HttpFixture(Path root) throws Exception {
            Path doc=Files.createDirectory(root.resolve("http-"+UUID.randomUUID()),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            tomcat.setBaseDir(doc.toString()); tomcat.setHostname("127.0.0.1"); tomcat.setPort(0);
            require(tomcat.getConnector().setProperty("address","127.0.0.1"),"HTTP_LOOPBACK_REQUIRED");
            Context context=tomcat.addContext("",doc.toString()); context.setParentClassLoader(Ur06EnrollMysqlFixture.class.getClassLoader());
            context.addServletContainerInitializer(new org.apache.tomcat.websocket.server.WsSci(),Set.of());
            spring.setServletContext(context.getServletContext()); spring.register(HttpConfiguration.class);
            bootStage="BOOT_MYBATIS_SPRING"; spring.refresh();
            for (Class<?> type : List.of(AgentRuntimeAuthenticationService.class,AgentRuntimeV1Service.class,AgentService.class))
                require(org.springframework.aop.support.AopUtils.isAopProxy(spring.getBean(type)),"REAL_TRANSACTION_PROXY_REQUIRED");
            require(nativeHandler!=null,"NATIVE_HANDLER_REQUIRED");
            var servlet=Tomcat.addServlet(context,"dispatcher",new DispatcherServlet(spring)); servlet.setLoadOnStartup(1); context.addServletMappingDecoded("/","dispatcher");
            addFilter(context,"response-cut",new ResponseCut()); addFilter(context,"runtime-security",new DelegatingFilterProxy("springSecurityFilterChain",spring));
        }
        static void addFilter(Context c,String name,Filter f) { FilterDef d=new FilterDef(); d.setFilterName(name); d.setFilter(f); c.addFilterDef(d); FilterMap m=new FilterMap(); m.setFilterName(name); m.addURLPattern("/*"); m.setDispatcher("REQUEST"); c.addFilterMap(m); }
        void start() throws Exception { bootStage="BOOT_TOMCAT_START"; tomcat.start(); require(port()>0,"BOUND_PORT_REQUIRED"); }
        int port() { return tomcat.getConnector().getLocalPort(); }
        @Override public void close() throws Exception { if (!closed) { closed=true; try {tomcat.stop();} finally {try {tomcat.destroy();} finally {spring.close();}} } }
    }
    static Path privateRoot(Path root) throws IOException {
        require(root.isAbsolute() && !Files.isSymbolicLink(root) && root.toRealPath().equals(root) && Files.isDirectory(root)
            && root.getFileName().toString().startsWith("ur06-") && Files.getPosixFilePermissions(root).equals(PosixFilePermissions.fromString("rwx------")),"PRIVATE_ROOT_REQUIRED"); return root;
    }
    /** All identifiers/credentials are supplied only on the private pipe. Strict fixed
     * loopback URL is derived internally, never imported from application properties. */
    record DatabaseSpec(int port, Path root, Path datadir, Path binary, long pid, String start, String inode, String dataInode, String user, String password) {
        static DatabaseSpec from(JsonNode n) { return new DatabaseSpec(Math.toIntExact(integer(n,"port")),Path.of(text(n,"root")),Path.of(text(n,"datadir")),Path.of(text(n,"binary")),integer(n,"pid"),text(n,"start"),text(n,"inode"),text(n,"dataInode"),text(n,"user"),text(n,"password")); }
        @Override public String toString() { return "UR06_PRIVATE_DATABASE_REDACTED"; }
        String url(String schema) { require(port>0 && port<=65535 && port!=3306 && port!=33060,"MYSQL_PORT_REQUIRED"); require(schema.matches("(?:ur06_fixture|mysql)"),"MYSQL_SCHEMA_REQUIRED"); return "jdbc:mysql://127.0.0.1:"+port+"/"+schema+"?sslMode=DISABLED&allowPublicKeyRetrieval=true&logger=com.mysql.cj.log.NullLogger"; }
        void owned() throws Exception {
            privateRoot(root); require(Files.readAttributes(root,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey().toString().equals(inode),"MYSQL_ROOT_INODE_REQUIRED");
            require(datadir.equals(root.resolve("mysql-data")) && datadir.toRealPath().equals(datadir) && !Files.isSymbolicLink(datadir)
                && Files.readAttributes(datadir,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey().toString().equals(dataInode),"MYSQL_DATADIR_INODE_REQUIRED");
            ProcessHandle h=ProcessHandle.of(pid).orElseThrow(()->new FixtureFailure("MYSQL_PROCESS_REQUIRED"));
            require(h.isAlive() && h.info().startInstant().map(Object::toString).filter(start::equals).isPresent()
                && h.info().command().map(Path::of).map(p -> {try{return p.toRealPath();}catch(IOException e){return null;}}).filter(binary::equals).isPresent(),"MYSQL_PROCESS_IDENTITY_REQUIRED");
            String[] args=h.info().arguments().orElseThrow(()->new FixtureFailure("MYSQL_ARGUMENT_PROOF_REQUIRED"));
            require(args.length>0 && args[0].equals("--no-defaults") && Arrays.asList(args).contains("--datadir="+datadir)
                && Arrays.asList(args).contains("--bind-address=127.0.0.1") && Arrays.asList(args).contains("--port="+port)
                && Arrays.asList(args).contains("--socket="+root.resolve("mysql.sock")) && Arrays.asList(args).contains("--pid-file="+root.resolve("mysql.pid"))
                && Arrays.asList(args).contains("--log-error="+root.resolve("mysql.error"))
                && Arrays.asList(args).contains("--secure-file-priv="+root.resolve("mysql-files"))
                && Arrays.asList(args).contains("--mysqlx=0")
                && Arrays.asList(args).contains("--general-log=OFF") && Arrays.asList(args).contains("--slow-query-log=OFF") && Arrays.asList(args).contains("--skip-log-bin"),"MYSQL_ARGUMENT_PROOF_REQUIRED");
            Path pidfile=root.resolve("mysql.pid"); require(!Files.isSymbolicLink(pidfile) && Files.readString(pidfile).strip().equals(Long.toString(pid)),"MYSQL_PIDFILE_REQUIRED");
        }
        void verify(Connection c) throws Exception {
            owned(); require("MySQL".equals(c.getMetaData().getDatabaseProductName()),"REAL_MYSQL_REQUIRED");
            try (Statement statement=c.createStatement(); ResultSet rs=statement.executeQuery("SELECT @@version,@@port,@@datadir,@@bind_address,@@general_log,@@slow_query_log,@@log_bin,@@log_error,@@socket,@@pid_file")) {
                require(rs.next() && rs.getString(1).matches("8\\.0\\.21(?:[-+].*)?") && rs.getInt(2)==port && Path.of(rs.getString(3)).toRealPath().equals(datadir)
                    && rs.getString(4).equals("127.0.0.1") && !rs.getBoolean(5) && !rs.getBoolean(6) && !rs.getBoolean(7)
                    && Path.of(rs.getString(8)).equals(root.resolve("mysql.error"))
                    && Path.of(rs.getString(9)).equals(root.resolve("mysql.sock"))
                    && Path.of(rs.getString(10)).equals(root.resolve("mysql.pid")),"MYSQL_SERVER_PROOF_REQUIRED");
            }
        }
        Map<String,Object> pipe() { return Map.ofEntries(Map.entry("port",port),Map.entry("root",root.toString()),Map.entry("datadir",datadir.toString()),Map.entry("binary",binary.toString()),Map.entry("pid",pid),Map.entry("start",start),Map.entry("inode",inode),Map.entry("dataInode",dataInode),Map.entry("user",user),Map.entry("password",password)); }
    }
    /** Transparent JDBC observation, not a substitute DAO/transaction. Verifies the
     * real owned server before each execute/batch/commit, including original initializers. */
    static final class GuardedDataSource extends AbstractDataSource {
        final DatabaseSpec spec; GuardedDataSource(DatabaseSpec spec) { this.spec=spec; }
        @Override public Connection getConnection() throws SQLException {
            Connection raw=DriverManager.getConnection(spec.url("ur06_fixture"),spec.user(),spec.password());
            try {
                spec.verify(raw);
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)-> {
                    try {
                        if (Set.of("commit","setAutoCommit").contains(m.getName())) spec.verify(raw);
                        if (m.getName().equals("unwrap")) {
                            require(a != null && a.length == 1 && a[0] == Connection.class,"JDBC_UNWRAP_FORBIDDEN"); return p;
                        }
                        Object result=m.invoke(raw,a);
                        if (result instanceof Statement statement) {
                            Class<?> type=result instanceof CallableStatement ? CallableStatement.class : result instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
                            return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(sp,sm,sa)-> {
                                try { if (sm.getName().startsWith("execute") || sm.getName().equals("addBatch")) spec.verify(raw);
                                    if (sm.getName().equals("unwrap")) {
                                        require(sa != null && sa.length == 1 && sa[0] == type,"JDBC_UNWRAP_FORBIDDEN"); return sp;
                                    }
                                    if (sm.getName().equals("getConnection")) return p; return sm.invoke(statement,sa); }
                                catch(InvocationTargetException e){throw e.getCause();}
                            });
                        }
                        return result;
                    } catch(InvocationTargetException e){throw e.getCause();}
                });
            } catch(Exception failure) { raw.close(); throw new SQLException("UR06_MYSQL_GUARD_REJECTED","HY000",failure); }
        }
        @Override public Connection getConnection(String user,String password) throws SQLException {
            require(Objects.equals(user,spec.user()) && Objects.equals(password,spec.password()),"MYSQL_CREDENTIAL_SCOPE_REQUIRED"); return getConnection();
        }
    }
    static Map<String,Object> safeFailure(Throwable failure) {
        Map<String,Object> result=new LinkedHashMap<>(); result.put("code","UR06_JAVA_FAILURE");
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Throwable cause=failure; cause!=null && seen.add(cause); cause=cause.getCause()) {
            if(cause instanceof SQLException sql && sql.getSQLState()!=null && sql.getSQLState().matches("[A-Z0-9]{5}")) {result.put("sqlState",sql.getSQLState());result.put("sqlError",sql.getErrorCode());}
            if(cause instanceof BeanCreationException b && b.getBeanName()!=null && Set.of("sqlSessionFactory","runtimes","installations","agents","catalog","runtime","authentication","springSecurityFilterChain").contains(b.getBeanName())) result.put("bean",b.getBeanName());
            if(cause instanceof IllegalStateException && cause.getMessage()!=null
                && cause.getMessage().startsWith("PWA-HOSTED-P0 CHECK set drift:")) result.put("code","HOSTED_CHECK_SET_DRIFT");
            if(cause instanceof FixtureFailure f) result.put("code",f.code);
        }
        return result;
    }
    static final class FixtureFailure extends RuntimeException { final String code; FixtureFailure(String code) { super(code); this.code=code; } FixtureFailure(String code,Throwable cause) { super(code,cause);this.code=code; } }
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @EnableTransactionManagement(proxyTargetClass = true)
    @EnableWebSocket
    @Import(AgentRuntimeSecurityConfiguration.class)
    static class HttpConfiguration implements WebSocketConfigurer {
        @org.springframework.beans.factory.annotation.Autowired org.springframework.context.ApplicationContext beans;
        @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
            try {
                // Actual native handler; unrelated CHAT/skill/Provider beans intentionally not assembled.
                nativeHandler = new AgentWebSocketHandler(null, beans.getBeanProvider(AgentService.class),
                    beans.getBean(ChatMessageDao.class),
                    new ChatConversationEventBroker());
                nativeHandler.setRuntimeAuthentication(beans.getBean(AgentRuntimeAuthenticationService.class));
                registry.addHandler(nativeHandler, "/ws/agent/channel")
                    .addInterceptors(new AgentRuntimeHandshakeInterceptor(beans.getBean(AgentRuntimeAuthenticationService.class)));
            } catch (Exception failure) { throw new FixtureFailure("NATIVE_HANDLER_ASSEMBLY_FAILED", failure); }
        }
        @Bean DataSource dataSource() { return activeDataSource; }
        @Bean DataSourceTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true); configuration.setLogImpl(NoLoggingImpl.class);
            for (Class<?> mapper : List.of(AgentRuntimeV1InstallationMapper.class, AgentRuntimeMapper.class,
                    AgentIdentityRegistryMapper.class, AgentIdentityAliasMapper.class, AgentPersonaBindingMapper.class,
                    AgentCommandRecoveryMapper.class, AgentPersonaMapper.class, AgentTaskMetaMapper.class,
                    AgentTaskMemberMapper.class, AgentTaskWorkItemMapper.class, AgentTaskRequestMapper.class,
                    AgentTaskArtifactMapper.class, AgentTaskEventMapper.class, AgentWorkItemReassignmentMapper.class,
                    AgentTaskNoteMapper.class, DialogueTemplateMapper.class, AgentCommandTransportMapper.class,
                    EconomyHostingRentMapper.class, EconomySkillApplicationMapper.class, ChatMessageMapper.class)) configuration.addMapper(mapper);
            GlobalConfig global = new GlobalConfig(); global.setBanner(false);
            global.setIdentifierGenerator(new DefaultIdentifierGenerator());
            MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
            bean.setDataSource(source); bean.setConfiguration(configuration); bean.setGlobalConfig(global);
            // Original production XML registers InfoMapper and its security statements, not test SQL.
            bean.setMapperLocations(new ClassPathResource("cn/jia/user/mapper/InfoMapper.xml"));
            SqlSessionFactory factory = Objects.requireNonNull(bean.getObject());
            require(factory.getConfiguration().hasStatement(InfoMapper.class.getName() + ".selectSecurityByExactJiacn"),
                    "UR06_PRODUCTION_ACCOUNT_XML_REQUIRED");
            return factory;
        }
        @Bean SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }
        // BaseDaoImpl declares inherited @Inject baseMapper: registering it only in MyBatis
        // is insufficient for Spring. Expose exact real template proxies, with no mapper scan
        // or reflective field assignment. DAO and catalog injection remain normal Spring work.
        @Bean AgentRuntimeV1InstallationMapper agentRuntimeV1InstallationMapper(SqlSessionTemplate session) { return session.getMapper(AgentRuntimeV1InstallationMapper.class); }
        @Bean AgentRuntimeMapper agentRuntimeMapper(SqlSessionTemplate session) { return session.getMapper(AgentRuntimeMapper.class); }
        @Bean AgentIdentityRegistryMapper agentIdentityRegistryMapper(SqlSessionTemplate session) { return session.getMapper(AgentIdentityRegistryMapper.class); }
        @Bean AgentIdentityAliasMapper agentIdentityAliasMapper(SqlSessionTemplate session) { return session.getMapper(AgentIdentityAliasMapper.class); }
        @Bean AgentPersonaBindingMapper agentPersonaBindingMapper(SqlSessionTemplate session) { return session.getMapper(AgentPersonaBindingMapper.class); }
        @Bean InfoMapper infoMapper(SqlSessionTemplate session) { return session.getMapper(InfoMapper.class); }
        @Bean AgentPersonaMapper agentPersonaMapper(SqlSessionTemplate session) { return session.getMapper(AgentPersonaMapper.class); }
        @Bean AgentTaskMetaMapper agentTaskMetaMapper(SqlSessionTemplate session) { return session.getMapper(AgentTaskMetaMapper.class); }
        @Bean AgentTaskEventMapper agentTaskEventMapper(SqlSessionTemplate session) { return session.getMapper(AgentTaskEventMapper.class); }
        @Bean AgentTaskNoteMapper agentTaskNoteMapper(SqlSessionTemplate session) { return session.getMapper(AgentTaskNoteMapper.class); }
        @Bean DialogueTemplateMapper dialogueTemplateMapper(SqlSessionTemplate session) { return session.getMapper(DialogueTemplateMapper.class); }
        @Bean ChatMessageMapper chatMessageMapper(SqlSessionTemplate session) { return session.getMapper(ChatMessageMapper.class); }
        @Bean ChatMessageDao chatMessages() { return new ChatMessageDaoImpl(); }
        @Bean AgentRuntimeV1InstallationDao installations() { return new AgentRuntimeV1InstallationDaoImpl(); }
        @Bean AgentRuntimeDao runtimes() { return new AgentRuntimeDaoImpl(); }
        @Bean AgentIdentityRegistryDao registry() { return new AgentIdentityRegistryDaoImpl(); }
        @Bean AgentIdentityAliasDao aliases() { return new AgentIdentityAliasDaoImpl(); }
        @Bean AgentPersonaBindingDao bindings() { return new AgentPersonaBindingDaoImpl(); }
        @Bean UserInfoDao users() { return new UserInfoDaoImpl(); }
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
        @Bean AgentRabbitSafetyGate ackGate() {
            return new AgentRabbitSafetyGate(new AgentRabbitSafetyProperties(
                    new AgentRabbitSafetyProperties.CommandOutbox(true), new AgentRabbitSafetyProperties.RabbitTopology(true),
                    new AgentRabbitSafetyProperties.RabbitPublish(true), new AgentRabbitSafetyProperties.RabbitConsume(true),
                    new AgentRabbitSafetyProperties.RabbitDispatch(true),
                    new AgentRabbitSafetyProperties.RabbitBroker("isolated.invalid", 35672, "synthetic", "synthetic", "/ur06")),
                    new AgentRabbitDispatchScopeProperties(List.of(new AgentRabbitDispatchScopeProperties.AllowedScope(TENANT, CLIENT))));
        }
        @Bean AgentCommandAckService commandAcks(SqlSessionTemplate session, AgentRabbitSafetyGate gate, DataSourceTransactionManager manager) {
            return new AgentCommandAckServiceImpl(
                    new AgentCommandRecoveryDaoImpl(session.getMapper(AgentCommandRecoveryMapper.class)), gate, manager);
        }
        @Bean AgentRuntimeV1Service runtime(AgentRuntimeV1InstallationDao installations, AgentIdentityService identity,
                AgentIdentityRegistryDao registry, ObjectProvider<AgentCommandAckService> acks, AgentRuntimeAuthenticationService authentication) {
            return new AgentRuntimeV1ServiceImpl(installations, identity, registry, acks, authentication);
        }
        @Bean AgentRuntimeV1Controller controller(AgentRuntimeV1Service runtime) { return new AgentRuntimeV1Controller(runtime); }
        @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
        @Bean AgentPersonaCatalogCache catalog() { return new AgentPersonaCatalogCache(); }
        @Bean AgentPersonaDao personas() { return new AgentPersonaDaoImpl(); }
        @Bean AgentTaskMetaDao meta() { return new AgentTaskMetaDaoImpl(); }
        @Bean AgentTaskMemberDao members(SqlSessionTemplate s) { return new AgentTaskMemberDaoImpl(s.getMapper(AgentTaskMemberMapper.class)); }
        @Bean AgentTaskWorkItemDao workItems(SqlSessionTemplate s) { return new AgentTaskWorkItemDaoImpl(s.getMapper(AgentTaskWorkItemMapper.class)); }
        @Bean AgentTaskRequestDao requests(SqlSessionTemplate s) { return new AgentTaskRequestDaoImpl(s.getMapper(AgentTaskRequestMapper.class)); }
        @Bean AgentTaskArtifactDao artifacts(SqlSessionTemplate s) { return new AgentTaskArtifactDaoImpl(s.getMapper(AgentTaskArtifactMapper.class)); }
        @Bean AgentTaskEventDao events() { return new AgentTaskEventDaoImpl(); }
        @Bean AgentTaskNoteDao notes() { return new AgentTaskNoteDaoImpl(); }
        @Bean DialogueTemplateDao dialogues() { return new DialogueTemplateDaoImpl(); }
        @Bean AgentTaskMutationTransaction mutation(AgentTaskMetaDao meta, DataSourceTransactionManager manager) { return new AgentTaskMutationTransactionImpl(meta, manager); }
        @Bean AgentTaskEventBroker broker() { return new AgentTaskEventBroker(); }
        @Bean AgentTaskEventAfterCommitPublisher afterCommit(AgentTaskEventBroker broker, DataSourceTransactionManager manager) { return new AgentTaskEventAfterCommitPublisher(broker, manager); }
        @Bean AgentTaskEventWriter writer(AgentTaskEventDao dao, DataSourceTransactionManager manager, AgentTaskEventAfterCommitPublisher after) { return new AgentTaskEventWriterImpl(dao, manager, after); }
        @Bean AgentTaskAggregationService aggregation(AgentTaskMetaDao meta, AgentIdentityService identity, AgentTaskMutationTransaction mutation, AgentTaskEventWriter writer) {
            return new AgentTaskAggregationServiceImpl(meta, identity, mutation, writer);
        }
        @Bean AgentLegacyTaskCompatibilityService legacy(AgentTaskMetaDao meta, AgentTaskMemberDao members, AgentTaskWorkItemDao items,
                AgentTaskAggregationService aggregation, AgentIdentityService identity, AgentTaskMutationTransaction mutation, AgentTaskEventWriter writer) {
            return new AgentLegacyTaskCompatibilityService(meta, members, items, aggregation, identity, mutation, writer);
        }
        @Bean AgentTaskRequirementSnapshotService requirements(JdbcTemplate jdbc, AgentTaskMutationTransaction mutation) { return new AgentTaskRequirementSnapshotServiceImpl(jdbc, mutation); }
        @Bean AgentHostingWorkAdmission hosting(SqlSessionTemplate s) { return new HostingRentWorkAdmission(s.getMapper(EconomyHostingRentMapper.class), false); }
        @Bean HostingRentOwnerResolver hostingOwners(ObjectProvider<AccountSecurityService> accounts, ObjectProvider<UserService> users) { return new HostingRentOwnerResolver(accounts, users); }
        @Bean SkillAgentVersions skillVersions(SqlSessionTemplate s, AgentRuntimeDao runtimes, ObjectProvider<AgentService> agents,
                HostingRentOwnerResolver owners, DataSourceTransactionManager manager) {
            return new SkillAgentVersions(s.getMapper(EconomySkillApplicationMapper.class), runtimes, agents, owners, manager, false);
        }
        @Bean AgentService agents(AgentRuntimeDao runtimes, AgentIdentityService identity, AgentPersonaDao personas, AgentPersonaBindingDao bindings,
                AgentTaskMetaDao meta, AgentTaskMemberDao members, AgentLegacyTaskCompatibilityService legacy, AgentTaskNoteDao notes, DialogueTemplateDao dialogues,
                ObjectProvider<AgentEventPublisher> publisher, ObjectProvider<TaskService> tasks, ObjectProvider<ApiKeyService> keys, ObjectProvider<AgentSceneService> scene,
                AgentTaskMutationTransaction mutation, AgentTaskEventWriter writer) {
            return new AgentServiceImpl(runtimes, identity, personas, bindings, meta, members, legacy, notes, dialogues, publisher, tasks, keys, scene,
                new AgentScopePublicationCoordinator(), new AgentSceneFeatureFlags(false, false), mutation, writer);
        }
        @Bean AgentTaskCollaborationAccessService access(AgentTaskMetaDao meta, AgentTaskMemberDao members) { return new AgentTaskCollaborationAccessServiceImpl(meta, members); }
        @Bean AgentCommandTransportWriter transportWriter(SqlSessionTemplate s, AgentRabbitSafetyGate gate, AgentService agents,
                AgentTaskCollaborationAccessService access, DataSourceTransactionManager manager) {
            return new AgentCommandTransportWriterImpl(new AgentCommandTransportDaoImpl(s.getMapper(AgentCommandTransportMapper.class)), gate, agents, access, manager);
        }
        @Bean AgentWorkItemLeaseService leases(AgentTaskMemberDao members, AgentTaskWorkItemDao items, AgentTaskMutationTransaction mutation, AgentTaskEventWriter writer) {
            return new AgentWorkItemLeaseServiceImpl(members, items, mutation, writer, 900_000);
        }
        @Bean AgentWorkItemReassignmentService reassignments(SqlSessionTemplate s, AgentTaskMemberDao members, AgentTaskWorkItemDao items,
                AgentTaskMutationTransaction mutation, AgentIdentityService identity, AgentService agents, AgentTaskEventWriter writer,
                ObjectProvider<AgentCommandTransportWriter> commands, AgentWorkItemLeaseService leases) {
            return new AgentWorkItemReassignmentServiceImpl(new AgentWorkItemReassignmentDaoImpl(s.getMapper(AgentWorkItemReassignmentMapper.class)),
                members, items, mutation, identity, agents, writer, commands, leases, 900_000);
        }
        @Bean AgentTaskArtifactStorage storage() { return new FileSystemAgentTaskArtifactStorage(activeRoot.resolve("artifact-storage"), 262144, Set.of("text/plain")); }
        @Bean AgentTaskArtifactService publications(AgentTaskMetaDao meta, AgentTaskMemberDao members, AgentTaskWorkItemDao items, AgentTaskRequestDao requests,
                AgentTaskArtifactDao artifacts, AgentTaskArtifactStorage storage, AgentTaskMutationTransaction mutation, AgentTaskEventWriter writer) {
            return new AgentTaskCollaborationServiceImpl(meta, members, items, requests, artifacts, storage, mutation, writer);
        }
        @Bean AgentWorkItemResultCommitService results(AgentWorkItemLeaseService leases, AgentTaskArtifactService artifacts, AgentTaskWorkItemDao items,
                AgentTaskMutationTransaction mutation, AgentTaskEventWriter writer, AgentWorkItemReassignmentService reassignments, AgentTaskEventDao events) {
            var result = new AgentWorkItemResultCommitServiceImpl(leases, artifacts, items, mutation, writer);
            result.setRuntimeResultRecovery(reassignments, events); return result;
        }
        @Bean AgentWorkItemReassignmentController leaseController(AgentWorkItemReassignmentService service, AgentRuntimeAuthenticationService auth) { return new AgentWorkItemReassignmentController(service, auth); }
        @Bean AgentWorkItemRuntimeResultController resultController(AgentWorkItemResultCommitService service, AgentWorkItemReassignmentService reassignments,
                AgentRuntimeAuthenticationService auth) { return new AgentWorkItemRuntimeResultController(service, reassignments, auth); }
        @Bean SensitiveResponseProperties sensitiveProperties() { return new SensitiveResponseProperties(); }
        @Bean SensitiveResponseBodyAdvice sensitiveAdvice(SensitiveResponseProperties properties) { return new SensitiveResponseBodyAdvice(properties); }
    }
}
