package cn.jia.economy.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = EconomyMySqlTestFixture.URL_ENV, matches = ".+")
class EconomyHostingRentSchemaInitializerMySqlTest {
    private final EconomyMySqlTestFixture fixture = new EconomyMySqlTestFixture();

    @BeforeEach
    void setUp() {
        fixture.start();
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    void additiveInitializerPreservesAcceptedFoundationAndSeedsNoPriceOrHistory() {
        JdbcTemplate jdbc = fixture.newDatabase("rent_schema").jdbc();
        new EconomySchemaInitializer(jdbc).afterPropertiesSet();
        List<String> foundationBefore = EconomySchemaInitializer.TABLES.stream()
                .map(table -> jdbc.queryForObject("SHOW CREATE TABLE " + table,
                        (rs, row) -> rs.getString(2)))
                .toList();

        EconomyHostingRentSchemaInitializer initializer = new EconomyHostingRentSchemaInitializer(jdbc);
        initializer.afterPropertiesSet();
        initializer.afterPropertiesSet();

        List<String> foundationAfter = EconomySchemaInitializer.TABLES.stream()
                .map(table -> jdbc.queryForObject("SHOW CREATE TABLE " + table,
                        (rs, row) -> rs.getString(2)))
                .toList();
        assertEquals(foundationBefore, foundationAfter);
        for (String table : EconomyHostingRentSchemaInitializer.TABLES) {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class), table);
        }
        assertEquals(4, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.triggers
                WHERE trigger_schema=DATABASE() AND trigger_name LIKE 'trg_hosting_%'
                """, Integer.class));
    }

    @Test
    void partialCatalogAndImmutablePlanOrQuoteMutationFailClosed() {
        JdbcTemplate partial = fixture.newDatabase("rent_partial").jdbc();
        partial.execute(EconomyHostingRentSchemaInitializer.tableDdlStatements().getFirst());
        IllegalStateException partialFailure = assertThrows(IllegalStateException.class,
                () -> new EconomyHostingRentSchemaInitializer(partial).afterPropertiesSet());
        assertTrue(partialFailure.getMessage().contains("Partial"), partialFailure.getMessage());

        JdbcTemplate immutable = fixture.newDatabase("rent_immutable").jdbc();
        new EconomyHostingRentSchemaInitializer(immutable).afterPropertiesSet();
        immutable.update("""
                INSERT INTO economy_hosting_rent_plan(
                    plan_id,plan_version,amount_micro,period_seconds,quote_ttl_seconds,
                    currency,status,tenant_id,client_id,create_time)
                VALUES('fixture-only',1,1000000000,2592000,30,'SILVER','ACTIVE','Tenant-A','Client-A',1)
                """);
        assertThrows(RuntimeException.class, () -> immutable.update(
                "UPDATE economy_hosting_rent_plan SET amount_micro=1000000001 WHERE plan_id='fixture-only'"));
        assertThrows(RuntimeException.class, () -> immutable.update(
                "DELETE FROM economy_hosting_rent_plan WHERE plan_id='fixture-only'"));
    }
}
