package cn.jia.agent.acceptance;

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

/** Test-only real HTTP child JVM. No auto-configuration, mock DAO or fake business callback.
 * Synthetic SENT rows model prior delivery, not Rabbit/WS/business-execution evidence.
 * H2-file transactions/restart evidence is explicitly NOT MySQL evidence.
 */
public final class Ur03RuntimeHttpFixture {
    static final String TENANT = "0", CLIENT = "ur03-client", OWNER = "ur03-owner";
    static final String AGENT = "agt_" + "a".repeat(32), INSTALLATION = "rti_" + "b".repeat(32);
    static final String HOST = "ur03-host", PREFIX = "UR03_PIPE ";
    static final long INITIAL_VERSION = 7;
    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AtomicLong ACK_UPDATES = new AtomicLong();
    private static JdbcDataSource activeDataSource;
    private static String bootStage = "BOOT_INPUT";

    private Ur03RuntimeHttpFixture() { }

    public static void main(String[] ignored) {
        PrintStream protocol = System.out;
        // Framework logs/exception chains can contain JDBC paths or SQL parameters. Never export them.
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        java.util.logging.LogManager.getLogManager().reset();
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            JsonNode init = JSON.readTree(input.readLine());
            require("INIT".equals(init.path("op").asText()), "UR03_INIT_REQUIRED");
            Path root = privateRoot(Path.of(init.path("root").asText()));
            boolean initialize = init.path("initialize").asBoolean();
            String authorization = init.path("authorization").asText();
            String manifestHash = init.path("manifestHash").asText();
            require(authorization.matches("rta1_[0-9a-f]{64}") && manifestHash.matches("[0-9a-f]{64}"),
                    "UR03_SYNTHETIC_INPUT_REQUIRED");
            bootStage = "BOOT_SCHEMA";
            activeDataSource = dataSource(root, !initialize);
            JdbcTemplate jdbc = new JdbcTemplate(activeDataSource);
            if (initialize) {
                require(!Files.exists(root.resolve("ur03-runtime.mv.db")), "UR03_FRESH_DB_REQUIRED");
                new ResourceDatabasePopulator(new ClassPathResource("ur03/runtime-http-db-h2.sql"))
                        .execute(activeDataSource);
                seed(jdbc, authorization, manifestHash);
            } else {
                require(jdbc.queryForObject("SELECT COUNT(*) FROM agent_runtime", Integer.class) == 1,
                        "UR03_RESTART_RESEED_FORBIDDEN");
            }
            bootStage = "BOOT_HTTP";
            try (HttpFixture http = new HttpFixture(root)) {
                http.start();
                emit(protocol, Map.of("stage", "JAVA_READY", "port", http.port(),
                        "pid", ProcessHandle.current().pid(), "commands", commandInputs(jdbc)));
                String line;
                while ((line = input.readLine()) != null) {
                    JsonNode request = JSON.readTree(line);
                    String op = request.path("op").asText();
                    if ("STOP".equals(op)) {
                        bootStage = "STOP_HTTP_DB";
                        http.close();
                        jdbc.execute("SHUTDOWN");
                        emit(protocol, Map.of("stage", "STOPPED"));
                        return;
                    }
                    require("SNAPSHOT".equals(op), "UR03_UNKNOWN_STAGE");
                    bootStage = "SNAPSHOT_JDBC";
                    emit(protocol, snapshot(jdbc, request.path("tokenDigest").asText("")));
                }
            }
            throw new FixtureFailure("UR03_STOP_REQUIRED");
        } catch (Throwable failure) {
            // No original message, SQL statement, URL, request, credential or exception chain.
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("stage", "ERROR"); safe.put("at", bootStage);
            safe.put("code", failure instanceof FixtureFailure f ? f.code : "UR03_JAVA_FAILURE");
            Throwable cause = failure;
            Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            while (cause != null && seen.add(cause)) {
                if (cause instanceof SQLException sql && sql.getSQLState() != null
                        && sql.getSQLState().matches("[A-Z0-9]{5}")) safe.put("sqlState", sql.getSQLState());
                String type = cause.getClass().getSimpleName();
                if (type.matches("[A-Za-z0-9]{1,80}")) safe.put("type", type);
                cause = cause.getCause();
            }
            emit(protocol, safe);
            System.exit(1);
        }
    }

    static Path privateRoot(Path root) throws IOException {
        require(root.isAbsolute() && !Files.isSymbolicLink(root) && Files.isDirectory(root), "UR03_PRIVATE_ROOT_REQUIRED");
        Path real = root.toRealPath();
        require(real.getFileName().toString().startsWith("ur03-")
                && Files.getPosixFilePermissions(real).equals(PosixFilePermissions.fromString("rwx------")),
                "UR03_PRIVATE_ROOT_REQUIRED");
        return real;
    }

    static JdbcDataSource dataSource(Path root, boolean existing) throws IOException {
        Path real = privateRoot(root);
        Path database = real.resolve("ur03-runtime");
        require(!Files.isSymbolicLink(real.resolve("ur03-runtime.mv.db")), "UR03_DB_SYMLINK_FORBIDDEN");
        if (existing) require(Files.isRegularFile(real.resolve("ur03-runtime.mv.db")), "UR03_EXISTING_DB_REQUIRED");
        // No external URL/env input, TCP, AUTO_SERVER, migration or application properties.
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:file:" + database + ";MODE=MYSQL" + (existing ? ";IFEXISTS=TRUE" : ""));
        source.setUser("sa"); source.setPassword("");
        return source;
    }

    private static void seed(JdbcTemplate jdbc, String authorization, String manifestHash) {
        long now = System.currentTimeMillis();
        new TransactionTemplate(new DataSourceTransactionManager(activeDataSource)).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO user_info(id,jiacn,account_state,auth_epoch) VALUES(7,?,'ACTIVE',2)", OWNER);
            jdbc.update("""
                    INSERT INTO agent_persona_binding(id,jiacn,persona_code,agent_id,bound_at,status,
                        tenant_id,client_id,create_time,update_time) VALUES(3,?,'ur03-persona',?,?,1,?,?,?,?)
                    """, OWNER, AGENT, now, TENANT, CLIENT, now, now);
            jdbc.update("""
                    INSERT INTO agent_identity_registry(id,canonical_agent_id,canonical_type,lifecycle_status,
                        owner_jiacn,binding_id,provisioned_at,activated_at,audit_reason,
                        tenant_id,client_id,create_time,update_time)
                    VALUES(2,?,'OPAQUE','ACTIVE',?,3,?,?,'UR03_SYNTHETIC_FIXTURE',?,?,?,?)
                    """, AGENT, OWNER, now, now, TENANT, CLIENT, now, now);
            jdbc.update("""
                    INSERT INTO agent_runtime_v1_installation(id,installation_id,canonical_agent_id,
                        manifest_version,manifest_sha256,enrollment_secret_hash,enrollment_expires_at,
                        enrollment_consumed_at,runtime_authorization_hash,runtime_authorization_issued_at,
                        status,version,tenant_id,client_id,create_time,update_time)
                    VALUES(1,?,?,'1',?,?,?,?,?,?,'ACTIVE',0,?,?,?,?)
                    """, INSTALLATION, AGENT, manifestHash, sha256("synthetic-enrollment-not-used"),
                    now + 86_400_000, now, sha256(authorization), now, TENANT, CLIENT, now, now);
            seedCommand(jdbc, 1, now);
            seedCommand(jdbc, 2, now);
        });
    }

    private static void seedCommand(JdbcTemplate jdbc, int id, long now) {
        String task = "ur03-task-" + id;
        String message = "00000000-0000-0000-0000-" + String.format(Locale.ROOT, "%012d", id);
        String command = AgentCommandCanonicalCodec.taskInviteCommandId(TENANT, CLIENT, OWNER, task, AGENT);
        AgentCommandDraft draft = new AgentCommandDraft(1, command, task, "ur03-cause-" + id,
                TENANT, CLIENT, OWNER, task, null, AGENT, "TASK_INVITE", now,
                now + AgentCommandCanonicalCodec.TASK_INVITE_TTL_MILLIS,
                new AgentTaskInvitePayload("task_briefing", "宋江首领已完成悬赏分派，请按职责协作推进。",
                        "阅读悬赏任务，确认自己的职责；如需协助，优先参考协作名册中的好汉能力并回报下一步计划。",
                        "UR03 synthetic ACK only", List.of("review"), AGENT, List.of(AGENT), "coordinator",
                        "回报执行计划、风险和协助诉求；Protocol v1 使用 work.progress，完成后使用 work.result，旧客户端由兼容层处理。",
                        "juyiting"));
        byte[] business = AgentCommandCanonicalCodec.businessBytes(draft);
        byte[] wire = AgentCommandCanonicalCodec.wireBytes(draft, message, 1);
        var route = AgentRabbitTopologyManifest.canonical().defaultCommandPublishRoute();
        // Synthetic historical broker evidence, not a broker/network call or TASK execution.
        jdbc.update("""
                INSERT INTO agent_command_delivery(id,owner_jiacn,command_id,task_id,target_agent_id,
                    command_type,command_payload,command_payload_hash,status,attempt_count,
                    active_message_id,active_attempt,expires_at,version,tenant_id,client_id,create_time,update_time)
                VALUES(?,?,?,?,?,'TASK_INVITE',?,?,'SENT',1,?,1,?,?,?,?,?,?)
                """, id, OWNER, command, task, AGENT, business, AgentCommandCanonicalCodec.sha256(business),
                message, draft.expiresAt(), INITIAL_VERSION, TENANT, CLIENT, now, now);
        jdbc.update("""
                INSERT INTO agent_outbox_event(id,event_id,message_id,command_id,delivery_id,aggregate_type,
                    aggregate_id,destination,routing_key,wire_payload,wire_payload_hash,status,attempt_count,
                    active_attempt,expires_at,publisher_confirm_status,confirmed_at,mandatory_return_status,
                    published_at,version,tenant_id,client_id,create_time,update_time)
                VALUES(?,?,?,?,?,'task',?,?,?,?,?,'PUBLISHED',1,1,?,'ACK',?,'NOT_RETURNED',?,0,?,?,?,?)
                """, id, "ur03-event-" + id, message, command, id, task, route.destination(), route.routingKey(),
                wire, AgentCommandCanonicalCodec.sha256(wire), draft.expiresAt(), now, now, TENANT, CLIENT, now, now);
        jdbc.update("""
                INSERT INTO agent_consumer_inbox(id,consumer_name,message_id,event_id,command_id,delivery_id,
                    wire_payload,wire_payload_hash,status,result_status,attempt_count,active_attempt,
                    expires_at,processed_at,version,tenant_id,client_id,create_time,update_time)
                VALUES(?,'agent-command-dispatch-v1',?,?,?,?,?,?,'PROCESSED','SENT',1,1,?,?,0,?,?,?,?)
                """, id, message, "ur03-event-" + id, command, id, wire, AgentCommandCanonicalCodec.sha256(wire),
                draft.expiresAt(), now, TENANT, CLIENT, now, now);
    }

    private static List<Map<String, Object>> commandInputs(JdbcTemplate jdbc) {
        return jdbc.query("SELECT * FROM agent_command_delivery ORDER BY id", (rs, index) -> {
            Map<String, Object> command = new LinkedHashMap<>();
            command.put("installationId", INSTALLATION); command.put("tenantId", TENANT);
            command.put("clientId", CLIENT); command.put("canonicalAgentId", AGENT);
            command.put("messageId", rs.getString("active_message_id"));
            command.put("correlationId", rs.getString("task_id"));
            command.put("commandId", rs.getString("command_id")); command.put("taskId", rs.getString("task_id"));
            command.put("workItemId", null); command.put("payloadReference", null);
            command.put("expiresAt", java.time.Instant.ofEpochMilli(rs.getLong("expires_at")).toString());
            return command;
        });
    }

    /** Separate JDBC read after the completed HTTP transaction, never the MyBatis session cache. */
    static Map<String, Object> snapshot(JdbcTemplate jdbc, String expectedDigest) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("stage", "SNAPSHOT");
        snapshot.put("updates", ACK_UPDATES.get());
        snapshot.put("deliveries", jdbc.query("SELECT status,version FROM agent_command_delivery ORDER BY id",
                (rs, index) -> Map.of("status", rs.getString(1), "version", rs.getLong(2))));
        snapshot.put("runtimeCount", jdbc.queryForObject("SELECT COUNT(*) FROM agent_runtime", Integer.class));
        snapshot.put("installationCount", jdbc.queryForObject("SELECT COUNT(*) FROM agent_runtime_v1_installation", Integer.class));
        snapshot.put("outboxCount", jdbc.queryForObject("SELECT COUNT(*) FROM agent_outbox_event", Integer.class));
        snapshot.put("inboxCount", jdbc.queryForObject("SELECT COUNT(*) FROM agent_consumer_inbox", Integer.class));
        jdbc.query("SELECT * FROM agent_runtime", rs -> {
            snapshot.put("generation", rs.getLong("runtime_session_generation"));
            snapshot.put("boot", rs.getString("runtime_instance_id"));
            String verifier = rs.getString("token_hash");
            snapshot.put("digestOnly", verifier != null && verifier.matches("urs1:[0-9a-f]{64}:7:2"));
            snapshot.put("digestMatches", expectedDigest.matches("[0-9a-f]{64}")
                    && ("urs1:" + expectedDigest + ":7:2").equals(verifier));
            snapshot.put("scopeMatches", TENANT.equals(rs.getString("tenant_id"))
                    && CLIENT.equals(rs.getString("client_id")) && OWNER.equals(rs.getString("owner_jiacn"))
                    && AGENT.equals(rs.getString("agent_id")) && INSTALLATION.equals(rs.getString("runtime_installation_id"))
                    && HOST.equals(rs.getString("runtime_host_id")) && rs.getLong("binding_id") == 3);
        });
        // Full original command bytes/hashes/keys and source rows, excluding only mutable delivery ACK fields.
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            jdbc.query("""
                    SELECT id,owner_jiacn,command_id,task_id,work_item_id,target_agent_id,command_type,
                        command_payload,command_payload_hash,attempt_count,active_message_id,active_attempt,
                        expires_at,tenant_id,client_id FROM agent_command_delivery ORDER BY id
                    """, (RowCallbackHandler) rs -> fingerprintRow(hash, rs));
            jdbc.query("SELECT * FROM agent_outbox_event ORDER BY id", (RowCallbackHandler) rs -> fingerprintRow(hash, rs));
            jdbc.query("SELECT * FROM agent_consumer_inbox ORDER BY id", (RowCallbackHandler) rs -> fingerprintRow(hash, rs));
            snapshot.put("sourceFingerprint", HexFormat.of().formatHex(hash.digest()));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new FixtureFailure("UR03_DIGEST_UNAVAILABLE"); }
        return snapshot;
    }

    private static void fingerprintRow(MessageDigest hash, ResultSet rs) throws SQLException {
        for (int column = 1; column <= rs.getMetaData().getColumnCount(); column++) {
            Object value = rs.getObject(column);
            byte[] bytes;
            if (value == null) bytes = new byte[]{0};
            else if (value instanceof byte[] binary) bytes = binary;
            else if (value instanceof java.sql.Blob blob) bytes = blob.getBytes(1, Math.toIntExact(blob.length()));
            else bytes = value.toString().getBytes(StandardCharsets.UTF_8);
            hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); hash.update(bytes);
        }
    }

    static byte[] sha256(String value) { return AgentCommandCanonicalCodec.sha256(value.getBytes(StandardCharsets.UTF_8)); }
    static void require(boolean condition, String code) { if (!condition) throw new FixtureFailure(code); }
    private static void emit(PrintStream pipe, Object value) { pipe.println(PREFIX + JSON.writeValueAsString(value)); pipe.flush(); }
    static final class FixtureFailure extends RuntimeException {
        final String code;
        FixtureFailure(String code) { super(code, null, false, false); this.code = code; }
    }

    /** Non-mutating H2-only observer: proves a real SQL ACK UPDATE happened before rollback. */
    public static final class AckAdvanceProbe implements Trigger {
        private int statusColumn;
        @Override public void init(Connection connection, String schemaName, String triggerName,
                String tableName, boolean before, int type) throws SQLException {
            try (var statement = connection.createStatement(); var rs = statement.executeQuery("SELECT * FROM agent_command_delivery WHERE 1=0")) {
                statusColumn = -1;
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    if ("STATUS".equalsIgnoreCase(rs.getMetaData().getColumnName(i))) statusColumn = i - 1;
                }
                if (statusColumn < 0) throw new SQLException("UR03_PROBE_COLUMN_REQUIRED", "HY000");
            }
        }
        @Override public void fire(Connection connection, Object[] oldRow, Object[] newRow) {
            if (!Objects.equals(oldRow[statusColumn], newRow[statusColumn])) ACK_UPDATES.incrementAndGet();
        }
        @Override public void close() { }
        @Override public void remove() { }
    }

    private static final class HttpFixture implements AutoCloseable {
        private final Tomcat tomcat = new Tomcat();
        private final AnnotationConfigWebApplicationContext spring = new AnnotationConfigWebApplicationContext();
        private boolean closed;
        HttpFixture(Path root) throws IOException {
            Path doc = Files.createDirectory(root.resolve("http-" + UUID.randomUUID()), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            tomcat.setBaseDir(doc.toString()); tomcat.setHostname("127.0.0.1"); tomcat.setPort(0);
            require(tomcat.getConnector().setProperty("address", "127.0.0.1"), "UR03_LOOPBACK_BIND_REQUIRED");
            Context context = tomcat.addContext("", doc.toString());
            context.setParentClassLoader(Ur03RuntimeHttpFixture.class.getClassLoader());
            spring.setServletContext(context.getServletContext());
            spring.register(HttpConfiguration.class);
            bootStage = "BOOT_MYBATIS_SPRING";
            spring.refresh();
            // Sanity check actual proxies, real provider and XML mapper before opening HTTP.
            require(org.springframework.aop.support.AopUtils.isAopProxy(spring.getBean(AgentRuntimeAuthenticationService.class))
                    && org.springframework.aop.support.AopUtils.isAopProxy(spring.getBean(AgentRuntimeV1Service.class)),
                    "UR03_TRANSACTION_PROXIES_REQUIRED");
            var servlet = Tomcat.addServlet(context, "dispatcher", new DispatcherServlet(spring));
            servlet.setLoadOnStartup(1); context.addServletMappingDecoded("/", "dispatcher");
            FilterDef definition = new FilterDef(); definition.setFilterName("runtime-security");
            definition.setFilter(new DelegatingFilterProxy("springSecurityFilterChain", spring));
            context.addFilterDef(definition);
            FilterMap mapping = new FilterMap(); mapping.setFilterName("runtime-security");
            mapping.addURLPattern("/*"); mapping.setDispatcher("REQUEST"); context.addFilterMap(mapping);
        }
        void start() throws Exception { bootStage = "BOOT_TOMCAT_START"; tomcat.start(); require(port() > 0, "UR03_BOUND_PORT_REQUIRED"); }
        int port() { return tomcat.getConnector().getLocalPort(); }
        @Override public void close() throws Exception {
            if (!closed) { closed = true; try { tomcat.stop(); } finally { try { tomcat.destroy(); } finally { spring.close(); } } }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import(AgentRuntimeSecurityConfiguration.class)
    static class HttpConfiguration {
        @Bean DataSource dataSource() { return activeDataSource; }
        @Bean DataSourceTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true); configuration.setLogImpl(NoLoggingImpl.class);
            for (Class<?> mapper : List.of(AgentRuntimeV1InstallationMapper.class, AgentRuntimeMapper.class,
                    AgentIdentityRegistryMapper.class, AgentIdentityAliasMapper.class, AgentPersonaBindingMapper.class,
                    AgentCommandRecoveryMapper.class)) configuration.addMapper(mapper);
            GlobalConfig global = new GlobalConfig(); global.setBanner(false);
            global.setIdentifierGenerator(new DefaultIdentifierGenerator());
            MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
            bean.setDataSource(source); bean.setConfiguration(configuration); bean.setGlobalConfig(global);
            // Original production XML registers InfoMapper and its security statements, not test SQL.
            bean.setMapperLocations(new ClassPathResource("cn/jia/user/mapper/InfoMapper.xml"));
            SqlSessionFactory factory = Objects.requireNonNull(bean.getObject());
            require(factory.getConfiguration().hasStatement(InfoMapper.class.getName() + ".selectSecurityByExactJiacn"),
                    "UR03_PRODUCTION_ACCOUNT_XML_REQUIRED");
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
                    new AgentRabbitSafetyProperties.RabbitBroker("isolated.invalid", 35672, "synthetic", "synthetic", "/ur03")),
                    new AgentRabbitDispatchScopeProperties(List.of(new AgentRabbitDispatchScopeProperties.AllowedScope(TENANT, CLIENT))));
        }
        @Bean AgentCommandAckService commandAcks(SqlSessionTemplate session, AgentRabbitSafetyGate gate, DataSourceTransactionManager manager) {
            return new AgentCommandAckServiceImpl(new AgentCommandRecoveryDaoImpl(session.getMapper(AgentCommandRecoveryMapper.class)), gate, manager);
        }
        @Bean AgentRuntimeV1Service runtime(AgentRuntimeV1InstallationDao installations, AgentIdentityService identity,
                AgentIdentityRegistryDao registry, ObjectProvider<AgentCommandAckService> acks, AgentRuntimeAuthenticationService authentication) {
            return new AgentRuntimeV1ServiceImpl(installations, identity, registry, acks, authentication);
        }
        @Bean AgentRuntimeV1Controller controller(AgentRuntimeV1Service runtime) { return new AgentRuntimeV1Controller(runtime); }
        @Bean SensitiveResponseProperties sensitiveProperties() { return new SensitiveResponseProperties(); }
        @Bean SensitiveResponseBodyAdvice sensitiveAdvice(SensitiveResponseProperties properties) { return new SensitiveResponseBodyAdvice(properties); }
    }

    private static <T extends BaseDaoImpl<?, ?>> T wire(T dao, Object mapper) throws Exception {
        Field field = BaseDaoImpl.class.getDeclaredField("baseMapper");
        field.setAccessible(true); field.set(dao, mapper); return dao;
    }
}
