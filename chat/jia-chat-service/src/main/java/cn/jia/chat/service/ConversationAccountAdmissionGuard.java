package cn.jia.chat.service;

import cn.jia.core.tenant.UserAdmissionScope;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Revalidates and locks the current account inside the caller's transaction.
 * This guard never creates a transaction and does not replace conversation ACLs.
 */
@Service
public final class ConversationAccountAdmissionGuard {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final String lockClause;

    public ConversationAccountAdmissionGuard(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.dataSource = Objects.requireNonNull(jdbc.getDataSource(), "jdbc.dataSource");
        String database = jdbc.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getDatabaseProductName());
        if ("MySQL".equals(database)) {
            lockClause = "FOR SHARE";
        } else if ("H2".equals(database)) {
            lockClause = "FOR UPDATE";
        } else {
            throw denied();
        }
    }

    /**
     * Requires one exact, active account row and holds its database lock until the
     * transaction already owned by the caller completes.
     */
    public void requireCurrent(UserAdmissionScope scope) {
        Objects.requireNonNull(scope, "scope");
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(dataSource)) {
            throw denied();
        }

        Account account = one(jdbc.query("""
                SELECT id,jiacn,account_state,auth_epoch FROM user_info
                WHERE id=? FOR SHARE
                """.replace("FOR SHARE", lockClause), (rs, row) -> new Account(
                rs.getLong("id"), rs.getString("jiacn"), rs.getString("account_state"),
                nullableEpoch(rs)), scope.userId()));

        if (account.id() != scope.userId()
                || !byteExact(scope.businessScope().ownerJiacn(), account.jiacn())
                || !"ACTIVE".equals(account.state())
                || account.authEpoch() == null
                || account.authEpoch() != scope.authEpoch()) {
            throw denied();
        }
    }

    private record Account(long id, String jiacn, String state, Long authEpoch) {}

    private static Long nullableEpoch(ResultSet rs) throws SQLException {
        long value = rs.getLong("auth_epoch");
        return rs.wasNull() ? null : value;
    }

    private static boolean byteExact(String expected, String actual) {
        return actual != null && Arrays.equals(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static <T> T one(List<T> rows) {
        if (rows.size() != 1 || rows.get(0) == null) {
            throw denied();
        }
        return rows.get(0);
    }

    private static IllegalStateException denied() {
        return new IllegalStateException("Conversation account admission is unavailable");
    }
}
