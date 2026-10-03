package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentOutboxCandidate;
import cn.jia.agent.mapper.AgentOutboxRelayMapper;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in MySQL 8.4 production-mapper coverage for the Unicode-control discovery predicate.
 * The fixed CYF_H02 database is destructive test state; this class never creates or drops a database.
 */
@EnabledIfEnvironmentVariable(named = "CYF_H02_MYSQL_URL", matches = ".+")
class AgentOutboxRelayMapperMySqlTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final long EXPIRES = NOW + 3_600_000L;
    private static final List<String> TRANSPORT_TABLES_IN_DROP_ORDER = List.of(
            "agent_command_redrive_operation",
            "agent_command_operation_audit",
            "agent_consumer_inbox",
            "agent_outbox_event",
            "agent_command_delivery");

    private JdbcTemplate jdbc;
    private AgentOutboxRelayMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        Target target = requireDisposableTarget(System.getenv());
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(target.jdbcUrl());
        source.setUsername(required("CYF_H02_MYSQL_USER"));
        source.setPassword(required("CYF_H02_MYSQL_PASSWORD"));
        jdbc = new JdbcTemplate(source);

        assertEquals(target.databaseName(), jdbc.queryForObject("SELECT DATABASE()", String.class));
        Integer port = jdbc.queryForObject("SELECT @@port", Integer.class);
        assertNotNull(port);
        assertTrue(port >= 1024 && port != 3306 && port != 33060, "unsafe MySQL port");

        dropTransportTables();
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/agent-command-transport-schema.sql"));
        }

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentOutboxRelayMapper.class);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(source);
        factoryBean.setConfiguration(configuration);
        factoryBean.setGlobalConfig(globalConfig);
        SqlSessionFactory factory = factoryBean.getObject();
        assertNotNull(factory);
        mapper = new SqlSessionTemplate(factory).getMapper(AgentOutboxRelayMapper.class);
    }

    @AfterEach
    void tearDown() {
        if (jdbc != null) dropTransportTables();
    }

    @Test
    void utf8ControlPatternDiscoversDueStaleAndCorruptRowsWithExactScopeAndOwnerRoots() {
        DataAccessException invalidTenant = assertThrows(
                DataAccessException.class,
                () -> insertDeliveryWithTenant(99, "command-invalid-tenant", "owner-a",
                        "message-invalid-tenant", "tenant-a", 0));
        Throwable rootCause = invalidTenant.getMostSpecificCause();
        assertTrue(rootCause instanceof SQLException, rootCause.toString());
        SQLException sqlFailure = (SQLException) rootCause;
        assertEquals(3819, sqlFailure.getErrorCode());
        assertTrue(sqlFailure.getMessage().contains("chk_delivery_single_tenant"),
                sqlFailure.getMessage());

        insertDelivery(101, "command-shared", "owner-a", "message-due", 0);
        insertOutbox(1, 101, "event-due", "message-due", "command-shared",
                "PENDING", "0", "client-a", 0, null, NOW - 20);

        insertDelivery(102, "command-stale", "owner-a", "message-stale", 0);
        insertOutbox(2, 102, "event-stale", "message-stale", "command-stale",
                "CLAIMED", "0", "client-a", 0, NOW - 10, NOW - 30);

        insertOutbox(3, 101, "event-empty", "message-empty", "command-shared",
                "PENDING", "0", "", 0, null, NOW - 19);
        insertOutbox(4, 101, "event-blank", "message-blank", "command-shared",
                "PENDING", "0", " ", 0, null, NOW - 18);
        insertOutbox(5, 101, "event-control", "message-control", "command-shared",
                "PENDING", "0", "client-\u0085", 0, null, NOW - 17);
        insertOutbox(6, 101, "event-outbox-version", "message-outbox-version", "command-shared",
                "PENDING", "0", "client-a", Long.MAX_VALUE - 1, null, NOW - 16);

        insertDelivery(107, "command-delivery-version", "owner-a", "message-delivery-version",
                Long.MAX_VALUE - 1);
        insertOutbox(7, 107, "event-delivery-version", "message-delivery-version",
                "command-delivery-version", "PENDING", "0", "client-a",
                0, null, NOW - 15);

        insertDelivery(108, "command-scope", "owner-a", "message-scope", 0);
        insertOutbox(8, 108, "event-foreign-client", "message-foreign-client", "command-scope",
                "PENDING", "0", "client-b", 0, null, NOW - 14);
        insertOutbox(9, 108, "event-client-case", "message-client-case", "command-scope",
                "PENDING", "0", "Client-A", 0, null, NOW - 13);

        // The same command id is legal for another authenticated owner. Discovery is global,
        // but the candidate remains bound to that owner's exact delivery id rather than being
        // inferred from command id or another owner's row.
        insertDelivery(110, "command-shared", "owner-b", "message-owner-b", 0);
        insertOutbox(10, 110, "event-owner-b", "message-owner-b", "command-shared",
                "PENDING", "0", "client-a", 0, null, NOW - 12);

        List<AgentOutboxCandidate> due = mapper.selectDueCandidates(NOW, 20);
        assertEquals(List.of(1L, 10L), ids(due));
        assertEquals(List.of(101L, 110L),
                due.stream().map(AgentOutboxCandidate::deliveryId).toList());
        assertEquals(List.of(2L), ids(mapper.selectStaleCandidates(NOW, 20)));
        assertEquals(List.of(3L, 4L, 5L, 6L, 7L, 8L, 9L),
                ids(mapper.selectCorruptCandidates(NOW, 20)));

        assertEquals(List.of("owner-a", "owner-b"), jdbc.queryForList("""
                SELECT owner_jiacn FROM agent_command_delivery
                WHERE command_id='command-shared' ORDER BY owner_jiacn
                """, String.class));
    }

    private void insertDelivery(long id, String commandId, String owner, String messageId,
                                long version) {
        insertDeliveryWithTenant(id, commandId, owner, messageId, "0", version);
    }

    private void insertDeliveryWithTenant(long id, String commandId, String owner, String messageId,
                                          String tenantId, long version) {
        jdbc.update("""
                INSERT INTO agent_command_delivery(
                    id,command_id,owner_jiacn,task_id,target_agent_id,command_type,
                    command_payload,command_payload_hash,status,attempt_count,next_retry_at,
                    active_message_id,active_attempt,expires_at,version,
                    tenant_id,client_id,create_time,update_time)
                VALUES (?,?,?,?,?,'ARCHIVE_MAINTENANCE',X'01',UNHEX(REPEAT('00',32)),
                        'PENDING',0,?,?,?,?,?,?,'client-a',?,?)
                """, id, commandId, owner, "task-" + id, "agent-" + id,
                NOW - 1, messageId, 1, EXPIRES, version, tenantId, NOW - 100, NOW - 100);
    }

    private void insertOutbox(long id, long deliveryId, String eventId, String messageId,
                              String commandId, String status, String tenantId, String clientId,
                              long version, Long leaseUntil, long createTime) {
        jdbc.update("""
                INSERT INTO agent_outbox_event(
                    id,event_id,message_id,command_id,delivery_id,aggregate_type,aggregate_id,
                    destination,routing_key,wire_payload,wire_payload_hash,status,attempt_count,
                    next_retry_at,lease_owner,lease_until,active_attempt,expires_at,
                    publisher_confirm_status,mandatory_return_status,version,
                    tenant_id,client_id,create_time,update_time)
                VALUES (?,?,?,?,?,'task',?,'agent.command','agent.command',X'02',
                        UNHEX(REPEAT('11',32)),?,0,NULL,?,?,1,?,'NONE','NONE',?,?,?,?,?)
                """, id, eventId, messageId, commandId, deliveryId, "task-" + deliveryId,
                status, leaseUntil == null ? null : "worker-expired", leaseUntil, EXPIRES,
                version, tenantId, clientId, createTime, createTime);
    }

    private void dropTransportTables() {
        for (String table : TRANSPORT_TABLES_IN_DROP_ORDER) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    private static List<Long> ids(List<AgentOutboxCandidate> candidates) {
        return candidates.stream().map(AgentOutboxCandidate::outboxId).toList();
    }

    private static Target requireDisposableTarget(Map<String, String> environment) {
        require("true".equals(environment.get("CYF_H02_MYSQL_ISOLATED")),
                "CYF_H02_MYSQL_ISOLATED must be exactly true");
        String jdbcUrl = environment.get("CYF_H02_MYSQL_URL");
        require(jdbcUrl != null && jdbcUrl.startsWith("jdbc:mysql://"),
                "CYF_H02_MYSQL_URL must use jdbc:mysql://");
        URI uri;
        try {
            uri = new URI("mysql://" + jdbcUrl.substring("jdbc:mysql://".length()));
        } catch (URISyntaxException failure) {
            throw new IllegalStateException("Unsafe CYF_H02 MySQL target: malformed URL", failure);
        }
        require(uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null,
                "CYF_H02_MYSQL_URL must not contain credentials, query, or fragment");
        require("127.0.0.1".equals(uri.getHost()) || "::1".equals(uri.getHost()),
                "CYF_H02_MYSQL_URL must use an IP-literal loopback host");
        require(uri.getPort() >= 1024 && uri.getPort() != 3306 && uri.getPort() != 33060,
                "CYF_H02_MYSQL_URL must use an explicit non-production port");
        String path = uri.getPath();
        require(path != null && path.matches("/cyf_h02_[a-z0-9_]{1,56}"),
                "CYF_H02_MYSQL_URL must name one fixed cyf_h02_ database");
        String databaseName = path.substring(1);
        require(databaseName.equals(environment.get("CYF_H02_MYSQL_DATABASE_CONFIRM")),
                "CYF_H02_MYSQL_DATABASE_CONFIRM must exactly match the URL database");
        String user = environment.get("CYF_H02_MYSQL_USER");
        require(user != null && !user.isBlank() && !"root".equalsIgnoreCase(user),
                "CYF_H02_MYSQL_USER must be an explicit non-root account");
        require(environment.get("CYF_H02_MYSQL_PASSWORD") != null
                        && !environment.get("CYF_H02_MYSQL_PASSWORD").isBlank(),
                "CYF_H02_MYSQL_PASSWORD must be explicit");
        return new Target(jdbcUrl, databaseName);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Unsafe CYF_H02 MySQL target: " + message);
    }

    private record Target(String jdbcUrl, String databaseName) {
    }
}
