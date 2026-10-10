package cn.jia.tenantcard;

import cn.jia.chat.service.ConversationAccountAdmissionGuard;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.UserAdmissionScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TenantCardA06aTest {
    private static final long UID = 101L;
    private static final long AUTH_EPOCH = 7L;

    @AfterEach
    void clearThreadTransactionState() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void noTransactionFailsBeforeAccountQuery() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate("MySQL", row(UID, "owner-a", "ACTIVE", AUTH_EPOCH));
        ConversationAccountAdmissionGuard guard = new ConversationAccountAdmissionGuard(jdbc);

        assertThrows(IllegalStateException.class, () -> guard.requireCurrent(scope(UID, "owner-a", AUTH_EPOCH)));
        assertTrue(jdbc.queries == 0, "an absent transaction must cause zero account queries");
    }

    @Test
    void transactionBoundToDifferentDataSourceFailsBeforeAccountQuery() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate("MySQL", row(UID, "owner-a", "ACTIVE", AUTH_EPOCH));
        DriverManagerDataSource other = h2DataSource();
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(other));

        transaction.executeWithoutResult(status -> assertThrows(IllegalStateException.class,
                () -> new ConversationAccountAdmissionGuard(jdbc)
                        .requireCurrent(scope(UID, "owner-a", AUTH_EPOCH))));

        assertTrue(jdbc.queries == 0, "a transaction for another DataSource must cause zero account queries");
    }

    @Test
    void basicScopeIsAcceptedInsideTheExistingH2Transaction() {
        Fixture fixture = fixture(UID, "owner-a", "ACTIVE", AUTH_EPOCH);

        fixture.transaction.executeWithoutResult(status -> assertDoesNotThrow(
                () -> fixture.guard.requireCurrent(scope(UID, "owner-a", AUTH_EPOCH))));
    }

    @Test
    void missingUserIdIsRejected() {
        Fixture fixture = fixture(UID, "owner-a", "ACTIVE", AUTH_EPOCH);

        assertRejected(fixture, scope(102L, "owner-a", AUTH_EPOCH));
    }

    @Test
    void nonByteExactOwnerIsRejected() {
        Fixture fixture = fixture(UID, "Owner-a", "ACTIVE", AUTH_EPOCH);

        assertRejected(fixture, scope(UID, "owner-a", AUTH_EPOCH));
    }

    @Test
    void nonActiveAccountStateIsRejected() {
        Fixture fixture = fixture(UID, "owner-a", "FROZEN", AUTH_EPOCH);

        assertRejected(fixture, scope(UID, "owner-a", AUTH_EPOCH));
    }

    @Test
    void changedAuthEpochIsRejected() {
        Fixture fixture = fixture(UID, "owner-a", "ACTIVE", AUTH_EPOCH + 1);

        assertRejected(fixture, scope(UID, "owner-a", AUTH_EPOCH));
    }

    @Test
    void nullAuthEpochIsNeverTreatedAsZero() {
        Fixture fixture = fixture(UID, "owner-a", "ACTIVE", null);

        assertRejected(fixture, scope(UID, "owner-a", 0L));
    }

    @Test
    void mysqlUsesSharedLockSql() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate("MySQL", row(UID, "owner-a", "ACTIVE", AUTH_EPOCH));

        withBoundTransaction(jdbc.getDataSource(), () ->
                new ConversationAccountAdmissionGuard(jdbc).requireCurrent(scope(UID, "owner-a", AUTH_EPOCH)));

        assertTrue(jdbc.sql.endsWith("FOR SHARE\n"), jdbc.sql);
    }

    @Test
    void h2UsesUpdateLockSql() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate("H2", row(UID, "owner-a", "ACTIVE", AUTH_EPOCH));

        withBoundTransaction(jdbc.getDataSource(), () ->
                new ConversationAccountAdmissionGuard(jdbc).requireCurrent(scope(UID, "owner-a", AUTH_EPOCH)));

        assertTrue(jdbc.sql.endsWith("FOR UPDATE\n"), jdbc.sql);
    }

    @Test
    void unsupportedDatabaseFailsClosedBeforeAccountQuery() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate("PostgreSQL", row(UID, "owner-a", "ACTIVE", AUTH_EPOCH));

        assertThrows(IllegalStateException.class, () -> new ConversationAccountAdmissionGuard(jdbc));
        assertTrue(jdbc.queries == 0);
    }

    private static void assertRejected(Fixture fixture, UserAdmissionScope scope) {
        fixture.transaction.executeWithoutResult(status ->
                assertThrows(IllegalStateException.class, () -> fixture.guard.requireCurrent(scope)));
    }

    private static Fixture fixture(long id, String owner, String state, Long epoch) {
        DriverManagerDataSource dataSource = h2DataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE user_info (
                    id BIGINT NOT NULL,
                    jiacn VARCHAR(100),
                    account_state VARCHAR(32),
                    auth_epoch BIGINT
                )
                """);
        jdbc.update("INSERT INTO user_info(id,jiacn,account_state,auth_epoch) VALUES (?,?,?,?)",
                id, owner, state, epoch);
        return new Fixture(new ConversationAccountAdmissionGuard(jdbc),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    private static DriverManagerDataSource h2DataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:a06a_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static UserAdmissionScope scope(long userId, String owner, long authEpoch) {
        return UserAdmissionScope.basic(new BusinessScope(
                TenantId.of("1"), DataDomainId.of("cyf"), owner, "client-a"), userId, authEpoch);
    }

    private static AccountRow row(long id, String owner, String state, Long epoch) {
        return new AccountRow(id, owner, state, epoch);
    }

    private static void withBoundTransaction(DataSource dataSource, Runnable work) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.bindResource(dataSource, new Object());
        try {
            work.run();
        } finally {
            TransactionSynchronizationManager.unbindResource(dataSource);
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private record Fixture(ConversationAccountAdmissionGuard guard, TransactionTemplate transaction) {}
    private record AccountRow(long id, String owner, String state, Long epoch) {}

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final String database;
        private final AccountRow row;
        private int queries;
        private String sql;

        private RecordingJdbcTemplate(String database, AccountRow row) {
            super(mock(DataSource.class));
            this.database = database;
            this.row = row;
        }

        @Override
        public <T> T execute(ConnectionCallback<T> action) {
            @SuppressWarnings("unchecked")
            T result = (T) database;
            return result;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            this.queries++;
            this.sql = sql;
            ResultSet resultSet = mock(ResultSet.class);
            try {
                when(resultSet.getLong("id")).thenReturn(row.id());
                when(resultSet.getString("jiacn")).thenReturn(row.owner());
                when(resultSet.getString("account_state")).thenReturn(row.state());
                when(resultSet.getLong("auth_epoch")).thenReturn(row.epoch() == null ? 0L : row.epoch());
                when(resultSet.wasNull()).thenReturn(row.epoch() == null);
                return List.of(rowMapper.mapRow(resultSet, 0));
            } catch (SQLException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }
}
