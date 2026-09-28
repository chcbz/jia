package cn.jia.chat.service;

import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatDispatchOutboxEntity;
import cn.jia.core.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
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
        if (candidate == null || leaseMillis < 1) return null;
        ChatDispatchOutboxEntity row = dao.lockOutboxById(candidate.getTenantId(), candidate.getOwnerJiacn(),
                candidate.getClientId(), candidate.getEventId());
        if (row == null || terminal(row.getStatus())) return null;
        boolean staleLease = "CLAIMED".equals(row.getStatus())
                && row.getLeaseUntil() != null && row.getLeaseUntil() <= now;
        boolean ready = ("READY".equals(row.getStatus()) || "RETRY".equals(row.getStatus())
                || "AWAITING_ACK".equals(row.getStatus()))
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

    /** Renews only the exact owner/fence/version lease; a failed CAS permanently invalidates this claim. */
    @Transactional(rollbackFor = Exception.class)
    public boolean renew(Claim claim, long now, long leaseMillis) {
        if (claim == null || leaseMillis < 1) return false;
        synchronized (claim) {
            if (claim.lost || claim.settled) return false;
            long until = Math.addExact(now, leaseMillis);
            if (dao.renewOutbox(claim.row, until, now) != 1) {
                claim.lost = true;
                return false;
            }
            claim.row.setLeaseUntil(until).setUpdatedAt(now).setVersion(claim.row.getVersion() + 1);
            return true;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean sent(Claim claim, long now) { return settle(claim, "SENT", null, null, now, now); }

    @Transactional(rollbackFor = Exception.class)
    public boolean awaitingAck(Claim claim, long now, long acknowledgementTimeoutMillis) {
        if (acknowledgementTimeoutMillis < 1) throw new IllegalArgumentException("acknowledgementTimeoutMillis");
        return settle(claim, "AWAITING_ACK", Math.addExact(now, acknowledgementTimeoutMillis),
                "AWAITING_DURABLE_AGENT_ACK", null, now);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean retry(Claim claim, String error, long now) {
        int attempt = claim == null || claim.row.getAttemptCount() == null ? 1 : claim.row.getAttemptCount();
        long delay = Math.min(60_000L, 500L << Math.min(7, Math.max(0, attempt - 1)));
        return settle(claim, "RETRY", now + delay, bounded(error), null, now);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean dead(Claim claim, String error, long now) {
        return settle(claim, "DEAD", null, bounded(error), null, now);
    }

    private boolean settle(Claim claim, String status, Long availableAt, String error, Long sentAt, long now) {
        if (claim == null) return false;
        synchronized (claim) {
            if (claim.lost || claim.settled) return false;
            ChatDispatchOutboxEntity row = lockCurrent(claim);
            boolean updated = dao.settleOutbox(row, status, availableAt, error, sentAt, now) == 1;
            if (updated) {
                claim.settled = true;
                claim.row.setStatus(status).setAvailableAt(availableAt).setLastError(error).setSentAt(sentAt)
                        .setLeaseOwner(null).setLeaseUntil(null).setVersion(row.getVersion() + 1).setUpdatedAt(now);
            }
            return updated;
        }
    }

    /** Durable receiver acknowledgement for chat dispatch. Stable messageId is the outbox event id. */
    @Transactional(rollbackFor = Exception.class)
    public boolean acknowledgeHostedDispatch(String tenantId, String ownerJiacn, String clientId,
            String agentId, String dispatchId, String messageId, long now) {
        requireExact(tenantId, 50); requireExact(ownerJiacn, 50); requireExact(clientId, 50);
        requireExact(agentId, 100); requireExact(dispatchId, 64); requireExact(messageId, 64);
        ChatDispatchOutboxEntity row = dao.lockOutboxByDispatch(tenantId, ownerJiacn, clientId, dispatchId);
        if (row == null || !dispatchId.equals(row.getDispatchId()) || !messageId.equals(row.getEventId())) return false;
        if (!agentId.equals(targetAgent(row))) return false;
        if ("SENT".equals(row.getStatus())) return true;
        if ("DEAD".equals(row.getStatus())) return false;
        return dao.acknowledgeOutbox(row, now) == 1;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean acknowledgeHostedReceipt(String tenantId, String ownerJiacn, String clientId,
            String agentId, String dispatchId, long now) {
        requireExact(tenantId, 50); requireExact(ownerJiacn, 50); requireExact(clientId, 50);
        requireExact(agentId, 100); requireExact(dispatchId, 64);
        ChatDispatchOutboxEntity row = dao.lockOutboxByDispatch(tenantId, ownerJiacn, clientId, dispatchId);
        if (row == null || !agentId.equals(targetAgent(row)) || "DEAD".equals(row.getStatus())) return false;
        if ("SENT".equals(row.getStatus())) return true;
        return dao.acknowledgeOutbox(row, now) == 1;
    }

    private String targetAgent(ChatDispatchOutboxEntity row) {
        try {
            Map<?,?> payload = JsonUtil.getMapper().readValue(row.getPayloadJson(), Map.class);
            Object value = payload.get("targetAgentId");
            return value instanceof String text ? text : null;
        } catch (Exception malformed) {
            return null;
        }
    }

    private ChatDispatchOutboxEntity lockCurrent(Claim claim) {
        ChatDispatchOutboxEntity row = dao.lockOutboxById(claim.row.getTenantId(), claim.row.getOwnerJiacn(),
                claim.row.getClientId(), claim.row.getEventId());
        if (row == null || !"CLAIMED".equals(row.getStatus())
                || !claim.row.getLeaseOwner().equals(row.getLeaseOwner())
                || !claim.row.getFencingToken().equals(row.getFencingToken())
                || !claim.row.getVersion().equals(row.getVersion())) {
            claim.lost = true;
            throw new IllegalStateException("Stale chat outbox lease");
        }
        return row;
    }

    private boolean terminal(String status) { return "SENT".equals(status) || "DEAD".equals(status); }
    private String bounded(String error) { String value=error==null?"DELIVERY_FAILED":error; return value.length()<=500?value:value.substring(0,500); }
    private static void requireExact(String value,int max){if(value==null||value.isBlank()||value.length()>max||!value.equals(value.strip())||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid durable dispatch identity");}

    public static String leaseOwner() { return "chat-relay-" + UUID.randomUUID(); }

    public static final class Claim {
        private final ChatDispatchOutboxEntity row;
        private final boolean recoveredStaleLease;
        private boolean lost;
        private boolean settled;
        public Claim(ChatDispatchOutboxEntity row, boolean recoveredStaleLease) {
            this.row = row; this.recoveredStaleLease = recoveredStaleLease;
        }
        public ChatDispatchOutboxEntity row() { return row; }
        public boolean recoveredStaleLease() { return recoveredStaleLease; }
        public synchronized boolean active() { return !lost && !settled; }
    }
}
