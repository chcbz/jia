package cn.jia.agent.skill;

import cn.jia.agent.security.AgentRuntimeAuthentication;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;

import cn.jia.agent.config.AgentTaskEventsGate;
import cn.jia.agent.dao.*;
import cn.jia.agent.entity.*;
import cn.jia.agent.event.AgentEventPublisher;
import cn.jia.agent.service.AgentStatusTransitionWorker;
import cn.jia.agent.service.impl.AgentIdentityServiceImpl;
import cn.jia.user.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real private MySQL transactions with production fence/identity/monitor services.
 * DAO adapters use minimal locking SQL, without touching the production database. */
class SkillManagementRuntimeMonitorLockOrderTest extends SkillManagementMySqlFixture {
    @Test
    void fencedCallbackCanRelockBindingWhileMonitorWaitsWithoutReversingLockOrder() throws Exception {
        String database = "cyf_lock_" + java.util.UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE `" + database + "`");
        var source = source(database);
        var jdbc = new JdbcTemplate(source);
        for (String table : new String[]{"binding_row", "identity_row", "runtime_row"}) {
            jdbc.execute("CREATE TABLE " + table + " (id BIGINT PRIMARY KEY, value_num BIGINT)");
            jdbc.update("INSERT INTO " + table + " VALUES (1,0)");
        }
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var bindingDao = mock(AgentPersonaBindingDao.class);
        var registry = mock(AgentIdentityRegistryDao.class);
        var runtimes = mock(AgentRuntimeDao.class);
        var installations = mock(AgentRuntimeV1InstallationDao.class);
        var accounts = mock(AccountSecurityService.class);
        var identityService = new AgentIdentityServiceImpl(registry, mock(AgentIdentityAliasDao.class), bindingDao);
        String agent = "agt_" + "a".repeat(32), installationId = "rti_" + "1".repeat(32);
        var binding = new AgentPersonaBindingEntity().setId(1L).setJiacn("owner")
                .setPersonaCode("wuyong").setAgentId(agent).setStatus(1);
        binding.setTenantId("0"); binding.setClientId("client");
        var identity = new AgentIdentityRegistryEntity().setId(1L).setBindingId(1L)
                .setCanonicalAgentId(agent).setCanonicalType("OPAQUE").setOwnerJiacn("owner")
                .setLifecycleStatus("ACTIVE");
        identity.setTenantId("0"); identity.setClientId("client");
        var installation = new AgentRuntimeV1InstallationEntity().setId(1L)
                .setInstallationId(installationId).setCanonicalAgentId(agent).setStatus("ACTIVE");
        installation.setTenantId("0"); installation.setClientId("client");
        var row = new AgentRuntimeEntity().setId(1L).setAgentId(agent).setOwnerJiacn("owner")
                .setBindingId(1L).setStatus("online").setLastSeenAt(1L)
                .setRuntimeInstallationId(installationId).setRuntimeHostId("host")
                .setRuntimeInstanceId("boot").setRuntimeSessionGeneration(1L)
                .setTokenHash("urs1:digest:7:2");
        row.setTenantId("0"); row.setClientId("client");
        var proof = new AgentRuntimeAuthenticationService.Proof(
                new AgentRuntimeAuthentication.Scope("0", "client", "owner", agent, "boot"),
                installationId, "host", 1, "digest", 7, 2);
        var requestHasBinding = new AtomicBoolean();
        var runtimeLocked = new CountDownLatch(1);
        var monitorAttemptedBinding = new CountDownLatch(1);
        var monitorThread = new ThreadLocal<Boolean>();
        when(bindingDao.selectById(1L)).thenReturn(binding);
        when(bindingDao.findByIdForUpdate(1L)).thenAnswer(call -> {
            boolean monitor = Boolean.TRUE.equals(monitorThread.get());
            if (monitor) monitorAttemptedBinding.countDown();
            jdbc.queryForObject("SELECT id FROM binding_row WHERE id=1 FOR UPDATE", Long.class);
            if (!monitor) requestHasBinding.set(true);
            return binding;
        });
        when(registry.findExactByCanonicalInScope("0", "client", "owner", agent)).thenReturn(identity);
        when(registry.findExactByCanonicalInScopeForUpdate("0", "client", "owner", agent)).thenAnswer(call -> {
            jdbc.queryForObject("SELECT id FROM identity_row WHERE id=1 FOR UPDATE", Long.class);
            return identity;
        });
        when(registry.findExactByBindingInScopeForUpdate("0", "client", "owner", 1L)).thenAnswer(call -> {
            jdbc.queryForObject("SELECT id FROM identity_row WHERE id=1 FOR UPDATE", Long.class);
            return identity;
        });
        when(runtimes.lockInScope("0", "client", agent)).thenAnswer(call -> {
            assertTrue(requestHasBinding.get(), "request must own binding before runtime");
            jdbc.queryForObject("SELECT id FROM runtime_row WHERE id=1 FOR UPDATE", Long.class);
            runtimeLocked.countDown();
            assertTrue(monitorAttemptedBinding.await(5, TimeUnit.SECONDS));
            return row;
        });
        when(runtimes.findByAgentIdForUpdate(agent)).thenAnswer(call -> {
            jdbc.queryForObject("SELECT id FROM runtime_row WHERE id=1 FOR UPDATE", Long.class);
            return row;
        });
        when(runtimes.updateById(any(AgentRuntimeEntity.class))).thenAnswer(call ->
                jdbc.update("UPDATE runtime_row SET value_num=value_num+1 WHERE id=1"));
        when(installations.lock(installationId)).thenReturn(installation);
        when(accounts.findUniqueByExactJiacn("owner")).thenReturn(Optional.of(
                new AccountSecuritySnapshot(7, "owner", AccountState.ACTIVE, 2)));
        var auth = new AgentRuntimeAuthenticationService(runtimes, installations, registry,
                identityService, accounts, mock(AgentTaskEventsGate.class));
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentEventPublisher> publishers = mock(ObjectProvider.class);
        var publisher = mock(AgentEventPublisher.class);
        when(publishers.getIfAvailable()).thenReturn(publisher);
        when(publisher.connectedAgentIds()).thenReturn(Set.of(agent));
        var monitor = new AgentStatusTransitionWorker(bindingDao, registry, runtimes, publishers);
        var threads = Executors.newFixedThreadPool(2);
        try {
            var request = threads.submit(() -> tx.execute(status -> auth.withFence(proof, false, () -> {
                // Same nested binding lock that used to reverse the monitor's lock order.
                identityService.lockActiveCanonicalAgentIdsInScope("0", "client", "owner", java.util.List.of(agent));
                return "committed";
            })));
            var scan = threads.submit(() -> {
                assertTrue(runtimeLocked.await(5, TimeUnit.SECONDS));
                monitorThread.set(true);
                try { return tx.execute(status -> monitor.refreshConnected(row, 2L)); }
                finally { monitorThread.remove(); }
            });
            assertEquals("committed", request.get(10, TimeUnit.SECONDS));
            assertNotNull(scan.get(10, TimeUnit.SECONDS));
            assertEquals(1L, jdbc.queryForObject("SELECT value_num FROM runtime_row WHERE id=1", Long.class));
        } finally {
            threads.shutdownNow();
            assertTrue(threads.awaitTermination(10, TimeUnit.SECONDS));
            admin.execute("DROP DATABASE `" + database + "`");
        }
    }
}
