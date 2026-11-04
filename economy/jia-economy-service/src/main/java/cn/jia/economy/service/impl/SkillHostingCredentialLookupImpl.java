package cn.jia.economy.service.impl;
import cn.jia.economy.service.skill.*;
import cn.jia.economy.mapper.*;
import org.springframework.stereotype.Service;
import java.util.List;
@Service
public class SkillHostingCredentialLookupImpl implements SkillHostingCredentialLookup {
    private final SkillHostingCredentialMapper credentials;
    private final EconomyHostingRentMapper hosting;
    public SkillHostingCredentialLookupImpl(SkillHostingCredentialMapper credentials,EconomyHostingRentMapper hosting) {this.credentials=credentials;this.hosting=hosting;}
    public List<SkillHostingCredential> candidates(String tenant,String client,String agent) {
        return credentials.hostingCandidates(tenant,client,agent).stream().map(v->new SkillHostingCredential()
                .setIntentId(v.getIntentId()).setLeaseId(v.getLeaseId()).setPrincipalType(v.getPrincipalType())
                .setPrincipalId(v.getPrincipalId()).setManagedApiKeyId(v.getManagedApiKeyId())).toList();
    }
    public int referenceCount(String keyId) {return credentials.hostingReferenceCount(keyId);}
    public SkillHostingLease lockLease(String tenant,String client,String leaseId) {
        var v=hosting.selectLeaseForUpdate(tenant,client,leaseId);
        return v==null?null:new SkillHostingLease().setAgentId(v.getAgentId()).setBindingId(v.getBindingId()).setStatus(v.getStatus());
    }
}
