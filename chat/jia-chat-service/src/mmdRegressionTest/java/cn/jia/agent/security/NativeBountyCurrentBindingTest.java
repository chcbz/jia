package cn.jia.agent.security;

import cn.jia.agent.config.AgentTaskEventsGate;
import cn.jia.agent.config.AgentTaskEventsProperties;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.AgentRuntimeV1InstallationDao;
import cn.jia.agent.dao.AgentIdentityRegistryDao;
import cn.jia.agent.entity.AgentRuntimeV1InstallationEntity;
import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.service.AgentIdentityService;
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
    void nonsecretLookupRechecksPersistedInstallationHostGenerationAccountEpochAndIdentity() {
        AgentRuntimeDao rows=mock(AgentRuntimeDao.class);AgentIdentityService identities=mock(AgentIdentityService.class);
        AgentRuntimeV1InstallationDao installations=mock(AgentRuntimeV1InstallationDao.class);
        AccountSecurityService accounts=mock(AccountSecurityService.class);
        AgentTaskEventsGate gate=new AgentTaskEventsGate(new AgentTaskEventsProperties(false,List.of()));
        AgentRuntimeAuthenticationService service=new AgentRuntimeAuthenticationService(rows,installations,
                mock(AgentIdentityRegistryDao.class),identities,accounts,gate);
        String digest="1".repeat(64),agent="agt_"+"a".repeat(32),installationId="rti_"+"b".repeat(32);
        String verifier="urs1:"+digest+":9:3";
        var installation=new AgentRuntimeV1InstallationEntity().setInstallationId(installationId)
                .setCanonicalAgentId(agent).setStatus("ACTIVE");installation.setTenantId("0");installation.setClientId("client-a");
        AgentRuntimeEntity runtime=new AgentRuntimeEntity().setAgentId(agent).setOwnerJiacn("owner-a").setBindingId(7L)
                .setStatus("online").setTokenHash(verifier).setRuntimeInstallationId(installationId)
                .setRuntimeHostId("host-a").setRuntimeInstanceId("runtime-a").setRuntimeSessionGeneration(1L);
        runtime.setTenantId("0");runtime.setClientId("client-a");
        AgentIdentityRegistryEntity identity=new AgentIdentityRegistryEntity().setCanonicalAgentId(agent).setBindingId(7L);
        when(installations.findInScope("0","client-a",installationId)).thenReturn(installation);
        when(rows.findInScope("0","client-a",agent)).thenReturn(runtime);
        when(accounts.findUniqueByExactJiacn("owner-a")).thenReturn(Optional.of(new AccountSecuritySnapshot(9,"owner-a",AccountState.ACTIVE,3)));
        when(identities.requireActiveIdentityForBinding("0","client-a","owner-a",7L,agent)).thenReturn(identity);
        when(identities.requireActiveBinding(identity,null)).thenReturn(new AgentPersonaBindingEntity());
        var proof=new AgentRuntimeAuthenticationService.Proof(new AgentRuntimeAuthentication.Scope(
                "0","client-a","owner-a",agent,"runtime-a"),installationId,"host-a",1,digest,9,3);
        service.bind("session-a",proof,()->true);
        assertTrue(service.isCurrentBinding("session-a","0","client-a","owner-a",agent,"runtime-a"));

        runtime.setTokenHash("2".repeat(32));
        assertThrows(IllegalArgumentException.class,()->service.isCurrentBinding("session-a","0","client-a","owner-a",agent,"runtime-a"));
        runtime.setTokenHash(verifier);runtime.setRuntimeSessionGeneration(2L);
        assertThrows(IllegalArgumentException.class,()->service.isCurrentBinding("session-a","0","client-a","owner-a",agent,"runtime-a"));
        runtime.setRuntimeSessionGeneration(1L);runtime.setRuntimeHostId("foreign-host");
        assertThrows(IllegalArgumentException.class,()->service.isCurrentBinding("session-a","0","client-a","owner-a",agent,"runtime-a"));
        runtime.setRuntimeHostId("host-a");installation.setStatus("REVOKED");
        assertThrows(IllegalArgumentException.class,()->service.isCurrentBinding("session-a","0","client-a","owner-a",agent,"runtime-a"));
        installation.setStatus("ACTIVE");
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
