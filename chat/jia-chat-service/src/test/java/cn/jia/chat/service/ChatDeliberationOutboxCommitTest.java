package cn.jia.chat.service;

import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatDispatchOutboxEntity;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** In-memory claim follows commit, never a SQL write that may still roll back. */
class ChatDeliberationOutboxCommitTest {
    @Test void rollbackKeepsClaimReusableAndCommitAloneMakesItTerminal() {
        var dao=mock(ChatDeliberationDao.class);var service=new ChatDeliberationOutboxService(dao, new cn.jia.core.security.FixedTenantAdmissionPolicy("0", null));
        var row=new ChatDispatchOutboxEntity().setTenantId("0").setOwnerJiacn("owner").setClientId("client")
                .setEventId("event").setStatus("CLAIMED").setLeaseOwner("worker").setFencingToken(1L).setVersion(2L).setLeaseUntil(100L);
        var claim=new ChatDeliberationOutboxService.Claim(row,false);
        when(dao.lockOutboxById("0","owner","client","event")).thenReturn(row);
        when(dao.settleOutbox(any(),eq("SENT"),isNull(),isNull(),eq(10L),eq(10L))).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertTrue(service.sent(claim,10));assertTrue(claim.active());assertEquals("CLAIMED",row.getStatus());
            for(var sync:TransactionSynchronizationManager.getSynchronizations())sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            assertTrue(claim.active());assertEquals(2L,row.getVersion());
        } finally {TransactionSynchronizationManager.clearSynchronization();}
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertTrue(service.sent(claim,10));assertTrue(claim.active());
            for(var sync:TransactionSynchronizationManager.getSynchronizations())sync.afterCommit();
            assertFalse(claim.active());assertEquals("SENT",row.getStatus());assertEquals(3L,row.getVersion());
            assertFalse(service.sent(claim,10));
        } finally {TransactionSynchronizationManager.clearSynchronization();}
    }
    @Test void staleFenceCannotSettleAnotherWorkersClaim() {
        var dao=mock(ChatDeliberationDao.class);var service=new ChatDeliberationOutboxService(dao, new cn.jia.core.security.FixedTenantAdmissionPolicy("0", null));
        var row=new ChatDispatchOutboxEntity().setTenantId("0").setOwnerJiacn("owner").setClientId("client")
                .setEventId("event").setLeaseOwner("worker").setFencingToken(1L).setVersion(2L);
        var current=new ChatDispatchOutboxEntity().setStatus("CLAIMED").setLeaseOwner("next-worker").setFencingToken(2L).setVersion(3L);
        var claim=new ChatDeliberationOutboxService.Claim(row,false);
        when(dao.lockOutboxById("0","owner","client","event")).thenReturn(current);
        assertThrows(IllegalStateException.class,()->service.sent(claim,10));assertFalse(claim.active());
        verify(dao,never()).settleOutbox(any(),any(),any(),any(),any(),anyLong());
    }

    @Test void wakeupIsAfterCommitAndRollbackDoesNotPublish() {
        var publisher=mock(org.springframework.context.ApplicationEventPublisher.class);
        TransactionSynchronizationManager.initSynchronization();
        try {
            ChatDeliberationOutboxWakeup.afterCommit(publisher, 1500);
            verifyNoInteractions(publisher);
            for(var sync:TransactionSynchronizationManager.getSynchronizations())sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            verifyNoInteractions(publisher);
        } finally {TransactionSynchronizationManager.clearSynchronization();}
        TransactionSynchronizationManager.initSynchronization();
        try {
            ChatDeliberationOutboxWakeup.afterCommit(publisher, 1500);
            for(var sync:TransactionSynchronizationManager.getSynchronizations())sync.afterCommit();
            verify(publisher).publishEvent(new ChatDeliberationOutboxWakeup(1500));
        } finally {TransactionSynchronizationManager.clearSynchronization();}
    }

    @Test void retrySettlementPublishesOnlyCommittedDeadlineAndUsesConfiguredPartition() {
        var dao=mock(ChatDeliberationDao.class);
        var service=new ChatDeliberationOutboxService(dao,new cn.jia.core.security.FixedTenantAdmissionPolicy("0","client"));
        var publisher=mock(org.springframework.context.ApplicationEventPublisher.class);service.setOutboxEvents(publisher);
        var row=new ChatDispatchOutboxEntity().setTenantId("0").setOwnerJiacn("owner").setClientId("client")
                .setEventId("event").setStatus("CLAIMED").setLeaseOwner("worker").setFencingToken(1L).setVersion(2L).setLeaseUntil(100L);
        var claim=new ChatDeliberationOutboxService.Claim(row,false);
        when(dao.lockOutboxById("0","owner","client","event")).thenReturn(row);
        when(dao.settleOutbox(any(),eq("RETRY"),eq(510L),eq("OFFLINE"),isNull(),eq(10L))).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertTrue(service.retry(claim,"OFFLINE",10));verifyNoInteractions(publisher);
            for(var sync:TransactionSynchronizationManager.getSynchronizations())sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            assertTrue(claim.active());verifyNoInteractions(publisher);
        } finally {TransactionSynchronizationManager.clearSynchronization();}
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertTrue(service.retry(claim,"OFFLINE",10));verifyNoInteractions(publisher);
            for(var sync:TransactionSynchronizationManager.getSynchronizations())sync.afterCommit();
            verify(publisher).publishEvent(new ChatDeliberationOutboxWakeup(510));assertFalse(claim.active());
        } finally {TransactionSynchronizationManager.clearSynchronization();}
        service.nextDueAt(10);verify(dao).nextOutboxDue("0","client",10);
    }

    @org.junit.jupiter.api.Nested class Scheduling {
        final ChatDeliberationOutboxService outbox=mock(ChatDeliberationOutboxService.class);
        final java.util.concurrent.ScheduledExecutorService scheduler=mock(java.util.concurrent.ScheduledExecutorService.class);
        final java.util.concurrent.atomic.AtomicLong now=new java.util.concurrent.atomic.AtomicLong(1000);
        final java.util.List<Runnable> callbacks=new java.util.ArrayList<>();
        final java.util.List<Long> delays=new java.util.ArrayList<>();
        final ChatDeliberationOutboxRelay relay=new ChatDeliberationOutboxRelay(outbox,null,null,null,null,null,null);
        @org.junit.jupiter.api.BeforeEach void setup() {
            doAnswer(call->{callbacks.add(call.getArgument(0));delays.add(call.getArgument(1));return mock(java.util.concurrent.ScheduledFuture.class);})
                .when(scheduler).schedule(any(Runnable.class),anyLong(),eq(java.util.concurrent.TimeUnit.MILLISECONDS));
            when(outbox.discover(anyLong(),anyInt())).thenReturn(java.util.List.of());
            when(outbox.nextDueAt(anyLong())).thenReturn(null); // SQL MIN on an empty partition is NULL, not Mockito boxed-zero.
            relay.scheduling(()->scheduler,now::get);relay.start();
        }
        @org.junit.jupiter.api.AfterEach void close() {relay.stop();}
        void tick() {callbacks.get(callbacks.size()-1).run();}
        @Test void idleUsesSingleFiveSecondRecoveryInsteadOfHalfSecondDiscovery() {
            tick();assertEquals(5000L,delays.get(delays.size()-1));
            verify(scheduler,never()).scheduleWithFixedDelay(any(),anyLong(),anyLong(),any());
            for(int i=0;i<12;i++){now.addAndGet(5000);tick();}
            verify(outbox,times(13)).discover(anyLong(),anyInt());
        }
        @Test void burstHintsCoalesceAndEarlierHintReplacesTheRecoveryWake() {
            tick();int before=callbacks.size();
            for(int i=0;i<100;i++)relay.onWorkAvailable(new ChatDeliberationOutboxWakeup(now.get()));
            assertEquals(before+1,callbacks.size());assertEquals(0L,delays.get(delays.size()-1));
            tick();verify(outbox,times(2)).discover(anyLong(),anyInt());
        }
        @Test void persistentFutureDeadlineSurvivesRestartAndIsNotRoundedToCompensation() {
            when(outbox.nextDueAt(1000)).thenReturn(1500L);tick();assertEquals(500L,delays.get(delays.size()-1));
            now.set(1500);tick();assertEquals(5000L,delays.get(delays.size()-1));
        }
        @Test void deadlineCrossedDuringScanStillSchedulesImmediateRecovery() {
            when(outbox.discover(anyLong(),anyInt())).thenAnswer(call->{now.set(1600);return java.util.List.of();});
            when(outbox.nextDueAt(1000)).thenReturn(1500L);
            tick();assertEquals(0L,delays.get(delays.size()-1));
            verify(outbox).nextDueAt(1000);
        }
        @Test void hintDuringClaimScanTriggersOneTailPassWithoutConcurrentDiscovery() {
            when(outbox.discover(anyLong(),anyInt())).thenAnswer(call->{
                relay.onWorkAvailable(new ChatDeliberationOutboxWakeup(now.get()));
                relay.pollOnce();return java.util.List.of();});
            tick();verify(outbox).discover(anyLong(),anyInt());assertEquals(0L,delays.get(delays.size()-1));
        }
        @Test void stoppedOrReplacedLifecycleCannotExecuteOldTimer() {
            Runnable old=callbacks.get(0);relay.stop();int size=callbacks.size();
            relay.onWorkAvailable(new ChatDeliberationOutboxWakeup(now.get()));assertEquals(size,callbacks.size());
            relay.start();old.run();verifyNoInteractions(outbox);tick();verify(outbox).discover(anyLong(),anyInt());
        }
        @Test void fullDeliverySlotsSkipSqlAndCompletionDrainsBacklogWithoutWaitingForCompensation() {
            var unused=(java.util.concurrent.ExecutorService)org.springframework.test.util.ReflectionTestUtils.getField(relay,"deliveryExecutor");
            unused.shutdownNow();
            var workers=mock(java.util.concurrent.ExecutorService.class);
            var queued=new java.util.ArrayList<Runnable>();
            doAnswer(call->{queued.add(call.getArgument(0));return null;}).when(workers).execute(any(Runnable.class));
            when(workers.shutdownNow()).thenAnswer(call->java.util.List.copyOf(queued));
            org.springframework.test.util.ReflectionTestUtils.setField(relay,"deliveryExecutor",workers);
            var rows=java.util.stream.IntStream.range(0,5).mapToObj(i->new ChatDispatchOutboxEntity()
                    .setEventId("event"+i).setEventType("CANCEL_REQUESTED").setPayloadJson("{}")).toList();
            when(outbox.discover(anyLong(),anyInt())).thenReturn(rows.subList(0,4),rows.subList(4,5));
            when(outbox.claim(any(),anyString(),anyLong(),anyLong())).thenAnswer(call->
                    new ChatDeliberationOutboxService.Claim(call.getArgument(0),false));
            tick();assertEquals(4,queued.size());verify(outbox,never()).nextDueAt(anyLong());
            now.addAndGet(5000);tick();verify(outbox).discover(anyLong(),anyInt());
            queued.get(0).run();assertEquals(0L,delays.get(delays.size()-1));
            tick();assertEquals(5,queued.size());verify(outbox,times(2)).discover(anyLong(),anyInt());
            relay.stop();
            var slots=(java.util.concurrent.Semaphore)org.springframework.test.util.ReflectionTestUtils.getField(relay,"deliverySlots");
            assertEquals(4,slots.availablePermits());
            queued.get(1).run();assertEquals(4,slots.availablePermits()); // Removed tasks cannot execute later.
        }
        @Test void failingScanRetainsSingleRecoveryAndDoesNotSpinOnConcurrentHint() {
            when(outbox.discover(anyLong(),anyInt())).thenAnswer(call->{
                relay.onWorkAvailable(new ChatDeliberationOutboxWakeup(now.get()));throw new IllegalStateException("transport");});
            tick();assertEquals(500L,delays.get(delays.size()-1));verify(outbox).discover(anyLong(),anyInt());
        }
    }
}
