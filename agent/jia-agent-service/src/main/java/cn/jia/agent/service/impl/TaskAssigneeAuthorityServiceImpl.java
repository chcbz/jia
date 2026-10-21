package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentExternalServicePolicyDao;
import cn.jia.agent.dao.AgentTaskExecutionGrantDao;
import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.service.*;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.List;
import java.util.Objects;
import static cn.jia.agent.service.AgentTaskExecutionGrantException.Reason.*;

@Named
public final class TaskAssigneeAuthorityServiceImpl implements TaskAssigneeAuthorityService {
    private final AgentExternalServicePolicyDao policies;
    private final AgentIdentityService identities;
    private final AgentTaskExecutionGrantDao grants;
    @Inject public TaskAssigneeAuthorityServiceImpl(AgentExternalServicePolicyDao policies,
            AgentIdentityService identities, AgentTaskExecutionGrantDao grants) {
        this.policies=policies; this.identities=identities; this.grants=grants;
    }
    public Target resolveForAssignment(String tenant,String client,String taskOwner,String agent) {
        var identity=identity(tenant,client,agent);
        identities.requireCanonicalAgentIdInScope(tenant,client,identity.getOwnerJiacn(),agent);
        return target(identity,null);
    }
    public Target lockForAssignment(String tenant,String client,String taskOwner,String agent) {
        var observed=identity(tenant,client,agent);
        identities.lockActiveCanonicalAgentIdsInScope(tenant,client,observed.getOwnerJiacn(),List.of(agent));
        // This service joins the caller's task-root transaction. The identity/binding lock
        // also serializes switch writes, including the first absent-policy operation.
        var current=identity(tenant,client,agent);
        if (!Objects.equals(observed.getBindingId(),current.getBindingId())
                || !Objects.equals(observed.getOwnerJiacn(),current.getOwnerJiacn())) throw missing();
        Long revision=null;
        if (!taskOwner.equals(current.getOwnerJiacn())) {
            var policy=policies.latestForUpdate(tenant,client,agent,current.getBindingId());
            if (policy==null || !Boolean.TRUE.equals(policy.getEnabled()))
                throw new AgentTaskExecutionGrantException(CONFLICT,"Agent is not accepting new tasks");
            revision=policy.getRevision();
        }
        return target(current,revision);
    }
    public Target requireGrantTarget(String tenant,String client,String taskOwner,String task,
            String grantId,long version) {
        var grant=grants.findByGrant(tenant,client,taskOwner,task,grantId);
        if (grant==null || !Objects.equals(grant.getGrantVersion(),version)
                || grant.getExecutorOwnerJiacn()==null || grant.getExecutorBindingId()==null) throw missing();
        var identity=identity(tenant,client,grant.getTargetAgentId());
        if (!Objects.equals(identity.getOwnerJiacn(),grant.getExecutorOwnerJiacn())
                || !Objects.equals(identity.getBindingId(),grant.getExecutorBindingId())) throw missing();
        identities.requireActiveIdentityForBinding(tenant,client,identity.getOwnerJiacn(),
                identity.getBindingId(),identity.getCanonicalAgentId());
        return target(identity,grant.getExternalPolicyRevision());
    }
    private AgentIdentityRegistryEntity identity(String tenant,String client,String agent) {
        if (tenant==null || client==null || agent==null) throw missing();
        var i=policies.identity(tenant,client,agent);
        if (i==null || !tenant.equals(i.getTenantId()) || !client.equals(i.getClientId())
                || !agent.equals(i.getCanonicalAgentId()) || !"ACTIVE".equals(i.getLifecycleStatus())
                || i.getOwnerJiacn()==null || i.getBindingId()==null || i.getBindingId()<=0
                || "SYSTEM".equals(i.getCanonicalType())) throw missing();
        return i;
    }
    private static Target target(AgentIdentityRegistryEntity i,Long revision) {
        return new Target(i.getTenantId(),i.getClientId(),i.getOwnerJiacn(),i.getCanonicalAgentId(),i.getBindingId(),revision);
    }
    private static AgentTaskExecutionGrantException missing() {
        return new AgentTaskExecutionGrantException(NOT_FOUND,"Task execution target is unavailable");
    }
}
