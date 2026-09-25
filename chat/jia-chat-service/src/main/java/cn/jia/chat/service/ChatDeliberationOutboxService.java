package cn.jia.chat.service;

import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatDispatchOutboxEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Short fenced transactions for the durable chat outbox relay. */
@Service
@RequiredArgsConstructor
public class ChatDeliberationOutboxService {
    private final ChatDeliberationDao dao;

    @Transactional(readOnly = true)
    public List<ChatDispatchOutboxEntity> discover(long now, int limit) {
        return dao.findDueOutbox(now, Math.max(1, Math.min(limit, 32)));
    }

    @Transactional(rollbackFor = Exception.class)
    public Claim claim(ChatDispatchOutboxEntity candidate, String leaseOwner, long now, long leaseMillis) {
        if (candidate == null) return null;
        ChatDispatchOutboxEntity row = dao.lockOutboxById(candidate.getTenantId(), candidate.getOwnerJiacn(),
                candidate.getClientId(), candidate.getEventId());
        if (row == null || terminal(row.getStatus())) return null;
        boolean staleLease = "CLAIMED".equals(row.getStatus())
                && row.getLeaseUntil() != null && row.getLeaseUntil() <= now;
        boolean ready = ("READY".equals(row.getStatus()) || "RETRY".equals(row.getStatus()))
                && row.getAvailableAt() != null && row.getAvailableAt() <= now;
        if (!staleLease && !ready) return null;
        long fence = Math.addExact(row.getFencingToken() == null ? 0L : row.getFencingToken(), 1L);
        long until = Math.addExact(now, leaseMillis);
        if (dao.claimOutbox(row, leaseOwner, until, fence, now) != 1) return null;
        row.setStatus("CLAIMED").setLeaseOwner(leaseOwner).setLeaseUntil(until)
                .setAttemptCount((row.getAttemptCount() == null ? 0 : row.getAttemptCount()) + 1)
                .setFencingToken(fence).setVersion(row.getVersion() + 1).setUpdatedAt(now);
        return new Claim(row, staleLease);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean sent(Claim claim, long now) {
        ChatDispatchOutboxEntity row = lockCurrent(claim);
        return dao.settleOutbox(row, "SENT", null, null, now, now) == 1;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean retry(Claim claim, String error, long now) {
        ChatDispatchOutboxEntity row = lockCurrent(claim);
        int attempt = row.getAttemptCount() == null ? 1 : row.getAttemptCount();
        long delay = Math.min(60_000L, 500L << Math.min(7, Math.max(0, attempt - 1)));
        return dao.settleOutbox(row, "RETRY", now + delay, bounded(error), null, now) == 1;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean dead(Claim claim, String error, long now) {
        ChatDispatchOutboxEntity row = lockCurrent(claim);
        return dao.settleOutbox(row, "DEAD", null, bounded(error), null, now) == 1;
    }

    private ChatDispatchOutboxEntity lockCurrent(Claim claim) {
        ChatDispatchOutboxEntity row = dao.lockOutboxById(claim.row().getTenantId(), claim.row().getOwnerJiacn(),
                claim.row().getClientId(), claim.row().getEventId());
        if (row == null || !"CLAIMED".equals(row.getStatus())
                || !claim.row().getLeaseOwner().equals(row.getLeaseOwner())
                || !claim.row().getFencingToken().equals(row.getFencingToken())) {
            throw new IllegalStateException("Stale chat outbox lease");
        }
        return row;
    }

    private boolean terminal(String status) {
        return "SENT".equals(status) || "DEAD".equals(status);
    }

    private String bounded(String error) {
        String value = error == null ? "DELIVERY_FAILED" : error;
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    public static String leaseOwner() { return "chat-relay-" + UUID.randomUUID(); }
    public record Claim(ChatDispatchOutboxEntity row, boolean recoveredStaleLease) { }
}
