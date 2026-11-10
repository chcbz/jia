package cn.jia.agent.hosting;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.AgentRuntimeV1InstallationDao;
import cn.jia.agent.mapper.HostingRentFinishingMapper;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentRuntimeV1Service;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.economy.common.EconomyPrincipalType;
import cn.jia.economy.hosting.HostingRentRenewalService;
import cn.jia.economy.mapper.EconomyHostingRentMapper;
import cn.jia.economy.service.EconomyPrincipal;
import cn.jia.economy.service.EconomyScope;
import cn.jia.oauth.service.ApiKeyService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Objects;

/** Last DB-only handover step. The lifecycle owner invokes this only after Runtime CLEANED.
 * No public controller, process control, account-login lookup, deletion or standalone financial release. */
@Service
public class HostingRentHandoverCompletion {
    private final AgentRuntimeV1InstallationDao installations;
    private final AgentPersonaBindingDao bindings;
    private final EconomyHostingRentMapper leases;
    private final HostingRentRenewalService renewal;
    private final AgentIdentityService identities;
    private final AgentRuntimeDao runtimes;
    private final AgentRuntimeV1Service runtime;
    private final HostingRentFinishingMapper tasks;
    private final ObjectProvider<ApiKeyService> keys;
    private final FixedTenantAdmissionPolicy scope;

    public HostingRentHandoverCompletion(AgentRuntimeV1InstallationDao installations, AgentPersonaBindingDao bindings,
            EconomyHostingRentMapper leases, HostingRentRenewalService renewal, AgentIdentityService identities,
            AgentRuntimeDao runtimes, AgentRuntimeV1Service runtime, HostingRentFinishingMapper tasks,
            ObjectProvider<ApiKeyService> keys, FixedTenantAdmissionPolicy scope) {
        this.installations=installations; this.bindings=bindings; this.leases=leases; this.renewal=renewal;
        this.identities=identities; this.runtimes=runtimes; this.runtime=runtime; this.tasks=tasks; this.keys=keys; this.scope=scope;
    }

    @Transactional(rollbackFor = Exception.class)
    public void completeManaged(ManagedHostingProvisioner.CurrentOperation cleaned) {
        var p=cleaned.preparation(); var candidate=cleaned.candidate();
        scope.requireAdmitted(p.tenantId(), p.clientId());
        // Same root order as installation authorization: installation -> binding -> intent -> lease.
        var installation=installations.lock(candidate.installationId());
        long bindingId=Long.parseLong(p.bindingId());
        var binding=bindings.findByIdForUpdate(bindingId);
        var intent=leases.selectIntentForUpdate(p.tenantId(), p.clientId(), p.intentId());
        var lease=leases.selectLeaseForUpdate(p.tenantId(), p.clientId(), p.leaseId());
        require(binding!=null && Objects.equals(binding.getId(), bindingId)
                && p.tenantId().equals(binding.getTenantId()) && p.clientId().equals(binding.getClientId())
                && p.ownerJiacn().equals(binding.getJiacn()) && p.agentId().equals(binding.getAgentId()));
        require(intent!=null && "INITIAL".equals(intent.getQuotePurpose()) && "USER".equals(intent.getPrincipalType())
                && "ACTIVE".equals(intent.getStatus()) && intent.getRefundTransactionId()==null
                && p.agentId().equals(intent.getAgentId()) && p.leaseId().equals(intent.getLeaseId())
                && Objects.equals(p.reservedAt(), intent.getReservedAt())
                && candidate.installationId().equals(intent.getRuntimeInstallationId())
                && candidate.manifestSha256().equals(intent.getRuntimeManifestSha256()));
        require(lease!=null && "USER".equals(lease.getPrincipalType())
                && intent.getPrincipalId().equals(lease.getPrincipalId()) && p.agentId().equals(lease.getAgentId())
                && p.bindingId().equals(lease.getBindingId()) && binding.getPersonaCode().equals(lease.getPersonaCode()));
        // Historical completion replay never revokes a later invitation's authorization or clears its projection.
        if ("CLOSED".equals(lease.getStatus())) return;
        require("FINISHING".equals(lease.getStatus()) && Integer.valueOf(AgentConstants.BINDING_STATUS_ACTIVE).equals(binding.getStatus()));
        require(installation!=null && p.tenantId().equals(installation.getTenantId()) && p.clientId().equals(installation.getClientId())
                && p.agentId().equals(installation.getCanonicalAgentId()) && candidate.manifestSha256().equals(installation.getManifestSha256()));
        require(tasks.unfinishedPage(p.tenantId(), p.clientId(), p.ownerJiacn(), p.agentId(), 0).isEmpty());
        var projection=runtimes.findByAgentIdForUpdate(p.agentId());
        require(projection!=null && projection.getId()!=null && Objects.equals(projection.getBindingId(), bindingId)
                && p.clientId().equals(projection.getClientId()) && p.ownerJiacn().equals(projection.getOwnerJiacn())
                && candidate.installationId().equals(projection.getRuntimeInstallationId())
                && candidate.hostId().equals(projection.getRuntimeHostId()));
        long now=System.currentTimeMillis();
        runtime.revoke(p.tenantId(), p.clientId(), p.ownerJiacn(), candidate.installationId(), now);
        if (intent.getManagedApiKeyId()!=null) {
            var keyService=keys.getIfAvailable();
            require(keyService!=null && keyService.disableManagedKey(intent.getManagedApiKeyId(), p.tenantId(),
                    p.clientId(), p.ownerJiacn(), "hosting:"+p.intentId()));
        }
        renewal.completeHandover(new EconomyScope(p.tenantId(), p.clientId()),
                new EconomyPrincipal(EconomyPrincipalType.USER, intent.getPrincipalId()), p.leaseId(), p.bindingId(), p.agentId());
        binding.setStatus(AgentConstants.BINDING_STATUS_SUSPENDED);
        require(bindings.updateById(binding)==1);
        identities.suspendForBinding(p.tenantId(), p.clientId(), p.ownerJiacn(), bindingId);
        require(runtimes.clearBindingAfterUnbind(projection.getId(), p.agentId(), bindingId,
                p.clientId(), p.ownerJiacn(), now)==1);
    }
    private static void require(boolean condition) {
        if (!condition) throw new IllegalStateException("Original hosting handover relationship changed or remains unfinished");
    }
}
