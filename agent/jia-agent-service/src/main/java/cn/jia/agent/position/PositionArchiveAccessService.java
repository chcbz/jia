package cn.jia.agent.position;

import cn.jia.agent.service.PositionArchiveAccess;
import cn.jia.agent.service.PositionAuthority;
import cn.jia.agent.service.InstalledSkillDescriptorLookup;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.Function;
import static cn.jia.agent.position.PositionSkillException.require;

/** Appointment and native installation admission remain in the Agent business domain. */
@Service
public final class PositionArchiveAccessService implements PositionArchiveAccess {
    private final PositionSkillService positions;
    public PositionArchiveAccessService(PositionSkillService positions){this.positions=positions;}
    @Override public <T>T withAuthority(PositionAuthority.Scope scope,String agentId,String runtimeInstallationId,String runtimeHostId,
            String work,boolean publish,List<InstalledSkillDescriptorLookup.Descriptor> descriptors,Function<Session,T> operation) {
        var state=positions.read(scope).state();
        var appointment=state.appointments().values().stream().filter(a->a.active()&&a.agentId().equals(agentId))
                .filter(a->{var p=state.positions().get(a.positionId());return p!=null&&"archive:public".equals(p.resource())&&(!publish||p.allowPublish());})
                .sorted(Comparator.comparing(PositionSkillState.Appointment::id)).findFirst().orElse(null);
        require(appointment!=null,403,"ARCHIVE_APPOINTMENT_REQUIRED");var position=state.positions().get(appointment.positionId());
        var grant=new PositionAuthority.Grant(appointment.id(),appointment.revision(),position.revision(),appointment.agentId(),appointment.bindingId(),work,publish);
        return positions.withAuthority(scope,grant,()->{
            var current=positions.currentState(scope);
            var descriptor=descriptors.stream().filter(d->current.installations().values().stream().anyMatch(i->
                i.usable()&&position.skillKeys().contains(i.skillKey())&&i.skillKey().equals(d.skillKey())&&i.agentId().equals(grant.agentId())&&i.bindingId()==grant.bindingId()
                &&Objects.equals(PositionSkillRuntimeService.physical(i),d.installationId())&&Objects.equals(i.packageDigest(),"sha256:"+d.packageSha256())
                &&i.manifestSha256()==null&&Objects.equals(i.runtimeInstallationId(),runtimeInstallationId)&&Objects.equals(i.runtimeHostId(),runtimeHostId)))
                .findFirst().orElse(null);
            require(descriptor!=null,403,"ARCHIVE_SKILL_NOT_READY");return operation.apply(new Session(grant,descriptor));
        });
    }
}
