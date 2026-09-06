package cn.jia.economy.config.skillapplication;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** No DB, driver, environment gate, worker thread, or process control: W09 fixture lifecycle proof only. */
class EconomySkillApplicationMySqlTestFixtureLifecycleTest {
    private static final String PREFIX = "w09_lifecycle";
    private final JdbcTemplate admin = mock(JdbcTemplate.class);
    private final EconomySkillApplicationMySqlTestFixture fixture = new EconomySkillApplicationMySqlTestFixture();

    @Test
    void unknownCreateResultIsAlreadyOwnedAndCloseAttemptsTheExactDrop() {
        prepare(List.of());
        doThrow(new IllegalStateException("lost CREATE response"))
                .when(admin).execute(argThat((String sql) -> sql.startsWith("CREATE DATABASE `" + PREFIX + "_create_")));

        assertThrows(IllegalStateException.class, () -> fixture.newDatabase("create"));
        List<String> owned = ownedDatabases();
        assertEquals(1, owned.size());
        String database = owned.getFirst();
        assertTrue(database.startsWith(PREFIX + "_create_"));

        fixture.close();
        var ordered = inOrder(admin);
        ordered.verify(admin).execute("CREATE DATABASE `" + database
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        ordered.verify(admin).execute("DROP DATABASE IF EXISTS `" + database + "`");
        assertTrue(ownedDatabases().isEmpty());
    }

    @Test
    void successfulDropIsRemovedButFailedDropRemainsOwnedForRetry() {
        String first = PREFIX + "_first_owned";
        String second = PREFIX + "_second_owned";
        prepare(List.of(first, second));
        doThrow(new IllegalStateException("lost DROP response"))
                .when(admin).execute("DROP DATABASE IF EXISTS `" + first + "`");

        assertThrows(IllegalStateException.class, fixture::close);
        assertEquals(List.of(first), ownedDatabases(), "confirmed second DROP is removed; failed first DROP is retained");
        verify(admin).execute("DROP DATABASE IF EXISTS `" + second + "`");

        org.mockito.Mockito.reset(admin);
        fixture.close();
        assertTrue(ownedDatabases().isEmpty());
        verify(admin).execute("DROP DATABASE IF EXISTS `" + first + "`");
    }

    @Test
    void unownedNameIsNeverDropped() {
        prepare(List.of("foreign_database"));
        assertThrows(IllegalStateException.class, fixture::close);
        verify(admin, never()).execute(org.mockito.ArgumentMatchers.anyString());
        assertEquals(List.of("foreign_database"), ownedDatabases());
    }

    @Test
    void driverWaitsAreFiniteEvenWhenInputRequestsInfiniteWaitsOrReconnect() {
        assertEquals("jdbc:mysql://localhost/test?useSSL=false&connectTimeout=10000&socketTimeout=2400000"
                        + "&autoReconnect=false&autoReconnectForPools=false",
                EconomySkillApplicationMySqlTestFixture.boundedJdbcUrl(
                        "jdbc:mysql://localhost/test?connectTimeout=0&socket%54imeout=0"
                                + "&autoReconnect=true&autoReconnectForPools=true&useSSL=false"));
    }

    private void prepare(List<String> databases) {
        ReflectionTestUtils.setField(fixture, "admin", admin);
        ReflectionTestUtils.setField(fixture, "baseUrl", "jdbc:mysql://localhost/test");
        ReflectionTestUtils.setField(fixture, "username", "fixture");
        ReflectionTestUtils.setField(fixture, "password", "fixture");
        ReflectionTestUtils.setField(fixture, "databasePrefix", PREFIX);
        ReflectionTestUtils.setField(fixture, "databases", new ArrayList<>(databases));
    }

    @SuppressWarnings("unchecked")
    private List<String> ownedDatabases() {
        return (List<String>) ReflectionTestUtils.getField(fixture, "databases");
    }
}
