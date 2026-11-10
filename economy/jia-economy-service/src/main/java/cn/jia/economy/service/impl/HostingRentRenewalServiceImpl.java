package cn.jia.economy.service.impl;

import cn.jia.economy.common.*;
import cn.jia.economy.config.EconomyPreviewGate;
import cn.jia.economy.entity.EconomyAccountEntity;
import cn.jia.economy.entity.EconomyHostingLeaseEntity;
import cn.jia.economy.hosting.*;
import cn.jia.economy.mapper.*;
import cn.jia.economy.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import static cn.jia.economy.hosting.HostingRentException.Reason.*;

/** DB-only renewal. No provider call, reserve, or bill for an unpaid interval. */
@Service
public final class HostingRentRenewalServiceImpl implements HostingRentRenewalService {
    public static final long PERIOD_MILLIS = 30L * 24 * 60 * 60 * 1000;
    public static final long LOCK_RETENTION_MILLIS = 15L * 24 * 60 * 60 * 1000;
    private final EconomyHostingRentMapper leases;
    private final EconomyHostingRenewalMapper renewal;
    private final EconomyLedgerMapper accounts;
    private final EconomyPostingService posting;
    private final EconomyPreviewGate gate;
    private final TransactionTemplate transactions;
    private List<HostingLeaseChangeListener> listeners = List.of();

    public HostingRentRenewalServiceImpl(EconomyHostingRentMapper leases, EconomyHostingRenewalMapper renewal,
            EconomyLedgerMapper accounts, EconomyPostingService posting, EconomyPreviewGate gate,
            PlatformTransactionManager manager) {
        this.leases = leases; this.renewal = renewal; this.accounts = accounts;
        this.posting = posting; this.gate = gate; this.transactions = new TransactionTemplate(manager);
    }
    @Autowired(required = false)
    public void setLeaseChangeListeners(List<HostingLeaseChangeListener> listeners) {
        this.listeners = List.copyOf(listeners);
    }

    @Override public boolean lockExpiredFinishing(EconomyScope scope, String leaseId, String bindingId, String agentId) {
        gate.requireMutationAllowed(scope.tenantId(), scope.clientId());
        // Without the caller transaction the lease lock would disappear before task capture/release.
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Finishing settlement requires the task root transaction");
        var lease = leases.selectLeaseForUpdate(scope.tenantId(), scope.clientId(), leaseId);
        return lease != null && "FINISHING".equals(lease.getStatus())
                && Objects.equals(bindingId, lease.getBindingId()) && Objects.equals(agentId, lease.getAgentId())
                && lease.getLockedAt() != null
                && renewal.nowMillis() >= Math.addExact(lease.getLockedAt(), LOCK_RETENTION_MILLIS);
    }

    @Override public Result completeHandover(EconomyScope scope, EconomyPrincipal owner, String leaseId,
                                              String bindingId, String agentId) {
        gate.requireMutationAllowed(scope.tenantId(), scope.clientId());
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Handover completion requires the binding release transaction");
        var lease = leases.selectLeaseForUpdate(scope.tenantId(), scope.clientId(), leaseId);
        if (owner == null || owner.type() != EconomyPrincipalType.USER || lease == null
                || !"USER".equals(lease.getPrincipalType()) || !Objects.equals(owner.id(), lease.getPrincipalId())
                || !Objects.equals(bindingId, lease.getBindingId()) || !Objects.equals(agentId, lease.getAgentId()))
            throw new HostingRentException(NOT_FOUND_OR_FORBIDDEN, "lease relationship not found");
        if ("CLOSED".equals(lease.getStatus())) return result(lease, null);
        if (!"FINISHING".equals(lease.getStatus()))
            throw new HostingRentException(LEASE_CONFLICT, "lease is not finishing");
        if (renewal.closeAfterHandover(scope.tenantId(), scope.clientId(), lease, renewal.nowMillis()) != 1)
            throw new HostingRentException(LEASE_CONFLICT, "lease handover changed");
        lease.setStatus("CLOSED");
        return result(lease, null);
    }

    @Override public Long reconcileUser(EconomyScope scope, String userId) {
        gate.requireMutationAllowed(scope.tenantId(), scope.clientId());
        long after = 0; String afterId = "";
        while (true) {
            long cursor = after; String cursorId = afterId;
            var page = transactions.execute(tx -> {
                // Lock the ordered business roots before money. Duplicate/stale owners cannot spend
                // a user's balance on later rows while an earlier page is being reconciled.
                var rows = renewal.duePageForUpdate(scope.tenantId(), scope.clientId(), userId,
                        cursor, cursorId, renewal.nowMillis());
                var changes = new ArrayList<HostingLeaseChangeListener.Change>();
                for (var lease : rows) reconcileLocked(scope, lease, lease.getPaidThrough(), changes);
                // Register only after all money operations; a FINISHING row before a paid row must
                // not acquire the schedule lock before the wallet (credits lock wallet -> schedule).
                publish(changes);
                return new ReconciledPage(rows, registerNextDeadline(scope, userId));
            });
            if (page.leases().size() < 100) return page.nextDeadline();
            var last = page.leases().getLast(); after = last.getPaidThrough(); afterId = last.getLeaseId();
        }
    }

    @Override public Result reconcile(EconomyScope scope, String leaseId, long expectedPaidThrough) {
        gate.requireMutationAllowed(scope.tenantId(), scope.clientId());
        return transactions.execute(tx -> {
            var lease = leases.selectLeaseForUpdate(scope.tenantId(), scope.clientId(), leaseId);
            if (lease == null) throw new HostingRentException(NOT_FOUND_OR_FORBIDDEN, "lease not found");
            var changes = new ArrayList<HostingLeaseChangeListener.Change>();
            var result = reconcileLocked(scope, lease, expectedPaidThrough, changes);
            publish(changes);
            registerNextDeadline(scope, lease.getPrincipalId());
            return result;
        });
    }
    @Override public Result stopRenewal(EconomyScope scope, EconomyPrincipal owner, String leaseId) {
        gate.requireMutationAllowed(scope.tenantId(), scope.clientId());
        return transactions.execute(tx -> {
            var lease = leases.selectLeaseForUpdate(scope.tenantId(), scope.clientId(), leaseId);
            if (owner == null || owner.type() != EconomyPrincipalType.USER || lease == null
                    || !"USER".equals(lease.getPrincipalType())
                    || !Objects.equals(owner.id(), lease.getPrincipalId())) {
                throw new HostingRentException(NOT_FOUND_OR_FORBIDDEN, "lease not found");
            }
            // The root lock serializes removal with both expiry and credit-driven renewal.
            // Repeating a removal cannot resurrect a period or manufacture another finishing event.
            if ("FINISHING".equals(lease.getStatus())) return result(lease, null);
            if (!"ACTIVE".equals(lease.getStatus()) && !"LOCKED".equals(lease.getStatus())) {
                throw new HostingRentException(LEASE_CONFLICT, "lease is not active or locked");
            }
            var changes = new ArrayList<HostingLeaseChangeListener.Change>();
            var stopped = change(scope, lease, "FINISHING", lease.getPaidFrom(), lease.getPaidThrough(),
                    lease.getLockedAt(), renewal.nowMillis(), null, changes);
            publish(changes);
            registerNextDeadline(scope, lease.getPrincipalId());
            return stopped;
        });
    }
    private Result reconcileLocked(EconomyScope scope, EconomyHostingLeaseEntity lease, long expectedPaidThrough,
                                   List<HostingLeaseChangeListener.Change> changes) {
            if ((!"ACTIVE".equals(lease.getStatus()) && !"LOCKED".equals(lease.getStatus()))
                    || !Objects.equals(lease.getPaidThrough(), expectedPaidThrough)) return result(lease, null);
            long now = renewal.nowMillis();
            if (now < expectedPaidThrough) return result(lease, null);
            if (!"USER".equals(lease.getPrincipalType()) || lease.getAmountWen() == null
                    || lease.getAmountWen() <= 0 || !Objects.equals(lease.getPeriodSeconds(), PERIOD_MILLIS / 1000))
                throw new HostingRentException(DATA_CORRUPT, "invalid recurring lease terms");
            // LOCKED is recoverable arrears finishing; FINISHING is irrevocable final removal.
            // Anchor unpaid time to expiry, not scheduler observation: downtime cannot extend grace.
            if ("LOCKED".equals(lease.getStatus()) && lease.getLockedAt() == null)
                throw new HostingRentException(DATA_CORRUPT, "locked lease has no lock time");
            long unpaidSince = "LOCKED".equals(lease.getStatus()) ? lease.getLockedAt() : expectedPaidThrough;
            // At the exact deadline removal wins over credit, including a delayed first expiry wakeup.
            if (now >= Math.addExact(unpaidSince, LOCK_RETENTION_MILLIS))
                return change(scope, lease, "FINISHING", lease.getPaidFrom(), lease.getPaidThrough(),
                        unpaidSince, now, null, changes);
            // Global account order is SYSTEM then USER. Provision the system account with an exclusive lock,
            // not INSERT IGNORE's shared-lock upgrade. Posting reuses these locks in the same transaction.
            accounts.insertAccountIfAbsentAndLock(new EconomyAccountEntity()
                    .setAccountId("system_hosting_rent").setOwnerType("SYSTEM")
                    .setOwnerId(EconomyConstants.HOSTING_RENT_SYSTEM_OWNER_ID).setPurpose("HOSTING_RENT")
                    .setCurrency(EconomyConstants.CURRENCY_WEN).setBalanceWen(0L).setAllowNegative(0)
                    .setStatus("ACTIVE").setVersion(0L).setTenantId(scope.tenantId()).setClientId(scope.clientId())
                    .setCreateTime(now).setUpdateTime(now));
            var wallet = accounts.selectAccountForUpdate(scope.tenantId(), scope.clientId(),
                    EconomyConstants.CURRENCY_WEN, "USER", lease.getPrincipalId(), "AVAILABLE");
            if (wallet == null || !"ACTIVE".equals(wallet.getStatus()))
                throw new HostingRentException(DATA_CORRUPT, "lease payer wallet is missing or inactive");
            if (wallet.getBalanceWen() < lease.getAmountWen()) {
                if ("LOCKED".equals(lease.getStatus())) return result(lease, null); // no lock-time reset
                return change(scope, lease, "LOCKED", lease.getPaidFrom(), lease.getPaidThrough(), unpaidSince, now, null, changes);
            }
            // Stable business period identity, independent of scheduler claims/revisions or retry time.
            byte[] hash = digest(scope.tenantId() + "\0" + scope.clientId() + "\0" + lease.getLeaseId() + "\0" + expectedPaidThrough);
            String businessId = "rent_" + HexFormat.of().formatHex(hash);
            var paid = posting.post(new EconomyPostingCommand(scope,
                    new EconomyPrincipal(EconomyPrincipalType.USER, lease.getPrincipalId()),
                    UUID.nameUUIDFromBytes(hash).toString(), hash, EconomyJournalType.RENEW_HOSTING_RENT, businessId,
                    List.of(new EconomyPostingLine(new EconomyAccountKey(EconomyConstants.CURRENCY_WEN,
                                    EconomyAccountOwnerType.USER, lease.getPrincipalId(), EconomyAccountPurpose.AVAILABLE), -lease.getAmountWen()),
                            new EconomyPostingLine(new EconomyAccountKey(EconomyConstants.CURRENCY_WEN,
                                    EconomyAccountOwnerType.SYSTEM, EconomyConstants.HOSTING_RENT_SYSTEM_OWNER_ID,
                                    EconomyAccountPurpose.HOSTING_RENT), lease.getAmountWen())), null));
            // DB time after the successful posting, not a scheduler/app clock or the unpaid old expiry.
            long started = renewal.nowMillis();
            return change(scope, lease, "ACTIVE", started, Math.addExact(started, PERIOD_MILLIS), null,
                    started, paid.transactionId(), changes);
    }
    private record ReconciledPage(List<EconomyHostingLeaseEntity> leases, Long nextDeadline) { }
    private Long registerNextDeadline(EconomyScope scope, String userId) {
        Long next = renewal.nextDeadline(scope.tenantId(), scope.clientId(), userId);
        for (var listener : listeners) listener.reconciled(scope, userId, next);
        return next;
    }
    private void publish(List<HostingLeaseChangeListener.Change> changes) {
        for (var change : changes) for (var listener : listeners) listener.changed(change);
    }
    private Result change(EconomyScope scope, EconomyHostingLeaseEntity lease, String state,
                          Long from, Long through, Long lockedAt, long now, String transactionId,
                          List<HostingLeaseChangeListener.Change> changes) {
        var next = new EconomyHostingLeaseEntity().setStatus(state).setPaidFrom(from).setPaidThrough(through).setLockedAt(lockedAt);
        if (renewal.update(scope.tenantId(), scope.clientId(), lease, next, now) != 1)
            throw new HostingRentException(CONCURRENCY_CONFLICT, "lease renewal CAS failed");
        changes.add(new HostingLeaseChangeListener.Change(scope,
                lease.getLeaseId(), lease.getPrincipalId(), state, through, lockedAt));
        return result(next, transactionId);
    }
    private static Result result(EconomyHostingLeaseEntity lease, String transactionId) {
        return new Result(lease.getStatus(), lease.getPaidThrough(), lease.getLockedAt(), transactionId);
    }
    private static byte[] digest(String input) {
        try { return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
