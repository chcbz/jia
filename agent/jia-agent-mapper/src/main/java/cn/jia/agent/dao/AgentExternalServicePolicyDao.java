package cn.jia.agent.dao;

import cn.jia.agent.entity.*;
import java.util.List;
public interface AgentExternalServicePolicyDao {
    AgentExternalServicePolicyEntity latestForUpdate(String tenant,String client,String agent,long binding);
    AgentExternalServicePolicyEntity receiptForUpdate(String tenant,String client,String owner,String key);
    AgentExternalServicePolicyEntity latest(String tenant, String client, String agent, long binding);
    AgentExternalServicePolicyEntity receipt(String tenant, String client, String owner, String key);
    AgentIdentityRegistryEntity identity(String tenant, String client, String agent);
    List<AgentIdentityRegistryEntity> candidates(String tenant, String client, String owner, String after, int limit);
    void insert(AgentExternalServicePolicyEntity policy);
}
