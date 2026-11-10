package cn.jia.agent.hosting;

import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.AgentTaskFormalDeliveryDao;
import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.agent.entity.AgentTaskFormalDeliveryDecisionDTO;
import cn.jia.agent.mapper.HostingRentFinishingMapper;
import cn.jia.agent.service.AgentTaskCancellationService;
import cn.jia.agent.service.AgentTaskFormalDeliveryDecisionService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.economy.mapper.EconomyHostingRentMapper;
import cn.jia.economy.mapper.EconomyHostingRenewalMapper;
import cn.jia.economy.service.EconomyScope;
import org.springframework.stereotype.Service;
import java.util.Objects;
import java.util.Set;

/** Only the task phase of handover. No lease release, Runtime deletion or claim of process exit. */
@Service
public final class HostingRentTaskSettlement {
    private static final long RETENTION = 15L * 24 * 60 * 60 * 1000;
    private final HostingRentFinishingMapper discovery;
    private final AgentPersonaBindingDao bindings;
    private final EconomyHostingRentMapper leases;
    private final EconomyHostingRenewalMapper clock;
    private final AgentTaskMutationTransaction tasks;
    private final AgentTaskFormalDeliveryDao deliveries;
    private final AgentTaskFormalDeliveryDecisionService decisions;
    private final AgentTaskCancellationService cancellation;
    private final FixedTenantAdmissionPolicy scopePolicy;

    public HostingRentTaskSettlement(HostingRentFinishingMapper discovery, AgentPersonaBindingDao bindings,
            EconomyHostingRentMapper leases, EconomyHostingRenewalMapper clock, AgentTaskMutationTransaction tasks,
            AgentTaskFormalDeliveryDao deliveries, AgentTaskFormalDeliveryDecisionService decisions,
            AgentTaskCancellationService cancellation, FixedTenantAdmissionPolicy scopePolicy) {
        this.discovery=discovery; this.bindings=bindings; this.leases=leases; this.clock=clock;
        this.tasks=tasks; this.deliveries=deliveries; this.decisions=decisions;
        this.cancellation=cancellation; this.scopePolicy=scopePolicy;
    }

    /** Trusted schedule scope; no caller-provided owner or task list. Voluntary removal does not force old work. */
    public void settleExpiredTasks(EconomyScope scope, String leaseId) {
        scopePolicy.requireAdmitted(scope.tenantId(), scope.clientId());
        var binding = discovery.findFinishingBinding(scope.tenantId(), scope.clientId(), leaseId);
        if (binding == null) return; // already released/stale scheduled key
        long after = 0;
        while (true) {
            var page = discovery.unfinishedPage(scope.tenantId(), scope.clientId(), binding.getJiacn(),
                    binding.getAgentId(), after);
            for (var task : page) settleOne(scope, leaseId, binding, task.getTaskId());
            if (page.size() < 100) return;
            after = page.getLast().getId();
        }
    }

    private void settleOne(EconomyScope scope, String leaseId, AgentPersonaBindingEntity expected, String taskId) {
        tasks.executeWithLockedTaskRootInOwnerScope(scope.tenantId(), scope.clientId(), expected.getJiacn(), taskId, root -> {
            // Same order as new acceptance: task -> binding -> financial lease. Never lease -> task.
            var binding = bindings.findExactActiveByScopeAndPersonaForUpdate(scope.tenantId(), scope.clientId(),
                    expected.getJiacn(), expected.getPersonaCode());
            if (binding == null || !Objects.equals(binding.getId(), expected.getId())
                    || !Objects.equals(binding.getAgentId(), expected.getAgentId())) return null;
            var lease = leases.selectLeaseForUpdate(scope.tenantId(), scope.clientId(), leaseId);
            if (lease == null || !"FINISHING".equals(lease.getStatus())
                    || !Objects.equals(lease.getBindingId(), String.valueOf(binding.getId()))
                    || !Objects.equals(lease.getAgentId(), binding.getAgentId())
                    || lease.getLockedAt() == null || clock.nowMillis() < Math.addExact(lease.getLockedAt(), RETENTION)) return null;
            if (Set.of("completed", "cancelled", "archived").contains(root.getRewardStatus())) return null;
            if (discovery.related(scope.tenantId(), scope.clientId(), expected.getJiacn(),
                    expected.getAgentId(), taskId) != 1) return null; // assignment changed since discovery
            if ("reviewing".equals(root.getRewardStatus())) {
                var delivery = deliveries.findLatestTaskForUpdate(scope.tenantId(), scope.clientId(), taskId);
                // A reviewing label without a submitted formal delivery is not proof of delivered work.
                if (delivery == null || !"submitted".equals(delivery.getState()))
                    throw new IllegalStateException("Reviewing task has no submitted formal delivery");
                var command = new AgentTaskFormalDeliveryDecisionDTO();
                command.setDeliveryId(delivery.getDeliveryId()); command.setExpectedDeliveryVersion(delivery.getVersion());
                command.setExpectedTaskVersion(root.getTaskVersion()); command.setDecision("accepted");
                decisions.acceptForHostingExpiry(scope.tenantId(), scope.clientId(), taskId, expected.getJiacn(), command);
            } else {
                cancellation.cancel(scope.tenantId(), scope.clientId(), expected.getJiacn(),
                        "hosting-rent-expiry", taskId, root.getTaskVersion());
            }
            return null;
        });
    }
}
