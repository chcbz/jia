package cn.jia.agent.hosting;

import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.economy.hosting.HostingRentRenewalService;
import cn.jia.economy.service.EconomyScope;
import cn.jia.task.scheduling.ScheduledInvocation;
import cn.jia.task.scheduling.ScheduledJobHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Expiry and balance events coalesce per user; one ordering responsibility, no per-Agent idle polling. */
@Component
@ConditionalOnProperty(name = "agent.hosting-rent.auto-renew-enabled", havingValue = "true")
public final class HostingRentRenewalHandler implements ScheduledJobHandler {
    private final HostingRentRenewalService renewal;
    private final FixedTenantAdmissionPolicy scope;
    public HostingRentRenewalHandler(HostingRentRenewalService renewal,
                                     FixedTenantAdmissionPolicy scope) {
        this.renewal = renewal; this.scope = scope;
    }
    @Override public ExecutionClass executionClass() { return ExecutionClass.CONTROL; }
    @Override public String getName() { return HostingRentBalanceWakeup.HANDLER; }
    @Override public void executeScheduled(ScheduledInvocation invocation) {
        scope.requireAdmitted(invocation.tenantId(), invocation.key().clientId());
        renewal.reconcileUser(new EconomyScope(invocation.tenantId(), invocation.key().clientId()),
                invocation.key().businessKey());
    }
}
