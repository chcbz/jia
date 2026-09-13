package cn.jia.agent.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRabbitTopologyStartupTest {
    @Test
    void lifecycleActivatesTopologyBeforeRelayAndRabbitListeners() {
        AgentRabbitTopologyProvisioner provisioner = mock(AgentRabbitTopologyProvisioner.class);
        when(provisioner.activate()).thenReturn(new AgentRabbitTopologyReadiness.Snapshot(
                AgentRabbitTopologyReadiness.Status.READY,
                AgentRabbitTopologyReadiness.Source.PROVISION,
                AgentRabbitTopologyReadiness.Coverage.CANONICAL_TOPOLOGY,
                AgentRabbitTopologyManifest.CANONICAL_SHA256, 1L, null));
        AgentRabbitTopologyStartup startup = new AgentRabbitTopologyStartup(provisioner);

        assertTrue(startup.isAutoStartup());
        assertFalse(startup.isRunning());
        assertEquals(Integer.MAX_VALUE - 200, startup.getPhase());
        assertTrue(startup.getPhase() < Integer.MAX_VALUE - 100);
        assertTrue(startup.getPhase() < Integer.MAX_VALUE);

        startup.start();
        startup.start();

        assertTrue(startup.isRunning());
        verify(provisioner, times(1)).activate();

        startup.stop();
        assertFalse(startup.isRunning());
    }

    @Test
    void startupFailsClosedWhenActivationDoesNotReachCanonicalReadiness() {
        AgentRabbitTopologyProvisioner provisioner = mock(AgentRabbitTopologyProvisioner.class);
        when(provisioner.activate()).thenReturn(new AgentRabbitTopologyReadiness.Snapshot(
                AgentRabbitTopologyReadiness.Status.EXISTENCE_CONFIRMED,
                AgentRabbitTopologyReadiness.Source.PASSIVE_VERIFY,
                AgentRabbitTopologyReadiness.Coverage.RESOURCE_EXISTENCE,
                AgentRabbitTopologyManifest.CANONICAL_SHA256, 1L, null));

        AgentRabbitTopologyStartup startup = new AgentRabbitTopologyStartup(provisioner);
        assertThrows(IllegalStateException.class, startup::start);
        assertFalse(startup.isRunning());
    }
}
