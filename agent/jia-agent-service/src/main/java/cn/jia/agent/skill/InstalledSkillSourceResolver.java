package cn.jia.agent.skill;

import cn.jia.agent.service.InstalledSkillResolver;

/** Internal source adapter. Implementations must return only proofs for the exact request scope. */
public interface InstalledSkillSourceResolver {
    InstalledSkillResolver.Origin origin();
    InstalledSkillResolver.Resolution resolve(InstalledSkillResolver.Request request);
}
