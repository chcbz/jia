package cn.jia.chat.service;

import cn.jia.agent.entity.WorkspaceExecutionProjectionHint;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ChatBountyAssetRelayTest {
    final ChatBountyAssetProjector projector = mock(ChatBountyAssetProjector.class);
    final ScheduledExecutorService worker = mock(ScheduledExecutorService.class);
    final List<Runnable> drains = new ArrayList<>();
    final cn.jia.task.scheduling.CronScheduleService schedules = mock(cn.jia.task.scheduling.CronScheduleService.class);
    final ChatBountyAssetRelay relay = new ChatBountyAssetRelay(projector, schedules,
            new cn.jia.core.security.FixedTenantAdmissionPolicy("0",null), "node-a", "0/30 * * * * *", () -> worker);
    final WorkspaceExecutionProjectionHint hint = new WorkspaceExecutionProjectionHint("0", "client", "owner", "exec");
    final ChatBountyAssetProjector.Candidate candidate = new ChatBountyAssetProjector.Candidate("0","owner","client","request","step","exec");
    @BeforeEach void setup() {
        doAnswer(i -> { drains.add(i.getArgument(0)); return null; }).when(worker).execute(any());
        when(projector.pendingExecution(hint)).thenReturn(List.of(candidate));
        when(projector.pending(any(),anyInt())).thenReturn(List.of());
        relay.start();
    }
    @AfterEach void stop() { relay.stop(); }
    @Test void hintIsImmediateTargetedAndCoalescedNotFullScan() {
        for (int i=0;i<20;i++) relay.onProjectionHint(hint);
        assertEquals(1,drains.size()); verifyNoInteractions(projector);
        drains.getFirst().run();
        verify(projector).pendingExecution(hint); verify(projector).project(candidate);
        verify(projector,never()).pending(any(),anyInt());
    }
    @Test void hintDuringFlightGetsOneTrailingRead() {
        when(projector.pendingExecution(hint)).thenAnswer(i -> {
            relay.onProjectionHint(hint); relay.onProjectionHint(hint); return List.of(candidate);
        }).thenReturn(List.of());
        relay.onProjectionHint(hint); drains.getFirst().run();
        verify(projector,times(2)).pendingExecution(hint); verify(projector).project(candidate);
        assertEquals(1,drains.size());
    }
    @Test void lostHintUsesThirtySecondCompensationAndOriginalProjection() {
        when(projector.pending(null,64)).thenReturn(List.of(candidate));
        relay.executeScheduled(null); verify(projector).project(candidate);
        verify(worker,never()).scheduleWithFixedDelay(any(),anyLong(),anyLong(),any());
    }
    @Test void failureDoesNotRetryInTightLoopAndScanRecovers() {
        when(projector.pendingExecution(hint)).thenThrow(new IllegalStateException("synthetic"));
        relay.onProjectionHint(hint); assertDoesNotThrow(drains.getFirst()::run);
        verify(projector).pendingExecution(hint); assertEquals(1,drains.size());
        when(projector.pending(null,64)).thenReturn(List.of(candidate));
        relay.executeScheduled(null); verify(projector).project(candidate);
    }
    @Test void oldCallbacksCannotRunAcrossStopRestart() {
        relay.onProjectionHint(hint); relay.stop(); relay.start();
        drains.getFirst().run(); verifyNoInteractions(projector);
        relay.onProjectionHint(hint); assertEquals(2,drains.size()); drains.get(1).run();
        verify(projector).project(candidate);
    }
    @Test void stoppedHintsDoNotCreateTimersAndStartupCompensates() {
        relay.stop(); relay.onProjectionHint(hint); assertTrue(drains.isEmpty());
        relay.start(); relay.executeScheduled(null); verify(projector).pending(null,64);
    }
    @Test void startupRegistersNodeRecoveryWithoutBusinessScan() {
        relay.onApplicationReady(null);
        verify(schedules).register(new cn.jia.task.scheduling.CronSchedule("",ChatBountyAssetRelay.HANDLER,
                "node-a","0/30 * * * * *","UTC",cn.jia.task.scheduling.CronSchedule.Misfire.COALESCE));
        verifyNoInteractions(projector);
    }
    @Test void stoppedRecoveryDoesNotQuery() {
        relay.stop(); relay.executeScheduled(null); verifyNoInteractions(projector);
    }
    @Test void rejectionCannotFailCommittedProducer() {
        doThrow(new RejectedExecutionException()).when(worker).execute(any());
        assertDoesNotThrow(() -> relay.onProjectionHint(hint)); verifyNoInteractions(projector);
    }
}
