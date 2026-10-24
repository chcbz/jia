package cn.jia.agent.config;

import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentRabbitV2ScopedTopologyTest {
    private static AgentRabbitV2TopologyManifest manifest(String tenant, String domain, String pool) {
        return AgentRabbitV2TopologyManifest.forScope(
                TenantId.of(tenant), DataDomainId.of(domain), pool);
    }

    @Test
    void sameScopeIsStableAndAdjacentScopesHaveDisjointNamesRoutesAndDigest() {
        AgentRabbitV2TopologyManifest first = manifest("1", "cyf", "local-test");
        AgentRabbitV2TopologyManifest same = manifest("1", "cyf", "local-test");
        AgentRabbitV2TopologyManifest tenant = manifest("2", "cyf", "local-test");
        AgentRabbitV2TopologyManifest domain = manifest("1", "other", "local-test");
        AgentRabbitV2TopologyManifest pool = manifest("1", "cyf", "shared");

        assertEquals(first.canonicalText(), same.canonicalText());
        assertEquals(first.sha256(), same.sha256());
        assertEquals(first.defaultCommandPublishRoute(), same.defaultCommandPublishRoute());
        for (AgentRabbitV2TopologyManifest other : Set.of(tenant, domain, pool)) {
            assertNotEquals(first.scopeSha256(), other.scopeSha256());
            assertNotEquals(first.sha256(), other.sha256());
            assertNotEquals(first.defaultCommandPublishRoute(), other.defaultCommandPublishRoute());
            Set<String> names = new HashSet<>();
            first.exchanges().forEach(spec -> names.add(spec.name()));
            first.queues().forEach(spec -> names.add(spec.name()));
            assertTrue(other.exchanges().stream().noneMatch(spec -> names.contains(spec.name())));
            assertTrue(other.queues().stream().noneMatch(spec -> names.contains(spec.name())));
        }
        assertTrue(first.exchanges().stream().allMatch(spec -> spec.name().length() <= 255));
        assertTrue(first.queues().stream().allMatch(spec -> spec.name().length() <= 255));
        assertTrue(first.bindings().stream().allMatch(spec -> spec.routingKey().length() <= 100));
        assertTrue(first.exchanges().stream().noneMatch(spec ->
                spec.name().equals(AgentRabbitTopologyManifest.MAIN_EXCHANGE)
                        || spec.name().equals(AgentRabbitTopologyManifest.DEAD_LETTER_EXCHANGE)));
    }

    @Test
    void canonicalScopeValidationRejectsUnsafeOrOversizedValues() {
        assertThrows(IllegalArgumentException.class,
                () -> manifest("01", "cyf", "local-test"));
        assertThrows(IllegalArgumentException.class,
                () -> manifest("1", "Cyf", "local-test"));
        assertThrows(IllegalArgumentException.class,
                () -> manifest("1", "cyf", "../shared"));
        assertThrows(IllegalArgumentException.class,
                () -> manifest("1", "cyf", "a".repeat(101)));
    }

    @Test
    void readinessIsExactScopeAndPassiveExistenceIsNotCanonicalReadiness() throws Exception {
        AgentRabbitV2TopologyManifest first = manifest("1", "cyf", "local-test");
        AgentRabbitV2TopologyManifest second = manifest("2", "cyf", "local-test");
        RabbitAdmin admin = mock(RabbitAdmin.class);
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        Channel channel = mock(Channel.class);
        when(factory.createConnection()).thenReturn(connection);
        when(connection.createChannel(false)).thenReturn(channel);
        AgentRabbitV2TopologyReadiness readiness = new AgentRabbitV2TopologyReadiness();
        AgentRabbitV2TopologyProvisioner provisioner =
                new AgentRabbitV2TopologyProvisioner(admin, factory, readiness);

        assertFalse(readiness.snapshot(first).canonicalTopologyReady(first));
        assertFalse(readiness.snapshot(second).canonicalTopologyReady(second));
        AgentRabbitV2TopologyReadiness.Snapshot existence = provisioner.passiveVerify(first);
        assertEquals(AgentRabbitV2TopologyReadiness.Status.EXISTENCE_CONFIRMED,
                existence.status());
        assertFalse(existence.canonicalTopologyReady(first));
        assertFalse(readiness.snapshot(second).canonicalTopologyReady(second));

        AgentRabbitV2TopologyReadiness.Snapshot ready = provisioner.provision(first);
        assertTrue(ready.canonicalTopologyReady(first));
        assertFalse(readiness.snapshot(second).canonicalTopologyReady(second));
        verify(admin, never()).deleteExchange(anyString());
        verify(admin, never()).deleteQueue(anyString());
    }

    @Test
    void boundedRegistryRejectsBeforeBrokerIoAndPreservesReadyScopes() {
        AgentRabbitV2TopologyManifest first = manifest("1", "cyf", "pool-a");
        AgentRabbitV2TopologyManifest second = manifest("2", "cyf", "pool-a");
        AgentRabbitV2TopologyManifest overflow = manifest("3", "cyf", "pool-a");
        RabbitAdmin admin = mock(RabbitAdmin.class);
        ConnectionFactory factory = mock(ConnectionFactory.class);
        AgentRabbitV2TopologyReadiness readiness = new AgentRabbitV2TopologyReadiness(2);
        AgentRabbitV2TopologyProvisioner provisioner =
                new AgentRabbitV2TopologyProvisioner(admin, factory, readiness);

        provisioner.provision(first);
        provisioner.provision(second);
        AgentRabbitTopologyOperationException failure = assertThrows(
                AgentRabbitTopologyOperationException.class,
                () -> provisioner.passiveVerify(overflow));

        assertEquals(AgentRabbitTopologyOperationException.ErrorCode.PASSIVE_VERIFY_FAILED,
                failure.errorCode());
        assertEquals(AgentRabbitV2TopologyReadiness.ScopeCapacityExceededException.class.getName(),
                failure.failureType());
        assertEquals(2, readiness.registeredScopeCount());
        assertTrue(readiness.snapshot(first).canonicalTopologyReady(first));
        assertTrue(readiness.snapshot(second).canonicalTopologyReady(second));
        assertEquals(AgentRabbitV2TopologyReadiness.Status.NOT_CHECKED,
                readiness.snapshot(overflow).status());
        verifyNoInteractions(factory);
    }

    @Test
    void concurrentRegistrationsCannotExceedTheTrustedCapacity() throws Exception {
        AgentRabbitV2TopologyManifest first = manifest("1", "cyf", "pool-a");
        AgentRabbitV2TopologyManifest second = manifest("2", "cyf", "pool-a");
        RabbitAdmin admin = mock(RabbitAdmin.class);
        ConnectionFactory factory = mock(ConnectionFactory.class);
        AgentRabbitV2TopologyReadiness readiness = new AgentRabbitV2TopologyReadiness(1);
        AgentRabbitV2TopologyProvisioner firstProvisioner =
                new AgentRabbitV2TopologyProvisioner(admin, factory, readiness);
        AgentRabbitV2TopologyProvisioner secondProvisioner =
                new AgentRabbitV2TopologyProvisioner(admin, factory, readiness);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> firstResult = executor.submit(() -> {
                start.await();
                try { firstProvisioner.provision(first); return true; }
                catch (AgentRabbitTopologyOperationException rejected) { return false; }
            });
            Future<Boolean> secondResult = executor.submit(() -> {
                start.await();
                try { secondProvisioner.provision(second); return true; }
                catch (AgentRabbitTopologyOperationException rejected) { return false; }
            });
            start.countDown();
            boolean firstReady = firstResult.get(5, TimeUnit.SECONDS);
            boolean secondReady = secondResult.get(5, TimeUnit.SECONDS);
            assertNotEquals(firstReady, secondReady);
            assertEquals(1, readiness.registeredScopeCount());
            assertEquals(1, Set.of(readiness.snapshot(first).status(),
                    readiness.snapshot(second).status()).stream()
                    .filter(status -> status == AgentRabbitV2TopologyReadiness.Status.READY)
                    .count());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void constructionIsSideEffectFreeAndProvisionOrderIsCanonical() {
        AgentRabbitV2TopologyManifest scoped = manifest("1", "cyf", "local-test");
        RabbitAdmin admin = mock(RabbitAdmin.class);
        ConnectionFactory factory = mock(ConnectionFactory.class);
        AgentRabbitV2TopologyReadiness readiness = new AgentRabbitV2TopologyReadiness();
        AgentRabbitV2TopologyProvisioner provisioner =
                new AgentRabbitV2TopologyProvisioner(admin, factory, readiness);
        verifyNoInteractions(admin, factory);

        provisioner.provision(scoped);
        var ordered = inOrder(admin);
        ordered.verify(admin, org.mockito.Mockito.times(scoped.exchanges().size()))
                .declareExchange(org.mockito.ArgumentMatchers.any());
        ordered.verify(admin, org.mockito.Mockito.times(scoped.queues().size()))
                .declareQueue(org.mockito.ArgumentMatchers.any());
        ordered.verify(admin, org.mockito.Mockito.times(scoped.bindings().size()))
                .declareBinding(org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(factory);
    }
}
