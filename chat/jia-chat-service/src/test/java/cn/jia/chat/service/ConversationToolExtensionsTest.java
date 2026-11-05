package cn.jia.chat.service;

import cn.jia.agent.api.ConversationToolExtensions;
import cn.jia.agent.security.AgentRuntimeAuthentication;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.InstalledSkillDescriptorLookup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationToolExtensionsTest {
    private final AgentRuntimeAuthenticationService auth=mock(AgentRuntimeAuthenticationService.class);
    private final PrivateConversationToolAccess access=mock(PrivateConversationToolAccess.class);
    private final AgentRuntimeAuthentication principal=mock(AgentRuntimeAuthentication.class);
    private final InstalledSkillDescriptorLookup installed=mock(InstalledSkillDescriptorLookup.class);
    private final PrivateConversationToolAccess.Subject subject=new PrivateConversationToolAccess.Subject("tenant","client","owner","agent");
    @BeforeEach void setup() {
        var scope=new AgentRuntimeAuthentication.Scope("tenant","client","owner","agent","instance");
        when(principal.isAuthenticated()).thenReturn(true);when(principal.getPrincipal()).thenReturn(scope);
        when(principal.getDetails()).thenReturn(new AgentRuntimeAuthenticationService.Proof(scope,"installation","host",1,"digest",1,1));
        when(auth.withNativeFence(eq(principal),any())).thenAnswer(i->((Supplier<?>)i.getArgument(1)).get());
        when(access.withCurrentTurn(eq(subject),eq("request"),eq("turn"),any())).thenAnswer(i->((Supplier<?>)i.getArgument(3)).get());
        when(installed.readCurrent(any())).thenReturn(new InstalledSkillDescriptorLookup.Snapshot(List.of()));
    }
    private InstalledSkillDescriptorLookup.Descriptor descriptor(String key,String runtime,String reason) {
        return new InstalledSkillDescriptorLookup.Descriptor(key,"1.0.0",key+"-installation","a".repeat(64),null,null,runtime,"host",reason);
    }
    private ConversationToolExtensions registry(List<ConversationToolProvider> providers) {
        return new ConversationToolExtensions(auth,access,providers,installed);
    }
    @Test void discoversArbitraryNewSkillsWithoutBusinessProviderOrTool() {
        when(installed.readCurrent(any())).thenReturn(new InstalledSkillDescriptorLookup.Snapshot(List.of(
                descriptor("writing-guide","installation",null),descriptor("new-skill-never-coded","installation",null),
                descriptor("wrong-runtime","another",null),descriptor("revoked","installation","DISABLED"))));
        var catalog=registry(List.of()).catalog(principal,"request","turn");
        assertTrue(catalog.tools().isEmpty());
        assertEquals(List.of("writing-guide","new-skill-never-coded"),catalog.skills().stream().map(ConversationToolProvider.Skill::skillKey).toList());
        verify(installed).readCurrent(new cn.jia.agent.service.SkillCapabilityLookup.Scope("tenant","client","owner","agent"));
    }
    @Test void toolsAreNotGrantedBySkillNamesAndDoNotRequireInstructions() {
        var provider=mock(ConversationToolProvider.class);
        when(provider.names()).thenReturn(Set.of("business_read"));
        when(provider.catalog(any(),any())).thenReturn(List.of(Map.of("name","business_read")));
        var catalog=registry(List.of(provider)).catalog(principal,"request","turn");
        assertEquals(1,catalog.tools().size());assertTrue(catalog.skills().isEmpty());
    }
    @Test void currentTurnDenialPreventsDiscoveryAndDispatch() {
        when(access.withCurrentTurn(eq(subject),eq("request"),eq("turn"),any())).thenThrow(new PrivateConversationToolAccess.Denied());
        var provider=mock(ConversationToolProvider.class);when(provider.names()).thenReturn(Set.of("test_tool"));
        var registry=registry(List.of(provider));
        assertEquals(ConversationToolProvider.Catalog.empty(),registry.catalog(principal,"request","turn"));
        assertThrows(PrivateConversationToolAccess.Denied.class,()->registry.call(principal,"request","turn","test_tool",Map.of()));
        verify(provider,never()).call(any(),any(),any(),any());verifyNoInteractions(installed);
    }
    @Test void providerCanRefenceAfterPreparationWithoutHoldingTransportLockDuringIo() {
        var called=new java.util.concurrent.atomic.AtomicBoolean();
        ConversationToolProvider provider=new ConversationToolProvider() {
            public Set<String> names(){return Set.of("test_tool");}
            public List<Map<String,Object>> catalog(Context c,Boundary b){return List.of();}
            public Object call(Context c,Boundary b,String name,Map<String,Object> args){
                when(access.withCurrentTurn(eq(subject),eq("request"),eq("turn"),any())).thenThrow(new PrivateConversationToolAccess.Denied());
                return b.execute(()->{called.set(true);return "success";});
            }
        };
        assertThrows(PrivateConversationToolAccess.Denied.class,()->registry(List.of(provider)).call(principal,"request","turn","test_tool",Map.of()));
        assertFalse(called.get());
    }
    @Test void duplicateProviderNamesCannotOverrideAnyDomain() {
        var first=mock(ConversationToolProvider.class);var second=mock(ConversationToolProvider.class);
        when(first.names()).thenReturn(Set.of("arbitrary_tool"));when(second.names()).thenReturn(Set.of("arbitrary_tool"));
        assertThrows(IllegalStateException.class,()->registry(List.of(first,second)));
        assertThrows(IllegalArgumentException.class,()->registry(List.of()).call(principal,"request","turn","shell",Map.of()));
    }
}
