package cn.jia.chat.archive.maintenance.service;

import cn.jia.agent.service.AgentPlatformSkillProvisioningPolicy;
import cn.jia.chat.archive.maintenance.model.ArchiveActorScope;
import cn.jia.chat.archive.maintenance.store.ArchiveMaintenanceStore;
import org.springframework.stereotype.Service;

/** v1 catalog maps archive-maintainer to the platform-classics collection only. */
@Service
public final class ArchivePlatformSkillProvisioningPolicy implements AgentPlatformSkillProvisioningPolicy {
    private final ArchiveMaintenanceStore store;
    public ArchivePlatformSkillProvisioningPolicy(ArchiveMaintenanceStore store) { this.store=store; }
    @Override public void requireAllowed(String tenant,String client,String owner,String skillKey,boolean lock) {
        if(!"archive-maintainer".equals(skillKey)) throw new IllegalArgumentException("Platform skill not authorized");
        var grant=store.findManagerGrant(new ArchiveActorScope(tenant,client,owner),"platform-classics",lock);
        if(grant==null || !"platform-classics".equals(grant.collectionId()) || !tenant.equals(grant.tenantId())
                || !client.equals(grant.clientId()) || !owner.equals(grant.ownerJiacn()) || !grant.allows("appoint"))
            throw new IllegalArgumentException("Platform skill management authorization required");
    }
}
