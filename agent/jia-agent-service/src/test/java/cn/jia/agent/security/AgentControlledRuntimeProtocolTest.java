package cn.jia.agent.security;

import cn.jia.agent.config.*;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.*;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.oauth.entity.OauthApiKeyEntity;
import cn.jia.oauth.service.ApiKeyService;
import cn.jia.user.security.*;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AgentControlledRuntimeProtocolTest {
    static final String AGENT="agt_"+"a".repeat(32),TOKEN="1".repeat(32),PROTOCOL="PLATFORM_SKILL_INSTALL/v1";
    AgentRuntimeAuthenticationService auth;
    AgentRuntimeEntity runtime;
    OauthApiKeyEntity key;
    AccountSecurityService accounts;
    AtomicBoolean connected;
    @BeforeEach void setup() {
        var runtimes=mock(AgentRuntimeDao.class);var identities=mock(AgentIdentityService.class);var keys=mock(ApiKeyService.class);accounts=mock(AccountSecurityService.class);
        runtime=new AgentRuntimeEntity().setAgentId(AGENT).setOwnerJiacn("owner-a").setBindingId(17L).setTokenHash(TOKEN).setStatus("online");
        runtime.setTenantId("0");runtime.setClientId("client-a");when(runtimes.findByAgentId(AGENT)).thenReturn(runtime);
        var identity=new AgentIdentityRegistryEntity().setCanonicalAgentId(AGENT);when(identities.requireActiveIdentityForBinding("0","client-a","owner-a",17L,AGENT)).thenReturn(identity);
        when(identities.requireActiveBinding(identity,null)).thenReturn(new AgentPersonaBindingEntity().setId(17L));
        key=new OauthApiKeyEntity().setId("key-a").setApiKey("test-api-key").setJiacn("owner-a").setStatus(1);key.setTenantId("0");key.setClientId("client-a");when(keys.get("key-a")).thenReturn(key);
        when(accounts.findUniqueByExactJiacn("owner-a")).thenReturn(Optional.of(new AccountSecuritySnapshot(1,"owner-a",AccountState.ACTIVE,0)));
        auth=new AgentRuntimeAuthenticationService(runtimes,identities,keys,accounts,new AgentTaskEventsGate(new AgentTaskEventsProperties(false,List.of())));
        connected=new AtomicBoolean(true);auth.bind("socket-a","client-a","owner-a",AGENT,"runtime-a","key-a",TOKEN,connected::get);
    }
    AgentRuntimeAuthenticationService.ControlledTarget proof(){return auth.requireControlledTarget("0","client-a","owner-a",AGENT,17,PROTOCOL);}
    @Test void capabilityComesFromExactAuthenticatedRegistrationNotAbilitiesOrCallerBody() {
        assertThrows(IllegalArgumentException.class,this::proof);
        assertThrows(IllegalArgumentException.class,()->auth.registerCommandProtocols("other-socket",AGENT,List.of(PROTOCOL)));
        auth.registerCommandProtocols("socket-a",AGENT,List.of(PROTOCOL));assertEquals("runtime-a",proof().runtimeInstanceId());
        assertThrows(IllegalArgumentException.class,()->auth.requireControlledTarget("0","client-a","other-owner",AGENT,17,PROTOCOL));
        assertThrows(IllegalArgumentException.class,()->auth.requireControlledTarget("0","client-a","owner-a",AGENT,18,PROTOCOL));
        assertThrows(IllegalArgumentException.class,()->auth.requireControlledTarget("0","client-a","owner-a",AGENT,17,"ARCHIVE_MAINTENANCE_EXECUTE/v1"));
    }
    @Test void withdrawalDisconnectAndNewBindingRequireFreshProtocolProof() {
        auth.registerCommandProtocols("socket-a",AGENT,List.of(PROTOCOL));proof();
        auth.registerCommandProtocols("socket-a",AGENT,List.of());assertThrows(IllegalArgumentException.class,this::proof);
        auth.registerCommandProtocols("socket-a",AGENT,List.of(PROTOCOL));connected.set(false);assertThrows(IllegalArgumentException.class,this::proof);
        connected.set(true);auth.bind("socket-b","client-a","owner-a",AGENT,"runtime-b","key-a",TOKEN,connected::get);assertThrows(IllegalArgumentException.class,this::proof);
        auth.registerCommandProtocols("socket-b",AGENT,List.of(PROTOCOL));auth.disconnect("socket-a");assertEquals("runtime-b",proof().runtimeInstanceId());
        auth.disconnect("socket-b");assertThrows(IllegalArgumentException.class,this::proof);
    }
    @Test void protocolProofRechecksKeyAndAccountEpochAndDoesNotExposeMutableDigest() {
        auth.registerCommandProtocols("socket-a",AGENT,List.of(PROTOCOL));var first=proof();byte[] digest=first.registrationHash();digest[0]^=1;
        assertArrayEquals(first.registrationHash(),proof().registrationHash());key.setStatus(0);assertThrows(IllegalArgumentException.class,this::proof);
        key.setStatus(1);when(accounts.findUniqueByExactJiacn("owner-a")).thenReturn(Optional.of(new AccountSecuritySnapshot(1,"owner-a",AccountState.ACTIVE,1)));
        assertThrows(IllegalArgumentException.class,this::proof);
    }
}
