package cn.jia.agent.security;

import cn.jia.agent.config.AgentTaskEventsGate;
import cn.jia.agent.dao.*;
import cn.jia.agent.entity.*;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.user.security.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Owner regression; persistent evidence rather than a local socket map supplies ownership. */
class AgentRuntimePersistentSessionFenceTest {
    static final String ID = "rti_" + "1".repeat(32), AGENT = "agt_" + "a".repeat(32);
    AgentRuntimeDao rows;
    AgentRuntimeV1InstallationDao installations;
    AgentIdentityRegistryDao registry;
    AgentIdentityService identities;
    AccountSecurityService accounts;
    AgentRuntimeAuthenticationService auth;
    AgentRuntimeV1InstallationEntity installation;
    AgentIdentityRegistryEntity identity;
    AgentPersonaBindingEntity binding;
    AgentRuntimeEntity row;

    @BeforeEach void setup() {
        rows = mock(AgentRuntimeDao.class); installations = mock(AgentRuntimeV1InstallationDao.class);
        registry = mock(AgentIdentityRegistryDao.class); identities = mock(AgentIdentityService.class);
        accounts = mock(AccountSecurityService.class);
        var gate = mock(AgentTaskEventsGate.class);
        auth = new AgentRuntimeAuthenticationService(rows, installations, registry, identities, accounts, gate);
        installation = new AgentRuntimeV1InstallationEntity().setId(1L).setInstallationId(ID)
                .setCanonicalAgentId(AGENT).setStatus("ACTIVE");
        installation.setTenantId("0"); installation.setClientId("client");
        identity = new AgentIdentityRegistryEntity().setId(2L).setBindingId(3L)
                .setCanonicalAgentId(AGENT).setOwnerJiacn("owner").setLifecycleStatus("PROVISIONED");
        identity.setTenantId("0"); identity.setClientId("client");
        binding = new AgentPersonaBindingEntity().setId(3L);
        when(registry.selectByMap(anyMap())).thenReturn(List.of(identity));
        when(identities.requireRegistrationIdentityInScope("0", "client", "owner", AGENT)).thenReturn(identity);
        when(identities.requireActiveIdentityForBinding("0", "client", "owner", 3L, AGENT)).thenReturn(identity);
        when(identities.requireActiveBinding(identity, null)).thenReturn(binding);
        when(identities.activateForFirstRegistration(identity)).thenReturn(identity);
        when(accounts.findUniqueByExactJiacn("owner")).thenReturn(Optional.of(new AccountSecuritySnapshot(7, "owner", AccountState.ACTIVE, 2)));
        when(installations.lock(ID)).thenReturn(installation);
        when(installations.findInScope("0", "client", ID)).thenReturn(installation);
        when(rows.findInScope("0", "client", AGENT)).thenAnswer(i -> row);
        when(rows.lockInScope("0", "client", AGENT)).thenAnswer(i -> row);
        when(rows.insert(any())).thenAnswer(i -> { row = i.getArgument(0); row.setId(4L); return 1; });
        when(rows.updateById(any())).thenReturn(1);
    }
    AgentRuntimeV1SessionRequest request(String host, String boot) {
        return new AgentRuntimeV1SessionRequest(ID, "0", "client", AGENT, "1", "b".repeat(64), host, boot);
    }
    AgentRuntimeAuthenticationFilter.SessionHeaders headers(AgentRuntimeV1SessionResponse session) {
        return new AgentRuntimeAuthenticationFilter.SessionHeaders(AGENT, ID, session.hostId(), session.runtimeInstanceId(),
                session.sessionGeneration(), session.sessionToken());
    }

    @Test void filterAuthenticationEntryActuallyKeepsInstallationReadInsideProxyTransaction() {
        var session = auth.issue(installation, request("host", "boot"), 1000);
        var manager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        var transaction = mock(org.springframework.transaction.TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(transaction);
        var interceptor = new org.springframework.transaction.interceptor.TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource());
        var factory = new org.springframework.aop.framework.ProxyFactory(auth);
        factory.setProxyTargetClass(true); factory.addAdvice(interceptor);
        var proxy = (AgentRuntimeAuthenticationService) factory.getProxy();
        clearInvocations(installations, rows);

        assertEquals(AGENT, proxy.authenticate(headers(session), false).getName());

        var order = inOrder(manager, installations, rows);
        order.verify(manager).getTransaction(any());
        order.verify(installations).lock(ID);
        order.verify(rows).findInScope("0", "client", AGENT);
        order.verify(manager).commit(transaction);
        verify(manager, times(1)).getTransaction(any());
        verify(manager, never()).rollback(any());
    }
    @Test void staleFencedCallbackRollsBackThroughProxyRatherThanReturningACommit() {
        var first = auth.issue(installation, request("host", "boot"), 1000);
        var proof = auth.verify(headers(first));
        auth.issue(installation, request("host", "replacement"), 1001);
        var manager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        var transaction = mock(org.springframework.transaction.TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(transaction);
        var interceptor = new org.springframework.transaction.interceptor.TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource());
        var factory = new org.springframework.aop.framework.ProxyFactory(auth);
        factory.setProxyTargetClass(true); factory.addAdvice(interceptor);
        var proxy = (AgentRuntimeAuthenticationService) factory.getProxy();
        AtomicBoolean entered = new AtomicBoolean();

        assertThrows(RuntimeException.class, () -> proxy.withFence(proof, false, () -> entered.compareAndSet(false, true)));

        assertFalse(entered.get()); verify(manager).rollback(transaction); verify(manager, never()).commit(any());
    }

    @Test void coldProvisionedIdentityIssuesIndependentDigestOnlyPendingSession() {
        var session = auth.issue(installation, request("host", "boot"), 1000);
        assertEquals("CHANNEL_PENDING", session.status()); assertEquals(1, session.sessionGeneration());
        assertEquals("AgentRuntime", session.scheme()); assertTrue(session.sessionToken().matches("rts1_[0-9a-f]{64}"));
        assertFalse(row.getTokenHash().contains(session.sessionToken()));
        assertTrue(row.getTokenHash().endsWith(":7:2")); assertEquals("offline", row.getStatus());
        assertEquals(ID, row.getRuntimeInstallationId()); assertEquals("host", row.getRuntimeHostId());
        assertFalse(session.toString().contains(session.sessionToken()));
        verify(identities, never()).activateForFirstRegistration(any());
    }
    @Test void persistentProofSurvivesApiRestartWithoutPretendingChannelReadiness() {
        var session = auth.issue(installation, request("host", "boot"), 1000);
        var restart = new AgentRuntimeAuthenticationService(rows, installations, registry, identities, accounts, mock(AgentTaskEventsGate.class));
        var proof = restart.verify(headers(session));
        assertEquals("owner", proof.scope().ownerJiacn());
        assertEquals(AGENT, restart.authenticate(headers(session), false).getName());
        assertThrows(RuntimeException.class, () -> restart.authenticate(headers(session), true));
        assertFalse(proof.toString().contains(session.sessionToken()));
    }
    @Test void sameInstallationAndHostRotateAndFenceOldBoot() {
        var first = auth.issue(installation, request("host", "boot-1"), 1000);
        var second = auth.issue(installation, request("host", "boot-2"), 1001);
        assertEquals(2, second.sessionGeneration());
        assertNotEquals(first.sessionToken(), second.sessionToken());
        assertThrows(RuntimeException.class, () -> auth.verify(headers(first)));
        assertEquals("boot-2", auth.verify(headers(second)).scope().runtimeInstanceId());
    }
    @Test void otherHostCannotStealExistingOwnershipOrRewriteKeys() {
        var first = auth.issue(installation, request("host", "boot"), 1000);
        var verifier = row.getTokenHash();
        assertThrows(RuntimeException.class, () -> auth.issue(installation, request("foreign-host", "foreign-boot"), 1001));
        assertEquals(verifier, row.getTokenHash()); assertEquals(1, row.getRuntimeSessionGeneration());
        assertEquals(AGENT, auth.verify(headers(first)).scope().agentId());
        verify(rows, never()).updateById(any());
    }
    @Test void anotherInstallationCannotTakeOverEvenSameHost() {
        auth.issue(installation, request("host", "boot"), 1000);
        installation.setInstallationId("rti_" + "2".repeat(32));
        assertThrows(RuntimeException.class, () -> auth.issue(installation, request("host", "boot-2"), 1001));
        assertEquals(ID, row.getRuntimeInstallationId()); verify(rows, never()).updateById(any());
    }
    @Test void nullOwnershipFailsClosedUntilInstallationClaim() {
        row = new AgentRuntimeEntity().setId(4L).setAgentId(AGENT).setOwnerJiacn("owner")
                .setBindingId(3L).setTokenHash("legacy-raw-token").setStatus("online");
        row.setTenantId("0"); row.setClientId("client");
        assertThrows(RuntimeException.class, () -> auth.verify(new AgentRuntimeAuthenticationFilter.SessionHeaders(
                AGENT, ID, "host", "boot", 1, "rts1_" + "a".repeat(64))));
        var session = auth.issue(installation, request("host", "boot"), 1000);
        assertEquals(4L, row.getId()); assertEquals(1, session.sessionGeneration());
        verify(rows, never()).insert(any());
    }
    @Test void partialOwnershipIsNotAnImplicitClaimOrRepair() {
        auth.issue(installation, request("host", "boot"), 1000);
        row.setRuntimeHostId(null);
        assertThrows(RuntimeException.class, () -> auth.issue(installation, request("host", "boot-2"), 1001));
        verify(rows, never()).updateById(any());
    }
    @Test void fullScopeAndAuthoritativeOwnerAreRequired() {
        identity.setClientId("CLIENT");
        assertThrows(RuntimeException.class, () -> auth.issue(installation, request("host", "boot"), 1000));
        verifyNoInteractions(rows);
    }
    @Test void ambiguousOwnerRegistryIsRejected() {
        when(registry.selectByMap(anyMap())).thenReturn(List.of(identity, identity));
        assertThrows(RuntimeException.class, () -> auth.issue(installation, request("host", "boot"), 1000));
        verifyNoInteractions(rows);
    }
    @Test void existingGlobalKeyInAnotherScopeCannotBeStolen() {
        when(rows.findByAgentId(AGENT)).thenReturn(new AgentRuntimeEntity().setAgentId(AGENT));
        assertThrows(RuntimeException.class, () -> auth.issue(installation, request("host", "boot"), 1000));
        verify(rows, never()).insert(any());
    }
    @Test void generationExhaustionDoesNotRotateCredential() {
        auth.issue(installation, request("host", "boot"), 1000); row.setRuntimeSessionGeneration(Long.MAX_VALUE);
        String digest = row.getTokenHash();
        assertThrows(RuntimeException.class, () -> auth.issue(installation, request("host", "boot-2"), 1001));
        assertEquals(digest, row.getTokenHash()); verify(rows, never()).updateById(any());
    }
    @Test void accountEpochAndReplacementArePersistentlyRechecked() {
        var session = auth.issue(installation, request("host", "boot"), 1000);
        when(accounts.findUniqueByExactJiacn("owner")).thenReturn(Optional.of(new AccountSecuritySnapshot(7, "owner", AccountState.ACTIVE, 3)));
        assertThrows(RuntimeException.class, () -> auth.verify(headers(session)));
        when(accounts.findUniqueByExactJiacn("owner")).thenReturn(Optional.of(new AccountSecuritySnapshot(8, "owner", AccountState.ACTIVE, 2)));
        assertThrows(RuntimeException.class, () -> auth.verify(headers(session)));
    }
    @Test void revokedInstallationCannotAuthorizeTerminalProofOrLiveBinding() {
        var session = auth.issue(installation, request("host", "boot"), 1000);
        installation.setStatus("REVOKED");
        assertThrows(RuntimeException.class, () -> auth.verify(headers(session)));
    }
    @Test void staleGenerationCannotEnterBusinessCallbackAndLockOrderIsStable() {
        var first = auth.issue(installation, request("host", "boot"), 1000); var proof = auth.verify(headers(first));
        auth.issue(installation, request("host", "new-boot"), 1001);
        AtomicBoolean called = new AtomicBoolean();
        assertThrows(RuntimeException.class, () -> auth.withFence(proof, false, () -> called.compareAndSet(false, true)));
        assertFalse(called.get());
        clearInvocations(installations, rows);
        var current = auth.verify(headers(auth.issue(installation, request("host", "third-boot"), 1002)));
        clearInvocations(installations, rows);
        assertEquals("committed", auth.withFence(current, false, () -> "committed"));
        var ordered = inOrder(installations, rows);
        ordered.verify(installations).lock(ID); ordered.verify(rows).lockInScope("0", "client", AGENT);
    }
    @Test void registrationReceiptAndDisconnectCannotRevokeReplacementChannel() {
        var session = auth.issue(installation, request("host", "boot"), 1000); row.setStatus("online");
        var proof = auth.verify(headers(session));
        auth.bind("old-socket", proof, () -> true); auth.bind("new-socket", proof, () -> true);
        auth.disconnect("old-socket");
        assertTrue(auth.isCurrentBinding("new-socket", "0", "client", "owner", AGENT, "boot"));
        assertFalse(auth.isCurrentBinding("old-socket", "0", "client", "owner", AGENT, "boot"));
    }
}
