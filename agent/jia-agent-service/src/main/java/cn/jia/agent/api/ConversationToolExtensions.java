package cn.jia.agent.api;

import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.chat.service.ConversationToolProvider;
import cn.jia.chat.service.PrivateConversationToolAccess;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.Supplier;

/** Only transport/admission composition; domain providers own their schemas and business permissions. */
@Service
public final class ConversationToolExtensions {
    private final AgentRuntimeAuthenticationService authentication;
    private final PrivateConversationToolAccess conversations;
    private final List<ConversationToolProvider> providers;
    private final cn.jia.agent.service.InstalledSkillDescriptorLookup installed;
    public ConversationToolExtensions(AgentRuntimeAuthenticationService authentication,
            PrivateConversationToolAccess conversations,List<ConversationToolProvider> providers,cn.jia.agent.service.InstalledSkillDescriptorLookup installed) {
        this.authentication=authentication;this.conversations=conversations;this.providers=List.copyOf(providers);this.installed=installed;
        var names=new HashSet<String>();
        for(var provider:providers)for(var name:provider.names())
            if(!names.add(name))throw new IllegalStateException("Duplicate conversation tool");
    }
    public ConversationToolProvider.Catalog catalog(Authentication principal,String request,String turn) {
        var context=context(principal);var boundary=boundary(principal,request,turn,context);
        // Check the turn before reading installation metadata. Package I/O stays outside locks.
        try { boundary.execute(()->true); }
        catch(PrivateConversationToolAccess.Denied denied) { return ConversationToolProvider.Catalog.empty(); }
        var subject=context.subject();
        var snapshot=installed.readCurrent(new cn.jia.agent.service.SkillCapabilityLookup.Scope(
                subject.tenantId(),subject.clientId(),subject.ownerJiacn(),subject.agentId()));
        var skills=snapshot.skills().stream()
                .filter(d->d.verifiedNative()&&d.manifestSha256()==null
                        &&Objects.equals(context.runtimeInstallationId(),d.runtimeInstallationId())
                        &&Objects.equals(context.runtimeHostId(),d.runtimeHostId()))
                .map(d->new ConversationToolProvider.Skill(d.skillKey(),d.skillVersion(),d.installationId(),d.packageSha256())).toList();
        var tools=new ArrayList<Map<String,Object>>();
        for(var provider:providers)tools.addAll(provider.catalog(context,boundary));
        return boundary.execute(()->new ConversationToolProvider.Catalog(tools,skills));
    }
    public Object call(Authentication principal,String request,String turn,String name,Map<String,Object> arguments) {
        var provider=providers.stream().filter(p->p.names().contains(name)).findFirst().orElseThrow(IllegalArgumentException::new);
        var context=context(principal);
        var boundary=boundary(principal,request,turn,context);
        boundary.execute(()->true);
        return provider.call(context,boundary,name,arguments);
    }
    private ConversationToolProvider.Context context(Authentication principal) {
        var proof=AgentRuntimeAuthenticationService.requireProof(principal);var s=proof.scope();
        return new ConversationToolProvider.Context(new PrivateConversationToolAccess.Subject(s.tenantId(),s.clientId(),s.ownerJiacn(),s.agentId()),proof.installationId(),proof.hostId());
    }
    private ConversationToolProvider.Boundary boundary(Authentication principal,String request,String turn,ConversationToolProvider.Context context) {
        return new ConversationToolProvider.Boundary() {
            public <T>T execute(Supplier<T> action) {
                return authentication.withNativeFence(principal,()->conversations.withCurrentTurn(context.subject(),request,turn,action));
            }
        };
    }
}
