package cn.jia.agent.api;


import cn.jia.agent.position.PositionSkillPolicy;
import cn.jia.agent.position.PositionSkillService;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.PositionAuthority;
import cn.jia.agent.skill.SkillPackagePublicationService;
import cn.jia.chat.service.ConversationToolProvider;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import static cn.jia.agent.skill.SkillMarketplaceException.require;

/** Native HTTP tool adapter. Chat admission crosses its API port; product mutations stay in Agent. */
@Service
public final class SkillConversationToolAdapter implements ConversationToolProvider {
    private final AgentIdentityService identities;
    private final PositionSkillService positions;
    private final SkillPackagePublicationService publications;

    public SkillConversationToolAdapter(AgentIdentityService identities, PositionSkillService positions,
            SkillPackagePublicationService publications) {
        this.identities=identities; this.positions=positions;
        this.publications=publications;
    }

    @Override public Set<String> names() {
        return SkillConversationTools.catalog().stream().map(t -> (String)t.get("name")).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    @Override public java.util.List<java.util.Map<String,Object>> catalog(ConversationToolProvider.Context context, Boundary boundary) {
        try {
            withConversation(context,boundary,c->positions.withSkillManagementAuthority(c.scope(),c.grant(),()->true));
            return SkillConversationTools.catalog();
        } catch(cn.jia.agent.skill.SkillMarketplaceException failure) {
            if(failure.status()!=403)throw failure;
            return java.util.List.of();
        } catch(cn.jia.agent.position.PositionSkillException failure) {
            if(failure.status()!=403)throw failure;
            return java.util.List.of();
        }
    }
    @Override public Object call(ConversationToolProvider.Context context, Boundary boundary, String name, java.util.Map<String,Object> arguments) {
        return SkillConversationTools.wire(SkillConversationTools.call(this,context,boundary,name,
                tools.jackson.databind.json.JsonMapper.builder().build().valueToTree(arguments)));
    }
    public java.util.Map<String,Object> readPackage(ConversationToolProvider.Context context,Boundary boundary,String version,String path) {
        var original=withConversation(context,boundary,c->positions.withSkillManagementAuthority(c.scope(),c.grant(),()->c));
        var result=publications.readPackage(original.scope(),original.grant(),version,path);
        return withConversation(context,boundary,c->{
            require(original.equals(c),403,"POSITION_AUTHORITY_CHANGED");
            return positions.withSkillManagementAuthority(c.scope(),c.grant(),()->result);
        });
    }
    public java.util.List<SkillPackagePublicationService.ProductView> list(ConversationToolProvider.Context context,Boundary boundary,int offset,int limit) {
        return withConversation(context,boundary,c->publications.listProducts(c.scope(),c.grant(),offset,limit));
    }
    public SkillPackagePublicationService.Receipt publish(ConversationToolProvider.Context context,Boundary boundary,
            SkillPackagePublicationService.Publish command,java.util.List<cn.jia.agent.skill.SkillPublicationToolArchive.File> files) {
        var authorized=withConversation(context,boundary,c->positions.withSkillManagementAuthority(c.scope(),c.grant(),()->c));
        var bytes=cn.jia.agent.skill.SkillPublicationToolArchive.encode(files);
        String digest;
        try {digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        var actual=new SkillPackagePublicationService.Publish(command.productId(),command.productVersionId(),command.name(),command.description(),
                command.skillKey(),command.skillVersion(),command.priceWen(),command.deploymentRestriction(),command.approvedPermissionsManifest(),
                digest,null,command.expectedProductVersion(),command.coveredUpgradeFromProductVersionId());
        var prepared=publications.prepare(authorized.scope(),authorized.grant(),actual,bytes);
        return withConversation(context,boundary,c->{
            require(prepared.matches(c.scope(),c.grant()),403,"POSITION_AUTHORITY_CHANGED");
            return publications.commitPrepared(prepared);
        });
    }

    public SkillPackagePublicationService.ProductView read(ConversationToolProvider.Context context,Boundary boundary,String productId) {
        return withConversation(context,boundary,
                c->publications.readProduct(c.scope(),c.grant(),productId));
    }

    public SkillPackagePublicationService.MetadataReceipt updateMetadata(ConversationToolProvider.Context context,
            Boundary boundary,SkillPackagePublicationService.UpdateMetadata command) {
        return withConversation(context,boundary,
                c->publications.updateMetadata(c.scope(),c.grant(),command));
    }

    public SkillPackagePublicationService.RetirementReceipt retire(ConversationToolProvider.Context context,
            Boundary boundary,SkillPackagePublicationService.RetireProduct command) {
        return withConversation(context,boundary,
                c->publications.retireProduct(c.scope(),c.grant(),command));
    }

    private record GrantContext(PositionAuthority.Scope scope,PositionAuthority.Grant grant) { }

    private <T> T withConversation(ConversationToolProvider.Context context,Boundary boundary,Function<GrantContext,T> operation) {
        var runtime=context.subject();
        // Native identity precedes conversation/turn and position/product locks. Cancellation holds
        // the same conversation lock; resignation is serialized by the existing publication service.
        return boundary.execute(()->{
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
            return operation.apply(new GrantContext(scope,grant));
        });
    }
}
