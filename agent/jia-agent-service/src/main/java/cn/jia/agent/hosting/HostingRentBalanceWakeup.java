package cn.jia.agent.hosting;

import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.economy.service.EconomyAvailableBalanceListener;
import cn.jia.task.scheduling.ScheduleKey;
import cn.jia.task.scheduling.ScheduleService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Balance fact -> same-transaction durable wakeup. No Agent lookup or renewal under wallet locks. */
@Component
@ConditionalOnProperty(name = "agent.hosting-rent.auto-renew-enabled", havingValue = "true")
public final class HostingRentBalanceWakeup implements EconomyAvailableBalanceListener {
    public static final String HANDLER = "hosting-rent.balance-increased";
    private final ScheduleService schedules;
    private final FixedTenantAdmissionPolicy scope;

    public HostingRentBalanceWakeup(ScheduleService schedules, FixedTenantAdmissionPolicy scope) {
        this.schedules = schedules; this.scope = scope;
    }
    @Override public void increased(Increase increase) {
        // The posting scope must match the configured partition used by schedule storage.
        scope.requireAdmitted(increase.scope().tenantId(), increase.scope().clientId());
        schedules.signal(new ScheduleKey(increase.scope().clientId(), HANDLER, increase.userId(), ""), 0);
    }
}
