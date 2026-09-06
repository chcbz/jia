package cn.jia.agent.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real isolated MySQL CHECK/catalog regression sources; no application/shared database is used. */
@EnabledIfEnvironmentVariable(named = "ECO_V0_W02_MYSQL_URL", matches = ".+")
class AgentTaskBountyQuoteSchemaInitializerMySqlTest {
    private JdbcTemplate admin;
    private String baseUrl;
    private String username;
    private String password;
    private String namespace;
    private final List<String> databases = new ArrayList<>();

    @BeforeEach
    void setUp() {
        baseUrl = required("ECO_V0_W02_MYSQL_URL");
        username = required("ECO_V0_W02_MYSQL_USER");
        password = System.getenv("ECO_V0_W02_MYSQL_PASSWORD");
        String prefix = required("ECO_V0_W02_MYSQL_DATABASE_PREFIX");
        if (password == null || !prefix.matches("[A-Za-z0-9_]{1,20}")
                || !baseUrl.startsWith("jdbc:mysql://")
                || !"true".equals(required("ECO_V0_W02_MYSQL_ISOLATED_FIXTURE"))) {
            throw new IllegalStateException("Task-owned isolated MySQL fixture acknowledgement/credentials required");
        }
        namespace = prefix + "_w05_null_" + UUID.randomUUID().toString().substring(0, 8) + "_";
        admin = new JdbcTemplate(dataSource(baseUrl));
        String version = admin.queryForObject("SELECT VERSION()", String.class);
        assertTrue(version != null && version.startsWith("8.0.21"), version);
    }

    @AfterEach
    void tearDown() {
        if (admin == null) return;
        for (String database : databases) {
            if (!database.startsWith(namespace)) throw new IllegalStateException("Unowned database");
            admin.execute("DROP DATABASE IF EXISTS `" + database + "`");
        }
    }

    @Test
    void validCatalogIsRepeatableAndNullableOpenPostingRowsRemainLegal() {
        JdbcTemplate jdbc = database("valid");
        AgentTaskBountyQuoteSchemaInitializer initializer = new AgentTaskBountyQuoteSchemaInitializer(jdbc);
        initializer.afterPropertiesSet();
        List<String> before = definitions(jdbc);
        initializer.afterPropertiesSet();
        assertEquals(before, definitions(jdbc));
        insertOpenAndPosting(jdbc);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_bounty_quote "
                + "WHERE status='OPEN' AND claimed_at IS NULL", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_bounty_claim_operation "
                + "WHERE status='POSTING' AND receipt_task_version IS NULL AND claimed_at IS NULL", Integer.class));
    }

    @Test
    void claimedAndCompletedRowsRejectEachNullAndBothNullWithoutChangingReceipts() {
        JdbcTemplate jdbc = database("rows");
        new AgentTaskBountyQuoteSchemaInitializer(jdbc).afterPropertiesSet();
        insertOpenAndPosting(jdbc);
        assertEquals(1, jdbc.update("UPDATE agent_task_bounty_quote SET status='CLAIMED',claimed_at=2"));
        assertEquals(1, jdbc.update("UPDATE agent_task_bounty_claim_operation "
                + "SET status='COMPLETED',receipt_task_version=1,claimed_at=2"));
        assertCheckRejected(jdbc, "UPDATE agent_task_bounty_quote SET claimed_at=NULL", "chk_bounty_quote_state");
        for (String assignment : List.of("receipt_task_version=NULL", "claimed_at=NULL",
                "receipt_task_version=NULL,claimed_at=NULL")) {
            assertCheckRejected(jdbc, "UPDATE agent_task_bounty_claim_operation SET " + assignment,
                    "chk_bounty_claim_state");
        }
        assertEquals(2L, jdbc.queryForObject("SELECT claimed_at FROM agent_task_bounty_quote", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT receipt_task_version FROM agent_task_bounty_claim_operation", Long.class));
        assertEquals(2L, jdbc.queryForObject("SELECT claimed_at FROM agent_task_bounty_claim_operation", Long.class));
    }

    @Test
    void eachOldSameNamedNullableStateCheckFailsClosedWithoutRepair() {
        assertCatalogDrift("old_quote", 0, ddl -> ddl.replace("claimed_at IS NOT NULL AND ", ""));
        assertCatalogDrift("old_version", 1, ddl -> ddl.replace("receipt_task_version IS NOT NULL AND ", ""));
        assertCatalogDrift("old_time", 1, ddl -> ddl.replace("claimed_at IS NOT NULL AND ", ""));
    }

    @Test
    void eachNotEnforcedStateCheckFailsClosedWithoutRepair() {
        assertCatalogDrift("off_quote", 0, ddl -> ddl.replaceAll(
                "(?m)(  CONSTRAINT chk_bounty_quote_state CHECK \\(.*\\))(,?)$", "$1 NOT ENFORCED$2"));
        assertCatalogDrift("off_claim", 1, ddl -> ddl.replaceAll(
                "(?m)(  CONSTRAINT chk_bounty_claim_state CHECK \\(.*\\))(,?)$", "$1 NOT ENFORCED$2"));
    }

    private void assertCheckRejected(JdbcTemplate jdbc, String sql, String constraint) {
        DataAccessException failure = assertThrows(DataAccessException.class, () -> jdbc.update(sql));
        assertTrue(failure.getMostSpecificCause().getMessage().contains(constraint), failure.getMessage());
    }

    private void insertOpenAndPosting(JdbcTemplate jdbc) {
        jdbc.execute("""
                INSERT INTO agent_task_bounty_quote SET
                  quote_id='q',task_id='t',agent_id='a',principal_type='USER',principal_id='u',
                  idempotency_key=REPEAT(0x31,36),request_hash=REPEAT(0x01,32),task_version=0,
                  price_book_version='synthetic-test-only',task_input_hash='sha256:test',
                  skill_set_hash='sha256:test',model_route_version='test',
                  estimated_input_tokens=0,estimated_cached_input_tokens=0,
                  estimated_output_tokens=0,estimated_reasoning_tokens=0,
                  estimated_compute_micro=0,worst_compute_micro=0,platform_fee_micro=0,
                  gross_allocation_micro=1,estimated_agent_payout_micro=1,worst_agent_payout_micro=1,
                  minimum_accepted_payout_micro=0,budget_headroom_micro=0,
                  verified_skill_match=1,advisory_ability_match=1,budget_covered=1,agent_ready=1,
                  recommendation='recommended',reason_codes='[]',status='OPEN',expires_at=3,claimed_at=NULL,
                  tenant_id='Tenant-W05',client_id='Client-W05',create_time=1,update_time=1
                """);
        jdbc.execute("""
                INSERT INTO agent_task_bounty_claim_operation SET
                  principal_type='USER',principal_id='u',idempotency_key=REPEAT(0x31,36),
                  request_hash=REPEAT(0x01,32),task_id='t',agent_id='a',quote_id='q',status='POSTING',
                  receipt_task_version=NULL,claimed_at=NULL,tenant_id='Tenant-W05',client_id='Client-W05',
                  create_time=1,update_time=1
                """);
    }

    private void assertCatalogDrift(String label, int tableIndex, UnaryOperator<String> mutation) {
        JdbcTemplate jdbc = database(label);
        List<String> statements = AgentTaskBountyQuoteSchemaInitializer.tableDdlStatements();
        String drifted = mutation.apply(statements.get(tableIndex));
        assertTrue(!drifted.equals(statements.get(tableIndex)), "Mutation must change the target CHECK");
        for (int index = 0; index < statements.size(); index++) {
            jdbc.execute(index == tableIndex ? drifted : statements.get(index));
        }
        List<String> before = definitions(jdbc);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new AgentTaskBountyQuoteSchemaInitializer(jdbc).afterPropertiesSet());
        assertTrue(failure.getMessage().contains("state CHECK"), failure.getMessage());
        assertEquals(before, definitions(jdbc), "Catalog drift must fail closed, never auto-repair");
    }

    private List<String> definitions(JdbcTemplate jdbc) {
        return AgentTaskBountyQuoteSchemaInitializer.TABLES.stream().map(table ->
                jdbc.queryForObject("SHOW CREATE TABLE " + table, (rs, row) -> rs.getString(2))).toList();
    }

    private JdbcTemplate database(String label) {
        String database = namespace + label;
        admin.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
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
}
