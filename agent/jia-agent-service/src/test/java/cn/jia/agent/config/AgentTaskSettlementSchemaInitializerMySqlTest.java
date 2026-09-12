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

import static org.junit.jupiter.api.Assertions.*;

/** Isolated MySQL 8.0.21 source; migration and catalog are tested against real information_schema. */
@EnabledIfEnvironmentVariable(named = "ECO_V0_W02_MYSQL_URL", matches = ".+")
class AgentTaskSettlementSchemaInitializerMySqlTest {
    private JdbcTemplate admin;
    private String url;
    private String username;
    private String password;
    private String namespace;
    private final List<String> databases = new ArrayList<>();

    @BeforeEach
    void setUp() {
        url = required("ECO_V0_W02_MYSQL_URL"); username = required("ECO_V0_W02_MYSQL_USER");
        password = System.getenv("ECO_V0_W02_MYSQL_PASSWORD");
        String prefix = required("ECO_V0_W02_MYSQL_DATABASE_PREFIX");
        if (password == null || !url.startsWith("jdbc:mysql://") || !prefix.matches("[A-Za-z0-9_]{1,20}")
                || !"true".equals(required("ECO_V0_W02_MYSQL_ISOLATED_FIXTURE"))) {
            throw new IllegalStateException("Task-owned isolated MySQL fixture required");
        }
        namespace = prefix + "_w06_" + UUID.randomUUID().toString().substring(0, 8) + "_";
        admin = new JdbcTemplate(source(url));
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
    void freshAndRepeatedInitializationValidateExactCatalogAndAllReceiptNumbersAreNonnullable() {
        JdbcTemplate jdbc = database("fresh");
        new AgentTaskFundingSchemaInitializer(jdbc).afterPropertiesSet();
        AgentTaskSettlementSchemaInitializer initializer = new AgentTaskSettlementSchemaInitializer(jdbc);
        initializer.afterPropertiesSet();
        List<String> before = definitions(jdbc);
        initializer.afterPropertiesSet(); new AgentTaskFundingSchemaInitializer(jdbc).afterPropertiesSet();
        assertEquals(before, definitions(jdbc));
        jdbc.execute("""
                INSERT INTO agent_task_bounty_settlement SET tenant_id='T',client_id='C',principal_type='USER',principal_id='u',
                  idempotency_key=REPEAT(0x31,36),request_hash=REPEAT(0x01,32),task_id='t',quote_id='q',agent_id='a',escrow_id='e',
                  status='SETTLED',gross_micro=10,actual_compute_micro=1,platform_fee_micro=1,agent_payout_micro=8,refunded_micro=0,
                  task_version=2,funding_version=2,escrow_version=4,settled_at=1,transaction_ids='["tx1","tx2","tx3"]'
                """);
        for (String column : List.of("actual_compute_micro", "platform_fee_micro", "agent_payout_micro", "task_version",
                "funding_version", "escrow_version", "settled_at")) {
            assertThrows(DataAccessException.class, () -> jdbc.execute("UPDATE agent_task_bounty_settlement SET " + column + "=NULL"));
        }
        assertThrows(DataAccessException.class, () -> jdbc.execute("UPDATE agent_task_bounty_settlement SET agent_payout_micro=9"));
        jdbc.execute("""
                INSERT INTO agent_task_funding(task_id,funding_mode,funding_status,payer_principal_type,payer_principal_id,
                  settlement_policy,gross_bounty_amount_micro,remaining_micro,escrow_id,escrow_version,reserve_transaction_id,
                  required_skill_requirements,version,tenant_id,client_id,create_time,update_time)
                VALUES('t','FUNDED_SINGLE_AGENT','SETTLED','USER','u','GROSS_INCLUSIVE',10,0,'e',4,'reserve','[]',2,'T','C',1,1)
                """);
        DataAccessException rejected = assertThrows(DataAccessException.class,
                () -> jdbc.execute("UPDATE agent_task_funding SET escrow_version=NULL"));
        assertTrue(rejected.getMostSpecificCause().getMessage().contains("chk_agent_task_funding_state"));
    }

    @Test
    void onlyExactAcceptedW04CatalogMigratesToSettledAndWeakLegacyCatalogDoesNot() {
        JdbcTemplate jdbc = database("upgrade");
        installLegacy(jdbc, false);
        new AgentTaskFundingSchemaInitializer(jdbc).afterPropertiesSet();
        assertTrue(definition(jdbc, "agent_task_funding").contains("SETTLED"));
        String first = definition(jdbc, "agent_task_funding");
        new AgentTaskFundingSchemaInitializer(jdbc).afterPropertiesSet();
        assertEquals(first, definition(jdbc, "agent_task_funding"));
        JdbcTemplate weak = database("weak_legacy");
        installLegacy(weak, true);
        String before = definition(weak, "agent_task_funding");
        assertThrows(IllegalStateException.class, () -> new AgentTaskFundingSchemaInitializer(weak).afterPropertiesSet());
        assertEquals(before, definition(weak, "agent_task_funding"));
    }

    @Test
    void nullableUnenforcedOrWeakStateCatalogFailsWithoutRepair() {
        assertDrift("nullable", ddl -> ddl.replace("settled_at BIGINT NOT NULL", "settled_at BIGINT NULL"));
        assertDrift("weak", ddl -> ddl.replace("settled_at>0", "settled_at>=0"));
        assertDrift("unenforced", ddl -> ddl.replace("OCTET_LENGTH(transaction_ids)>2),", "OCTET_LENGTH(transaction_ids)>2) NOT ENFORCED,"));
        assertDrift("nonunique", ddl -> ddl.replace("UNIQUE KEY uk_bounty_settlement_actor_key", "KEY uk_bounty_settlement_actor_key"));
    }

    private void installLegacy(JdbcTemplate jdbc, boolean weak) {
        for (String ddl : AgentTaskFundingSchemaInitializer.tableDdlStatements()) {
            if (ddl.contains("CONSTRAINT chk_agent_task_funding_state CHECK (")) {
                int start = ddl.indexOf("CONSTRAINT chk_agent_task_funding_state CHECK (");
                int end = ddl.indexOf("\n    )", start) + "\n    )".length();
                String legacy = AgentTaskFundingSchemaInitializer.LEGACY_FUNDING_STATE;
                if (weak) legacy = legacy.replace("escrow_version IS NOT NULL AND ", "");
                ddl = ddl.substring(0, start) + "CONSTRAINT chk_agent_task_funding_state CHECK (" + legacy + ")" + ddl.substring(end);
            }
            jdbc.execute(ddl);
        }
    }

    private void assertDrift(String label, UnaryOperator<String> mutation) {
        JdbcTemplate jdbc = database(label);
        String pristine = AgentTaskSettlementSchemaInitializer.ddl(); String drifted = mutation.apply(pristine);
        assertNotEquals(pristine, drifted); jdbc.execute(drifted);
        String before = definition(jdbc, AgentTaskSettlementSchemaInitializer.TABLE);
        assertThrows(IllegalStateException.class, () -> new AgentTaskSettlementSchemaInitializer(jdbc).afterPropertiesSet());
        assertEquals(before, definition(jdbc, AgentTaskSettlementSchemaInitializer.TABLE));
    }
    private List<String> definitions(JdbcTemplate jdbc) {
        return List.of(definition(jdbc, "agent_task_funding"), definition(jdbc, AgentTaskSettlementSchemaInitializer.TABLE));
    }
    private String definition(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SHOW CREATE TABLE " + table, (rs, row) -> rs.getString(2));
    }
    private JdbcTemplate database(String label) {
        String database = namespace + label;
        admin.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin"); databases.add(database);
        int query = url.indexOf('?'); String parameters = query < 0 ? "" : url.substring(query);
        String root = query < 0 ? url : url.substring(0, query); int slash = root.indexOf('/', "jdbc:mysql://".length());
        return new JdbcTemplate(source((slash < 0 ? root + "/" : root.substring(0, slash + 1)) + database + parameters));
    }
    private DriverManagerDataSource source(String url) {
        DriverManagerDataSource source = new DriverManagerDataSource(); source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(url); source.setUsername(username); source.setPassword(password); return source;
    }
    private static String required(String name) {
        String value = System.getenv(name); if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required"); return value;
    }
}
