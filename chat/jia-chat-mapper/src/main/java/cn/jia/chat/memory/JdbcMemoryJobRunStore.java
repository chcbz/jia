package cn.jia.chat.memory;

import cn.jia.core.security.FixedTenantAdmissionPolicy;
import jakarta.inject.Named;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Exact primary-key access only. The process configuration, not invocation payload, owns scope. */
@Named
public final class JdbcMemoryJobRunStore implements MemoryJobRunStore {
    private final JdbcTemplate jdbc;
    private final String tenant;
    private final String client;

    public JdbcMemoryJobRunStore(JdbcTemplate jdbc, FixedTenantAdmissionPolicy scope) {
        this.jdbc = jdbc;
        this.tenant = scope.businessTenantId();
        this.client = scope.clientId() == null ? "" : scope.clientId();
    }

    @Override public boolean start(long scheduledAt, String token, long startedAt) {
        try {
            jdbc.update("""
                    INSERT INTO chat_memory_job_run
                    (tenant_id,client_id,scheduled_at,start_token,outcome,started_at,finished_at)
                    VALUES (?,?,?,?,'STARTED',?,NULL)
                    """, tenant, client, scheduledAt, token, startedAt);
            return true;
        } catch (DuplicateKeyException alreadyAccepted) {
            return false;
        }
    }

    @Override public Run find(long scheduledAt) {
        var rows = jdbc.query("""
                SELECT scheduled_at,start_token,outcome,started_at,finished_at
                FROM chat_memory_job_run WHERE tenant_id=? AND client_id=? AND scheduled_at=?
                """, (rs, row) -> new Run(rs.getLong("scheduled_at"), rs.getString("start_token"),
                    Outcome.valueOf(rs.getString("outcome")), rs.getLong("started_at"),
                    rs.getObject("finished_at", Long.class)), tenant, client, scheduledAt);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override public void finish(long scheduledAt, String token, Outcome outcome, long finishedAt) {
        if (outcome == null || outcome == Outcome.STARTED)
            throw new IllegalArgumentException("A terminal memory job outcome is required");
        if (jdbc.update("""
                UPDATE chat_memory_job_run SET outcome=?,finished_at=?
                WHERE tenant_id=? AND client_id=? AND scheduled_at=? AND start_token=? AND outcome='STARTED'
                """, outcome.name(), finishedAt, tenant, client, scheduledAt, token) != 1)
            throw new IllegalStateException("Memory job result no longer belongs to this attempt");
    }
}
