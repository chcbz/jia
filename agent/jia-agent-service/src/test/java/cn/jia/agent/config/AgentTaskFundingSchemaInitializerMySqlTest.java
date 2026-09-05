package cn.jia.agent.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Isolated MySQL 8.0.21 catalog and nullable-receipt regression sources for ECO-V0-W04. */
@EnabledIfEnvironmentVariable(named = "ECO_V0_W02_MYSQL_URL", matches = ".+")
class AgentTaskFundingSchemaInitializerMySqlTest {
    private JdbcTemplate admin;
    private String baseUrl;
    private String username;
    private String password;
    private String prefix;
    private final List<String> databases = new ArrayList<>();
    private final AtomicInteger sequence = new AtomicInteger();

    @BeforeEach
    void setUp() {
        baseUrl = required("ECO_V0_W02_MYSQL_URL");
        username = required("ECO_V0_W02_MYSQL_USER");
        password = present("ECO_V0_W02_MYSQL_PASSWORD");
        prefix = required("ECO_V0_W02_MYSQL_DATABASE_PREFIX");
        if (!"true".equals(required("ECO_V0_W02_MYSQL_ISOLATED_FIXTURE"))) {
            throw new IllegalStateException("isolated fixture acknowledgement is required");
        }
        admin = new JdbcTemplate(dataSource(baseUrl));
        String version = admin.queryForObject("SELECT VERSION()", String.class);
        assertTrue(version != null && version.startsWith("8.0.21"), version);
    }

    @AfterEach
    void tearDown() {
        if (admin == null) return;
        for (int index = databases.size() - 1; index >= 0; index--) {
            String database = databases.get(index);
            if (!database.startsWith(prefix + "_w04_")) {
                throw new IllegalStateException("refusing to drop unowned database");
            }
            admin.execute("DROP DATABASE IF EXISTS `" + database + "`");
        }
    }

    @Test
    void pristineAndRepeatedInitializationAcceptsMySqlCompoundCheckFormatting() {
        JdbcTemplate jdbc = database("repeat");
        AgentTaskFundingSchemaInitializer initializer = new AgentTaskFundingSchemaInitializer(jdbc);

        initializer.afterPropertiesSet();
        List<String> first = definitions(jdbc);
        initializer.afterPropertiesSet();

        assertEquals(first, definitions(jdbc));
        assertEquals(AgentTaskFundingSchemaInitializer.normalizeCheck(
                        "((status = 'POSTING') AND (reserve_transaction_id IS NULL)) OR "
                                + "((status = 'COMPLETED') AND (receipt_task_version IS NOT NULL))"),
                AgentTaskFundingSchemaInitializer.normalizeCheck(
                        "((`status` = _utf8mb4'POSTING') and (`reserve_transaction_id` is null)) "
                                + "or ((`status` = _utf8mb4'COMPLETED') "
                                + "and (`receipt_task_version` is not null))"));
    }

    @Test
    void notEnforcedCheckAndIncompatibleIndexPropertiesFailWithoutRepair() {
        assertCatalogDrift("unenforced", ddl -> ddl.replace(
                "CONSTRAINT chk_task_funding_operation_hash CHECK (OCTET_LENGTH(request_hash) = 32)",
                "CONSTRAINT chk_task_funding_operation_hash CHECK (OCTET_LENGTH(request_hash) = 32) NOT ENFORCED"));
        String payerIndex = "KEY idx_agent_task_funding_payer\n"
                + "        (tenant_id, client_id, payer_principal_type, payer_principal_id, funding_status, id)";
        assertCatalogDrift("prefix", ddl -> ddl.replace(payerIndex,
                "KEY idx_agent_task_funding_payer\n"
                        + "        (tenant_id(10), client_id, payer_principal_type, "
                        + "payer_principal_id, funding_status, id)"));
        assertCatalogDrift("invisible", ddl -> ddl.replace(payerIndex,
                payerIndex + " INVISIBLE"));
        assertCatalogDrift("descending", ddl -> ddl.replace(payerIndex,
                "KEY idx_agent_task_funding_payer\n"
                        + "        (tenant_id, client_id, payer_principal_type, "
                        + "payer_principal_id, funding_status, id DESC)"));
        assertCatalogDrift("fulltext", ddl -> ddl.replace(payerIndex,
                "FULLTEXT KEY idx_agent_task_funding_payer\n"
                        + "        (tenant_id, client_id, payer_principal_type, "
                        + "payer_principal_id, funding_status)"));
    }

    @Test
    void unexpectedOwnedOrSuspiciousNamedTriggerFailsClosed() {
        for (String triggerDdl : List.of(
                "CREATE TRIGGER arbitrary_funding_hook BEFORE UPDATE ON agent_task_funding "
                        + "FOR EACH ROW SET @w04_noop=1",
                "CREATE TRIGGER trg_agent_task_funding_shadow BEFORE INSERT ON unrelated_w04 "
                        + "FOR EACH ROW SET @w04_noop=1")) {
            JdbcTemplate jdbc = database("trigger");
            AgentTaskFundingSchemaInitializer.tableDdlStatements().forEach(jdbc::execute);
            jdbc.execute("CREATE TABLE unrelated_w04(id BIGINT NOT NULL)");
            jdbc.execute(triggerDdl);

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> new AgentTaskFundingSchemaInitializer(jdbc).afterPropertiesSet());

            assertTrue(failure.getMessage().toLowerCase().contains("trigger"), failure.getMessage());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.triggers "
                    + "WHERE trigger_schema=DATABASE()", Integer.class));
        }
    }

    @Test
    void completedAndRefundedStatesRejectNullableReceiptBypasses() {
        JdbcTemplate jdbc = database("nullable");
        new AgentTaskFundingSchemaInitializer(jdbc).afterPropertiesSet();

        assertCompletedReceiptRejected(jdbc, "task-null-version", "NULL", "1", "1");
        assertCompletedReceiptRejected(jdbc, "task-null-created", "0", "NULL", "1");
        assertCompletedReceiptRejected(jdbc, "task-null-updated", "0", "1", "NULL");
        assertThrows(RuntimeException.class, () -> insertRefunded(jdbc,
                "task-null-key", "NULL", "REPEAT(0x02,32)", "1", "1", "1"));
        assertThrows(RuntimeException.class, () -> insertRefunded(jdbc,
                "task-null-hash", "REPEAT(0x31,36)", "NULL", "1", "1", "1"));
        assertThrows(RuntimeException.class, () -> insertRefunded(jdbc,
                "task-null-amount", "REPEAT(0x31,36)", "REPEAT(0x02,32)", "NULL", "1", "1"));
        assertThrows(RuntimeException.class, () -> insertRefunded(jdbc,
                "task-null-version", "REPEAT(0x31,36)", "REPEAT(0x02,32)", "1", "NULL", "1"));
        assertThrows(RuntimeException.class, () -> insertRefunded(jdbc,
                "task-null-time", "REPEAT(0x31,36)", "REPEAT(0x02,32)", "1", "1", "NULL"));
    }

    @Test
    void heldAndRefundedStatesRejectNullEscrowVersionAndOldCatalogFailsClosed() {
        JdbcTemplate jdbc = database("null_escrow_version");
        new AgentTaskFundingSchemaInitializer(jdbc).afterPropertiesSet();
        // Start with otherwise-valid rows: the failed UPDATE must be the CHECK, not a
        // duplicate key or a different missing receipt field.
        jdbc.execute("INSERT INTO agent_task_funding(task_id,funding_mode,funding_status,"
                + "payer_principal_type,payer_principal_id,settlement_policy,gross_bounty_amount_micro,"
                + "remaining_micro,escrow_id,escrow_version,reserve_transaction_id,required_skill_requirements,"
                + "version,tenant_id,client_id,create_time,update_time) VALUES('held-version',"
                + "'FUNDED_SINGLE_AGENT','FUNDS_HELD','USER','u','GROSS_INCLUSIVE',10,10,"
                + "'esc-held',1,'etx-held','[]',1,'Tenant-A','Client-A',1,1)");
        insertRefunded(jdbc, "refunded-version", "REPEAT(0x31,36)", "REPEAT(0x02,32)", "10", "1", "1");
        for (String taskId : List.of("held-version", "refunded-version")) {
            org.springframework.dao.DataAccessException failure = assertThrows(
                    org.springframework.dao.DataAccessException.class, () -> jdbc.update(
                            "UPDATE agent_task_funding SET escrow_version=NULL WHERE task_id=?", taskId));
            assertTrue(failure.getMostSpecificCause().getMessage()
                    .contains("chk_agent_task_funding_state"), failure.getMessage());
        }
        assertEquals(List.of(1L, 2L), jdbc.queryForList(
                "SELECT escrow_version FROM agent_task_funding ORDER BY task_id", Long.class));
        assertCatalogDrift("old_held_version", ddl -> ddl.replace(
                "escrow_version IS NOT NULL AND escrow_version > 0", "escrow_version > 0"));
        assertCatalogDrift("old_refunded_version", ddl -> ddl.replace(
                "escrow_version IS NOT NULL AND escrow_version > 1", "escrow_version > 1"));
    }

    private void assertCompletedReceiptRejected(JdbcTemplate jdbc, String taskId,
            String taskVersion, String createdAt, String updatedAt) {
        assertThrows(RuntimeException.class, () -> jdbc.execute(
                "INSERT INTO agent_task_funding_operation("
                        + "principal_type,principal_id,idempotency_key,request_hash,task_id,status,"
                        + "reserve_transaction_id,receipt_task_version,receipt_created_at,receipt_updated_at,"
                        + "tenant_id,client_id,create_time,update_time) VALUES('USER','u',"
                        + "REPEAT(0x31,36),REPEAT(0x01,32),'" + taskId + "','COMPLETED',"
                        + "'etx-reserve'," + taskVersion + "," + createdAt + "," + updatedAt
                        + ",'Tenant-A','Client-A',1,1)"));
    }

    private void insertRefunded(JdbcTemplate jdbc, String taskId, String key, String hash,
            String amount, String taskVersion, String refundedAt) {
        jdbc.execute("INSERT INTO agent_task_funding("
                + "task_id,funding_mode,funding_status,payer_principal_type,payer_principal_id,"
                + "settlement_policy,gross_bounty_amount_micro,remaining_micro,escrow_id,escrow_version,"
                + "reserve_transaction_id,required_skill_requirements,cancel_idempotency_key,"
                + "cancel_request_hash,refund_transaction_id,cancel_refunded_micro,cancel_task_version,"
                + "refunded_at,version,tenant_id,client_id,create_time,update_time) VALUES('"
                + taskId + "','FUNDED_SINGLE_AGENT','REFUNDED','USER','u','GROSS_INCLUSIVE',10,0,"
                + "'esc-1',2,'etx-reserve','[]'," + key + "," + hash + ",'etx-refund',"
                + amount + "," + taskVersion + "," + refundedAt
                + ",2,'Tenant-A','Client-A',1,1)");
    }

    private void assertCatalogDrift(String label, DdlMutation mutation) {
        JdbcTemplate jdbc = database(label);
        boolean changed = false;
        for (String source : AgentTaskFundingSchemaInitializer.tableDdlStatements()) {
            String drifted = mutation.apply(source);
            changed |= !source.equals(drifted);
            jdbc.execute(drifted);
        }
        assertTrue(changed, label);
        List<String> before = definitions(jdbc);

        assertThrows(IllegalStateException.class,
                () -> new AgentTaskFundingSchemaInitializer(jdbc).afterPropertiesSet(), label);

        assertEquals(before, definitions(jdbc), label);
    }

    private List<String> definitions(JdbcTemplate jdbc) {
        return AgentTaskFundingSchemaInitializer.TABLES.stream()
                .map(table -> jdbc.queryForObject("SHOW CREATE TABLE " + table,
                        (rs, row) -> rs.getString(2)))
                .toList();
    }

    private JdbcTemplate database(String suffix) {
        String database = prefix + "_w04_" + suffix + "_" + sequence.incrementAndGet();
        admin.execute("CREATE DATABASE `" + database
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        databases.add(database);
        int query = baseUrl.indexOf('?');
        String parameters = query < 0 ? "" : baseUrl.substring(query);
        String root = query < 0 ? baseUrl : baseUrl.substring(0, query);
        int slash = root.indexOf('/', "jdbc:mysql://".length());
        String url = (slash < 0 ? root + "/" : root.substring(0, slash + 1)) + database + parameters;
        return new JdbcTemplate(dataSource(url));
    }

    private DriverManagerDataSource dataSource(String url) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(url);
        source.setUsername(username);
        source.setPassword(password);
        return source;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static String present(String name) {
        String value = System.getenv(name);
        if (value == null) throw new IllegalStateException(name + " must be present");
        return value;
    }

    @FunctionalInterface
    private interface DdlMutation {
        String apply(String ddl);
    }
}
