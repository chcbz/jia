package cn.jia.task.scheduling;

import cn.jia.core.security.FixedTenantAdmissionPolicy;

/** Internal scheduling scope only. Empty client denotes tenant maintenance, never an external identity.
 * A client-restricted process may neither register nor settle tenant-wide work. Discovery applies
 * the same distinction in SQL before ordering/locking. Normal client admission remains unchanged.
 */
final class ScheduleAdmission {
    private ScheduleAdmission() { }

    static void requireClient(FixedTenantAdmissionPolicy scope, String client) {
        if ("".equals(client) && scope.clientId() == null) return;
        scope.tenantForClient(client);
    }

    static void requireInvocation(FixedTenantAdmissionPolicy scope, ScheduledInvocation invocation) {
        if (!scope.businessTenantId().equals(invocation.tenantId()))
            throw new IllegalArgumentException("Scheduled tenant is not admitted");
        requireClient(scope, invocation.key().clientId());
    }
}
