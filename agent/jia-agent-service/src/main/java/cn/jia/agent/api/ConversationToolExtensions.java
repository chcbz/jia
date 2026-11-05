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
    public ConversationToolExtensions(AgentRuntimeAuthenticationService authentication,
            PrivateConversationToolAccess conversations,List<ConversationToolProvider> providers) {
        this.authentication=authentication;this.conversations=conversations;this.providers=List.copyOf(providers);
        var names=new HashSet<String>();
        SkillConversationTools.catalog().forEach(t->names.add((String)t.get("name")));
        for(var provider:providers)for(var name:provider.names())
            if(!names.add(name))throw new IllegalStateException("Duplicate conversation tool");
    }
    public ConversationToolProvider.Catalog catalog(Authentication principal,String request,String turn) {
        var context=context(principal);var boundary=boundary(principal,request,turn,context);
        var tools=new ArrayList<Map<String,Object>>();var skills=new ArrayList<ConversationToolProvider.Skill>();
        for(var provider:providers){var catalog=provider.catalog(context,boundary);tools.addAll(catalog.tools());skills.addAll(catalog.skills());}
        return new ConversationToolProvider.Catalog(tools,skills);
    }
    public boolean supports(String name) {return providers.stream().anyMatch(p->p.names().contains(name));}
    public Object call(Authentication principal,String request,String turn,String name,Map<String,Object> arguments) {
        var provider=providers.stream().filter(p->p.names().contains(name)).findFirst().orElseThrow(IllegalArgumentException::new);
        var context=context(principal);
        return provider.call(context,boundary(principal,request,turn,context),name,arguments);
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
