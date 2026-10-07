package cn.jia.agent.platform;

import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.InstalledSkillResolver;
import cn.jia.agent.service.impl.AgentServiceImpl;
import cn.jia.agent.skill.InstalledSkillSourceResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.Arrays;

import static cn.jia.agent.platform.PlatformInstallationStore.*;

/** Read-only PLATFORM_PROVISIONED adapter. It never locks, dispatches, or mutates installation state. */
@Component
@ConditionalOnProperty(prefix="agent.platform-skills",name="enabled",havingValue="true")
public final class PlatformInstalledSkillResolver implements InstalledSkillSourceResolver {
    private final PlatformInstallationStore store;
    private final PlatformSkillCatalog catalog;
    private final AgentIdentityService identities;
    private final AgentRuntimeDao runtimes;
    private final AgentRuntimeAuthenticationService authentication;
    private final Clock clock;

    @Autowired
    public PlatformInstalledSkillResolver(PlatformInstallationStore store,PlatformSkillCatalog catalog,
            AgentIdentityService identities,AgentRuntimeDao runtimes,
            AgentRuntimeAuthenticationService authentication) {
        this(store,catalog,identities,runtimes,authentication,Clock.systemUTC());
    }

    PlatformInstalledSkillResolver(PlatformInstallationStore store,PlatformSkillCatalog catalog,
            AgentIdentityService identities,AgentRuntimeDao runtimes,
            AgentRuntimeAuthenticationService authentication,Clock clock) {
        this.store=store;this.catalog=catalog;this.identities=identities;this.runtimes=runtimes;
        this.authentication=authentication;this.clock=clock;
    }

    @Override public InstalledSkillResolver.Origin origin() {
        return InstalledSkillResolver.Origin.PLATFORM_PROVISIONED;
    }

    @Override public InstalledSkillResolver.Resolution resolve(InstalledSkillResolver.Request request) {
        if(request.origin()!=origin()) return InstalledSkillResolver.Resolution.unavailable();
        Scope scope=new Scope(request.tenant(),request.client(),request.owner());
        AgentRuntimeAuthenticationService.ControlledTarget target;
        try {
            var identity=identities.requireActiveIdentityForBinding(request.tenant(),request.client(),request.owner(),
                    request.binding(),request.canonicalAgent());
            if(identity==null || !request.canonicalAgent().equals(identity.getCanonicalAgentId()))
                return InstalledSkillResolver.Resolution.unavailable();
            var runtime=runtimes.findByAgentId(request.canonicalAgent());
            if(runtime==null || !request.tenant().equals(runtime.getTenantId())
                    || !request.client().equals(runtime.getClientId()) || !request.owner().equals(runtime.getOwnerJiacn())
                    || !Long.valueOf(request.binding()).equals(runtime.getBindingId()))
                return InstalledSkillResolver.Resolution.unavailable();
            target=authentication.requireControlledTarget(request.tenant(),request.client(),request.owner(),
                    request.canonicalAgent(),request.binding(),PlatformSkillInstallationService.TYPE+"/v1");
        } catch(AgentServiceImpl.AgentBizException | IllegalArgumentException denied) {
            return InstalledSkillResolver.Resolution.unavailable();
        }

        ResolutionKey key=new ResolutionKey(scope,origin().name(),request.canonicalAgent(),request.key(),request.version(),
                request.packageDigest(),request.binding(),target.runtimeInstanceId(),target.registrationHash());
        boolean writeTransaction=TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        ResolutionCandidate success=writeTransaction
                ? store.verifiedCandidateForUpdate(key) : store.verifiedCandidate(key);
        if(success!=null) {
            if(!current(success.installation(),key) || !exactBusiness(success.installation(),request)
                    || !validSuccess(success.installation(),success))
                return InstalledSkillResolver.Resolution.unavailable();
            return catalog.isApproved(request.key(),request.version(),request.packageDigest())
                    ? verified(success.installation()) : revoked(success.installation());
        }
        ResolutionCandidate pending=store.pendingCandidate(key,clock.millis());
        if(pending!=null) {
            if(!current(pending.installation(),key) || !exactBusiness(pending.installation(),request)
                    || !validPending(pending.installation(),pending))
                return InstalledSkillResolver.Resolution.unavailable();
            return catalog.isApproved(request.key(),request.version(),request.packageDigest())
                    ? pending(pending.installation()) : revoked(pending.installation());
        }
        Installation historical=store.latestHistorical(key);
        if(historical==null || !current(historical,key) || !exactBusiness(historical,request))
            return InstalledSkillResolver.Resolution.unavailable();
        return revoked(historical);
    }

    private static boolean exactBusiness(Installation row,InstalledSkillResolver.Request request) {
        return row!=null && row.scope()!=null && request.tenant().equals(row.scope().tenant())
                && request.client().equals(row.scope().client()) && request.owner().equals(row.scope().owner())
                && request.canonicalAgent().equals(row.agentId()) && request.key().equals(row.skillKey())
                && request.version().equals(row.skillVersion()) && request.packageDigest().equals(row.packageSha());
    }

    private boolean validSuccess(Installation row,ResolutionCandidate candidate) {
        return PlatformSkillInstallationService.TYPE.equals(candidate.deliveryCommandType())
                && "SUCCEEDED".equals(row.state()) && row.resultSha()!=null
                && row.resultSha().matches("[0-9a-f]{64}") && row.errorCode()==null;
    }
    private boolean validPending(Installation row,ResolutionCandidate candidate) {
        return PlatformSkillInstallationService.TYPE.equals(candidate.deliveryCommandType())
                && "REQUESTED".equals(row.state()) && row.resultSha()==null && row.errorCode()==null
                && candidate.deliveryStatus()!=null
                && java.util.Set.of("PENDING","CONSUMED","SENT","RECEIVED","STARTED","SUCCEEDED").contains(candidate.deliveryStatus())
                && candidate.deliveryExpiresAt()!=null && candidate.deliveryExpiresAt()>clock.millis();
    }
    private static boolean current(Installation row,ResolutionKey key) {
        Scope scope=key.scope();
        return row!=null && scope.equals(row.scope()) && key.agentId().equals(row.agentId())
                && key.bindingId()==row.bindingId() && key.runtimeInstanceId().equals(row.runtimeInstanceId())
                && Arrays.equals(key.registrationHash(),row.registrationHash())
                && key.skillKey().equals(row.skillKey()) && key.skillVersion().equals(row.skillVersion())
                && key.packageSha().equals(row.packageSha());
    }
    private static InstalledSkillResolver.Resolution verified(Installation row) {
        return resolution(InstalledSkillResolver.State.VERIFIED,row);
    }
    private static InstalledSkillResolver.Resolution pending(Installation row) {
        return resolution(InstalledSkillResolver.State.PENDING,row);
    }
    private static InstalledSkillResolver.Resolution revoked(Installation row) {
        return resolution(InstalledSkillResolver.State.REVOKED,row);
    }
    private static InstalledSkillResolver.Resolution resolution(InstalledSkillResolver.State state,Installation row) {
        return new InstalledSkillResolver.Resolution(state,new InstalledSkillResolver.Proof(row.id(),row.revision(),
                row.skillKey(),row.skillVersion(),row.packageSha()));
    }
}
