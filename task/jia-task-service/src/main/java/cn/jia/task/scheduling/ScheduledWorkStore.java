package cn.jia.task.scheduling;

import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.task.entity.ScheduledWorkRow;
import cn.jia.task.mapper.ScheduledWorkMapper;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Same-database transactional schedule store; no callback, Redis call or wallet logic under its locks.
 * Not auto-activated: registration/migration and scheduler wiring are a separate rollout.
 */
public final class ScheduledWorkStore implements ScheduleService {
    private static final Logger log = LoggerFactory.getLogger(ScheduledWorkStore.class);
    public record LeasedWork(ScheduleKey key, ScheduledWork.Claim claim) { }
    private final ScheduledWorkMapper mapper;
    private final FixedTenantAdmissionPolicy scope;
    private final TransactionTemplate transaction;
    private final Consumer<ScheduleKey> wakeup;

    public ScheduledWorkStore(ScheduledWorkMapper mapper, FixedTenantAdmissionPolicy scope,
            PlatformTransactionManager manager) {
        this(mapper, scope, manager, key -> { });
    }

    public ScheduledWorkStore(ScheduledWorkMapper mapper, FixedTenantAdmissionPolicy scope,
            PlatformTransactionManager manager, Consumer<ScheduleKey> wakeup) {
        this.mapper = mapper; this.scope = scope; this.transaction = new TransactionTemplate(manager);
        this.wakeup = wakeup;
    }

    /** Joins the caller's business transaction: rollback also rolls back the notification. */
    public void signal(ScheduleKey key, long at) { change(key, work -> work.signal(at), true); }
    public void reschedule(ScheduleKey key, long at) { change(key, work -> work.reschedule(at), true); }
    public void cancel(ScheduleKey key) { change(key, ScheduledWork::cancel, false); }

    private void change(ScheduleKey key, Function<ScheduledWork, ScheduledWork> transition, boolean create) {
        ScheduleAdmission.requireClient(scope, key.clientId());
        transaction.executeWithoutResult(status -> {
            if (create) mapper.ensure(scope.businessTenantId(), key);
            var row = mapper.lock(scope.businessTenantId(), key);
            if (row != null) {
                var next = transition.apply(row.state());
                save(row, next);
                if (next.version() != row.getVersion()) notifyAfterCommit(key);
            }
        });
    }

    /** Index-only deadline discovery. Node identity comes from scheduler configuration, not HTTP. */
    public Long nextWake(String node) {
        return mapper.nextWake(scope.businessTenantId(), scope.clientId(), node);
    }

    /** Returns only after commit; caller must invoke business handlers outside this method. */
    public List<LeasedWork> claimDue(String node, int limit, long leaseMillis) {
        requireWorkerBoundary();
        if (limit <= 0 || leaseMillis <= 0) throw new IllegalArgumentException("Positive batch and lease required");
        return transaction.execute(status -> {
            long now = mapper.nowMillis();
            long until = Math.addExact(now, leaseMillis);
            var claimed = new ArrayList<LeasedWork>();
            for (var row : mapper.due(scope.businessTenantId(), scope.clientId(), node, now, limit)) {
                var result = row.state().acquire(now, until, UUID.randomUUID().toString());
                save(row, result.work());
                result.acquired().ifPresent(claim -> claimed.add(new LeasedWork(row.key(), claim)));
            }
            return List.copyOf(claimed);
        });
    }

    /** Delay uses the same DB clock as acquisition, avoiding application clock skew. */
    public Long nextDelay(String node) {
        return mapper.nextDelay(scope.businessTenantId(), scope.clientId(), node);
    }

    public boolean retryAfter(LeasedWork work, long delayMillis) {
        if (delayMillis <= 0) throw new IllegalArgumentException("Positive retry delay required");
        return settle(work, (state, now) -> state.retry(work.claim(), now, Math.addExact(now, delayMillis)));
    }
    public boolean renewFor(LeasedWork work, long leaseMillis) {
        if (leaseMillis <= 0) throw new IllegalArgumentException("Positive lease required");
        return settle(work, (state, now) -> state.renew(work.claim(), now, Math.addExact(now, leaseMillis)));
    }
    public boolean succeed(LeasedWork work) {
        return settle(work, (state, now) -> state.succeed(work.claim(), now));
    }
    public boolean retry(LeasedWork work, long retryAt) {
        return settle(work, (state, now) -> state.retry(work.claim(), now, retryAt));
    }
    public boolean renew(LeasedWork work, long expiresAt) {
        return settle(work, (state, now) -> state.renew(work.claim(), now, expiresAt));
    }

    private boolean settle(LeasedWork work,
            java.util.function.BiFunction<ScheduledWork, Long, Optional<ScheduledWork>> transition) {
        requireWorkerBoundary();
        ScheduleAdmission.requireClient(scope, work.key().clientId());
        return Boolean.TRUE.equals(transaction.execute(status -> {
            var row = mapper.lock(scope.businessTenantId(), work.key());
            if (row == null) return false;
            var changed = transition.apply(row.state(), mapper.nowMillis());
            changed.ifPresent(state -> save(row, state));
            return changed.isPresent();
        }));
    }

    private void save(ScheduledWorkRow row, ScheduledWork next) {
        if (row.getVersion() == next.version()) return;
        if (mapper.replace(ScheduledWorkRow.of(scope.businessTenantId(), row.key(), next), row.getVersion()) != 1)
            throw new OptimisticLockingFailureException("Scheduled work changed concurrently");
    }

    private void notifyAfterCommit(ScheduleKey key) {
        // Transaction synchronizations suspend/resume correctly with REQUIRES_NEW. No custom thread-local.
        for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof WakeupSynchronization pending && pending.owner == this) {
                pending.keys.add(key);
                return;
            }
        }
        var pending = new WakeupSynchronization();
        pending.keys.add(key);
        TransactionSynchronizationManager.registerSynchronization(pending);
    }

    private final class WakeupSynchronization implements TransactionSynchronization {
        private final ScheduledWorkStore owner = ScheduledWorkStore.this;
        private final Set<ScheduleKey> keys = new LinkedHashSet<>();
        @Override public void afterCommit() {
            for (var key : keys) {
                try { wakeup.accept(key); }
                catch (RuntimeException failure) {
                    // DB fact is committed. A wakeup is only an accelerator; recovery reads the due index.
                    log.warn("Schedule wakeup failed after commit; durable work retained", failure);
                }
            }
        }
    }

    private static void requireWorkerBoundary() {
        // Suspending a wallet transaction would retain its locks. Reject nesting instead.
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Schedule claim/settlement must run outside a business transaction");
    }
}
