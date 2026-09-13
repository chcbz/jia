package cn.jia.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRabbitTopologyStartupTest {
    @Test
    void startupActivatesTopologyAtHighestPrecedence() throws Exception {
        AgentRabbitTopologyProvisioner provisioner = mock(AgentRabbitTopologyProvisioner.class);
        when(provisioner.activate()).thenReturn(new AgentRabbitTopologyReadiness.Snapshot(
                AgentRabbitTopologyReadiness.Status.READY,
                AgentRabbitTopologyReadiness.Source.PROVISION,
                AgentRabbitTopologyReadiness.Coverage.CANONICAL_TOPOLOGY,
                AgentRabbitTopologyManifest.CANONICAL_SHA256, 1L, null));
        AgentRabbitTopologyStartup startup = new AgentRabbitTopologyStartup(provisioner);

        startup.run(null);

        assertEquals(Ordered.HIGHEST_PRECEDENCE, startup.getOrder());
        verify(provisioner).activate();
    }

    @Test
    void startupFailsClosedWhenActivationDoesNotReachCanonicalReadiness() {
        AgentRabbitTopologyProvisioner provisioner = mock(AgentRabbitTopologyProvisioner.class);
        when(provisioner.activate()).thenReturn(new AgentRabbitTopologyReadiness.Snapshot(
                AgentRabbitTopologyReadiness.Status.EXISTENCE_CONFIRMED,
                AgentRabbitTopologyReadiness.Source.PASSIVE_VERIFY,
                AgentRabbitTopologyReadiness.Coverage.RESOURCE_EXISTENCE,
                AgentRabbitTopologyManifest.CANONICAL_SHA256, 1L, null));

        assertThrows(IllegalStateException.class,
                () -> new AgentRabbitTopologyStartup(provisioner).run(null));
    }
}
