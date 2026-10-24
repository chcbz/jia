package cn.jia.agent.mapper;

import cn.jia.agent.dao.AgentCommandV2AckReceiptDao;
import cn.jia.agent.dao.AgentCommandV2ExecutionLeaseFactDao;
import cn.jia.agent.dao.AgentRuntimeV2DispatchDao;
import cn.jia.agent.dao.AgentRuntimeV2ExecutionCommitDao;
import cn.jia.agent.dao.AgentRuntimeV2BindingDao;
import cn.jia.agent.dao.AgentRuntimeV2BindingGenerationFactDao;
import cn.jia.agent.entity.AuthenticatedRuntimeSession;
import cn.jia.agent.entity.CommandProducerScope;
import cn.jia.agent.entity.RuntimeReceiverScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeV2PersistenceContractTest {
    @Test
    void appendOnlyFactApisExposeNoMutationAndBindingAuthorityIsReceiverOnly() {
        assertAppendOnly(AgentCommandV2AckReceiptMapper.class);
        assertAppendOnly(AgentCommandV2ExecutionLeaseFactMapper.class);
        assertAppendOnly(AgentCommandV2ExecutionGrantMapper.class);
        assertAppendOnly(AgentCommandV2ExecutionContinuationAnchorMapper.class);
        assertAppendOnly(AgentCommandV2ExecutionRequestMapper.class);
        assertAppendOnly(AgentRuntimeV2BindingGenerationFactMapper.class);
        assertSplitScoped(AgentCommandV2AckReceiptDao.class);
        assertSplitScoped(AgentCommandV2ExecutionLeaseFactDao.class);
        assertSplitScoped(AgentRuntimeV2DispatchDao.class);
        assertSplitScoped(AgentRuntimeV2ExecutionCommitDao.class);
        assertDaoScoped(AgentRuntimeV2BindingDao.class, RuntimeReceiverScope.class);
        assertDaoScoped(AgentRuntimeV2BindingGenerationFactDao.class, RuntimeReceiverScope.class);
        for (Method method : AgentRuntimeV2BindingDao.class.getDeclaredMethods()) {
            assertFalse(Arrays.asList(method.getParameterTypes()).contains(CommandProducerScope.class));
        }
        for (Method method : AgentRuntimeV2BindingGenerationFactDao.class.getDeclaredMethods()) {
            assertFalse(Arrays.asList(method.getParameterTypes()).contains(CommandProducerScope.class));
        }
    }

    @Test
    void producerReceiverAndSessionTypesRejectAliasesAndMalformedUnicode() {
        CommandProducerScope producer = new CommandProducerScope(
                TenantId.of("1"), DataDomainId.of("cyf"), "pool-a", "caller-a",
                "所有者-甲", "智能体-😀", "producer-a", 11, "agent-command", 13);
        RuntimeReceiverScope receiver = new RuntimeReceiverScope(
                TenantId.of("1"), DataDomainId.of("cyf"), "pool-a", "credential-r",
                "所有者-甲", "智能体-😀", "claimant-b", 7, 17, 11,
                "agent-command", 13);
        AuthenticatedRuntimeSession session = new AuthenticatedRuntimeSession(
                "00000000-0000-4000-8000-000000000001", 0,
                "运行实例-😀", "会话栅栏-甲", "副本-甲", 3);
        assertNotEquals(producer.commandCallerClientId(), receiver.runtimeCredentialClientId());
        assertTrue(session.runtimeInstanceId().contains("😀"));
        assertFalse(CommandProducerScope.class.isAssignableFrom(RuntimeReceiverScope.class));
        assertFalse(RuntimeReceiverScope.class.isAssignableFrom(CommandProducerScope.class));

        assertThrows(IllegalArgumentException.class, () -> receiver("credential-r "));
        assertThrows(IllegalArgumentException.class, () -> receiver("credential\n-r"));
        assertThrows(IllegalArgumentException.class, () -> receiver("\uD83D"));
        assertThrows(IllegalArgumentException.class, () -> receiver("\uDE00"));
        assertThrows(IllegalArgumentException.class, () -> new AuthenticatedRuntimeSession(
                "00000000-0000-4000-8000-000000000001", 0,
                "runtime", "\uD83D", "replica", 0));
        assertDoesNotThrow(() -> receiver("凭据-😀"));
    }

    @Test
    void trustedAuthorityScopeRejectsAdjacentAliasesAndNoncanonicalFences() {
        CommandProducerScope scope = producer("1", "cyf", "pool-a", "client-a");
        assertTrue(scope.tenantId().value().equals("1"));
        assertThrows(IllegalArgumentException.class,
                () -> producer("01", "cyf", "pool-a", "client-a"));
        assertThrows(IllegalArgumentException.class,
                () -> producer("1", "CYF", "pool-a", "client-a"));
        assertThrows(IllegalArgumentException.class,
                () -> producer("1", "cyf", "Pool-a", "client-a"));
        assertThrows(IllegalArgumentException.class,
                () -> producer("1", "cyf", "pool-a", "client-a "));
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeReceiverScope(TenantId.of("1"), DataDomainId.of("cyf"),
                        "pool-a", "credential", "owner-a", "agent-a", "claimant-a",
                        -1, 17, 11, "agent-command", 13));
    }

    @Test
    void manualMigrationIsAdditiveExplicitAndDoesNotSynthesizeAuthority() throws Exception {
        String sql = Files.readString(sqlPath());
        String executable = Arrays.stream(sql.split("\\R"))
                .filter(line -> !line.stripLeading().startsWith("--"))
                .reduce("", (left, right) -> left + "\n" + right)
                .toLowerCase(Locale.ROOT);
        assertTrue(executable.contains("drop check chk_inbox_consumer_v2_ack_evidence"));
        assertTrue(executable.contains("add constraint chk_inbox_consumer_v2_ack_evidence"));
        assertTrue(executable.contains("ack_runtime_installation_id is null"));
        assertTrue(executable.contains("create table agent_runtime_v2_binding"));
        assertTrue(executable.contains("create table agent_runtime_v2_binding_generation_fact"));
        assertTrue(executable.contains("create table agent_command_v2_ack_receipt"));
        assertTrue(executable.contains("create table agent_command_v2_execution_lease_fact"));
        assertTrue(executable.contains("create table agent_command_v2_execution_grant"));
        assertTrue(executable.contains("create table agent_command_v2_execution_continuation_anchor"));
        assertTrue(executable.contains("create table agent_command_v2_execution_request"));
        assertTrue(executable.contains("producer_deployment_id varchar(100) collate utf8mb4_0900_bin null"));
        assertTrue(executable.contains("unique key uk_binding_generation_fact_sequence"));
        assertTrue(executable.contains("unique key uk_binding_generation_fact_event"));
        assertTrue(executable.contains("unique key uk_binding_generation_fact_predecessor"));
        assertTrue(executable.contains("unique key uk_binding_generation_fact_revoke"));
        assertTrue(executable.contains("unique key uk_ack_receipt_ack_message"));
        assertTrue(executable.contains("unique key uk_ack_receipt_dispatch_status"));
        assertTrue(executable.contains("unique key uk_execution_lease_fact_sequence"));
        assertTrue(executable.contains("unique key uk_execution_grant_revision_kind"));
        assertTrue(executable.contains("unique key uk_execution_continuation_anchor"));
        assertTrue(executable.contains("unique key uk_execution_continuation_grant"));
        assertFalse(executable.contains("unique key uk_execution_grant_anchor"));
        assertFalse(executable.matches("(?s).*\\bupdate\\s+agent_.*"));
        assertFalse(executable.matches("(?s).*\\bdelete\\s+from\\s+.*"));
        assertFalse(executable.contains("insert into agent_runtime_v2_binding"));
        assertFalse(executable.contains("insert into agent_runtime_v2_binding_generation_fact"));
        assertFalse(executable.contains("backfill"));
    }

    @Test
    void productionGenerationFactDdlParsesInH2MySqlMode() throws Exception {
        String ddl = createTable(Files.readString(sqlPath()),
                "agent_runtime_v2_binding_generation_fact")
                .replaceAll("(?i) COLLATE utf8mb4_0900_bin", "")
                .replace(" STORED", "")
                .replaceAll("(?i)\\) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;$", ");");
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setUrl("jdbc:h2:mem:runtime_v2_ddl_" + UUID.randomUUID()
                + ";MODE=MYSQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
        source.setUsername("sa");
        source.setPassword("");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        assertDoesNotThrow(() -> jdbc.execute(ddl));
        assertTrue(jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_runtime_v2_binding_generation_fact", Integer.class) == 0);
    }

    private static Path sqlPath() {
        return Path.of(System.getProperty("user.dir"), "..", "jia-agent-mapper",
                "src", "main", "resources", "db",
                "agent-runtime-v2-persistence-manual-20260930.sql").normalize();
    }

    private static String createTable(String sql, String table) {
        String marker = "CREATE TABLE " + table + " (";
        int start = sql.indexOf(marker);
        if (start < 0) throw new IllegalArgumentException("missing table " + table);
        int end = sql.indexOf(';', start);
        if (end < 0) throw new IllegalArgumentException("unterminated table " + table);
        return sql.substring(start, end + 1);
    }

    private static void assertAppendOnly(Class<?> mapper) {
        for (Method method : mapper.getDeclaredMethods()) {
            assertFalse(method.isAnnotationPresent(Update.class), mapper + " exposes UPDATE");
            assertFalse(method.isAnnotationPresent(Delete.class), mapper + " exposes DELETE");
            assertFalse(method.getName().toLowerCase(Locale.ROOT).matches(".*(update|delete|remove).*"));
        }
    }

    private static void assertDaoScoped(Class<?> dao, Class<?> expectedScope) {
        for (Method method : dao.getDeclaredMethods()) {
            assertTrue(method.getParameterCount() > 0
                    && method.getParameterTypes()[0] == expectedScope,
                    () -> dao.getSimpleName() + "." + method.getName() + " is unscoped");
            assertFalse(method.getName().toLowerCase(Locale.ROOT).matches(".*(update|delete|remove).*"));
        }
    }

    private static void assertSplitScoped(Class<?> dao) {
        for (Method method : dao.getDeclaredMethods()) {
            assertTrue(method.getParameterCount() >= 2
                            && method.getParameterTypes()[0] == CommandProducerScope.class
                            && method.getParameterTypes()[1] == RuntimeReceiverScope.class,
                    () -> dao.getSimpleName() + "." + method.getName() + " is not split scoped");
            assertFalse(method.getName().toLowerCase(Locale.ROOT).matches(".*(update|delete|remove).*"));
        }
    }

    private static RuntimeReceiverScope receiver(String client) {
        return new RuntimeReceiverScope(TenantId.of("1"), DataDomainId.of("cyf"),
                "pool-a", client, "owner-a", "agent-a", "claimant-a",
                7, 17, 11, "agent-command", 13);
    }

    private static CommandProducerScope producer(String tenant, String domain,
            String pool, String client) {
        return new CommandProducerScope(TenantId.of(tenant), DataDomainId.of(domain),
                pool, client, "owner-a", "agent-a", "producer-a",
                11, "agent-command", 13);
    }
}
