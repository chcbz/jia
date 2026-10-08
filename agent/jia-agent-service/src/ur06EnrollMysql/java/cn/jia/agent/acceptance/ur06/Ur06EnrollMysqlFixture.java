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
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.aop.framework.ProxyFactory;
import org.aopalliance.intercept.MethodInterceptor;
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
import java.sql.BatchUpdateException;

/** Fixture-only original Spring/controller/DAO/native WS assembly on private MySQL.
 * No application auto-configuration, alternate auth or fake DAO. Foundation
 * installations use real create/enroll; M4 uses explicit synthetic ACTIVE history.
 * Child stdout is a parent-owned private pipe, NOT an artifact/report/log destination. */
public final class Ur06EnrollMysqlFixture {
    static final String TENANT = "0", CLIENT = "ur06-client", OWNER = "ur06-owner", PREFIX = "UR06_PIPE ";
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String[] AGENTS = java.util.stream.IntStream.range(1, 9).mapToObj(i -> "agt_" + Integer.toHexString(i).repeat(32)).toArray(String[]::new);
    static final String[] INSTALLATIONS = java.util.stream.IntStream.range(1, 9).mapToObj(i -> "rti_" + Integer.toHexString(i).repeat(32)).toArray(String[]::new);
    private static String purpose = "FOUNDATION", fault = "NONE";
    private static final long LEASE_MILLIS = 900_000;
    private static final CountDownLatch preparedRelease = new CountDownLatch(1), responseRelease = new CountDownLatch(1);
    private static final AtomicBoolean faultUsed = new AtomicBoolean();
    private static final AtomicLong ACK_UPDATES = new AtomicLong(), WORK_UPDATES = new AtomicLong(), ARTIFACT_INSERTS = new AtomicLong(), SUBMITTED_ATTEMPTS = new AtomicLong();
    private static boolean m4() { return "M4".equals(purpose); }
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
            purpose = text(init, "purpose"); require(Set.of("FOUNDATION", "M4").contains(purpose), "PURPOSE_ALLOWLIST_REQUIRED");
            fault = text(init, "fault"); require(Set.of("NONE", "RESULT_LOSS", "ACK_LOSS", "PREPARED", "ROLLBACK").contains(fault)
                && (m4() || "NONE".equals(fault)), "FAULT_ALLOWLIST_REQUIRED");
            activeDataSource = new GuardedDataSource(DatabaseSpec.from(init.get("database")), m4());
            JdbcTemplate jdbc = new JdbcTemplate(activeDataSource);
            bootStage = "BOOT_SCHEMA";
            if (init.get("initialize").asBoolean()) {
                if (m4()) initializeM4(jdbc, Path.of(text(init, "apiRoot")), init);
                else initialize(jdbc, Path.of(text(init, "apiRoot")));
            }
            else require(jdbc.queryForObject("SELECT COUNT(*) FROM agent_runtime WHERE agent_id='ur06-legacy'", Integer.class) == 1,
                    "NO_RESEED_RESTART_REQUIRED");
            if (m4()) require(jdbc.queryForObject("SELECT COUNT(*) FROM agent_runtime_v1_installation", Integer.class) == 3, "RESTART_RESEED_FORBIDDEN");
            bootStage = "BOOT_HTTP";
            try (HttpFixture http = new HttpFixture(activeRoot)) {
                http.start(); emit(Map.of("stage", "JAVA_READY", "pid", ProcessHandle.current().pid(), "port", http.port(), "commands", m4() ? commands(jdbc) : List.of()));
                String line;
                while ((line = in.readLine()) != null) {
                    JsonNode r = JSON.readTree(line); String op = text(r, "op");
                    if (op.equals("STOP")) { heldResponse.countDown(); responseRelease.countDown(); preparedRelease.countDown(); http.close(); emit(Map.of("stage", "STOPPED")); return; }
                    if (m4()) { handleM4(jdbc, r); continue; }
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
    private static void initializeSchema(JdbcTemplate jdbc, Path api) throws Exception {
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
    }
    private static void initialize(JdbcTemplate jdbc, Path api) throws Exception {
        initializeSchema(jdbc, api);
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
    /** Full original E05 SQL and owner Phase-A run on ONE verified JDBC session.
     * Synthetic historical receipts are NOT enrollment/Rabbit/JWT reassignment proof. */
    private static void initializeM4(JdbcTemplate jdbc, Path api, JsonNode init) throws Exception {
        initializeSchema(jdbc, api);
        try (Connection c = activeDataSource.getConnection()) {
            for (String name : List.of("agent-work-item-reassignment-e05.sql", "single-tenant-task-owner-ddl.sql")) {
                Path resource = api.resolve("agent/jia-agent-mapper/src/main/resources/db/" + name);
                require(resource.toRealPath().equals(resource), "DDL_ALIAS_FORBIDDEN");
                new ResourceDatabasePopulator(new FileSystemResource(resource)).populate(c);
            }
        }
        seedM4(jdbc, init);
        if ("ROLLBACK".equals(fault)) jdbc.execute("CREATE TRIGGER ur06_m4_submit_rollback BEFORE INSERT ON agent_task_event FOR EACH ROW BEGIN IF NEW.event_type='WORK_ITEM_SUBMITTED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='UR06_SYNTHETIC_SUBMITTED_SQL_FAILURE'; END IF; END");
    }
    private static void handleM4(JdbcTemplate jdbc, JsonNode request) {
        String op = text(request, "op");
        if ("SNAPSHOT".equals(op)) { emit(snapshotM4(jdbc)); return; }
        if ("RELEASE_PREPARED".equals(op)) { preparedRelease.countDown(); emit(Map.of("stage", "PREPARED_RELEASED")); return; }
        int index = Math.toIntExact(integer(request, "agentIndex")); require(index >= 0 && index < 3, "EXACT_AGENT_REQUIRED");
        if ("SEED_WORK".equals(op)) {
            require(jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_meta WHERE task_id=?", Integer.class, "ur04-task-" + index) == 0, "WORK_RESEED_FORBIDDEN");
            new TransactionTemplate(new DataSourceTransactionManager(activeDataSource)).executeWithoutResult(tx -> seedWork(jdbc, index, System.currentTimeMillis()));
            emit(Map.of("stage", "WORK_SEEDED", "commands", commands(jdbc)));
        } else if ("DISPATCH".equals(op) || "ALTERED_DISPATCH".equals(op)) {
            byte[] wire = jdbc.queryForObject("SELECT wire_payload FROM agent_outbox_event WHERE id=?", byte[].class, index * 2 + 2);
            if ("ALTERED_DISPATCH".equals(op)) {
                String raw = new String(wire, StandardCharsets.UTF_8); require(raw.contains("UR04 synthetic original execution"), "ALTERED_FRAME_ANCHOR_REQUIRED");
                wire = raw.replace("UR04 synthetic original execution", "UR04 conflicting original execution").getBytes(StandardCharsets.UTF_8);
            }
            var delivered = nativeHandler.dispatchExactRawCommand(TENANT, CLIENT, "ur04-task-" + index, AGENTS[index], wire);
            emit(Map.of("stage", "DISPATCHED", "sent", delivered.status() == AgentRawCommandDispatchResult.Status.SENT, "agentIndex", index));
        } else if ("MUTATE".equals(op)) { mutate(jdbc, request); emit(Map.of("stage", "MUTATED")); }
        else throw new FixtureFailure("OPERATION_ALLOWLIST_REQUIRED");
    }
    private static void seedM4(JdbcTemplate jdbc, JsonNode init) {
        long now = System.currentTimeMillis(); JsonNode manifests = init.get("manifests"), authorizations = init.get("authorizations");
        require(manifests != null && manifests.isArray() && manifests.size() == 3 && authorizations != null && authorizations.isArray() && authorizations.size() == 3, "THREE_SYNTHETIC_INSTALLATIONS_REQUIRED");
        new TransactionTemplate(new DataSourceTransactionManager(activeDataSource)).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO user_info(id,jiacn,account_state,auth_epoch,client_id,tenant_id) VALUES(700,?,'ACTIVE',2,?,'0')", OWNER, CLIENT);
            for (int index = 0; index < 3; index++) {
                JsonNode m = manifests.get(index); String authorization = authorizations.get(index).isTextual() ? authorizations.get(index).asText() : "";
                require(authorization.matches("rta1_[0-9a-f]{64}") && INSTALLATIONS[index].equals(text(m, "installationId"))
                    && AGENTS[index].equals(text(m, "canonicalAgentId")) && TENANT.equals(text(m, "tenantId")) && CLIENT.equals(text(m, "clientId")), "SYNTHETIC_SUBJECT_MISMATCH");
                String manifestHash = text(m, "manifestSha256"); require(manifestHash.matches("sha256:[0-9a-f]{64}"), "SEALED_MANIFEST_REQUIRED");
                long id = 1000 + index;
                jdbc.update("INSERT INTO agent_persona(id,persona_code,name,rank_no,abilities,active,system_agent,tenant_id,client_id) VALUES(?,?,?,?,?,1,0,?,?)",
                    id, "ur04-persona-" + index, "UR04 persona " + index, 200 + index, "[]", TENANT, CLIENT);
                jdbc.update("INSERT INTO agent_persona_binding(id,jiacn,persona_code,agent_id,bound_at,status,tenant_id,client_id,create_time,update_time) VALUES(?,?,?,?,?,1,?,?,?,?)",
                    id, OWNER, "ur04-persona-" + index, AGENTS[index], now, TENANT, CLIENT, now, now);
                jdbc.update("INSERT INTO agent_identity_registry(id,canonical_agent_id,canonical_type,lifecycle_status,owner_jiacn,binding_id,provisioned_at,activated_at,audit_reason,tenant_id,client_id,create_time,update_time) VALUES(?,?,'OPAQUE','ACTIVE',?,?,?,?,'UR04_SYNTHETIC',?,?,?,?)",
                    id, AGENTS[index], OWNER, id, now, now, TENANT, CLIENT, now, now);
                jdbc.update("INSERT INTO agent_runtime_v1_installation(id,installation_id,canonical_agent_id,manifest_version,manifest_sha256,enrollment_secret_hash,enrollment_expires_at,enrollment_consumed_at,runtime_authorization_hash,runtime_authorization_issued_at,status,version,tenant_id,client_id,create_time,update_time) VALUES(?,?,?,'1',?,?,?,?,?,?,'ACTIVE',0,?,?,?,?)",
                    id, INSTALLATIONS[index], AGENTS[index], manifestHash.substring(7), sha256("synthetic-enrollment-unused"), now + 86400000, now, sha256(authorization), now, TENANT, CLIENT, now, now);

            }
        });
    }
    private static void seedWork(JdbcTemplate jdbc, int index, long now) {
        String task = "ur04-task-" + index, work = "ur04-work-" + index;
        String previous = AGENTS[(index + 1) % 3], coordinator = AGENTS[(index + 2) % 3], target = AGENTS[index];
        String reassignment = "rsn_" + hex(sha256(String.join("\0", "e05-reassignment-v1", TENANT, CLIENT, OWNER, task, work, "ur04-key-" + index)));
        String token = "ur04-business-" + UUID.randomUUID(), message = "00000000-0000-0000-0000-" + String.format(Locale.ROOT, "%012d", index + 1);
        String sourceIntent = "ur04-source-" + index, intent = "ur04-successor-" + index, event = "ur04-outbox-" + index;
        String source = AgentCommandCanonicalCodec.hallCommandId(TENANT, CLIENT, OWNER, task, previous, sourceIntent, "WORK_ITEM_EXECUTE");
        String command = AgentCommandCanonicalCodec.hallCommandId(TENANT, CLIENT, OWNER, task, target, intent, "WORK_ITEM_EXECUTE");
        jdbc.update("INSERT INTO agent_task_meta(task_id,owner_jiacn,reward_status,collaboration_mode,risk_level,max_agents,coordinator_agent_id,review_required,task_version,current_event_version,tenant_id,client_id,create_time,update_time) VALUES(?,?,'running','team','low',3,?,0,7,0,?,?,?,?)",
            task, OWNER, coordinator, TENANT, CLIENT, now, now);
        for (String agent : Arrays.copyOf(AGENTS, 3)) jdbc.update("INSERT INTO agent_task_member(task_id,owner_jiacn,agent_id,member_role,member_status,assignment_source,version,tenant_id,client_id,create_time,update_time) VALUES(?,?,?,?,'working','manual',0,?,?,?,?)",
            task, OWNER, agent, agent.equals(coordinator) ? "coordinator" : "worker", TENANT, CLIENT, now, now);
        jdbc.update("INSERT INTO agent_task_work_item(work_item_id,task_id,owner_jiacn,title,description,work_type,assignee_agent_id,status,lease_token,lease_until,attempt_count,max_attempts,version,tenant_id,client_id,create_time,update_time) VALUES(?,?,?,'UR04 synthetic work','UR04 synthetic original execution','coding',?,'claimed',?,?,2,3,5,?,?,?,?)",
            work, task, OWNER, target, token, now + LEASE_MILLIS, TENANT, CLIENT, now, now);
        AgentHallCommandPayload predecessorPayload = new AgentHallCommandPayload("work_item_execute", "UR04 synthetic predecessor", "juyiting", "manual", null,
            "ur04-source-event-" + index, "supervised", true, new AgentHallCommandContext(null, "UR04 synthetic work", null, null, "4", List.of(), List.of()));
        AgentCommandDraft predecessor = new AgentCommandDraft(1, source, task, "ur04-source-event-" + index, TENANT, CLIENT, OWNER, task, work, previous,
            "WORK_ITEM_EXECUTE", now, now + AgentCommandCanonicalCodec.HALL_COMMAND_TTL_MILLIS, sourceIntent, predecessorPayload);
        AgentHallCommandPayload payload = new AgentHallCommandPayload("work_item_execute", "UR04 synthetic original execution", "juyiting", "lease_expired_reassignment", null,
            event, "supervised", true, new AgentHallCommandContext(null, "UR04 synthetic work", null, null, "5", List.of(source), List.of("lease-expired", "reassignment"), "e05-reassignment-v1", reassignment));
        AgentCommandDraft successor = new AgentCommandDraft(1, command, task, event, TENANT, CLIENT, OWNER, task, work, target,
            "WORK_ITEM_EXECUTE", now, now + AgentCommandCanonicalCodec.HALL_COMMAND_TTL_MILLIS, intent, payload);
        seedTransport(jdbc, index * 2 + 1, predecessor, "source-" + message, "ur04-source-event-" + index, now);
        seedTransport(jdbc, index * 2 + 2, successor, message, event, now);
        jdbc.update("INSERT INTO agent_work_item_reassignment(reassignment_id,request_sha256,task_id,owner_jiacn,work_item_id,operator_subject,coordinator_agent_id,previous_agent_id,target_agent_id,source_command_id,command_id,message_id,outbox_event_id,expected_work_item_version,result_work_item_version,task_version,lease_fence_sha256,previous_lease_until,lease_until,attempt_count,max_attempts,tenant_id,client_id,create_time,update_time) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,4,5,7,?,?,?,2,3,?,?,?,?)",
            reassignment, hex(sha256("synthetic-historical-request-" + index)), task, OWNER, work, "ur04-operator", coordinator, previous, target, source, command, message, event,
            hex(sha256(token)), now - 1000, now + LEASE_MILLIS, TENANT, CLIENT, now, now);
    }
    private static void seedTransport(JdbcTemplate jdbc, long id, AgentCommandDraft draft, String message, String event, long now) {
        byte[] business = AgentCommandCanonicalCodec.businessBytes(draft), wire = AgentCommandCanonicalCodec.wireBytes(draft, message, 1);
        var route = AgentRabbitTopologyManifest.canonical().defaultCommandPublishRoute();
        jdbc.update("INSERT INTO agent_command_delivery(id,owner_jiacn,command_id,task_id,work_item_id,target_agent_id,command_type,command_payload,command_payload_hash,status,attempt_count,active_message_id,active_attempt,expires_at,version,tenant_id,client_id,create_time,update_time) VALUES(?,?,?,?,?,?,'WORK_ITEM_EXECUTE',?,?,'SENT',1,?,1,?,7,?,?,?,?)",
            id, OWNER, draft.commandId(), draft.taskId(), draft.workItemId(), draft.targetAgentId(), business, AgentCommandCanonicalCodec.sha256(business), message, draft.expiresAt(), TENANT, CLIENT, now, now);
        jdbc.update("INSERT INTO agent_outbox_event(id,event_id,message_id,command_id,delivery_id,aggregate_type,aggregate_id,destination,routing_key,wire_payload,wire_payload_hash,status,attempt_count,active_attempt,expires_at,publisher_confirm_status,confirmed_at,mandatory_return_status,published_at,version,tenant_id,client_id,create_time,update_time) VALUES(?,?,?,?,?,'task',?,?,?,?,?,'PUBLISHED',1,1,?,'ACK',?,'NOT_RETURNED',?,0,?,?,?,?)",
            id, event, message, draft.commandId(), id, draft.taskId(), route.destination(), route.routingKey(), wire, AgentCommandCanonicalCodec.sha256(wire), draft.expiresAt(), now, now, TENANT, CLIENT, now, now);
        jdbc.update("INSERT INTO agent_consumer_inbox(id,consumer_name,message_id,event_id,command_id,delivery_id,wire_payload,wire_payload_hash,status,result_status,attempt_count,active_attempt,expires_at,processed_at,version,tenant_id,client_id,create_time,update_time) VALUES(?,'agent-command-dispatch-v1',?,?,?,?,?,?,'PROCESSED','SENT',1,1,?,?,0,?,?,?,?)",
            id, message, event, draft.commandId(), id, wire, AgentCommandCanonicalCodec.sha256(wire), draft.expiresAt(), now, TENANT, CLIENT, now, now);
    }
    private static List<Map<String, Object>> commands(JdbcTemplate jdbc) {
        return jdbc.query("SELECT r.*,d.expires_at,d.target_agent_id FROM agent_work_item_reassignment r JOIN agent_command_delivery d ON r.command_id=d.command_id ORDER BY r.id", (rs, row) -> {
            Map<String, Object> command = new LinkedHashMap<>();
            for (String name : List.of("commandId", "messageId", "taskId", "workItemId", "reassignmentId", "sourceCommandId")) command.put(name, rs.getString(name.replaceAll("([A-Z])", "_$1").toLowerCase(Locale.ROOT)));
            command.put("agentIndex", Arrays.asList(AGENTS).indexOf(rs.getString("target_agent_id"))); command.put("targetAgentId", rs.getString("target_agent_id"));
            command.put("expiresAt", java.time.Instant.ofEpochMilli(rs.getLong("expires_at")).toString()); return command;
        });
    }
    private static void mutate(JdbcTemplate jdbc, JsonNode request) {
        int index = Math.toIntExact(integer(request, "agentIndex")); require(index >= 0 && index < 3, "EXACT_AGENT_REQUIRED");
        String task = "ur04-task-" + index; String mutation = text(request, "mutation");
        switch (mutation) {
            case "SUCCESSOR_HASH" -> jdbc.update("UPDATE agent_command_delivery SET command_payload_hash=? WHERE id=?", new byte[32], index * 2 + 2);
            case "PREDECESSOR_HASH" -> jdbc.update("UPDATE agent_command_delivery SET command_payload_hash=? WHERE id=?", new byte[32], index * 2 + 1);
            case "SOURCE_TARGET" -> jdbc.update("UPDATE agent_command_delivery SET target_agent_id=? WHERE id=?", AGENTS[(index + 1) % 3], index * 2 + 2);
            case "PROOF" -> jdbc.update("UPDATE agent_task_event SET event_json='{}' WHERE task_id=? AND event_type='WORK_ITEM_SUBMITTED'", task);
            case "MATERIAL" -> jdbc.update("UPDATE agent_task_artifact SET content='tampered synthetic material' WHERE task_id=?", task);
            case "REVOKE" -> jdbc.update("UPDATE agent_runtime_v1_installation SET status='REVOKED',version=version+1 WHERE installation_id=?", INSTALLATIONS[index]);
            default -> throw new FixtureFailure("MUTATION_ALLOWLIST_REQUIRED");
        }
    }
    static Map<String, Object> snapshotM4(JdbcTemplate jdbc) {
        Map<String, Object> out = new LinkedHashMap<>(); out.put("stage", "SNAPSHOT");
        out.put("ackUpdates", ACK_UPDATES.get()); out.put("workUpdates", WORK_UPDATES.get()); out.put("artifactInserts", ARTIFACT_INSERTS.get()); out.put("submittedAttempts", SUBMITTED_ATTEMPTS.get());
        out.put("http", List.copyOf(HTTP));
        out.put("runtimes", jdbc.query("SELECT agent_id,runtime_session_generation,runtime_instance_id,status FROM agent_runtime WHERE agent_id IN (?, ?, ?) ORDER BY agent_id", (rs, row) -> Map.of("generation", rs.getLong(2), "boot", rs.getString(3), "status", rs.getString(4)), AGENTS[0], AGENTS[1], AGENTS[2]));
        out.put("work", jdbc.query("SELECT status,version,result_artifact_id,lease_token,lease_until FROM agent_task_work_item ORDER BY id", (rs, row) -> Map.of("status", rs.getString(1), "version", rs.getLong(2), "hasResult", rs.getString(3) != null, "leaseCleared", rs.getString(4) == null && rs.getObject(5) == null)));
        out.put("deliveries", jdbc.query("SELECT status,version FROM agent_command_delivery WHERE MOD(id,2)=0 ORDER BY id", (rs, row) -> Map.of("status", rs.getString(1), "version", rs.getLong(2))));
        out.put("artifacts", jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_artifact", Integer.class));
        out.put("submittedEvents", jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_event WHERE event_type='WORK_ITEM_SUBMITTED'", Integer.class));
        out.put("events", jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_event", Integer.class));
        out.put("eventVersion", jdbc.queryForObject("SELECT COALESCE(SUM(current_event_version),0) FROM agent_task_meta", Long.class));
        out.put("businessSha256", businessFingerprint(jdbc)); return out;
    }
    static String businessFingerprint(JdbcTemplate jdbc) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (String table : List.of("agent_task_meta", "agent_task_member", "agent_task_work_item", "agent_task_artifact", "agent_task_event", "agent_work_item_reassignment"))
                jdbc.query("SELECT * FROM " + table + " ORDER BY id", (RowCallbackHandler) rs -> fingerprintRow(hash, rs));
            return hex(hash.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new FixtureFailure("DIGEST_UNAVAILABLE"); }
    }
    private static void fingerprintRow(MessageDigest hash, ResultSet rs) throws SQLException {
        for (int column = 1; column <= rs.getMetaData().getColumnCount(); column++) {
            Object value = rs.getObject(column); byte[] bytes;
            if (value == null) bytes = new byte[]{0}; else if (value instanceof byte[] binary) bytes = binary;
            else if (value instanceof java.sql.Blob blob) bytes = blob.getBytes(1, Math.toIntExact(blob.length()));
            else if (value instanceof java.sql.Clob clob) bytes = clob.getSubString(1, Math.toIntExact(clob.length())).getBytes(StandardCharsets.UTF_8);
            else bytes = value.toString().getBytes(StandardCharsets.UTF_8);
            hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); hash.update(bytes);
        }
    }
    static String optionalText(JsonNode value, String key) { JsonNode field = value == null ? null : value.get(key); return field != null && field.isTextual() ? field.asText() : ""; }
    static String hex(byte[] value) { return HexFormat.of().formatHex(value); }
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
    private static final class M4ResponseCut implements Filter {
        @Override public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
            HttpServletRequest raw = (HttpServletRequest) req;
            if (raw.getRequestURI().equals("/ws/agent/channel")) { chain.doFilter(req, res); return; }
            // Spring 7's explicit cacheLimit=0 keeps all consumed synthetic request bytes:
            // observation only, no size rejection/truncation or eager body read.
            ContentCachingRequestWrapper request = new ContentCachingRequestWrapper(raw, 0);
            ContentCachingResponseWrapper response = new ContentCachingResponseWrapper((HttpServletResponse) res);
            String path = request.getRequestURI(), category = category(path), before = "";
            if ("GET".equals(request.getMethod()) && Set.of("RESULT", "LEASE").contains(category)) before = businessFingerprint(new JdbcTemplate(activeDataSource));
            try {
                chain.doFilter(request, response);
                byte[] bytes = response.getContentAsByteArray();
                // Background original pollers may encounter an unassembled unrelated 404 HTML route.
                // Optional observation must not turn that real status into a synthetic JSON failure.
                String contentType = response.getContentType();
                JsonNode body = bytes.length > 0 && contentType != null && contentType.startsWith("application/json")
                        ? JSON.readTree(bytes) : JSON.createObjectNode();
                if (body == null || !body.isObject()) body = JSON.createObjectNode();
                JsonNode data = body.get("data"); if (data == null || !data.isObject()) data = body;
                Map<String, Object> receipt = new LinkedHashMap<>(); receipt.put("category", category); receipt.put("method", request.getMethod()); receipt.put("status", response.getStatus());
                int index = commandIndex(path);
                JsonNode requestBody = request.getContentAsByteArray().length > 0
                        ? JSON.readTree(request.getContentAsByteArray()) : JSON.createObjectNode();
                if (requestBody == null || !requestBody.isObject()) requestBody = JSON.createObjectNode();
                if (category.equals("ACK")) index = Arrays.asList(AGENTS).indexOf(optionalText(requestBody, "canonicalAgentId"));
                receipt.put("index", index);
                if (Set.of("LEASE", "START", "HEARTBEAT").contains(category) && request.getMethod().equals("POST")) {
                    receipt.put("actorMatches", index >= 0 && AGENTS[index].equals(request.getParameter("actorAgentId"))
                            && request.getParameterValues("actorAgentId").length == 1);
                }
                JsonNode expected = requestBody.get("expectedWorkItemVersion");
                if (expected != null && expected.isIntegralNumber() && expected.canConvertToLong()) receipt.put("expectedVersion", expected.longValue());
                JsonNode confirmed = requestBody.get("deliveryVersion");
                if (category.equals("ACK")) {
                    receipt.put("firstAck", confirmed != null && confirmed.isNull());
                    if (confirmed != null && confirmed.isIntegralNumber() && confirmed.canConvertToLong()) receipt.put("lastConfirmedVersion", confirmed.longValue());
                }
                if (data.get("deliveryVersion") != null && data.get("deliveryVersion").isIntegralNumber()) receipt.put("version", data.get("deliveryVersion").longValue());
                if (data.get("workItemVersion") != null && data.get("workItemVersion").isIntegralNumber()) receipt.put("workVersion", data.get("workItemVersion").longValue());
                for (String key : List.of("kind", "status")) {
                    String v = optionalText(data, key);
                    if (Set.of("ADVANCED", "PRIOR", "RECEIVED", "STARTED", "SUCCEEDED", "REJECTED", "FAILED", "submitted", "claimed", "running").contains(v)) receipt.put(key.equals("status") ? "businessStatus" : key, v);
                }
                if (!before.isEmpty()) receipt.put("readOnly", before.equals(businessFingerprint(new JdbcTemplate(activeDataSource))));
                HTTP.add(receipt); emit(Map.of("stage", "HTTP_OBSERVED", "receipt", receipt));
                boolean resultCut = "RESULT_LOSS".equals(fault) && category.equals("RESULT") && request.getMethod().equals("POST") && response.getStatus() == 200;
                boolean ackCut = "ACK_LOSS".equals(fault) && category.equals("ACK") && response.getStatus() == 200 && "SUCCEEDED".equals(optionalText(data, "status"));
                if ((resultCut || ackCut) && faultUsed.compareAndSet(false, true)) {
                    // Actual DB state verifies transaction commit before holding the response.
                    Map<String, Object> snapshot = snapshotM4(new JdbcTemplate(activeDataSource));
                    require(((Number) snapshot.get("submittedEvents")).longValue() == 1, "CUT_REQUIRES_REAL_SUBMITTED_COMMIT");
                    if (ackCut) require("SUCCEEDED".equals(new JdbcTemplate(activeDataSource).queryForObject("SELECT status FROM agent_command_delivery WHERE id=2", String.class)), "CUT_REQUIRES_REAL_D06_COMMIT");
                    emit(Map.of("stage", resultCut ? "RESULT_COMMIT_HELD" : "TERMINAL_ACK_HELD", "snapshot", snapshot));
                    responseRelease.await(); // parent destroys only this owned JVM, making actual TCP response loss
                }
                response.copyBodyToResponse();
            } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new ServletException("UR04_PRIVATE_CUT_INTERRUPTED"); }
        }
        private static int commandIndex(String path) { for (int index = 0; index < 3; index++) if (path.contains("ur04-task-" + index)) return index; return -1; }
        private static String category(String path) {
            if (path.endsWith("/result-commit")) return "RESULT";
            if (path.endsWith("/lease/heartbeat")) return "HEARTBEAT";
            if (path.endsWith("/lease/start")) return "START";
            if (path.endsWith("/lease")) return "LEASE";
            if (path.endsWith("/acks")) return "ACK";
            if (path.endsWith("/session")) return "SESSION";
            if (path.endsWith("/heartbeat")) return "PRESENCE";
            return "OTHER";
        }
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
            addFilter(context,"response-cut", m4() ? new M4ResponseCut() : new ResponseCut()); addFilter(context,"runtime-security",new DelegatingFilterProxy("springSecurityFilterChain",spring));
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
        final DatabaseSpec spec; final boolean observe;
        GuardedDataSource(DatabaseSpec spec) { this(spec, false); }
        GuardedDataSource(DatabaseSpec spec, boolean observe) { this.spec=spec; this.observe=observe; }
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
                            String preparedSql = a != null && a.length > 0 && a[0] instanceof String sql ? sql : "";
                            Map<Integer, Object> parameters = new HashMap<>();
                            List<SqlObservation> batches = new ArrayList<>();
                            return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(sp,sm,sa)-> {
                                try {
                                    String name = sm.getName();
                                    if (name.startsWith("execute") || name.equals("addBatch")) spec.verify(raw);
                                    if (name.equals("unwrap")) { require(sa != null && sa.length == 1 && sa[0] == type,"JDBC_UNWRAP_FORBIDDEN"); return sp; }
                                    if (name.equals("getConnection")) return p;
                                    if (name.startsWith("set") && sa != null && sa.length >= 2 && sa[0] instanceof Integer key)
                                        parameters.put(key, name.equals("setNull") ? null : sa[1]);
                                    if (name.equals("clearParameters")) parameters.clear();
                                    if (name.equals("clearBatch")) batches.clear();
                                    String sql = sa != null && sa.length > 0 && sa[0] instanceof String text ? text : preparedSql;
                                    SqlObservation observation = SqlObservation.of(sql, parameters);
                                    if (name.equals("addBatch")) {
                                        Object added = sm.invoke(statement, sa); batches.add(observation); return added;
                                    }
                                    boolean batch = name.equals("executeBatch") || name.equals("executeLargeBatch");
                                    boolean execution = name.startsWith("execute");
                                    if (observe && execution && !batch) observation.before();
                                    Object executed;
                                    try { executed = sm.invoke(statement,sa); }
                                    catch (InvocationTargetException failure) {
                                        if (observe && batch && failure.getCause() instanceof BatchUpdateException partial)
                                            observeBatch(batches, partial.getLargeUpdateCounts());
                                        throw failure;
                                    }
                                    if (observe && execution) {
                                        if (batch) {
                                            int length = java.lang.reflect.Array.getLength(executed);
                                            long[] rows = new long[length];
                                            for (int i=0;i<length;i++) rows[i]=((Number)java.lang.reflect.Array.get(executed,i)).longValue();
                                            observeBatch(batches, rows);
                                        } else {
                                            long count = executed instanceof Number number ? number.longValue()
                                                : executed instanceof Boolean && !((Boolean)executed) ? statement.getLargeUpdateCount() : -1;
                                            observation.after(count);
                                        }
                                    }
                                    if (batch) batches.clear();
                                    return executed;
                                } catch(InvocationTargetException e){throw e.getCause();}
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
    /** A transparent successful SQL attempt receipt, NOT a commit receipt. Durable
     * independent JDBC rows (and trigger rollback) remain the original oracle. */
    private static void observeBatch(List<SqlObservation> batch, long[] counts) {
        // Actual executeBatch receipts only; not addBatch and not unattempted tail
        // after a partial BatchUpdateException. No synthetic success on SQL failure.
        require(counts.length <= batch.size(), "SQL_BATCH_RECEIPT_REQUIRED");
        for (int i=0;i<counts.length;i++) { batch.get(i).before(); batch.get(i).after(counts[i]); }
    }
    private record SqlObservation(String kind, boolean submitted) {
        static SqlObservation of(String sql, Map<Integer,Object> values) {
            String normalized = sql.toLowerCase(Locale.ROOT).replace("`", "").replaceAll("\\s+", " ").trim();
            String kind = normalized.startsWith("insert into agent_task_artifact ") || normalized.startsWith("insert into agent_task_artifact(") ? "ARTIFACT"
                : normalized.startsWith("update agent_task_work_item ") ? "WORK"
                : normalized.startsWith("update agent_command_delivery ") && normalized.matches("(?s).*set status\\s*=.*") ? "ACK" : "OTHER";
            boolean event = normalized.startsWith("insert into agent_task_event ") || normalized.startsWith("insert into agent_task_event(");
            // Capture only the immutable event type, never SQL/params/material/token.
            return new SqlObservation(kind, event && values.values().stream().anyMatch("WORK_ITEM_SUBMITTED"::equals));
        }
        void before() { if (submitted) SUBMITTED_ATTEMPTS.incrementAndGet(); }
        void after(long rows) {
            // MyBatis simple updates provide exact row counts. SUCCESS_NO_INFO is a
            // genuine executeBatch success receipt, counted as one attempt, not rows.
            if (rows <= 0 && rows != Statement.SUCCESS_NO_INFO) return;
            switch(kind) {
                case "ARTIFACT" -> ARTIFACT_INSERTS.addAndGet(rows == Statement.SUCCESS_NO_INFO ? 1 : rows);
                case "WORK" -> WORK_UPDATES.addAndGet(rows == Statement.SUCCESS_NO_INFO ? 1 : rows);
                case "ACK" -> ACK_UPDATES.addAndGet(rows == Statement.SUCCESS_NO_INFO ? 1 : rows);
                default -> { }
            }
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
        @Bean static BeanPostProcessor preparedBoundary() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!"results".equals(name)) return bean;
                    ProxyFactory observer = new ProxyFactory(bean); observer.setProxyTargetClass(true);
                    observer.addAdvice((MethodInterceptor) call -> {
                        Object prepared = call.proceed(); // real opaque prepare and its NEVER advice complete first
                        if ("prepareRuntimeResult".equals(call.getMethod().getName()) && m4() && "PREPARED".equals(fault)) {
                            require(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(), "PREPARE_OUTER_TRANSACTION_FORBIDDEN");
                            emit(Map.of("stage", "PREPARED_HELD")); preparedRelease.await();
                        }
                        return prepared; // unchanged production object, no fake receipt
                    }); return observer.getProxy();
                }
            };
        }
        @Bean SensitiveResponseProperties sensitiveProperties() { return new SensitiveResponseProperties(); }
        @Bean SensitiveResponseBodyAdvice sensitiveAdvice(SensitiveResponseProperties properties) { return new SensitiveResponseBodyAdvice(properties); }
    }
}
