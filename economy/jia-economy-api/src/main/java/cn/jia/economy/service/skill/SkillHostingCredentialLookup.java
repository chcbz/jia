package cn.jia.economy.service.skill;
import java.util.List;
/** Read-only proof used when a skill credential adopts an existing hosting key. */
public interface SkillHostingCredentialLookup {
    List<SkillHostingCredential> candidates(String tenant,String client,String agent);
    int referenceCount(String keyId);
    SkillHostingLease lockLease(String tenant,String client,String leaseId);
}
