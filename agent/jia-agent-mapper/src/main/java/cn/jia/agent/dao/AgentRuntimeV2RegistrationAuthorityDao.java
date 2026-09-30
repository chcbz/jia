package cn.jia.agent.dao;
import cn.jia.agent.entity.AgentRuntimeV2RegistrationAuthorityEntity;
public interface AgentRuntimeV2RegistrationAuthorityDao {
    int append(AgentRuntimeV2RegistrationAuthorityEntity authority);
    AgentRuntimeV2RegistrationAuthorityEntity lockByStableId(String authorityId);
    AgentRuntimeV2RegistrationAuthorityEntity lockExactSubject(String credentialKind,String credentialReference,
            String tenantId,String runtimeCredentialClientId,String ownerJiacn,String agentId);
}
