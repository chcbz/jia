package cn.jia.oauth.security;

import cn.jia.core.tenant.TenantId;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Tenant control-plane state transitions with If-Match and Idempotency-Key
 * semantics. This is an internal primitive: operator ACL, HTTP error mapping and
 * audit routing are still owned by the caller. Every transition holds the
 * control row's exclusive lock first, applies exactly one legal state edge,
 * advances the monotonic control revision, and records a tombstone operation row.
 */
public final class TenantControlTransitionService {
    private static final Set<String> STATES = Set.of(
            "ENABLED", "AUTH_CLOSED", "WRITE_CLOSED_CLAIM_CLOSED", "DRAINING", "DISABLED");

    private final JdbcOperations jdbc;
    private final TransactionTemplate transactions;

    public TenantControlTransitionService(JdbcOperations jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        if (!(jdbc instanceof JdbcTemplate template) || template.getDataSource() == null) {
            throw new IllegalArgumentException("Tenant control transitions require a transactional DataSource");
        }
        transactions = new TransactionTemplate(new DataSourceTransactionManager(template.getDataSource()));
        // A future operator audit must commit or roll back together with the
        // control transition on the same Spring-managed DataSource.
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Result transition(TenantId tenant, String operatorId, String idempotencyKey,
            Action action, String reason, Long expectedRevision, Long drainTimeoutSeconds) {
        Objects.requireNonNull(tenant, "tenant");
        String operator = requireToken(operatorId, "operatorId", 128);
        String key = requireToken(idempotencyKey, "idempotencyKey", 128);
        Objects.requireNonNull(action, "action");
        String normalizedReason = normalizeReason(reason);
        if (action != Action.CLOSE_WRITES_AND_CLAIMS && drainTimeoutSeconds != null) {
            throw new IllegalArgumentException("drainTimeoutSeconds is only allowed when closing writes");
        }
        Long timeout = action == Action.CLOSE_WRITES_AND_CLAIMS
                ? normalizeTimeout(drainTimeoutSeconds) : null;
        if (expectedRevision == null || expectedRevision < 0) {
            throw new PreconditionRequiredException();
        }
        String hash = hash(tenant.value(), action.name(), normalizedReason, timeout, expectedRevision);

        return transactions.execute(status -> {
            ControlRow control = lockControl(tenant);
            var existing = findOperation(tenant.value(), operator, key);
            if (existing != null) {
                if (!hash.equals(existing.get("request_hash"))) {
                    throw new ConflictException("Idempotency key reused with a different request");
                }
                return parseSnapshot(existing.get("result_snapshot"), true);
            }
            long nextRevision = nextRevision(control.revision(), expectedRevision);
            // DISABLE and ENABLE require proof about frozen executions, pending
            // external effects and a durable reopen preflight. None is wired yet.
            if (action == Action.DISABLE || action == Action.ENABLE) {
                throw new ConflictException("Drain settlement / reopen preflight is not installed");
            }

            State next = legalTransition(control.state(), action);
            Epochs epochs = epochs(control, action, timeout);
            int updated = jdbc.update("""
                    UPDATE core_tenant_control
                       SET state = ?, control_revision = ?, tenant_session_epoch = ?,
                           admission_epoch = ?, drain_epoch = ?, drain_started_at = ?, drain_deadline = ?
                     WHERE tenant_id = ? AND control_revision = ? AND state = ?
                    """,
                    next.name(), nextRevision, epochs.sessionEpoch(), epochs.admissionEpoch(),
                    epochs.drainEpoch(), epochs.drainStartedAt(), epochs.drainDeadline(),
                    tenant.value(), expectedRevision, control.state());
            if (updated != 1) {
                throw new PreconditionFailedException();
            }
            String snapshot = snapshot(next, nextRevision, epochs.sessionEpoch(), epochs.admissionEpoch());
            jdbc.update("""
                    INSERT INTO core_tenant_operation
                        (tenant_id, operator_id, idempotency_key, request_hash,
                         expected_revision, from_state, to_state, result_revision, result_snapshot)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    tenant.value(), operator, key, hash, expectedRevision,
                    control.state(), next.name(), nextRevision, snapshot);
            return parseSnapshot(snapshot, false);
        });
    }

    private ControlRow lockControl(TenantId tenant) {
        List<ControlRow> rows = jdbc.query("""
                SELECT state, control_revision, tenant_session_epoch, admission_epoch, drain_epoch, drain_started_at, drain_deadline
                  FROM core_tenant_control WHERE tenant_id = ? FOR UPDATE
                """, (rs, index) -> new ControlRow(
                rs.getString(1), rs.getLong(2), rs.wasNull(),
                rs.getLong(3), rs.wasNull(),
                rs.getLong(4), rs.wasNull(),
                rs.getLong(5), rs.wasNull(), rs.getTimestamp(6), rs.getTimestamp(7)), tenant.value());
        if (rows.size() != 1) {
            throw new ConflictException("Missing or ambiguous tenant control row");
        }
        ControlRow row = rows.get(0);
        if (!STATES.contains(row.state()) || row.revisionNull() || row.sessionNull()
                || row.admissionNull() || row.drainNull()) {
            throw new ConflictException("Missing or invalid tenant control state");
        }
        return row;
    }

    private Map<String, String> findOperation(String tenant, String operator, String key) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT request_hash, result_snapshot FROM core_tenant_operation
                 WHERE tenant_id = ? AND operator_id = ? AND idempotency_key = ?
                """, tenant, operator, key);
        if (rows.isEmpty()) {
            return null;
        }
        if (rows.size() != 1 || rows.get(0).get("request_hash") == null
                || rows.get(0).get("result_snapshot") == null) {
            throw new ConflictException("Ambiguous or malformed control operation record");
        }
        return Map.of("request_hash", String.valueOf(rows.get(0).get("request_hash")),
                "result_snapshot", String.valueOf(rows.get(0).get("result_snapshot")));
    }

    private static State legalTransition(String current, Action action) {
        State from = State.valueOf(current);
        return switch (action) {
            case CLOSE_AUTH -> requireFrom(from, State.AUTH_CLOSED, State.ENABLED);
            case CLOSE_WRITES_AND_CLAIMS -> requireFrom(from, State.WRITE_CLOSED_CLAIM_CLOSED,
                    State.ENABLED, State.AUTH_CLOSED);
            case BEGIN_DRAIN -> requireFrom(from, State.DRAINING, State.WRITE_CLOSED_CLAIM_CLOSED);
            case DISABLE -> requireFrom(from, State.DISABLED, State.DRAINING);
            case ENABLE -> requireFrom(from, State.ENABLED, State.DISABLED);
        };
    }

    private static State requireFrom(State current, State target, State... allowedFrom) {
        for (State allowed : allowedFrom) {
            if (current == allowed) {
                return target;
            }
        }
        throw new ConflictException("Illegal tenant control transition from " + current);
    }

    private static long nextRevision(long current, long expected) {
        if (current != expected) {
            throw new PreconditionFailedException();
        }
        if (current == Long.MAX_VALUE) {
            throw new ConflictException("Control revision exhausted");
        }
        return current + 1;
    }

    private Epochs epochs(ControlRow control, Action action, Long drainTimeoutSeconds) {
        long session = control.sessionEpoch();
        long admission = control.admissionEpoch();
        long drain = control.drainEpoch();
        java.sql.Timestamp startedAt = control.drainStartedAt();
        java.sql.Timestamp deadline = control.drainDeadline();
        switch (action) {
            case CLOSE_WRITES_AND_CLAIMS -> {
                admission = nextEpoch(admission);
                startedAt = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)", java.sql.Timestamp.class);
                deadline = java.sql.Timestamp.from(startedAt.toInstant().plusSeconds(drainTimeoutSeconds));
            }
            case BEGIN_DRAIN -> {
                drain = nextEpoch(drain);
            }
            case ENABLE -> throw new ConflictException("Reopen preflight is not installed");
            default -> {
                // CLOSE_AUTH retains the prior drain deadline.
            }
        }
        return new Epochs(session, admission, drain, startedAt, deadline);
    }

    private static long nextEpoch(long epoch) {
        if (epoch == Long.MAX_VALUE) {
            throw new ConflictException("Tenant epoch exhausted");
        }
        return epoch + 1;
    }

    private static Result parseSnapshot(String snapshot, boolean replay) {
        if (snapshot == null) {
            throw new ConflictException("Missing control operation result");
        }
        String[] parts = snapshot.split("\\|", -1);
        if (parts.length != 4) {
            throw new ConflictException("Malformed control operation result");
        }
        try {
            State state = State.valueOf(parts[0]);
            long revision = Long.parseLong(parts[1]);
            long session = Long.parseLong(parts[2]);
            long admission = Long.parseLong(parts[3]);
            if (revision < 0 || session < 0 || admission < 0) {
                throw new ConflictException("Invalid control operation result");
            }
            return new Result(state, revision, session, admission, replay);
        } catch (IllegalArgumentException invalid) {
            throw new ConflictException("Malformed control operation result");
        }
    }

    private static String snapshot(State state, long revision, long session, long admission) {
        return state.name() + "|" + revision + "|" + session + "|" + admission;
    }

    private static String hash(String tenant, String action, String reason, Long timeout, long revision) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((byte) 1);
            digest.update(tenant.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(action.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(reason.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Long.toUnsignedString(revision).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(timeout == null ? new byte[0] : Long.toUnsignedString(timeout).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String requireToken(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(name + " is required and must not exceed " + max + " characters");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || Character.isWhitespace(c)) {
                throw new IllegalArgumentException(name + " must not contain whitespace or control characters");
            }
        }
        return value;
    }

    private static String normalizeReason(String reason) {
        String text = reason == null ? "" : reason.strip();
        if (text.length() > 200) {
            throw new IllegalArgumentException("reason must not exceed 200 characters");
        }
        for (int i = 0; i < text.length(); i++) {
            if (Character.isISOControl(text.charAt(i))) {
                throw new IllegalArgumentException("reason contains control characters");
            }
        }
        return text;
    }

    private static Long normalizeTimeout(Long timeout) {
        if (timeout == null || timeout <= 0 || timeout > 31_536_000L) {
            throw new IllegalArgumentException("drainTimeoutSeconds must be a positive value within one year");
        }
        return timeout;
    }

    public enum Action {
        CLOSE_AUTH, CLOSE_WRITES_AND_CLAIMS, BEGIN_DRAIN, DISABLE, ENABLE
    }

    public enum State {
        ENABLED, AUTH_CLOSED, WRITE_CLOSED_CLAIM_CLOSED, DRAINING, DISABLED
    }

    public record Result(State state, long controlRevision, long tenantSessionEpoch,
                         long admissionEpoch, boolean replay) {}

    private record ControlRow(String state, long revision, boolean revisionNull, long sessionEpoch,
                              boolean sessionNull, long admissionEpoch, boolean admissionNull,
                              long drainEpoch, boolean drainNull, java.sql.Timestamp drainStartedAt,
                              java.sql.Timestamp drainDeadline) {}

    private record Epochs(long sessionEpoch, long admissionEpoch, long drainEpoch,
                          java.sql.Timestamp drainStartedAt, java.sql.Timestamp drainDeadline) {}

    public static class ControlException extends RuntimeException {
        ControlException(String message) {
            super(message);
        }
    }

    public static final class ConflictException extends ControlException {
        ConflictException(String message) {
            super(message);
        }
    }

    public static final class PreconditionFailedException extends ControlException {
        PreconditionFailedException() {
            super("Control revision does not match");
        }
    }

    public static final class PreconditionRequiredException extends ControlException {
        PreconditionRequiredException() {
            super("If-Match control revision is required");
        }
    }
}
