package cn.jia.agent.hosting;

import cn.jia.agent.mapper.HostingRentFinishingMapper;
import cn.jia.agent.skill.SkillManagementService;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.economy.hosting.HostingRentRenewalService;
import cn.jia.economy.service.EconomyScope;
import cn.jia.task.scheduling.ScheduledInvocation;
import cn.jia.task.scheduling.ScheduledJobHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Single durable FINISHING owner. Every external call is outside business transactions.
 * Exceptions retain the original scheduled lease key for recovery; no provision/reenroll fallback. */
@Component
@ConditionalOnProperty(name="agent.hosting-rent.auto-renew-enabled",havingValue="true")
public final class HostingRentFinishingHandler implements ScheduledJobHandler {
    private final FixedTenantAdmissionPolicy scope;
    private final HostingRentFinishingMapper discovery;
    private final HostingRentRenewalService renewal;
    private final HostingRentTaskSettlement tasks;
    private final HostingRentManagedStop stop;
    private final ManagedHostingProvisioner runtime;
    private final SkillManagementService skills;
    private final HostingRentHandoverCompletion completion;
    public HostingRentFinishingHandler(FixedTenantAdmissionPolicy scope,HostingRentFinishingMapper discovery,
            HostingRentRenewalService renewal,HostingRentTaskSettlement tasks,HostingRentManagedStop stop,
            ManagedHostingProvisioner runtime,SkillManagementService skills,HostingRentHandoverCompletion completion) {
        this.scope=scope;this.discovery=discovery;this.renewal=renewal;this.tasks=tasks;this.stop=stop;
        this.runtime=runtime;this.skills=skills;this.completion=completion;
    }
    @Override public String getName(){return HostingRentLeaseWakeup.FINISH;}
    @Override public void executeScheduled(ScheduledInvocation invocation) {
        scope.requireAdmitted(invocation.tenantId(),invocation.key().clientId());
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Finishing Runtime I/O requires transaction-external lifecycle owner");
        var partition=new EconomyScope(invocation.tenantId(),invocation.key().clientId());
        String leaseId=invocation.key().businessKey();
        var binding=discovery.findFinishingBinding(partition.tenantId(),partition.clientId(),leaseId);
        if(binding==null)return; // original closed/stale work, never resolve a new user's invitation
        var intent=renewal.initialHandoverIntent(partition,leaseId);
        if(intent==null)throw new IllegalStateException("Original handover intent unavailable");
        tasks.settleExpiredTasks(partition,leaseId);
        var initial=new ManagedHostingProvisioner.Preparation(partition.tenantId(),partition.clientId(),binding.getJiacn(),
                binding.getAgentId(),intent.getIntentId(),leaseId,String.valueOf(binding.getId()),intent.getReservedAt());
        var current=stop.stop(initial);
        if(current==null)throw new IllegalStateException("Original executor stop unconfirmed");
        var p=current.preparation();var candidate=current.candidate();
        if(!runtime.flushEvidence(p,candidate,leaseId))throw new IllegalStateException("Original evidence delivery unconfirmed");
        if(!skills.settleHandoverChanges(current))throw new IllegalStateException("Original skill settlement unresolved");
        if(!runtime.cleanup(p,candidate,leaseId))throw new IllegalStateException("Original private cleanup unconfirmed");
        completion.completeManaged(current);
    }
}
