package cn.jia.agent.acceptance.ur04;

import cn.jia.agent.api.AgentRuntimeV1Controller;
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
import cn.jia.common.dao.BaseDaoImpl;
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
import org.h2.api.Trigger;
import org.h2.jdbcx.JdbcDataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
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
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import cn.jia.agent.entity.*;
import cn.jia.agent.service.*;
import cn.jia.agent.service.impl.*;
import cn.jia.agent.cache.AgentPersonaCatalogCache;
import cn.jia.agent.hosting.*;
import cn.jia.agent.skill.SkillAgentVersions;
import cn.jia.economy.mapper.*;
import cn.jia.chat.config.AgentRuntimeHandshakeInterceptor;
import cn.jia.chat.handler.AgentWebSocketHandler;
import cn.jia.chat.dao.impl.ChatMessageDaoImpl;
import cn.jia.chat.mapper.ChatMessageMapper;
import cn.jia.chat.service.ChatConversationEventBroker;
import cn.jia.agent.api.AgentWorkItemReassignmentController;
import cn.jia.agent.api.AgentWorkItemRuntimeResultController;
import cn.jia.task.service.TaskService;
import cn.jia.oauth.service.ApiKeyService;
import cn.jia.user.service.UserService;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.aop.framework.ProxyFactory;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
/** Private real Tomcat/native WS/Spring/MyBatis/H2-file process. No production auto-config.
 * Synthetic ACTIVE installations/historical delivery; execution uses the original client engine.
 * Every failure output is allowlisted. H2/controlled child is NOT MySQL/Provider evidence. */
public final class Ur04E05HttpFixture {
    static final String TENANT = "0", CLIENT = "ur04-client", OWNER = "ur04-owner", PREFIX = "UR04_PIPE ";
    static final String[] AGENTS = {"agt_" + "a".repeat(32), "agt_" + "b".repeat(32), "agt_" + "c".repeat(32)};
    static final String[] INSTALLATIONS = {"rti_" + "1".repeat(32), "rti_" + "2".repeat(32), "rti_" + "3".repeat(32)};
    // Test lease duration, not a business performance deadline. Original heartbeat derives from lease.
    static final long LEASE_MILLIS = 6000, INITIAL_VERSION = 7;
    static final JsonMapper JSON = JsonMapper.builder().build();
    private static JdbcDataSource activeDataSource;
    private static Path activeRoot;
    private static PrintStream protocol;
    private static AgentWebSocketHandler nativeHandler;
    private static String bootStage = "BOOT_INPUT";
    private static volatile String fault = "NONE";
    private static CountDownLatch preparedRelease = new CountDownLatch(1), responseRelease = new CountDownLatch(1);
    private static final AtomicLong ACK_UPDATES = new AtomicLong(), WORK_UPDATES = new AtomicLong(), ARTIFACT_INSERTS = new AtomicLong(), SUBMITTED_ATTEMPTS = new AtomicLong();
    private static final AtomicBoolean faultUsed = new AtomicBoolean();
    private static final List<Map<String, Object>> HTTP = new CopyOnWriteArrayList<>();
    private Ur04E05HttpFixture() { }

    static String text(JsonNode value, String key) {
        JsonNode field = value == null ? null : value.get(key); require(field != null && field.isTextual() && !field.asText().isBlank(), "BUSINESS_TEXT_REQUIRED"); return field.asText();
    }
    static long integer(JsonNode value, String key) {
        JsonNode field = value == null ? null : value.get(key); require(field != null && field.isIntegralNumber() && field.canConvertToLong(), "BUSINESS_INTEGER_REQUIRED"); return field.longValue();
    }
    static String optionalText(JsonNode value, String key) { JsonNode field = value == null ? null : value.get(key); return field != null && field.isTextual() ? field.asText() : ""; }
    public static void main(String[] ignored) {
        protocol = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream())); System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        java.util.logging.LogManager.getLogManager().reset();
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            emit(protocol, Map.of("stage", "JAVA_BOOT", "pid", ProcessHandle.current().pid()));
            JsonNode init = JSON.readTree(input.readLine()); require("INIT".equals(text(init, "op")), "INIT_REQUIRED");
            activeRoot = privateRoot(Path.of(text(init, "root")));
            JsonNode initializing = init.get("initialize"); require(initializing != null && initializing.isBoolean(), "INITIALIZE_BOOLEAN_REQUIRED");
            boolean initialize = initializing.asBoolean(); fault = text(init, "fault");
            require(Set.of("NONE", "RESULT_LOSS", "ACK_LOSS", "PREPARED", "ROLLBACK").contains(fault), "FAULT_ALLOWLIST_REQUIRED");
            bootStage = "BOOT_SCHEMA"; activeDataSource = dataSource(activeRoot, !initialize);
            JdbcTemplate jdbc = new JdbcTemplate(activeDataSource);
            if (initialize) {
                require(!Files.exists(activeRoot.resolve("ur04-runtime.mv.db")), "FRESH_DB_REQUIRED");
                new ResourceDatabasePopulator(new ClassPathResource("ur04/e05-http-db-h2.sql")).execute(activeDataSource);
                seed(jdbc, init);
            } else require(jdbc.queryForObject("SELECT COUNT(*) FROM agent_runtime_v1_installation", Integer.class) == 3, "RESTART_RESEED_FORBIDDEN");
            bootStage = "BOOT_HTTP";
            try (HttpFixture http = new HttpFixture(activeRoot)) {
                http.start(); emit(protocol, Map.of("stage", "JAVA_READY", "port", http.port(), "pid", ProcessHandle.current().pid(), "commands", commands(jdbc)));
                String line;
                while ((line = input.readLine()) != null) {
                    JsonNode request = JSON.readTree(line); String op = text(request, "op");
                    if ("STOP".equals(op)) {
                        fault = "NONE"; responseRelease.countDown(); preparedRelease.countDown();
                        http.close(); jdbc.execute("SHUTDOWN"); emit(protocol, Map.of("stage", "STOPPED")); return;
                    }
                    if ("SEED_WORK".equals(op)) {
                        int index = Math.toIntExact(integer(request, "agentIndex")); require(index >= 0 && index < 3, "EXACT_AGENT_REQUIRED");
                        require(jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_meta WHERE task_id=?", Integer.class, "ur04-task-" + index) == 0, "WORK_RESEED_FORBIDDEN");
                        new TransactionTemplate(new DataSourceTransactionManager(activeDataSource)).executeWithoutResult(tx -> seedWork(jdbc, index, System.currentTimeMillis()));
                        emit(protocol, Map.of("stage", "WORK_SEEDED", "commands", commands(jdbc)));
                    } else if ("SNAPSHOT".equals(op)) emit(protocol, snapshot(jdbc));
                    else if ("DISPATCH".equals(op) || "ALTERED_DISPATCH".equals(op)) {
                        int index = Math.toIntExact(integer(request, "agentIndex")); require(index >= 0 && index < 3, "EXACT_AGENT_REQUIRED");
                        byte[] wire = jdbc.queryForObject("SELECT wire_payload FROM agent_outbox_event WHERE id=?", byte[].class, index * 2 + 2);
                        if ("ALTERED_DISPATCH".equals(op)) {
                            // Same original ID, different canonical business content; real processor must reject.
                            String raw = new String(wire, StandardCharsets.UTF_8);
                            require(raw.contains("UR04 synthetic original execution"), "ALTERED_FRAME_ANCHOR_REQUIRED");
                            wire = raw.replace("UR04 synthetic original execution", "UR04 conflicting original execution").getBytes(StandardCharsets.UTF_8);
                        }
                        var delivery = nativeHandler.dispatchExactRawCommand(TENANT, CLIENT, "ur04-task-" + index, AGENTS[index], wire);
                        emit(protocol, Map.of("stage", "DISPATCHED", "sent", delivery.status() == AgentRawCommandDispatchResult.Status.SENT, "agentIndex", index));
                    } else if ("RELEASE_PREPARED".equals(op)) { preparedRelease.countDown(); emit(protocol, Map.of("stage", "PREPARED_RELEASED")); }
                    else if ("MUTATE".equals(op)) { mutate(jdbc, request); emit(protocol, Map.of("stage", "MUTATED")); }
                    else throw new FixtureFailure("UNKNOWN_PRIVATE_OPERATION");
                }
            }
            throw new FixtureFailure("EXPLICIT_STOP_REQUIRED");
        } catch (Throwable failure) {
            Map<String, Object> safe = new LinkedHashMap<>(safeFailure(failure)); safe.put("stage", "ERROR"); safe.put("at", bootStage);
            safe.put("code", failure instanceof FixtureFailure f ? f.code : "UR04_JAVA_FAILURE"); emit(protocol, safe); System.exit(1);
        }
    }

    private static void seed(JdbcTemplate jdbc, JsonNode init) {
        long now = System.currentTimeMillis(); JsonNode manifests = init.get("manifests"), authorizations = init.get("authorizations");
        require(manifests != null && manifests.isArray() && manifests.size() == 3 && authorizations != null && authorizations.isArray() && authorizations.size() == 3, "THREE_SYNTHETIC_INSTALLATIONS_REQUIRED");
        new TransactionTemplate(new DataSourceTransactionManager(activeDataSource)).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO user_info(id,jiacn,account_state,auth_epoch) VALUES(7,?,'ACTIVE',2)", OWNER);
            for (int index = 0; index < 3; index++) {
                JsonNode m = manifests.get(index); String authorization = authorizations.get(index).isTextual() ? authorizations.get(index).asText() : "";
                require(authorization.matches("rta1_[0-9a-f]{64}") && INSTALLATIONS[index].equals(text(m, "installationId"))
                    && AGENTS[index].equals(text(m, "canonicalAgentId")) && TENANT.equals(text(m, "tenantId")) && CLIENT.equals(text(m, "clientId")), "SYNTHETIC_SUBJECT_MISMATCH");
                String manifestHash = text(m, "manifestSha256"); require(manifestHash.matches("sha256:[0-9a-f]{64}"), "SEALED_MANIFEST_REQUIRED");
                long id = index + 1;
                jdbc.update("INSERT INTO agent_persona(id,persona_code,name,rank_no,abilities,active,system_agent,tenant_id,client_id) VALUES(?,?,?,?,?,1,0,?,?)",
                    id, "ur04-persona-" + index, "UR04 persona " + index, index, "[]", TENANT, CLIENT);
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
        for (String agent : AGENTS) jdbc.update("INSERT INTO agent_task_member(task_id,owner_jiacn,agent_id,member_role,member_status,assignment_source,version,tenant_id,client_id,create_time,update_time) VALUES(?,?,?,?,'working','manual',0,?,?,?,?)",
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
    static Map<String, Object> snapshot(JdbcTemplate jdbc) {
        Map<String, Object> out = new LinkedHashMap<>(); out.put("stage", "SNAPSHOT");
        out.put("ackUpdates", ACK_UPDATES.get()); out.put("workUpdates", WORK_UPDATES.get()); out.put("artifactInserts", ARTIFACT_INSERTS.get()); out.put("submittedAttempts", SUBMITTED_ATTEMPTS.get());
        out.put("http", List.copyOf(HTTP));
        out.put("runtimes", jdbc.query("SELECT agent_id,runtime_session_generation,runtime_instance_id,status FROM agent_runtime ORDER BY agent_id", (rs, row) -> Map.of("generation", rs.getLong(2), "boot", rs.getString(3), "status", rs.getString(4))));
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
    static byte[] sha256(String value) { return AgentCommandCanonicalCodec.sha256(value.getBytes(StandardCharsets.UTF_8)); }
    static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
    static void require(boolean condition, String code) { if (!condition) throw new FixtureFailure(code); }
    static synchronized void emit(PrintStream pipe, Object value) { pipe.println(PREFIX + JSON.writeValueAsString(value)); pipe.flush(); }
    static final Set<String> FIXTURE_CODES = Set.of(
            "ALTERED_FRAME_ANCHOR_REQUIRED",
            "BOUND_PORT_REQUIRED",
            "BUSINESS_INTEGER_REQUIRED",
            "BUSINESS_TEXT_REQUIRED",
            "CUT_REQUIRES_REAL_D06_COMMIT",
            "CUT_REQUIRES_REAL_SUBMITTED_COMMIT",
            "DIGEST_UNAVAILABLE",
            "NATIVE_HANDLER_ASSEMBLY_FAILED",
            "UR04_PRODUCTION_ACCOUNT_XML_REQUIRED",
            "EXACT_AGENT_REQUIRED",
            "EXPLICIT_STOP_REQUIRED",
            "FAULT_ALLOWLIST_REQUIRED",
            "FRESH_DB_REQUIRED",
            "INITIALIZE_BOOLEAN_REQUIRED",
            "INIT_REQUIRED",
            "LOOPBACK_BIND_REQUIRED",
            "MUTATION_ALLOWLIST_REQUIRED",
            "PREPARE_OUTER_TRANSACTION_FORBIDDEN",
            "REAL_NATIVE_HANDLER_REQUIRED",
            "REAL_TRANSACTION_PROXY_REQUIRED",
            "RESTART_RESEED_FORBIDDEN",
            "SEALED_MANIFEST_REQUIRED",
            "SYNTHETIC_SUBJECT_MISMATCH",
            "THREE_SYNTHETIC_INSTALLATIONS_REQUIRED",
            "UNKNOWN_PRIVATE_OPERATION",
            "UR04_DB_SYMLINK_FORBIDDEN",
            "UR04_EXISTING_DB_REQUIRED",
            "UR04_PRIVATE_CUT_INTERRUPTED",
            "UR04_PRIVATE_ROOT_REQUIRED",
            "WORK_RESEED_FORBIDDEN");
    static final class FixtureFailure extends RuntimeException {
        final String code;
        FixtureFailure(String code) { this(code, null); }
        FixtureFailure(String code, Throwable cause) { super(code, cause, false, false); this.code = code; }
    }

    /** Non-mutating JDBC probes; failure only at the real submitted event SQL INSERT. */
    public static final class SqlProbe implements Trigger {
        private String table; private int status = -1, type = -1;
        @Override public void init(Connection connection, String schema, String trigger, String table, boolean before, int kind) throws SQLException {
            this.table = table.toLowerCase(Locale.ROOT);
            try (var s = connection.createStatement(); var rs = s.executeQuery("SELECT * FROM " + table + " WHERE 1=0")) {
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    String name = rs.getMetaData().getColumnName(i);
                    if ("STATUS".equalsIgnoreCase(name)) status = i - 1;
                    if ("EVENT_TYPE".equalsIgnoreCase(name)) type = i - 1;
                }
            }
        }
        @Override public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException {
            if (table.equals("agent_command_delivery") && !Objects.equals(oldRow[status], newRow[status])) ACK_UPDATES.incrementAndGet();
            if (table.equals("agent_task_work_item")) WORK_UPDATES.incrementAndGet();
            if (table.equals("agent_task_artifact")) ARTIFACT_INSERTS.incrementAndGet();
            if (table.equals("agent_task_event") && "WORK_ITEM_SUBMITTED".equals(newRow[type])) {
                SUBMITTED_ATTEMPTS.incrementAndGet();
                if ("ROLLBACK".equals(fault)) throw new SQLException("UR04_SYNTHETIC_SUBMITTED_SQL_FAILURE", "45000");
            }
        }
        @Override public void close() { }
        @Override public void remove() { }
    }
    /** Only observation/buffering at actual container boundary. Controller completes unchanged. */
    private static final class ResponseCut implements Filter {
        @Override public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
            HttpServletRequest raw = (HttpServletRequest) req;
            if (raw.getRequestURI().equals("/ws/agent/channel")) { chain.doFilter(req, res); return; }
            ContentCachingRequestWrapper request = new ContentCachingRequestWrapper(raw);
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
                HTTP.add(receipt); emit(protocol, Map.of("stage", "HTTP_OBSERVED", "receipt", receipt));
                boolean resultCut = "RESULT_LOSS".equals(fault) && category.equals("RESULT") && request.getMethod().equals("POST") && response.getStatus() == 200;
                boolean ackCut = "ACK_LOSS".equals(fault) && category.equals("ACK") && response.getStatus() == 200 && "SUCCEEDED".equals(optionalText(data, "status"));
                if ((resultCut || ackCut) && faultUsed.compareAndSet(false, true)) {
                    // Actual DB state verifies transaction commit before holding the response.
                    Map<String, Object> snapshot = snapshot(new JdbcTemplate(activeDataSource));
                    require(((Number) snapshot.get("submittedEvents")).longValue() == 1, "CUT_REQUIRES_REAL_SUBMITTED_COMMIT");
                    if (ackCut) require("SUCCEEDED".equals(new JdbcTemplate(activeDataSource).queryForObject("SELECT status FROM agent_command_delivery WHERE id=2", String.class)), "CUT_REQUIRES_REAL_D06_COMMIT");
                    emit(protocol, Map.of("stage", resultCut ? "RESULT_COMMIT_HELD" : "TERMINAL_ACK_HELD", "snapshot", snapshot));
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
    private static final class HttpFixture implements AutoCloseable {
        private final Tomcat tomcat = new Tomcat();
        private final AnnotationConfigWebApplicationContext spring = new AnnotationConfigWebApplicationContext();
        private boolean closed;
        HttpFixture(Path root) throws IOException {
            Path doc = Files.createDirectory(root.resolve("http-" + UUID.randomUUID()), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            tomcat.setBaseDir(doc.toString()); tomcat.setHostname("127.0.0.1"); tomcat.setPort(0);
            require(tomcat.getConnector().setProperty("address", "127.0.0.1"), "LOOPBACK_BIND_REQUIRED");
            Context context = tomcat.addContext("", doc.toString()); context.setParentClassLoader(Ur04E05HttpFixture.class.getClassLoader());
            context.addServletContainerInitializer(new org.apache.tomcat.websocket.server.WsSci(), Set.of());
            spring.setServletContext(context.getServletContext()); spring.register(HttpConfiguration.class);
            bootStage = "BOOT_MYBATIS_SPRING"; spring.refresh();
            for (Class<?> type : List.of(AgentRuntimeAuthenticationService.class, AgentRuntimeV1Service.class, AgentService.class,
                    AgentWorkItemLeaseService.class, AgentWorkItemReassignmentService.class, AgentWorkItemResultCommitService.class, AgentTaskArtifactService.class))
                require(org.springframework.aop.support.AopUtils.isAopProxy(spring.getBean(type)), "REAL_TRANSACTION_PROXY_REQUIRED");
            require(nativeHandler != null, "REAL_NATIVE_HANDLER_REQUIRED");
            var servlet = Tomcat.addServlet(context, "dispatcher", new DispatcherServlet(spring)); servlet.setLoadOnStartup(1); context.addServletMappingDecoded("/", "dispatcher");
            addFilter(context, "private-response-cut", new ResponseCut());
            addFilter(context, "runtime-security", new DelegatingFilterProxy("springSecurityFilterChain", spring));
        }
        private static void addFilter(Context context, String name, Filter filter) {
            FilterDef definition = new FilterDef(); definition.setFilterName(name); definition.setFilter(filter); context.addFilterDef(definition);
            FilterMap mapping = new FilterMap(); mapping.setFilterName(name); mapping.addURLPattern("/*"); mapping.setDispatcher("REQUEST"); context.addFilterMap(mapping);
        }
        void start() throws Exception { bootStage = "BOOT_TOMCAT_START"; tomcat.start(); require(port() > 0, "BOUND_PORT_REQUIRED"); }
        int port() { return tomcat.getConnector().getLocalPort(); }
        @Override public void close() throws Exception {
            if (!closed) { closed = true; try { tomcat.stop(); } finally { try { tomcat.destroy(); } finally { spring.close(); } } }
        }
    }
    // Exact allowlists: never stringify Throwable, SQL, bean definitions or arbitrary identifiers.
    private static final Set<String> DIAGNOSTIC_TYPES = Set.of(
            "java.io.IOException", "java.io.FileNotFoundException", "java.lang.AssertionError",
            "java.lang.IllegalArgumentException", "java.lang.IllegalStateException", "java.lang.NullPointerException",
            "java.lang.ClassNotFoundException", "java.lang.NoClassDefFoundError", "java.lang.NoSuchMethodError",
            "java.lang.ExceptionInInitializerError", "java.lang.UnsatisfiedLinkError", "java.lang.IllegalAccessError",
            "java.lang.reflect.InvocationTargetException", "java.nio.file.NoSuchFileException",
            "java.nio.file.AccessDeniedException", "java.nio.file.FileAlreadyExistsException",
            "java.sql.SQLException", "org.h2.jdbc.JdbcSQLSyntaxErrorException", "org.h2.jdbc.JdbcSQLNonTransientException",
            "org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException", "org.h2.jdbc.JdbcSQLNonTransientConnectionException",
            "org.springframework.beans.factory.BeanCreationException", "org.springframework.beans.factory.UnsatisfiedDependencyException",
            "org.springframework.beans.factory.NoSuchBeanDefinitionException", "org.springframework.beans.factory.BeanDefinitionStoreException",
            "org.springframework.beans.BeanInstantiationException", "org.springframework.jdbc.datasource.init.ScriptStatementFailedException",
            "org.springframework.jdbc.BadSqlGrammarException", "org.springframework.jdbc.UncategorizedSQLException",
            "org.springframework.context.ApplicationContextException", "org.apache.catalina.LifecycleException",
            "org.apache.ibatis.exceptions.PersistenceException", "org.apache.ibatis.binding.BindingException",
            "org.mybatis.spring.MyBatisSystemException", "org.springframework.aop.framework.AopConfigException",
            "cn.jia.agent.acceptance.ur04.Ur04E05HttpFixture$FixtureFailure");
    static final Set<String> DIAGNOSTIC_BEANS = Set.of("dataSource", "transactionManager", "sqlSessionFactory",
            "sqlSessionTemplate", "installations", "runtimes", "registry", "aliases", "bindings", "users", "identity",
            "accounts", "taskGate", "authentication", "ackGate", "commandAcks", "runtime", "controller",
            "sensitiveProperties", "sensitiveAdvice", "springSecurityFilterChain", "mvcHandlerMappingIntrospector",
            "requestMappingHandlerAdapter", "requestMappingHandlerMapping", "mvcContentNegotiationManager", "mvcConversionService",
            "jdbc", "catalog", "personas", "meta", "members", "workItems", "requests", "artifacts", "events", "notes", "dialogues",
            "mutation", "broker", "afterCommit", "writer", "aggregation", "legacy", "requirements", "hosting", "hostingOwners",
            "skillVersions", "agents", "access", "transportWriter", "leases", "reassignments", "storage", "publications", "results",
            "leaseController", "resultController", "preparedBoundary", "agentRuntimeV1ClientSecurityFilterChain",
            "agentRuntimeSecurityFilterChain", "webSocketHandlerMapping", "defaultSockJsScheduler", "webSocketHandlerAdapter");
    static final Set<String> DIAGNOSTIC_CLASSES = Set.of(
            "cn.jia.agent.acceptance.ur04.Ur04E05HttpFixture", "cn.jia.agent.acceptance.ur04.Ur04E05HttpFixture$SqlProbe",
            "cn.jia.agent.api.AgentRuntimeV1Controller", "cn.jia.agent.security.AgentRuntimeAuthenticationService",
            "cn.jia.agent.service.impl.AgentRuntimeV1ServiceImpl", "cn.jia.agent.mapper.AgentCommandRecoveryMapper",
            "cn.jia.user.security.AccountSecurityServiceImpl", "cn.jia.user.mapper.InfoMapper", "cn.jia.common.dao.BaseDaoImpl",
            "tools.jackson.databind.json.JsonMapper", "tools.jackson.databind.JsonNode", "tools.jackson.core.JsonFactory",
            "org.h2.jdbcx.JdbcDataSource", "org.h2.api.Trigger", "org.apache.catalina.startup.Tomcat",
            "org.springframework.web.servlet.DispatcherServlet", "org.springframework.web.context.support.AnnotationConfigWebApplicationContext",
            "org.springframework.security.config.annotation.web.configuration.EnableWebSecurity",
            "org.springframework.jdbc.core.JdbcTemplate", "org.springframework.jdbc.datasource.DataSourceTransactionManager",
            "org.apache.ibatis.session.SqlSessionFactory", "org.mybatis.spring.SqlSessionTemplate",
            "com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean",
            "jakarta.servlet.Servlet", "jakarta.servlet.http.HttpServlet", "org.slf4j.LoggerFactory",
            "org.slf4j.Logger", "org.apache.commons.logging.LogFactory", "org.springframework.ai.chat.client.ChatClient",
            "cn.jia.chat.handler.AgentWebSocketHandler", "cn.jia.chat.config.AgentRuntimeHandshakeInterceptor",
            "cn.jia.agent.api.AgentWorkItemRuntimeResultController", "cn.jia.agent.api.AgentWorkItemReassignmentController",
            "cn.jia.agent.service.impl.AgentWorkItemResultCommitServiceImpl", "cn.jia.agent.service.impl.AgentWorkItemReassignmentServiceImpl",
            "org.apache.tomcat.websocket.server.WsSci", "jakarta.websocket.server.ServerContainer");
    private static final Pattern OS_ERROR = Pattern.compile("\\berror=([0-9]{1,4})(?:,|\\b)");

    static String allowedMissingClass(String name) {
        // JVM linkage errors use slash-separated class names; emit only known runtime anchors.
        if (name == null) return null;
        String normalized = name.replace('/', '.');
        return DIAGNOSTIC_CLASSES.contains(normalized) ? normalized : null;
    }
    private static String diagnosticType(Throwable failure) {
        return DIAGNOSTIC_TYPES.contains(failure.getClass().getName()) ? failure.getClass().getSimpleName() : "OTHER";
    }
    static boolean allowedDiagnosticType(String value) {
        return "OTHER".equals(value) || DIAGNOSTIC_TYPES.stream().anyMatch(t -> t.substring(Math.max(t.lastIndexOf('.'), t.lastIndexOf('$')) + 1).equals(value));
    }
    static Map<String, Object> safeFailure(Throwable failure) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (failure == null) { safe.put("type", "OTHER"); safe.put("rootType", "OTHER"); return safe; }
        safe.put("type", diagnosticType(failure));
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            safe.put("rootType", diagnosticType(cause));
            if (cause instanceof SQLException sql && sql.getSQLState() != null
                    && sql.getSQLState().matches("[A-Z0-9]{5}")) safe.put("sqlState", sql.getSQLState());
            if (cause instanceof BeanCreationException bean && bean.getBeanName() != null
                    && DIAGNOSTIC_BEANS.contains(bean.getBeanName())) {
                safe.put("bean", bean.getBeanName());
            }
            if (cause instanceof ClassNotFoundException || cause instanceof NoClassDefFoundError) {
                String missing = cause.getMessage() == null ? null : allowedMissingClass(cause.getMessage());
                if (missing != null) safe.put("missingClass", missing);
            }
            if (cause instanceof IOException && cause.getMessage() != null) {
                // The private message can contain executable paths; only Linux's numeric errno leaves here.
                var match = OS_ERROR.matcher(cause.getMessage());
                if (match.find()) {
                    int errno = Integer.parseInt(match.group(1));
                    if (errno > 0 && errno <= 4095) safe.put("errno", errno);
                }
            }
        }
        return safe;
    }
    static String safeLauncherLine(String line) {
        // Pre-main JVM errors cannot use the child's JSON handler. Do not retain/export stderr.
        for (String prefix : List.of("Error: Could not find or load main class ",
                "Caused by: java.lang.ClassNotFoundException: ", "Caused by: java.lang.NoClassDefFoundError: ",
                "Exception in thread \"main\" java.lang.NoClassDefFoundError: ")) {
            if (line.startsWith(prefix)) {
                String missing = allowedMissingClass(line.substring(prefix.length()));
                return missing == null ? "UR04_JVM_LINKAGE_FAILURE" : "UR04_JVM_LINKAGE_FAILURE missingClass=" + missing;
            }
        }
        if (line.equals("Error: Unable to initialize main class cn.jia.agent.acceptance.ur04.Ur04E05HttpFixture")) {
            return "UR04_JVM_MAIN_INITIALIZATION_FAILED";
        }
        if (line.startsWith("Exception in thread \"main\" java.lang.ExceptionInInitializerError")) {
            return "UR04_JVM_STATIC_INITIALIZATION_FAILED";
        }
        return null;
    }

    static Path privateRoot(Path root) throws IOException {
        require(root.isAbsolute() && !Files.isSymbolicLink(root) && Files.isDirectory(root), "UR04_PRIVATE_ROOT_REQUIRED");
        Path real = root.toRealPath();
        require(real.getFileName().toString().startsWith("ur04-")
                && Files.getPosixFilePermissions(real).equals(PosixFilePermissions.fromString("rwx------")),
                "UR04_PRIVATE_ROOT_REQUIRED");
        return real;
    }

    static JdbcDataSource dataSource(Path root, boolean existing) throws IOException {
        Path real = privateRoot(root);
        Path database = real.resolve("ur04-runtime");
        require(!Files.isSymbolicLink(real.resolve("ur04-runtime.mv.db")), "UR04_DB_SYMLINK_FORBIDDEN");
        if (existing) require(Files.isRegularFile(real.resolve("ur04-runtime.mv.db")), "UR04_EXISTING_DB_REQUIRED");
        // No external URL/env input, TCP, AUTO_SERVER, migration or application properties.
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:file:" + database + ";MODE=MYSQL" + (existing ? ";IFEXISTS=TRUE" : ""));
        source.setUser("sa"); source.setPassword("");
        return source;
    }

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
                    wire(new ChatMessageDaoImpl(), beans.getBean(SqlSessionTemplate.class).getMapper(ChatMessageMapper.class)),
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
                    "UR04_PRODUCTION_ACCOUNT_XML_REQUIRED");
            return factory;
        }
        @Bean SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }
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
        @Bean AgentRabbitSafetyGate ackGate() {
            return new AgentRabbitSafetyGate(new AgentRabbitSafetyProperties(
                    new AgentRabbitSafetyProperties.CommandOutbox(true), new AgentRabbitSafetyProperties.RabbitTopology(true),
                    new AgentRabbitSafetyProperties.RabbitPublish(true), new AgentRabbitSafetyProperties.RabbitConsume(true),
                    new AgentRabbitSafetyProperties.RabbitDispatch(true),
                    new AgentRabbitSafetyProperties.RabbitBroker("isolated.invalid", 35672, "synthetic", "synthetic", "/ur04")),
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
        @Bean AgentPersonaDao personas(SqlSessionTemplate s, AgentPersonaCatalogCache cache) throws Exception {
            var dao = wire(new AgentPersonaDaoImpl(), s.getMapper(AgentPersonaMapper.class)); dao.setCatalogCache(cache); return dao;
        }
        @Bean AgentTaskMetaDao meta(SqlSessionTemplate s) throws Exception { return wire(new AgentTaskMetaDaoImpl(), s.getMapper(AgentTaskMetaMapper.class)); }
        @Bean AgentTaskMemberDao members(SqlSessionTemplate s) { return new AgentTaskMemberDaoImpl(s.getMapper(AgentTaskMemberMapper.class)); }
        @Bean AgentTaskWorkItemDao workItems(SqlSessionTemplate s) { return new AgentTaskWorkItemDaoImpl(s.getMapper(AgentTaskWorkItemMapper.class)); }
        @Bean AgentTaskRequestDao requests(SqlSessionTemplate s) { return new AgentTaskRequestDaoImpl(s.getMapper(AgentTaskRequestMapper.class)); }
        @Bean AgentTaskArtifactDao artifacts(SqlSessionTemplate s) { return new AgentTaskArtifactDaoImpl(s.getMapper(AgentTaskArtifactMapper.class)); }
        @Bean AgentTaskEventDao events(SqlSessionTemplate s) throws Exception { return wire(new AgentTaskEventDaoImpl(), s.getMapper(AgentTaskEventMapper.class)); }
        @Bean AgentTaskNoteDao notes(SqlSessionTemplate s) throws Exception { return wire(new AgentTaskNoteDaoImpl(), s.getMapper(AgentTaskNoteMapper.class)); }
        @Bean DialogueTemplateDao dialogues(SqlSessionTemplate s) throws Exception { return wire(new DialogueTemplateDaoImpl(), s.getMapper(DialogueTemplateMapper.class)); }
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
                members, items, mutation, identity, agents, writer, commands, leases, LEASE_MILLIS);
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
                        if ("prepareRuntimeResult".equals(call.getMethod().getName()) && "PREPARED".equals(fault)) {
                            require(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(), "PREPARE_OUTER_TRANSACTION_FORBIDDEN");
                            emit(protocol, Map.of("stage", "PREPARED_HELD")); preparedRelease.await();
                        }
                        return prepared; // unchanged production object, no fake receipt
                    }); return observer.getProxy();
                }
            };
        }
        @Bean SensitiveResponseProperties sensitiveProperties() { return new SensitiveResponseProperties(); }
        @Bean SensitiveResponseBodyAdvice sensitiveAdvice(SensitiveResponseProperties properties) { return new SensitiveResponseBodyAdvice(properties); }
    }

    private static <T extends BaseDaoImpl<?, ?>> T wire(T dao, Object mapper) throws Exception {
        Field field = BaseDaoImpl.class.getDeclaredField("baseMapper"); field.setAccessible(true); field.set(dao, mapper); return dao;
    }
}
