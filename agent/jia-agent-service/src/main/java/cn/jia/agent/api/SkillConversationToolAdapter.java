package cn.jia.agent.api;

import cn.jia.chat.service.PrivateConversationToolAccess;
import cn.jia.chat.service.PrivateConversationToolAccess.Denied;

import cn.jia.agent.position.PositionSkillPolicy;
import cn.jia.agent.position.PositionSkillService;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.PositionAuthority;
import cn.jia.agent.skill.SkillPackagePublicationService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import static cn.jia.agent.skill.SkillMarketplaceException.require;

/** Native HTTP tool adapter. Chat admission crosses its API port; product mutations stay in Agent. */
@Service
public final class SkillConversationToolAdapter {
    private final AgentRuntimeAuthenticationService authentication;
    private final PrivateConversationToolAccess conversations;
    private final AgentIdentityService identities;
    private final PositionSkillService positions;
    private final SkillPackagePublicationService publications;

    public SkillConversationToolAdapter(AgentRuntimeAuthenticationService authentication,
            PrivateConversationToolAccess conversations,
            AgentIdentityService identities, PositionSkillService positions,
            SkillPackagePublicationService publications) {
        this.authentication=authentication;
        this.conversations=conversations; this.identities=identities; this.positions=positions;
        this.publications=publications;
    }

    private cn.jia.agent.service.InstalledSkillDescriptorLookup installed;
    @org.springframework.beans.factory.annotation.Autowired
    public void setInstalled(cn.jia.agent.service.InstalledSkillDescriptorLookup installed) {this.installed=installed;}
    public record Skill(String skillKey,String skillVersion,String skillInstallationId,String packageSha256) { }
    public record Catalog(String schemaVersion,java.util.List<java.util.Map<String,Object>> tools,java.util.List<Skill> skills) { }
    public Catalog catalog(Authentication principal,String requestId,String turnId) {
        var proof=AgentRuntimeAuthenticationService.requireProof(principal);
        try {
            withConversation(principal,requestId,turnId,c->positions.withSkillManagementAuthority(c.scope(),c.grant(),()->true));
            var runtime=proof.scope();
            var scope=new cn.jia.agent.service.SkillCapabilityLookup.Scope(runtime.tenantId(),runtime.clientId(),runtime.ownerJiacn(),runtime.agentId());
            // Immutable package metadata is prepared outside the native/conversation transaction.
            var skills=installed.readCurrent(scope).skills().stream()
                    .filter(d->"skill-management".equals(d.skillKey())&&d.verifiedNative()&&d.manifestSha256()==null)
                    .map(d->new Skill(d.skillKey(),d.skillVersion(),d.installationId(),d.packageSha256())).toList();
            return new Catalog("1",skills.isEmpty()?java.util.List.of():SkillConversationTools.catalog(),skills);
        } catch(Denied denied) {
            return new Catalog("1",java.util.List.of(),java.util.List.of());
        } catch(cn.jia.agent.skill.SkillMarketplaceException failure) {
            if(failure.status()!=403)throw failure;
            return new Catalog("1",java.util.List.of(),java.util.List.of());
        } catch(cn.jia.agent.position.PositionSkillException failure) {
            if(failure.status()!=403)throw failure;
            return new Catalog("1",java.util.List.of(),java.util.List.of());
        }
    }
    public java.util.Map<String,Object> readPackage(Authentication principal,String requestId,String turnId,String version,String path) {
        var original=withConversation(principal,requestId,turnId,c->positions.withSkillManagementAuthority(c.scope(),c.grant(),()->c));
        var result=publications.readPackage(original.scope(),original.grant(),version,path);
        return withConversation(principal,requestId,turnId,c->{
            require(original.equals(c),403,"POSITION_AUTHORITY_CHANGED");
            return positions.withSkillManagementAuthority(c.scope(),c.grant(),()->result);
        });
    }
    public java.util.List<SkillPackagePublicationService.ProductView> list(Authentication principal,String requestId,String turnId,int offset,int limit) {
        return withConversation(principal,requestId,turnId,c->publications.listProducts(c.scope(),c.grant(),offset,limit));
    }
    public SkillPackagePublicationService.Receipt publish(Authentication principal,String requestId,String turnId,
            SkillPackagePublicationService.Publish command,java.util.List<cn.jia.agent.skill.SkillPublicationToolArchive.File> files) {
        var context=withConversation(principal,requestId,turnId,c->positions.withSkillManagementAuthority(c.scope(),c.grant(),()->c));
        var bytes=cn.jia.agent.skill.SkillPublicationToolArchive.encode(files);
        String digest;
        try {digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        var actual=new SkillPackagePublicationService.Publish(command.productId(),command.productVersionId(),command.name(),command.description(),
                command.skillKey(),command.skillVersion(),command.priceMicro(),command.deploymentRestriction(),command.approvedPermissionsManifest(),
                digest,null,command.expectedProductVersion(),command.coveredUpgradeFromProductVersionId());
        var prepared=publications.prepare(context.scope(),context.grant(),actual,bytes);
        return withConversation(principal,requestId,turnId,c->{
            require(prepared.matches(c.scope(),c.grant()),403,"POSITION_AUTHORITY_CHANGED");
            return publications.commitPrepared(prepared);
        });
    }

    public SkillPackagePublicationService.ProductView read(Authentication principal,String requestId,String turnId,String productId) {
        return withConversation(principal,requestId,turnId,
                context->publications.readProduct(context.scope(),context.grant(),productId));
    }

    public SkillPackagePublicationService.MetadataReceipt updateMetadata(Authentication principal,
            String requestId,String turnId,SkillPackagePublicationService.UpdateMetadata command) {
        return withConversation(principal,requestId,turnId,
                context->publications.updateMetadata(context.scope(),context.grant(),command));
    }

    public SkillPackagePublicationService.RetirementReceipt retire(Authentication principal,
            String requestId,String turnId,SkillPackagePublicationService.RetireProduct command) {
        return withConversation(principal,requestId,turnId,
                context->publications.retireProduct(context.scope(),context.grant(),command));
    }

    private record Context(PositionAuthority.Scope scope,PositionAuthority.Grant grant) { }

    private <T> T withConversation(Authentication principal,String requestId,String turnId,Function<Context,T> operation) {
        var runtime=AgentRuntimeAuthenticationService.requireProof(principal).scope();
        // Native identity precedes conversation/turn and position/product locks. Cancellation holds
        // the same conversation lock; resignation is serialized by the existing publication service.
        return authentication.withNativeFence(principal,()->conversations.withCurrentTurn(
                new PrivateConversationToolAccess.Subject(runtime.tenantId(),runtime.clientId(),runtime.ownerJiacn(),runtime.agentId()),requestId,turnId,()->{
            var scope=new PositionAuthority.Scope(runtime.tenantId(),runtime.clientId(),runtime.ownerJiacn());
            var identity=identities.requireRegistrationIdentityInScope(runtime.tenantId(),runtime.clientId(),runtime.ownerJiacn(),runtime.agentId());
            var state=positions.read(scope).state();
            var appointment=state.appointments().values().stream()
                    .filter(value->value.active()&&runtime.agentId().equals(value.agentId())
                            &&Objects.equals(identity.getBindingId(),value.bindingId()))
                    .filter(value->{var position=state.positions().get(value.positionId());
                        return position!=null&&position.allowPublish()&&PositionSkillPolicy.PLATFORM_SKILLS.equals(position.resource());})
                    .sorted(Comparator.comparing(cn.jia.agent.position.PositionSkillState.Appointment::id))
                    .findFirst().orElse(null);
            require(appointment!=null,403,"SKILL_PUBLICATION_FORBIDDEN");
            var position=state.positions().get(appointment.positionId());
            // This projection is not an authorization decision. The existing service validates
            // current appointment revisions and resource rights inside the mutation transaction.
            var grant=new PositionAuthority.Grant(appointment.id(),appointment.revision(),position.revision(),
                    runtime.agentId(),appointment.bindingId(),null,true);
            return operation.apply(new Context(scope,grant));
        }));
    }
}
