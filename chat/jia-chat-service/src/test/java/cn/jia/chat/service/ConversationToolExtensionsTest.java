package cn.jia.chat.service;

import cn.jia.agent.api.ConversationToolExtensions;
import cn.jia.agent.security.AgentRuntimeAuthentication;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationToolExtensionsTest {
    @Test void extensionUsesNativeFenceAndExactCurrentTurnAndDoesNotDispatchOnDenial() {
        var auth=mock(AgentRuntimeAuthenticationService.class);var access=mock(PrivateConversationToolAccess.class);
        var principal=mock(AgentRuntimeAuthentication.class);
        var scope=new AgentRuntimeAuthentication.Scope("tenant","client","owner","agent","instance");
        when(principal.isAuthenticated()).thenReturn(true);when(principal.getPrincipal()).thenReturn(scope);
        when(principal.getDetails()).thenReturn(new AgentRuntimeAuthenticationService.Proof(scope,"installation","host",1,"digest",1,1));
        when(auth.withNativeFence(eq(principal),any())).thenAnswer(i->((Supplier<?>)i.getArgument(1)).get());
        var subject=new PrivateConversationToolAccess.Subject("tenant","client","owner","agent");
        when(access.withCurrentTurn(eq(subject),eq("request"),eq("turn"),any())).thenThrow(new PrivateConversationToolAccess.Denied());
        var called=new java.util.concurrent.atomic.AtomicBoolean();
        ConversationToolProvider provider=new ConversationToolProvider() {
            public Set<String> names(){return Set.of("test_tool");}
            public Catalog catalog(Context c,Boundary b){return Catalog.empty();}
            public Object call(Context c,Boundary b,String name,Map<String,Object> args){return b.execute(()->{called.set(true);return "success";});}
        };
        var extensions=new ConversationToolExtensions(auth,access,List.of(provider));
        assertThrows(PrivateConversationToolAccess.Denied.class,()->extensions.call(principal,"request","turn","test_tool",Map.of()));
        assertFalse(called.get());verify(auth).withNativeFence(eq(principal),any());
        verify(access).withCurrentTurn(eq(subject),eq("request"),eq("turn"),any());
    }
    @Test void duplicateNamesCannotOverrideExistingBusinessTools() {
        var provider=mock(ConversationToolProvider.class);when(provider.names()).thenReturn(Set.of("skill_publish"));
        assertThrows(IllegalStateException.class,()->new ConversationToolExtensions(mock(AgentRuntimeAuthenticationService.class),mock(PrivateConversationToolAccess.class),List.of(provider)));
    }
}
