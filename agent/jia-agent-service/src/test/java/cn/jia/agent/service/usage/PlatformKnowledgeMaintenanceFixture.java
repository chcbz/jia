package cn.jia.agent.service.usage;

import cn.jia.agent.entity.*;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.mapper.HostingRentFinishingMapper;
import cn.jia.economy.entity.*;
import cn.jia.economy.hosting.HostingRentRenewalService;
import static org.mockito.Mockito.*;

/** Explicit synthetic relationship collaborators; never a substitute for identity/lease SQL acceptance. */
public final class PlatformKnowledgeMaintenanceFixture {
    public final HostingRentFinishingMapper relationships = mock(HostingRentFinishingMapper.class);
    public final AgentPersonaBindingDao bindings = mock(AgentPersonaBindingDao.class);
    public final HostingRentRenewalService renewal = mock(HostingRentRenewalService.class);
    public final AgentPersonaBindingEntity binding = new AgentPersonaBindingEntity();
    public final EconomyHostingProvisioningIntentEntity intent = new EconomyHostingProvisioningIntentEntity();
    public final EconomyHostingLeaseEntity lease = new EconomyHostingLeaseEntity();
    public final AgentRuntimeV1InstallationView installation;
    public PlatformKnowledgeMaintenanceFixture(String tenant, String client, String agent, String installationId) {
        installation = new AgentRuntimeV1InstallationView(installationId, tenant, client, agent, "1", "a".repeat(64), 0, "ACTIVE", null);
        binding.setId(3L); binding.setTenantId(tenant); binding.setClientId(client);
        binding.setJiacn("owner").setAgentId(agent).setPersonaCode("wuyong").setStatus(1);
        intent.setIntentId("initial"); intent.setQuotePurpose("INITIAL"); intent.setStatus("ACTIVE");
        intent.setPrincipalType("USER"); intent.setPrincipalId("7"); intent.setLeaseId("lease"); intent.setAgentId(agent);
        intent.setRuntimeInstallationId(installationId); intent.setRuntimeManifestSha256(installation.manifestSha256());
        lease.setLeaseId("lease"); lease.setStatus("FINISHING"); lease.setExecutorStoppedAt(100L);
        lease.setHandoverId("handover"); lease.setBindingId("3"); lease.setAgentId(agent); lease.setPersonaCode("wuyong");
        lease.setPrincipalType("USER"); lease.setPrincipalId("7");
        when(relationships.findFinishingBinding(tenant, client, "lease")).thenReturn(binding);
        when(bindings.findByIdForUpdate(3L)).thenReturn(binding);
        var scope = new cn.jia.economy.service.EconomyScope(tenant, client);
        when(renewal.initialHandoverIntent(scope, "lease")).thenReturn(intent);
        when(renewal.lockHandover(scope, "initial", "lease")).thenReturn(new HostingRentRenewalService.HandoverSnapshot(intent, lease));
    }
}
