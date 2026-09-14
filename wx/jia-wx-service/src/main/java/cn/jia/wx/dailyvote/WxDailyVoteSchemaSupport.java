package cn.jia.wx.dailyvote;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;

/**
 * Identifies the two durable daily-vote receipt tables without treating an arbitrary database
 * failure as an availability incident. The caller can preserve the current question and return a
 * recoverable WeChat reply while the explicitly enabled schema installer is run.
 */
public final class WxDailyVoteSchemaSupport {

    private static final Set<String> RECEIPT_TABLES = Set.of(
            "wx_daily_vote_receipt", "wx_daily_vote_message_receipt");

    private WxDailyVoteSchemaSupport() {
    }

    public static boolean isMissingReceiptSchema(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && visited.add(current);
             current = current.getCause()) {
            if (current instanceof MissingReceiptSchemaException) {
                return true;
            }
            if (!referencesReceiptTable(current.getMessage())) {
                continue;
            }
            if (current instanceof SQLException sqlException) {
                if (sqlException.getErrorCode() == 1146 || "42S02".equals(sqlException.getSQLState())) {
                    return true;
                }
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("doesn't exist")) {
                return true;
            }
        }
        return false;
    }

    public static RuntimeException unavailable() {
        return new MissingReceiptSchemaException();
    }

    private static boolean referencesReceiptTable(String message) {
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return RECEIPT_TABLES.stream().anyMatch(normalized::contains);
    }

    private static final class MissingReceiptSchemaException extends IllegalStateException {
        private MissingReceiptSchemaException() {
            super("daily-vote receipt schema is unavailable");
        }
    }
}
