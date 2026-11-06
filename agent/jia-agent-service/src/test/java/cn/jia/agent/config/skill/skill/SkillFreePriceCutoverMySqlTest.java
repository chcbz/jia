package cn.jia.agent.config.skill.skill;

import cn.jia.agent.config.skill.SkillSchemaInitializer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exact operator routine, on an explicitly isolated MySQL only. No application startup hook. */
@EnabledIfEnvironmentVariable(named = SkillMySqlTestFixture.URL_ENV, matches = ".+")
class SkillFreePriceCutoverMySqlTest {
    private static final String ROUTINE = "cyf_beta_free_skill_price_columns";
    private static final List<String> PRICED = List.of("agent_skill_product_version", "economy_skill_purchase_quote",
            "economy_skill_order", "economy_skill_order_receipt");
    private static final List<String> TABLES = List.of("agent_skill_product", "agent_skill_product_version",
            "economy_skill_purchase_quote", "economy_skill_order", "economy_skill_order_receipt", "agent_skill_installation", "agent_skill_entitlement");
    private final SkillMySqlTestFixture fixture = new SkillMySqlTestFixture();
    private SkillMySqlTestFixture.Database database;
    private JdbcTemplate jdbc;

    @BeforeEach void prepare() throws Exception {
        fixture.start(); scenario("freeprice", null, null);
    }
    private void scenario(String suffix, String nonzeroTable, String receiptEscrow) throws Exception {
        database = fixture.newDatabase(suffix); jdbc = database.jdbc();
        new SkillSchemaInitializer(jdbc).afterPropertiesSet(); seed(nonzeroTable, receiptEscrow);
        for (String table : PRICED) jdbc.execute("ALTER TABLE " + table + " RENAME COLUMN " + price(table, "wen") + " TO " + price(table, "micro"));
        String operatorSql = new ClassPathResource("db/migrations/20261106-free-skill-price-columns.sql").getContentAsString(StandardCharsets.UTF_8);
        // JDBC sends one CREATE statement; DELIMITER belongs only to the mysql CLI, not SQL semantics.
        jdbc.execute(operatorSql.substring(operatorSql.indexOf("CREATE PROCEDURE"), operatorSql.lastIndexOf("$$")));
    }
    @AfterEach void close() { fixture.close(); }

    @Test void preservesEveryRowPackagePermissionAndForeignKeyThroughCutoverAndPreResumeReversal() {
        var rows = rows(); var relationships = relationships();
        call("WEN"); assertUnit("wen");
        assertEquals(rows, rows()); assertEquals(relationships, relationships());
        new SkillSchemaInitializer(jdbc).afterPropertiesSet();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_skill_entitlement e JOIN agent_skill_installation i ON e.installation_id=i.installation_id JOIN economy_skill_order o ON e.order_id=o.order_id WHERE e.status='ACTIVE' AND i.status='INSTALLED' AND o.status='ACTIVE'", Integer.class));
        call("WEN"); assertEquals(rows, rows());
        call("MICRO"); assertUnit("micro"); assertEquals(rows, rows()); assertEquals(relationships, relationships());
        assertReleased();
    }
    @Test void resumesAPartiallyCompletedColumnChangeWithoutConvertingOrDuplicatingData() {
        var rows = rows();
        jdbc.execute("ALTER TABLE agent_skill_product_version RENAME COLUMN price_micro TO price_wen");
        jdbc.execute("ALTER TABLE economy_skill_purchase_quote RENAME COLUMN expected_price_micro TO expected_price_wen");
        call("WEN"); assertUnit("wen"); assertEquals(rows, rows());
        call("MICRO"); assertUnit("micro"); assertEquals(rows, rows());
    }
    @Test void eachNonzeroPriceRejectsBeforeAnyRenameAndCannotBeReversedAsOldMoney() throws Exception {
        int index = 0;
        for (String table : PRICED) {
            // Published versions, quotes and receipts are immutable. Seed the fact at INSERT,
            // never remove a trigger or mutate protected history merely to manufacture a fixture.
            scenario("nonzero_" + index++, table, null);
            var before = rows();
            assertThrows(DataAccessException.class, () -> call("WEN"));
            assertUnit("micro"); assertEquals(before, rows()); assertReleased();
        }
        scenario("reverse", null, null);
        call("WEN"); jdbc.update("UPDATE economy_skill_order SET price_wen=1");
        assertThrows(DataAccessException.class, () -> call("MICRO")); assertUnit("wen"); assertReleased();
    }
    @Test void financialReferencesAndAmbiguousColumnsAreNotErasedOrGuessed() throws Exception {
        for (String column : List.of("escrow_id", "reserve_transaction_id", "capture_transaction_id", "refund_transaction_id")) {
            jdbc.update("UPDATE economy_skill_order SET " + column + "='historical-reference'");
            var before = rows(); assertThrows(DataAccessException.class, () -> call("WEN"));
            assertUnit("micro"); assertEquals(before, rows()); assertReleased();
            jdbc.update("UPDATE economy_skill_order SET " + column + "=NULL");
        }
        scenario("receiptref", null, "historical-reference");
        assertThrows(DataAccessException.class, () -> call("WEN")); assertUnit("micro");
        scenario("ambiguous", null, null);
        jdbc.execute("ALTER TABLE economy_skill_order ADD price_wen BIGINT NOT NULL DEFAULT 0");
        assertThrows(DataAccessException.class, () -> call("WEN"));
        assertEquals(4, columns("micro")); assertEquals(1, columns("wen")); assertReleased();
    }
    @Test void anotherConnectionsMaintenanceLockIsNotStolenAndInvalidDirectionDoesNotWrite() throws Exception {
        assertThrows(DataAccessException.class, () -> call("OTHER")); assertUnit("micro"); assertReleased();
        try (var owner = database.dataSource().getConnection(); var statement = owner.createStatement()) {
            try (var result = statement.executeQuery("SELECT GET_LOCK('cyf:economy-v0:skill-schema',0)")) { assertTrue(result.next()); assertEquals(1, result.getInt(1)); }
            assertThrows(DataAccessException.class, () -> call("WEN")); assertUnit("micro");
            try (var result = statement.executeQuery("SELECT IS_USED_LOCK('cyf:economy-v0:skill-schema')=CONNECTION_ID()")) { assertTrue(result.next()); assertEquals(1, result.getInt(1)); }
            statement.execute("DO RELEASE_LOCK('cyf:economy-v0:skill-schema')");
        }
        call("WEN"); assertUnit("wen"); assertReleased();
    }
    private void call(String unit) { jdbc.execute("CALL " + ROUTINE + "('" + unit + "')"); }
    private void assertReleased() { assertNull(jdbc.queryForObject("SELECT IS_USED_LOCK('cyf:economy-v0:skill-schema')", Long.class)); }
    private int columns(String unit) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN ('agent_skill_product_version','economy_skill_purchase_quote','economy_skill_order','economy_skill_order_receipt') AND column_name IN (?,?)", Integer.class, "price_" + unit, "expected_price_" + unit);
    }
    private void assertUnit(String unit) { assertEquals(4, columns(unit)); assertEquals(0, columns(unit.equals("wen") ? "micro" : "wen")); }
    private static String price(String table, String unit) { return (table.equals("economy_skill_purchase_quote") ? "expected_price_" : "price_") + unit; }
    private Map<String, List<Map<String, String>>> rows() {
        Map<String, List<Map<String, String>>> snapshot = new TreeMap<>();
        for (String table : TABLES) snapshot.put(table, jdbc.query("SELECT * FROM " + table + " ORDER BY id", (rs, row) -> {
            Map<String, String> values = new TreeMap<>(); var metadata = rs.getMetaData();
            for (int n = 1; n <= metadata.getColumnCount(); n++) {
                Object value = rs.getObject(n); String column = metadata.getColumnLabel(n).replace("price_micro", "price_wen");
                values.put(column, value == null ? null : value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : value.toString());
            }
            return values;
        }));
        return snapshot;
    }
    private List<String> relationships() {
        return jdbc.queryForList("""
            SELECT CONCAT(table_name,':',constraint_name,':',column_name,':',referenced_table_name,':',referenced_column_name) AS value
            FROM information_schema.key_column_usage WHERE constraint_schema=DATABASE() AND referenced_table_name IS NOT NULL
            UNION ALL SELECT CONCAT(trigger_name,':',event_object_table,':',action_statement)
            FROM information_schema.triggers WHERE trigger_schema=DATABASE() ORDER BY value
            """, String.class);
    }
    private static long amount(String selected, String table) { return table.equals(selected) ? 1L : 0L; }
    private void seed(String nonzeroTable, String receiptEscrow) {
        jdbc.update("INSERT INTO agent_skill_product(product_id,name,description,status,tenant_id,client_id,create_time,update_time) VALUES('p','skill','synthetic','ACTIVE','tenant','client',1,1)");
        jdbc.update("""
            INSERT INTO agent_skill_product_version(product_version_id,product_id,version_sequence,skill_key,skill_version,price_wen,
              package_sha256,package_size,approved_permissions_manifest,approved_permissions_sha256,deployment_restriction,tenant_id,client_id,create_time)
            VALUES('v','p',1,'key','1.0.0',?,UNHEX(SHA2('synthetic-package',256)),17,'[]',UNHEX(SHA2('[]',256)),'NONE','tenant','client',1)
            """, amount(nonzeroTable, "agent_skill_product_version"));
        jdbc.update("""
            INSERT INTO economy_skill_purchase_quote(quote_id,actor_type,actor_id,idempotency_key,request_hash,product_version_id,target_agent_id,
              expected_agent_version,expected_price_wen,approved_permissions_manifest,approved_permissions_sha256,deployment_restriction,expires_at,tenant_id,client_id,create_time)
            VALUES('q','USER','buyer','quote-key',UNHEX(SHA2('request',256)),'v','agent',1,?,'[]',UNHEX(SHA2('[]',256)),'NONE',10,'tenant','client',1)
            """, amount(nonzeroTable, "economy_skill_purchase_quote"));
        jdbc.update("""
            INSERT INTO economy_skill_order(order_id,quote_id,product_version_id,target_agent_id,buyer_type,buyer_id,seller_type,seller_id,
              price_wen,expected_agent_version,permission_grant_version,approved_permissions_manifest,approved_permissions_sha256,status,tenant_id,client_id,held_at,active_at,update_time)
            VALUES('o','q','v','agent','USER','buyer','SYSTEM','SKILL_STORE',?,1,1,'[]',UNHEX(SHA2('[]',256)),'ACTIVE','tenant','client',1,2,2)
            """, amount(nonzeroTable, "economy_skill_order"));
        jdbc.update("""
            INSERT INTO economy_skill_order_receipt(order_id,actor_type,actor_id,idempotency_key,request_hash,order_version,order_status,
              price_wen,permission_grant_version,approved_permissions_sha256,escrow_id,tenant_id,client_id,create_time)
            VALUES('o','USER','buyer','receipt-key',UNHEX(SHA2('request',256)),1,'ACTIVE',?,1,UNHEX(SHA2('[]',256)),?,'tenant','client',2)
            """, amount(nonzeroTable, "economy_skill_order_receipt"), receiptEscrow);
        jdbc.update("""
            INSERT INTO agent_skill_installation(installation_id,order_id,product_version_id,target_agent_id,message_id,request_id,command_id,
              attempt,fencing_token,delivery_epoch,skill_key,skill_version,package_size,package_sha256,download_path,status,tenant_id,client_id,create_time,installed_at,update_time)
            VALUES('install','o','v','agent','message','request','command',1,1,1,'key','1.0.0',17,UNHEX(SHA2('synthetic-package',256)),'/fixture','INSTALLED','tenant','client',1,2,2)
            """);
        jdbc.update("""
            INSERT INTO agent_skill_entitlement(entitlement_id,order_id,installation_id,product_version_id,target_agent_id,skill_key,skill_version,
              permission_grant_version,approved_permissions_manifest,approved_permissions_sha256,status,tenant_id,client_id,create_time,activated_at,update_time)
            VALUES('right','o','install','v','agent','key','1.0.0',1,'[]',UNHEX(SHA2('[]',256)),'ACTIVE','tenant','client',1,2,2)
            """);
    }
}
