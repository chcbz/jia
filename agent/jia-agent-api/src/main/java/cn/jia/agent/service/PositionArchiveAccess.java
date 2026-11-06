package cn.jia.agent.service;

import java.util.List;
import java.util.function.Function;

/** Agent-owned appointment/installation admission for archive tools; caller holds native/turn fence. */
public interface PositionArchiveAccess {
    record Session(PositionAuthority.Grant grant,InstalledSkillDescriptorLookup.Descriptor skill) { }
    <T>T withAuthority(PositionAuthority.Scope scope,String agentId,String runtimeInstallationId,String runtimeHostId,
            String workId,boolean publish,List<InstalledSkillDescriptorLookup.Descriptor> descriptors,Function<Session,T> operation);
}
