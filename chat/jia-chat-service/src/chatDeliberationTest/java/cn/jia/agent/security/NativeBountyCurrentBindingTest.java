package cn.jia.agent.security;

import cn.jia.agent.config.AgentTaskEventsGate;
import cn.jia.agent.config.AgentTaskEventsProperties;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.oauth.entity.OauthApiKeyEntity;
import cn.jia.oauth.service.ApiKeyService;
import cn.jia.user.security.AccountSecurityService;
import cn.jia.user.security.AccountSecuritySnapshot;
import cn.jia.user.security.AccountState;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeBountyCurrentBindingTest {
    @Test
    void nonsecretLookupRechecksPersistedTokenKeyAccountEpochAndIdentity() {
        AgentRuntimeDao rows=mock(AgentRuntimeDao.class);AgentIdentityService identities=mock(AgentIdentityService.class);
        ApiKeyService keys=mock(ApiKeyService.class);AccountSecurityService accounts=mock(AccountSecurityService.class);
        AgentTaskEventsGate gate=new AgentTaskEventsGate(new AgentTaskEventsProperties(false,List.of()));
        AgentRuntimeAuthenticationService service=new AgentRuntimeAuthenticationService(rows,identities,keys,accounts,gate);
        String token="1".repeat(32),agent="agt_"+"a".repeat(32);
        OauthApiKeyEntity key=new OauthApiKeyEntity().setId("key-a").setClientId("client-a").setJiacn("owner-a").setApiKey("api-key-a").setStatus(1);key.setTenantId("0");
        AgentRuntimeEntity runtime=new AgentRuntimeEntity().setAgentId(agent).setOwnerJiacn("owner-a").setBindingId(7L).setStatus("online").setTokenHash(token);runtime.setTenantId("0");runtime.setClientId("client-a");
        AgentIdentityRegistryEntity identity=new AgentIdentityRegistryEntity().setCanonicalAgentId(agent);
        when(keys.get("key-a")).thenReturn(key);when(rows.findByAgentId(agent)).thenReturn(runtime);
        when(accounts.findUniqueByExactJiacn("owner-a")).thenReturn(Optional.of(new AccountSecuritySnapshot(9,"owner-a",AccountState.ACTIVE,3)));
        when(identities.requireActiveIdentityForBinding("0","client-a","owner-a",7L,agent)).thenReturn(identity);
        when(identities.requireActiveBinding(identity,null)).thenReturn(new AgentPersonaBindingEntity());
        service.bind("session-a","client-a","owner-a",agent,"runtime-a","key-a",token,()->true);
        assertTrue(service.isCurrentBinding("session-a","0","client-a","owner-a",agent,"runtime-a"));

        runtime.setTokenHash("2".repeat(32));
        assertThrows(IllegalArgumentException.class,()->service.isCurrentBinding("session-a","0","client-a","owner-a",agent,"runtime-a"));
        runtime.setTokenHash(token);key.setApiKey("rotated");
        assertThrows(IllegalArgumentException.class,()->service.isCurrentBinding("session-a","0","client-a","owner-a",agent,"runtime-a"));
        key.setApiKey("api-key-a");
        when(accounts.findUniqueByExactJiacn("owner-a")).thenReturn(Optional.of(new AccountSecuritySnapshot(9,"owner-a",AccountState.ACTIVE,4)));
        assertThrows(IllegalArgumentException.class,()->service.isCurrentBinding("session-a","0","client-a","owner-a",agent,"runtime-a"));

        when(accounts.findUniqueByExactJiacn("owner-a")).thenReturn(Optional.of(new AccountSecuritySnapshot(9,"owner-a",AccountState.ACTIVE,3)));
        when(identities.requireActiveIdentityForBinding("0","client-a","owner-a",7L,agent))
                .thenThrow(new RuntimeException("identity revoked"));
        assertThrows(IllegalArgumentException.class,()->service.isCurrentBinding(
                "session-a","0","client-a","owner-a",agent,"runtime-a"));
        // when(...) invokes an existing throwing stub during replacement. Use
        // doThrow so the storage outage occurs only in the asserted lookup.
        doThrow(new DataAccessResourceFailureException("identity store down"))
                .when(identities).requireActiveIdentityForBinding("0","client-a","owner-a",7L,agent);
        assertThrows(DataAccessResourceFailureException.class,()->service.isCurrentBinding(
                "session-a","0","client-a","owner-a",agent,"runtime-a"));
    }
}
