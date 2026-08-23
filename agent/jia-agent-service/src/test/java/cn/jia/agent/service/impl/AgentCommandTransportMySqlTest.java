package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.config.AgentCommandTransportSchemaInitializer;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitSafetyProperties;
import cn.jia.agent.dao.impl.AgentCommandTransportDaoImpl;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentCommandTransportWriteResult;
import cn.jia.agent.entity.AgentTaskInvitePayload;
import cn.jia.agent.mapper.AgentCommandTransportMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D02 transaction, failure, idempotency, and lock-order evidence on isolated MySQL 8.0.21. */
@EnabledIfEnvironmentVariable(named = "D02_MYSQL_URL", matches = ".+")
class AgentCommandTransportMySqlTest {
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-1";
    private static final String TARGET = "agent-1";
    private static final String EVENT = "evt-real-task-assigned";
    private static final long OCCURRED_AT = 1_700_000_000_000L;

    private String databaseName;
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private AgentCommandTransportWriterImpl writer;

    @BeforeEach
    void setUp() throws Exception {
        String baseUrl = requiredEnvironment("D02_MYSQL_URL");
        String username = requiredEnvironment("D02_MYSQL_USER");
        String password = requiredEnvironment("D02_MYSQL_PASSWORD");
        int expectedPort = Integer.parseInt(requiredEnvironment("D02_MYSQL_EXPECTED_PORT"));
        String expectedDatadir = requiredEnvironment("D02_MYSQL_EXPECTED_DATADIR");
        assertTrue(expectedPort != 3306, "D02 runner must never use the production MySQL port");
        assertTrue(baseUrl.startsWith("jdbc:mysql://127.0.0.1:" + expectedPort + "/"), baseUrl);

        DriverManagerDataSource adminSource = dataSource(baseUrl, username, password);
        admin = new JdbcTemplate(adminSource);
        String version = admin.queryForObject("SELECT VERSION()", String.class);
        Integer port = admin.queryForObject("SELECT @@port", Integer.class);
        String datadir = admin.queryForObject("SELECT @@datadir", String.class);
        assertTrue(version != null && version.startsWith("8.0.21"), version);
        assertEquals(expectedPort, port);
        assertTrue(datadir != null && datadir.startsWith(expectedDatadir + "/"), datadir);
        System.out.println("D02_MYSQL_VERSION=" + version);
        System.out.println("D02_MYSQL_PORT=" + port);
        System.out.println("D02_MYSQL_DATADIR=" + datadir);

        databaseName = "d02_transaction_" + Long.toUnsignedString(System.nanoTime());
        admin.execute("CREATE DATABASE `" + databaseName
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        DriverManagerDataSource source = dataSource(databaseUrl(baseUrl, databaseName), username, password);
        jdbc = new JdbcTemplate(source);
        executeResource("d02/task-domain-fixture.sql");
        executeResource("db/task-event-schema.sql");
        new AgentCommandTransportSchemaInitializer(jdbc).afterPropertiesSet();

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentCommandTransportMapper.class);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(source);
        bean.setConfiguration(configuration);
        bean.setGlobalConfig(globalConfig);
        SqlSessionFactory factory = bean.getObject();
        assertNotNull(factory);
        AgentCommandTransportMapper mapper = new SqlSessionTemplate(factory)
                .getMapper(AgentCommandTransportMapper.class);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
        transactions = new TransactionTemplate(manager);
        AtomicLong sequence = new AtomicLong();
        writer = new AgentCommandTransportWriterImpl(
                new AgentCommandTransportDaoImpl(mapper), gate(), manager,
                () -> new UUID(0L, sequence.incrementAndGet()));
        insertOpenTask();
    }

    @AfterEach
    void tearDown() {
        if (admin != null && databaseName != null) {
            admin.execute("DROP DATABASE IF EXISTS `" + databaseName + "`");
        }
    }

    @Test
    void successCommitsTaskDomainEventDeliveryAndOutboxTogether() {
        AgentCommandTransportWriteResult result = assignIfChanged(draft("Task One"));

        assertFalse(result.duplicate());
        assertCommittedFacts();
        assertEquals(EVENT, jdbc.queryForObject(
                "SELECT event_id FROM agent_task_event", String.class));
        assertArrayEquals(AgentCommandCanonicalCodec.businessBytes(draft("Task One")),
                jdbc.queryForObject("SELECT command_payload FROM agent_command_delivery", byte[].class));
        assertEquals("DEAD", jdbc.queryForObject(
                "SELECT status FROM agent_command_delivery", String.class));
        assertEquals(AgentCommandTransportWriterImpl.DB_SHADOW_MARKER, jdbc.queryForObject(
                "SELECT last_error FROM agent_outbox_event", String.class));
    }

    @Test
    void taskEventTriggerFailureRollsBackAllFourFactClasses() {
        failBeforeInsert("d02_fail_event", "agent_task_event", "D02_EVENT_FAILURE");

        assertDatabaseFailure("D02_EVENT_FAILURE",
                () -> assignIfChanged(draft("Task One")));

        assertRolledBackFacts();
    }

    @Test
    void deliveryTriggerFailureRollsBackDomainAndEventAndTransport() {
        failBeforeInsert("d02_fail_delivery", "agent_command_delivery", "D02_DELIVERY_FAILURE");

        assertDatabaseFailure("D02_DELIVERY_FAILURE",
                () -> assignIfChanged(draft("Task One")));

        assertRolledBackFacts();
    }

    @Test
    void outboxTriggerFailureRollsBackDomainEventAndDelivery() {
        failBeforeInsert("d02_fail_outbox", "agent_outbox_event", "D02_OUTBOX_FAILURE");

        assertDatabaseFailure("D02_OUTBOX_FAILURE",
                () -> assignIfChanged(draft("Task One")));

        assertRolledBackFacts();
    }

    @Test
    void exactDuplicateUnderTaskRootLockAddsNoSecondOutbox() {
        assignIfChanged(draft("Task One"));

        AgentCommandTransportWriteResult retry = retryUnderTaskRootLock(draft("Task One"));

        assertTrue(retry.duplicate());
        assertCommittedFacts();
    }

    @Test
    void sameCommandIdWithChangedCanonicalBytesFailsClosed() {
        assignIfChanged(draft("Task One"));
        byte[] original = jdbc.queryForObject(
                "SELECT command_payload FROM agent_command_delivery", byte[].class);

        assertThrows(IllegalStateException.class,
                () -> retryUnderTaskRootLock(draft("Changed title")));

        assertCommittedFacts();
        assertArrayEquals(original, jdbc.queryForObject(
                "SELECT command_payload FROM agent_command_delivery", byte[].class));
    }

    @Test
    void concurrentSameTaskAndCommandSerializesAtRootWithoutDeadlockOrDuplicates() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> concurrentAssign(ready, start));
            Future<Boolean> second = executor.submit(() -> concurrentAssign(ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS), "workers did not become ready");
            start.countDown();
            boolean firstChanged = first.get(15, TimeUnit.SECONDS);
            boolean secondChanged = second.get(15, TimeUnit.SECONDS);
            assertEquals(1, (firstChanged ? 1 : 0) + (secondChanged ? 1 : 0));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertCommittedFacts();
    }

    private AgentCommandTransportWriteResult assignIfChanged(AgentCommandDraft draft) {
        return transactions.execute(status -> {
            String rewardStatus = lockTaskRoot();
            if (!"open".equals(rewardStatus)) {
                return null;
            }
            mutateDomainAndAppendRealEvent();
            return writer.write(draft);
        });
    }

    private boolean concurrentAssign(CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS));
        Boolean changed = transactions.execute(status -> {
            String rewardStatus = lockTaskRoot();
            if (!"open".equals(rewardStatus)) {
                return false;
            }
            mutateDomainAndAppendRealEvent();
            writer.write(draft("Task One"));
            return true;
        });
        return Boolean.TRUE.equals(changed);
    }

    private AgentCommandTransportWriteResult retryUnderTaskRootLock(AgentCommandDraft draft) {
        return transactions.execute(status -> {
            assertEquals("assigned", lockTaskRoot());
            return writer.write(draft);
        });
    }

    private String lockTaskRoot() {
        return jdbc.queryForObject("""
                SELECT reward_status FROM agent_task_meta
                WHERE tenant_id=? AND client_id=? AND task_id=?
                FOR UPDATE
                """, String.class, TENANT, CLIENT, TASK);
    }

    private void mutateDomainAndAppendRealEvent() {
        assertEquals(1, jdbc.update("""
                UPDATE agent_task_meta
                SET reward_status='assigned', assigned_agent_id=?, assigned_at=?,
                    task_version=task_version+1, current_event_version=current_event_version+1,
                    update_time=?
                WHERE tenant_id=? AND client_id=? AND task_id=? AND reward_status='open'
                """, TARGET, OCCURRED_AT, OCCURRED_AT, TENANT, CLIENT, TASK));
        assertEquals(1, jdbc.update("""
                INSERT INTO agent_task_event(
                    task_id,event_version,event_id,event_type,actor_type,actor_id,
                    aggregate_type,aggregate_id,event_json,occurred_at,
                    tenant_id,client_id,create_time,update_time)
                VALUES (?,1,?,'TASK_ASSIGNED','agent',?,'task',?,
                    '{"targetAgentIds":["agent-1"]}',?,?,?,?,?)
                """, TASK, EVENT, TARGET, TASK, OCCURRED_AT,
                TENANT, CLIENT, OCCURRED_AT, OCCURRED_AT));
    }

    private void insertOpenTask() {
        assertEquals(1, jdbc.update("""
                INSERT INTO agent_task_meta(
                    task_id,reward_status,collaboration_mode,risk_level,max_agents,
                    review_required,task_version,current_event_version,
                    tenant_id,client_id,create_time,update_time)
                VALUES (?,'open','single','low',1,0,0,0,?,?,?,?)
                """, TASK, TENANT, CLIENT, OCCURRED_AT - 1, OCCURRED_AT - 1));
    }

    private void failBeforeInsert(String trigger, String table, String message) {
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON " + table
                + " FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='" + message + "'");
    }

    private void assertCommittedFacts() {
        assertEquals("assigned", jdbc.queryForObject(
                "SELECT reward_status FROM agent_task_meta", String.class));
        assertEquals(TARGET, jdbc.queryForObject(
                "SELECT assigned_agent_id FROM agent_task_meta", String.class));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT task_version FROM agent_task_meta", Long.class));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT current_event_version FROM agent_task_meta", Long.class));
        assertEquals(1, count("agent_task_meta"));
        assertEquals(1, count("agent_task_event"));
        assertEquals(1, count("agent_command_delivery"));
        assertEquals(1, count("agent_outbox_event"));
        assertEquals(0, count("agent_consumer_inbox"));
    }

    private void assertRolledBackFacts() {
        assertEquals("open", jdbc.queryForObject(
                "SELECT reward_status FROM agent_task_meta", String.class));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT task_version FROM agent_task_meta", Long.class));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT current_event_version FROM agent_task_meta", Long.class));
        assertEquals(1, count("agent_task_meta"));
        assertEquals(0, count("agent_task_event"));
        assertEquals(0, count("agent_command_delivery"));
        assertEquals(0, count("agent_outbox_event"));
        assertEquals(0, count("agent_consumer_inbox"));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private AgentCommandDraft draft(String title) {
        String commandId = AgentCommandCanonicalCodec.taskInviteCommandId(
                TENANT, CLIENT, TASK, TARGET);
        return new AgentCommandDraft(
                AgentCommandCanonicalCodec.SCHEMA_VERSION, commandId, TASK, EVENT,
                TENANT, CLIENT, TASK, null, TARGET,
                AgentProtocolConstants.COMMAND_TASK_INVITE,
                OCCURRED_AT, OCCURRED_AT + AgentCommandCanonicalCodec.TASK_INVITE_TTL_MILLIS,
                new AgentTaskInvitePayload(
                        "task_briefing", "宋江首领已完成悬赏分派，请按职责协作推进。",
                        "阅读悬赏任务，确认自己的职责；如需协助，优先参考协作名册中的好汉能力并回报下一步计划。",
                        title, List.of("planning"), TARGET, List.of(TARGET), "coordinator",
                        "回报执行计划、风险和协助诉求；Protocol v1 使用 work.progress，完成后使用 work.result，旧客户端由兼容层处理。",
                        "juyiting"));
    }

    private AgentRabbitSafetyGate gate() {
        return new AgentRabbitSafetyGate(new AgentRabbitSafetyProperties(
                new AgentRabbitSafetyProperties.CommandOutbox(true),
                new AgentRabbitSafetyProperties.RabbitTopology(false),
                new AgentRabbitSafetyProperties.RabbitPublish(false),
                new AgentRabbitSafetyProperties.RabbitConsume(false),
                new AgentRabbitSafetyProperties.RabbitDispatch(false), null));
    }

    private void executeResource(String resource) throws Exception {
        String sql;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException(resource + " missing");
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String statement : splitSql(sql)) {
            jdbc.execute(statement);
        }
    }

    private List<String> splitSql(String sql) {
        java.util.ArrayList<String> statements = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean lineComment = false;
        for (int index = 0; index < sql.length(); index++) {
            char character = sql.charAt(index);
            if (lineComment) {
                if (character == '\n' || character == '\r') {
                    lineComment = false;
                    current.append(' ');
                }
                continue;
            }
            if (!quoted && character == '-' && index + 1 < sql.length()
                    && sql.charAt(index + 1) == '-') {
                lineComment = true;
                index++;
                continue;
            }
            if (character == '\'' && (index == 0 || sql.charAt(index - 1) != '\\')) {
                quoted = !quoted;
            }
            if (character == ';' && !quoted) {
                String statement = current.toString().trim();
                if (!statement.isEmpty()) statements.add(statement);
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) statements.add(tail);
        return List.copyOf(statements);
    }

    private void assertDatabaseFailure(String marker, Runnable action) {
        RuntimeException failure = assertThrows(RuntimeException.class, action::run);
        Throwable cause = failure;
        StringBuilder messages = new StringBuilder();
        while (cause != null) {
            if (cause.getMessage() != null) messages.append(cause.getMessage()).append('\n');
            cause = cause.getCause();
        }
        assertTrue(messages.toString().contains(marker), messages.toString());
    }

    private DriverManagerDataSource dataSource(String url, String username, String password) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(url);
        source.setUsername(username);
        source.setPassword(password);
        return source;
    }

    private String databaseUrl(String baseUrl, String database) {
        int query = baseUrl.indexOf('?');
        String prefix = query < 0 ? baseUrl : baseUrl.substring(0, query);
        String suffix = query < 0 ? "" : baseUrl.substring(query);
        int slash = prefix.indexOf('/', "jdbc:mysql://".length());
        return prefix.substring(0, slash + 1) + database + suffix;
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
