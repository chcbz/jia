package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentRuntimeV2RegistrationAuthorityDao;
import cn.jia.agent.entity.AgentRuntimeV2RegistrationAuthorityEntity;
import cn.jia.agent.mapper.AgentRuntimeV2RegistrationAuthorityMapper;
import jakarta.inject.Named;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static cn.jia.agent.dao.impl.AgentRuntimeV2RegistrationDaoSupport.*;

@Named @RequiredArgsConstructor
public class AgentRuntimeV2RegistrationAuthorityDaoImpl implements AgentRuntimeV2RegistrationAuthorityDao {
    private final AgentRuntimeV2RegistrationAuthorityMapper mapper;
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public int append(AgentRuntimeV2RegistrationAuthorityEntity e){transaction(true);if(e.getId()!=null)throw new IllegalArgumentException("authority database id must be absent");id(e.getRegistrationAuthorityId(),"registrationAuthorityId");if(!"API_KEY".equals(e.getCredentialKind()))throw new IllegalArgumentException("R2b network authority must be API_KEY");exact(e.getCredentialReference(),200,"credentialReference");hash(e.getCredentialDigest(),"credentialDigest");exact(e.getCredentialKeyId(),100,"credentialKeyId");tenant(e.getTenantId());exact(e.getClientId(),50,"clientId");exact(e.getOwnerJiacn(),100,"ownerJiacn");exact(e.getAgentId(),100,"agentId");exact(e.getDataDomainId(),64,"dataDomainId");exact(e.getExecutionPoolId(),100,"executionPoolId");exact(e.getModuleId(),64,"moduleId");exact(e.getClaimantDeploymentId(),100,"claimantDeploymentId");nonnegative(e.getAuthorityRevision(),"authorityRevision");if(!"ACTIVE".equals(e.getStatus())||e.getActiveCredentialReference()!=null||e.getActiveBusinessSubject()!=null||e.getRevokedAt()!=null||e.getRevokeReason()!=null||e.getVersion()==null||e.getVersion()!=0)throw new IllegalArgumentException("new authority must be canonical ACTIVE revision");return mapper.insert(e);}
    @Override @Transactional(propagation=Propagation.MANDATORY,readOnly=true) public AgentRuntimeV2RegistrationAuthorityEntity lockByStableId(String authorityId){transaction(false);id(authorityId,"authorityId");return mapper.lockById(authorityId);}
    @Override @Transactional(propagation=Propagation.MANDATORY,readOnly=true) public AgentRuntimeV2RegistrationAuthorityEntity lockExactSubject(String kind,String reference,String tenant,String client,String owner,String agent){transaction(false);if(!"API_KEY".equals(kind))throw new IllegalArgumentException("network authority must be API_KEY");exact(reference,200,"credentialReference");tenant(tenant);exact(client,50,"clientId");exact(owner,100,"ownerJiacn");exact(agent,100,"agentId");return mapper.lockExactSubject(kind,reference,tenant,client,owner,agent);}
}
