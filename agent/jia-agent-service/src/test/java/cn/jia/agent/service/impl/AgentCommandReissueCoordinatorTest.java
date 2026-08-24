package cn.jia.agent.service.impl;

import cn.jia.agent.config.AgentCommandReissueSettings;
import cn.jia.agent.entity.AgentCommandReconnectScope;
import cn.jia.agent.entity.AgentCommandReissueScanResult;
import cn.jia.agent.service.AgentCommandReissueService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCommandReissueCoordinatorTest {
    @Test
    void signalIsNonBlockingBoundedAndRunIsFairAndBounded() {
        RecordingService service = new RecordingService();
        AgentCommandReissueSettings settings = new AgentCommandReissueSettings(2, 1, 3, 100);
        try (AgentCommandReissueCoordinator coordinator = new AgentCommandReissueCoordinator(
                service, settings, () -> 1_700_000_000_000L, false)) {
            AgentCommandReconnectScope first = scope("agent-a");
            assertTrue(coordinator.signalReconnect(first));
            assertTrue(coordinator.signalReconnect(scope("agent-b")));
            assertFalse(coordinator.signalReconnect(scope("agent-c")));
            assertEquals(2, coordinator.queuedSignalCount());

            coordinator.runOnce();
            assertEquals(List.of(first), service.reconnects);
            assertEquals(1, coordinator.queuedSignalCount());
            assertEquals(7, coordinator.schedulerCursor());

            coordinator.runOnce();
            assertEquals(2, service.reconnects.size());
            assertEquals(List.of(0L, 7L), service.cursors);
            assertEquals(11, coordinator.schedulerCursor());
        }
    }


    @Test
    void closeIsIdempotentClearsSignalsAndTerminatesDaemonWorkerBoundedly() {
        RecordingService service = new RecordingService();
        AgentCommandReissueCoordinator coordinator = new AgentCommandReissueCoordinator(
                service, new AgentCommandReissueSettings(2, 1, 1, 100),
                () -> 1_700_000_000_000L, true);
        assertTrue(coordinator.signalReconnect(scope("agent-a")));

        coordinator.close();
        coordinator.close();

        assertEquals(0, coordinator.queuedSignalCount());
        assertTrue(coordinator.workerTerminated());
        assertFalse(coordinator.signalReconnect(scope("agent-b")));
    }

    @Test
    void emptyDuePageWrapsCursorAndInvalidSignalIsDeclined() {
        RecordingService service = new RecordingService();
        service.results.clear();
        service.results.add(new AgentCommandReissueScanResult(1, 0, 9));
        service.results.add(new AgentCommandReissueScanResult(0, 0, 9));
        try (AgentCommandReissueCoordinator coordinator = new AgentCommandReissueCoordinator(
                service, new AgentCommandReissueSettings(1, 1, 1, 100), () -> 10L, false)) {
            assertFalse(coordinator.signalReconnect(new AgentCommandReconnectScope(
                    " tenant", "client", "agent", "agent", "AGENT_RECONNECT")));
            coordinator.runOnce();
            assertEquals(9, coordinator.schedulerCursor());
            coordinator.runOnce();
            assertEquals(0, coordinator.schedulerCursor());
        }
    }

    private static AgentCommandReconnectScope scope(String agentId) {
        return new AgentCommandReconnectScope(
                "tenant-a", "client-a", agentId, agentId, "AGENT_RECONNECT");
    }

    private static final class RecordingService implements AgentCommandReissueService {
        private final List<AgentCommandReconnectScope> reconnects = new ArrayList<>();
        private final List<Long> cursors = new ArrayList<>();
        private final List<AgentCommandReissueScanResult> results = new ArrayList<>(List.of(
                new AgentCommandReissueScanResult(3, 1, 7),
                new AgentCommandReissueScanResult(3, 1, 11)));

        @Override
        public AgentCommandReissueScanResult reissueForReconnect(
                AgentCommandReconnectScope scope, int limit, long now) {
            reconnects.add(scope);
            return new AgentCommandReissueScanResult(1, 1, 1);
        }

        @Override
        public AgentCommandReissueScanResult reissueDue(int limit, long afterDeliveryId, long now) {
            cursors.add(afterDeliveryId);
            return results.removeFirst();
        }
    }
}
