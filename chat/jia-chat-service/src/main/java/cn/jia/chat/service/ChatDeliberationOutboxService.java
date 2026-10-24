package cn.jia.chat.service;

import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatDispatchOutboxEntity;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.TrustedTenantContext;
import cn.jia.core.util.JsonUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Short fenced transactions for the durable chat outbox relay. */
@Service
public class ChatDeliberationOutboxService {
    private static final DataDomainId CHAT_DATA_DOMAIN = DataDomainId.of("cyf");
    private final ChatDeliberationDao dao;
    private final ChatWorkerTenantPolicy tenantPolicy;
    private ChatDispatchAuthorityService dispatchAuthority;
    @org.springframework.beans.factory.annotation.Autowired
    public void setDispatchAuthority(ChatDispatchAuthorityService value) { dispatchAuthority=value; }

    @Autowired
    public ChatDeliberationOutboxService(
            ChatDeliberationDao dao, ChatWorkerTenantPolicy tenantPolicy) {
        this.dao = Objects.requireNonNull(dao, "dao");
        this.tenantPolicy = Objects.requireNonNull(tenantPolicy, "tenantPolicy");
    }

    /** Legacy direct-construction compatibility remains restricted to tenant zero. */
    public ChatDeliberationOutboxService(ChatDeliberationDao dao) {
        this(dao, new ChatWorkerTenantPolicy(Set.of("0")));
    }

    @Transactional(readOnly = true)
    public List<ChatDispatchOutboxEntity> discover(long now, int limit) {
        return dao.findDueOutbox(tenantPolicy.allowedTenants(), now,
                Math.max(1, Math.min(limit, 32))).stream()
                .filter(row -> trustedScope(row) != null)
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public Claim claim(ChatDispatchOutboxEntity candidate, String leaseOwner, long now, long leaseMillis) {
        if (candidate == null || leaseMillis < 1) return null;
        OutboxScope expected = scopeOf(candidate);
        if (expected == null || !tenantPolicy.allows(expected.tenantId())) return null;
        requireTrustedScope(expected);
        ChatDispatchOutboxEntity row = dao.lockOutboxById(expected.tenantId(), expected.ownerJiacn(),
                expected.clientId(), expected.eventId());
        OutboxScope locked = scopeOf(row);
        if (locked == null || !tenantPolicy.allows(locked.tenantId())
                || !expected.equals(locked) || terminal(row.getStatus())) return null;
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
            if (!claimScopeAllowed(claim) || !completeLeaseIdentity(claim.row)
                    || !activeLease(claim.row, now)) {
                claim.lost = true;
                return false;
            }
            ChatDispatchOutboxEntity row;
            try {
                row = lockCurrent(claim, now);
            } catch (StaleClaimException stale) {
                return false;
            }
            long until = Math.addExact(now, leaseMillis);
            if (dao.renewOutbox(row, until, now) != 1) {
                claim.lost = true;
                return false;
            }
            claim.row.setLeaseUntil(until).setUpdatedAt(now).setVersion(row.getVersion() + 1);
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
            ChatDispatchOutboxEntity row = lockCurrent(claim, now);
            boolean updated = dao.settleOutbox(row, status, availableAt, error, sentAt, now) == 1;
            if (updated) {
                Runnable committed=()-> {synchronized(claim) {
                    claim.settled = true;
                    claim.row.setStatus(status).setAvailableAt(availableAt).setLastError(error).setSentAt(sentAt)
                            .setLeaseOwner(null).setLeaseUntil(null).setVersion(row.getVersion()+1).setUpdatedAt(now);
                }};
                // A surrounding review transaction may still roll back after the fenced SQL.
                // Never make the in-memory claim terminal before the database commit.
                if(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                    org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override public void afterCommit() {committed.run();}
                        });
                } else committed.run();
            } else {
                claim.lost = true;
            }
            return updated;
        }
    }

    /** Durable receiver acknowledgement for chat dispatch. Stable messageId is the outbox event id. */
    @Transactional(rollbackFor = Exception.class)
    public boolean acknowledgeHostedDispatch(String tenantId, String executorOwner, String clientId,
            String agentId, String dispatchId, String messageId, long now) {
        requireExact(tenantId, 50); requireExact(executorOwner, 50); requireExact(clientId, 50);
        requireExact(agentId, 100); requireExact(dispatchId, 64); requireExact(messageId, 64);
        if (!tenantPolicy.allows(tenantId)) return false;
        if (dispatchAuthority == null) throw new IllegalStateException("Dispatch authority unavailable");
        String ownerJiacn=dispatchAuthority.incoming(tenantId,clientId,executorOwner,agentId,dispatchId).turn().getOwnerJiacn();
        ChatDispatchOutboxEntity row = dao.lockOutboxByDispatch(tenantId, ownerJiacn, clientId, dispatchId);
        if (!acknowledgementScopeMatches(row, tenantId, ownerJiacn, clientId, dispatchId)
                || !messageId.equals(row.getEventId())) return false;
        if (!agentId.equals(targetAgent(row))) return false;
        if ("SENT".equals(row.getStatus())) return true;
        if ("DEAD".equals(row.getStatus())) return false;
        return dao.acknowledgeOutbox(row, now) == 1;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean acknowledgeHostedReceipt(String tenantId, String executorOwner, String clientId,
            String agentId, String dispatchId, long now) {
        requireExact(tenantId, 50); requireExact(executorOwner, 50); requireExact(clientId, 50);
        requireExact(agentId, 100); requireExact(dispatchId, 64);
        if (!tenantPolicy.allows(tenantId)) return false;
        if (dispatchAuthority == null) throw new IllegalStateException("Dispatch authority unavailable");
        String ownerJiacn=dispatchAuthority.incoming(tenantId,clientId,executorOwner,agentId,dispatchId).turn().getOwnerJiacn();
        ChatDispatchOutboxEntity row = dao.lockOutboxByDispatch(tenantId, ownerJiacn, clientId, dispatchId);
        if (!acknowledgementScopeMatches(row, tenantId, ownerJiacn, clientId, dispatchId)
                || !agentId.equals(targetAgent(row)) || "DEAD".equals(row.getStatus())) return false;
        if ("SENT".equals(row.getStatus())) return true;
        return dao.acknowledgeOutbox(row, now) == 1;
    }

    private boolean acknowledgementScopeMatches(ChatDispatchOutboxEntity row, String tenantId,
            String ownerJiacn, String clientId, String dispatchId) {
        OutboxScope scope = scopeOf(row);
        return scope != null && tenantPolicy.allows(scope.tenantId())
                && tenantId.equals(scope.tenantId())
                && ownerJiacn.equals(scope.ownerJiacn())
                && clientId.equals(scope.clientId())
                && validExact(row.getDispatchId(), 64)
                && dispatchId.equals(row.getDispatchId());
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

    /** Hold the exact durable claim through a short action-admission transaction. */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public ChatDispatchOutboxEntity requireActiveClaim(Claim claim, long now) {
        if (claim == null || !claim.active()) throw new IllegalStateException("Inactive action outbox claim");
        synchronized (claim) {
            return lockCurrent(claim, now);
        }
    }

    private ChatDispatchOutboxEntity lockCurrent(Claim claim, long now) {
        requireClaimScope(claim);
        if (!completeLeaseIdentity(claim.row)) {
            throw staleClaim(claim);
        }
        requireTrustedScope(claim.scope);
        OutboxScope expected = claim.scope;
        ChatDispatchOutboxEntity row = dao.lockOutboxById(expected.tenantId(), expected.ownerJiacn(),
                expected.clientId(), expected.eventId());
        OutboxScope locked = scopeOf(row);
        if (locked == null || !tenantPolicy.allows(locked.tenantId()) || !expected.equals(locked)
                || !"CLAIMED".equals(row.getStatus()) || !completeLeaseIdentity(row)
                || !activeLease(row, now)
                || !Objects.equals(claim.row.getLeaseOwner(), row.getLeaseOwner())
                || !Objects.equals(claim.row.getFencingToken(), row.getFencingToken())
                || !Objects.equals(claim.row.getVersion(), row.getVersion())) {
            throw staleClaim(claim);
        }
        return row;
    }

    private boolean claimScopeAllowed(Claim claim) {
        return claim.scope != null && tenantPolicy.allows(claim.scope.tenantId())
                && claim.scope.equals(scopeOf(claim.row));
    }

    BusinessScope trustedScope(ChatDispatchOutboxEntity row) {
        OutboxScope scope = scopeOf(row);
        return scope == null || !tenantPolicy.allows(scope.tenantId()) ? null : businessScope(scope);
    }

    BusinessScope trustedScope(Claim claim) {
        return claim == null || !claim.active() || !tenantPolicy.allows(claim.scope.tenantId())
                ? null : businessScope(claim.scope);
    }

    private static BusinessScope businessScope(OutboxScope scope) {
        try {
            TenantId tenantId = TenantId.of(scope.tenantId());
            if (!tenantId.value().equals(scope.tenantId())) return null;
            return new BusinessScope(tenantId, CHAT_DATA_DOMAIN, scope.ownerJiacn(), scope.clientId());
        } catch (RuntimeException invalidIdentity) {
            return null;
        }
    }

    private static void requireTrustedScope(OutboxScope expected) {
        BusinessScope trusted = TrustedTenantContext.requireScope();
        if (!expected.tenantId().equals(trusted.tenantId().value())
                || !CHAT_DATA_DOMAIN.equals(trusted.dataDomainId())
                || !expected.ownerJiacn().equals(trusted.ownerJiacn())
                || !expected.clientId().equals(trusted.callerClientId())) {
            throw new IllegalStateException("Trusted tenant scope does not match durable outbox identity");
        }
    }

    private void requireClaimScope(Claim claim) {
        if (!claimScopeAllowed(claim)) {
            throw staleClaim(claim);
        }
    }

    private static StaleClaimException staleClaim(Claim claim) {
        claim.lost = true;
        return new StaleClaimException();
    }

    private static boolean activeLease(ChatDispatchOutboxEntity row, long now) {
        return row.getLeaseUntil() != null && row.getLeaseUntil() > now;
    }

    private static boolean completeLeaseIdentity(ChatDispatchOutboxEntity row) {
        return row.getLeaseOwner() != null && row.getFencingToken() != null && row.getVersion() != null;
    }

    private boolean terminal(String status) { return "SENT".equals(status) || "DEAD".equals(status); }
    private String bounded(String error) { String value=error==null?"DELIVERY_FAILED":error; return value.length()<=500?value:value.substring(0,500); }
    private static OutboxScope scopeOf(ChatDispatchOutboxEntity row) {
        if (row == null || !validExact(row.getTenantId(), 50)
                || !validExact(row.getOwnerJiacn(), 50)
                || !validExact(row.getClientId(), 50)
                || !validExact(row.getEventId(), 64)) return null;
        return new OutboxScope(row.getTenantId(), row.getOwnerJiacn(), row.getClientId(), row.getEventId());
    }
    private static boolean validExact(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max && value.equals(value.strip())
                && value.chars().noneMatch(Character::isISOControl);
    }
    private static void requireExact(String value,int max){if(value==null||value.isBlank()||value.length()>max||!value.equals(value.strip())||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid durable dispatch identity");}

    private record OutboxScope(String tenantId, String ownerJiacn, String clientId, String eventId) {}

    private static final class StaleClaimException extends IllegalStateException {
        private StaleClaimException() {
            super("Stale chat outbox lease");
        }
    }

    public static String leaseOwner() { return "chat-relay-" + UUID.randomUUID(); }

    public static final class Claim {
        private final ChatDispatchOutboxEntity row;
        private final OutboxScope scope;
        private final boolean recoveredStaleLease;
        private boolean lost;
        private boolean settled;
        public Claim(ChatDispatchOutboxEntity row, boolean recoveredStaleLease) {
            this.row = row;
            this.scope = scopeOf(row);
            this.recoveredStaleLease = recoveredStaleLease;
        }
        public ChatDispatchOutboxEntity row() { return row; }
        public boolean recoveredStaleLease() { return recoveredStaleLease; }
        public synchronized boolean active() {
            return !lost && !settled && scope != null && scope.equals(scopeOf(row));
        }
        /** A renewal transport failure has an unknown server result; this worker must stop settling. */
        public synchronized void invalidate() { lost = true; }
    }
}
