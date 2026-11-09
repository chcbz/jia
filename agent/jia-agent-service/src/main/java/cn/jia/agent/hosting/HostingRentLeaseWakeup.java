package cn.jia.agent.hosting;

import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.economy.hosting.HostingLeaseChangeListener;
import cn.jia.economy.service.EconomyScope;
import cn.jia.task.scheduling.ScheduleKey;
import cn.jia.task.scheduling.ScheduleService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Atomic lease fact -> durable due/finishing work; no business query or external call. */
@Component
@ConditionalOnProperty(name = "agent.hosting-rent.auto-renew-enabled", havingValue = "true")
public final class HostingRentLeaseWakeup implements HostingLeaseChangeListener {
    public static final String FINISH = "hosting-rent.finish";
    private static final long RETENTION = 15L * 24 * 60 * 60 * 1000;
    private final ScheduleService schedules;
    private final FixedTenantAdmissionPolicy scope;
    public HostingRentLeaseWakeup(ScheduleService schedules, FixedTenantAdmissionPolicy scope) {
        this.schedules = schedules; this.scope = scope;
    }
    @Override public void reconciled(EconomyScope partition, String userId, Long nextDeadline) {
        scope.requireAdmitted(partition.tenantId(), partition.clientId());
        if (nextDeadline != null) schedules.signal(new ScheduleKey(partition.clientId(),
                HostingRentBalanceWakeup.HANDLER, userId, ""), nextDeadline);
    }
    @Override public void changed(Change change) {
        scope.requireAdmitted(change.scope().tenantId(), change.scope().clientId());
        var due = new ScheduleKey(change.scope().clientId(), HostingRentBalanceWakeup.HANDLER, change.userId(), "");
        switch (change.status()) {
            case "ACTIVE" -> schedules.signal(due, change.paidThrough());
            case "LOCKED" -> schedules.signal(due, Math.addExact(change.lockedAt(), RETENTION + 1));
            case "FINISHING" -> {
                schedules.signal(new ScheduleKey(change.scope().clientId(), FINISH, change.leaseId(), ""), 0);
            }
            default -> { /* Other leases of this user retain their deadlines. */ }
        }
    }
}
