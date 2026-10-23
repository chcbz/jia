package cn.jia.chat.handler;

import cn.jia.agent.service.SkillCapabilityLookup;
import cn.jia.agent.skill.SkillManagementCodec;
import java.util.*;

/** The authenticated registration boundary accepts exactly SM-C1 schemaVersion=2. */
final class SkillExecutionDeclaration {
    static SkillCapabilityLookup.Catalog parse(Object value) {
        try {
            SkillManagementCodec.validate("SkillExecutionDeclaration",value);
            var catalog=SkillManagementCodec.object(value);var skills=new ArrayList<SkillCapabilityLookup.RuntimeSkill>();
            for(var row:(List<?>)catalog.get("skills")) {
                var s=SkillManagementCodec.object(row);
                skills.add(new SkillCapabilityLookup.RuntimeSkill((String)s.get("skillKey"),(String)s.get("skillVersion"),(String)s.get("packageSha256"),
                    (String)s.get("manifestSha256"),(String)s.get("runner"),SkillCapabilityLookup.Readiness.valueOf((String)s.get("readiness")),
                    (String)s.get("reasonCode"),(String)s.get("toolchainDigest"),(String)s.get("bindingRevision"),(String)s.get("skillInstallationId"),
                    (String)s.get("productVersionId"),(String)s.get("kind"),(String)s.get("policyRevision"),(Boolean)s.get("enabled"),((Number)s.get("observedAt")).longValue()));
            }
            return new SkillCapabilityLookup.Catalog((String)catalog.get("catalogRevision"),skills);
        } catch(RuntimeException invalid) {return null;}
    }
}
