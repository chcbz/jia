package cn.jia.agent.service.impl;

import cn.jia.agent.config.AgentCommandReissueSettings;
import cn.jia.agent.entity.AgentCommandManualReissueResult;
import cn.jia.agent.entity.AgentCommandOperationRequest;
import cn.jia.agent.entity.AgentCommandReconnectScope;
import cn.jia.agent.entity.AgentCommandReissueScanResult;
import cn.jia.agent.service.AgentCommandReissueService;
import org.junit.jupiter.api.Test;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.task.scheduling.ScheduleService;
import cn.jia.task.scheduling.ScheduleKey;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        try (AgentCommandReissueCoordinator coordinator = coordinator(service, settings, () -> 1_700_000_000_000L)) {
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
        AgentCommandReissueCoordinator coordinator = coordinator(service, new AgentCommandReissueSettings(2, 1, 1, 100),
                () -> 1_700_000_000_000L);
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
        try (AgentCommandReissueCoordinator coordinator = coordinator(service, new AgentCommandReissueSettings(1, 1, 1, 100), () -> 10L)) {
            assertFalse(coordinator.signalReconnect(new AgentCommandReconnectScope(
                    " tenant", "client", "agent", "agent", "AGENT_RECONNECT")));
            assertFalse(coordinator.signalReconnect(new AgentCommandReconnectScope(
                    "tenant", "client", "agent", "operator", "AGENT_RECONNECT")));
            assertFalse(coordinator.signalReconnect(new AgentCommandReconnectScope(
                    "tenant", "client", "agent", "other-agent", "AGENT_RECONNECT")));
            assertFalse(coordinator.signalReconnect(new AgentCommandReconnectScope(
                    "tenant", "client", "agent", "agent", "WAITING_AGENT_SCHEDULER")));
            assertEquals(0, coordinator.queuedSignalCount());
            coordinator.runOnce();
            assertEquals(9, coordinator.schedulerCursor());
            coordinator.runOnce();
            assertEquals(0, coordinator.schedulerCursor());
        }
    }

    @Test
    void readyRegistersOnceAndClosedCallbacksNeitherRegisterNorScan() {
        var service = mock(AgentCommandReissueService.class);
        var schedules = mock(ScheduleService.class);
        try (var coordinator = new AgentCommandReissueCoordinator(service,
                new AgentCommandReissueSettings(2, 1, 1, 100), schedules,
                new FixedTenantAdmissionPolicy("tenant-a", "client-a"), "node-a", () -> 1000L)) {
            coordinator.executeScheduled(null);
            assertTrue(coordinator.signalReconnect(scope("agent-a")));
            verifyNoInteractions(service, schedules);
            coordinator.onApplicationEvent(null);
            coordinator.onApplicationEvent(null);
            verify(schedules).signal(new ScheduleKey("client-a", AgentCommandReissueCoordinator.HANDLER,
                    "recovery", "node-a"), 1100L);
            verifyNoInteractions(service);
            coordinator.close();
            clearInvocations(schedules);
            coordinator.executeScheduled(null);
            coordinator.onApplicationEvent(null);
            verifyNoInteractions(service, schedules);
        }
    }

    @Test
    void slowScanDoesNotBlockCallbacksOrQueueDuplicateScans() throws Exception {
        var service = mock(AgentCommandReissueService.class);
        var schedules = mock(ScheduleService.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(service.reissueDue(anyInt(), anyLong(), anyLong())).thenAnswer(invocation -> {
            entered.countDown();
            release.await();
            return new AgentCommandReissueScanResult(0, 0, 0);
        });
        try (var coordinator = new AgentCommandReissueCoordinator(service,
                new AgentCommandReissueSettings(2, 1, 1, 100), schedules,
                new FixedTenantAdmissionPolicy("tenant-a", null), "node-a", () -> 1000L)) {
            coordinator.onApplicationEvent(null);
            coordinator.executeScheduled(null);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            // These return while the scanner remains blocked on release.
            for (int i = 0; i < 10; i++) coordinator.executeScheduled(null);
            verify(service).reissueDue(1, 0, 1000L);
            verify(schedules, times(12)).signal(new ScheduleKey("", AgentCommandReissueCoordinator.HANDLER,
                    "recovery", "node-a"), 1100L);
            release.countDown();
        } finally { release.countDown(); }
        verify(service).reissueDue(1, 0, 1000L);
    }

    @Test
    void cannotStartScanWithoutPersistingItsRecoveryDeadline() {
        var service = mock(AgentCommandReissueService.class);
        var schedules = mock(ScheduleService.class);
        try (var coordinator = new AgentCommandReissueCoordinator(service,
                new AgentCommandReissueSettings(2, 1, 1, 100), schedules,
                new FixedTenantAdmissionPolicy("tenant-a", null), "node-a", () -> 1000L)) {
            coordinator.onApplicationEvent(null);
            doThrow(new IllegalStateException("schedule unavailable")).when(schedules).signal(any(), anyLong());
            assertThrows(IllegalStateException.class, () -> coordinator.executeScheduled(null));
            verifyNoInteractions(service);
        }
    }

    @Test
    void failedWorkerRetainsAlreadyPersistedRecoveryDeadline() throws Exception {
        var service = mock(AgentCommandReissueService.class);
        var schedules = mock(ScheduleService.class);
        var attempted = new CountDownLatch(1);
        when(service.reissueDue(anyInt(), anyLong(), anyLong())).thenAnswer(invocation -> {
            attempted.countDown();
            throw new IllegalStateException("scan unavailable");
        });
        try (var coordinator = new AgentCommandReissueCoordinator(service,
                new AgentCommandReissueSettings(2, 1, 1, 100), schedules,
                new FixedTenantAdmissionPolicy("tenant-a", null), "node-a", () -> 1000L)) {
            coordinator.onApplicationEvent(null);
            coordinator.executeScheduled(null);
            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            verify(schedules, times(2)).signal(new ScheduleKey("", AgentCommandReissueCoordinator.HANDLER,
                    "recovery", "node-a"), 1100L);
        }
    }

    private static AgentCommandReissueCoordinator coordinator(AgentCommandReissueService service,
            AgentCommandReissueSettings settings, LongSupplier clock) {
        return new AgentCommandReissueCoordinator(service, settings, mock(ScheduleService.class),
                new FixedTenantAdmissionPolicy("tenant-a", null), "node-a", clock);
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
        public AgentCommandManualReissueResult reissueManually(
                AgentCommandOperationRequest request, long now) {
            throw new AssertionError("manual reissue is not used by the coordinator");
        }

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
