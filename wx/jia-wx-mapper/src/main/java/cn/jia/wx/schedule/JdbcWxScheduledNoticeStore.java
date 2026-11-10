package cn.jia.wx.schedule;

import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.wx.schedule.WxScheduledNotice.Key;
import cn.jia.wx.schedule.WxScheduledNotice.State;
import jakarta.inject.Named;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Date;

/** Process scope and exact PK operations. Caller owns short plan/start/result transactions. */
@Named
public final class JdbcWxScheduledNoticeStore implements WxScheduledNoticeStore {
    private static final String KEY = "tenant_id=? AND client_id=? AND notice_day=? AND kind=? AND subscription_id=?";
    private final JdbcTemplate jdbc;
    private final FixedTenantAdmissionPolicy scope;
    public JdbcWxScheduledNoticeStore(JdbcTemplate jdbc, FixedTenantAdmissionPolicy scope) {
        this.jdbc = jdbc; this.scope = scope;
    }
    private String tenant(Key key) { return scope.tenantForClient(key.clientId()); }

    @Override public WxScheduledNotice plan(Key key, String jiacn, long dueAt) {
        String tenant = tenant(key);
        if (jiacn == null || jiacn.isBlank() || dueAt < 0)
            throw new IllegalArgumentException("Notice recipient and deadline required");
        try {
            jdbc.update("""
                    INSERT INTO wx_scheduled_notice
                    (tenant_id,client_id,notice_day,kind,subscription_id,jiacn,due_at,state)
                    VALUES (?,?,?,?,?,?,?,'PLANNED')
                    """, tenant, key.clientId(), Date.valueOf(key.day()), key.kind().name(), key.subscriptionId(), jiacn, dueAt);
        } catch (DuplicateKeyException alreadyPlanned) {
            // A repeated cron or concurrent planner must not move an existing random deadline.
        }
        WxScheduledNotice notice = find(key);
        if (notice == null) throw new IllegalStateException("Notice planning receipt is unavailable");
        return notice;
    }
    @Override public WxScheduledNotice find(Key key) {
        var rows = jdbc.query("SELECT jiacn,due_at,state,start_token FROM wx_scheduled_notice WHERE " + KEY,
                (rs, row) -> new WxScheduledNotice(key, rs.getString("jiacn"), rs.getLong("due_at"),
                        State.valueOf(rs.getString("state")), rs.getString("start_token")),
                tenant(key), key.clientId(), Date.valueOf(key.day()), key.kind().name(), key.subscriptionId());
        return rows.isEmpty() ? null : rows.getFirst();
    }
    @Override public boolean start(Key key, String token, long now) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Send attempt token required");
        return jdbc.update("UPDATE wx_scheduled_notice SET state='STARTED',start_token=?,started_at=? WHERE "
                        + KEY + " AND state='PLANNED' AND due_at<=?", token, now,
                tenant(key), key.clientId(), Date.valueOf(key.day()), key.kind().name(), key.subscriptionId(), now) == 1;
    }
    @Override public void finish(Key key, String token, State outcome, long now) {
        if (outcome != State.SUCCEEDED && outcome != State.FAILED && outcome != State.UNKNOWN)
            throw new IllegalArgumentException("A send result is required");
        int updated = jdbc.update("UPDATE wx_scheduled_notice SET state=?,finished_at=? WHERE " + KEY
                        + " AND state='STARTED' AND start_token=?", outcome.name(), now,
                tenant(key), key.clientId(), Date.valueOf(key.day()), key.kind().name(), key.subscriptionId(), token);
        if (updated != 1) throw new IllegalStateException("Notice result no longer belongs to this send attempt");
    }
    @Override public boolean skip(Key key, State reason, long now) {
        if (reason != State.SKIPPED && reason != State.EXPIRED)
            throw new IllegalArgumentException("Only unsent notices can be skipped or expired");
        return jdbc.update("UPDATE wx_scheduled_notice SET state=?,finished_at=? WHERE " + KEY + " AND state='PLANNED'",
                reason.name(), now, tenant(key), key.clientId(), Date.valueOf(key.day()), key.kind().name(), key.subscriptionId()) == 1;
    }
}
