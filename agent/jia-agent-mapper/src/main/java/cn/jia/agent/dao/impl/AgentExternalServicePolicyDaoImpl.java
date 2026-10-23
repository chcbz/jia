package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentExternalServicePolicyDao;
import cn.jia.agent.entity.*;
import cn.jia.agent.mapper.AgentExternalServicePolicyMapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.List;

@Named
public final class AgentExternalServicePolicyDaoImpl implements AgentExternalServicePolicyDao {
    private final AgentExternalServicePolicyMapper mapper;
    @Inject public AgentExternalServicePolicyDaoImpl(AgentExternalServicePolicyMapper mapper) { this.mapper=mapper; }
    public AgentExternalServicePolicyEntity latestForUpdate(String t,String c,String a,long b) { return mapper.latestForUpdate(t,c,a,b); }
    public AgentExternalServicePolicyEntity receiptForUpdate(String t,String c,String o,String k) { return mapper.receiptForUpdate(t,c,o,k); }
    public AgentExternalServicePolicyEntity latest(String t,String c,String a,long b) { return mapper.latest(t,c,a,b); }
    public AgentExternalServicePolicyEntity receipt(String t,String c,String o,String k) { return mapper.receipt(t,c,o,k); }
    public AgentIdentityRegistryEntity identity(String t,String c,String a) { return mapper.identity(t,c,a); }
    public List<AgentIdentityRegistryEntity> candidates(String t,String c,String o,String a,int n) { return mapper.candidates(t,c,o,a,n); }
    public void insert(AgentExternalServicePolicyEntity policy) {
        if (mapper.insert(policy)!=1) throw new IllegalStateException("Policy receipt insert failed");
    }
}
