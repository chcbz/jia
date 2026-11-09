package cn.jia.task.scheduling;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScheduledWorkTest {
    private ScheduledWork running() {
        return ScheduledWork.empty().signal(100).acquire(100, 200, "a").work();
    }
    @Test void idleHasNoTimer() {
        assertNull(ScheduledWork.empty().nextWakeAt());
        assertEquals(ScheduledWork.empty(), ScheduledWork.empty().cancel());
    }
    @Test void signalsCoalesceAtEarliestDue() {
        var work=ScheduledWork.empty().signal(150).signal(180).signal(120);
        assertEquals(120L,work.nextWakeAt());assertEquals(3,work.requestedRevision());
    }
    @Test void futureWorkIsNotClaimedEarly() {
        var work=ScheduledWork.empty().signal(150);
        var result=work.acquire(149,200,"a");
        assertTrue(result.acquired().isEmpty());assertSame(work,result.work());
    }
    @Test void onlyOneActiveClaimAndNoRepeatedVersionBump() {
        var work=running();var result=work.acquire(199,300,"b");
        assertTrue(result.acquired().isEmpty());assertSame(work,result.work());
        assertEquals(200L,work.nextWakeAt());
    }
    @Test void successfulWorkBecomesIdle() {
        var work=running();var done=work.succeed(work.claim(),150).orElseThrow();
        assertNull(done.nextWakeAt());assertNull(done.claim());
        assertTrue(done.succeed(work.claim(),151).isEmpty());
    }
    @Test void eventDuringRunSurvivesCompletion() {
        var work=running();var newer=work.signal(140);
        var done=newer.succeed(work.claim(),150).orElseThrow();
        assertEquals(140L,done.nextWakeAt());assertEquals(2,done.requestedRevision());
        assertEquals(2,done.acquire(150,250,"b").acquired().orElseThrow().revision());
    }
    @Test void rescheduledFutureSurvivesOldCompletion() {
        var work=running();var next=work.reschedule(400).succeed(work.claim(),150).orElseThrow();
        assertEquals(400L,next.nextWakeAt());
    }
    @Test void cancellationDoesNotInterruptButPreventsRetry() {
        var work=running();var cancelled=work.cancel();
        assertEquals(work.claim(),cancelled.claim());
        assertNull(cancelled.retry(work.claim(),150,300).orElseThrow().nextWakeAt());
    }
    @Test void cancellationAfterAnEventClearsPendingWork() {
        var work=running().signal(120).cancel();
        assertNull(work.dueAt());assertNull(work.succeed(work.claim(),150).orElseThrow().nextWakeAt());
    }
    @Test void cancelledExpiredClaimRetiresWithoutInvocation() {
        var result=running().cancel().acquire(200,300,"b");
        assertTrue(result.acquired().isEmpty());assertNull(result.work().nextWakeAt());
    }
    @Test void expiredClaimReplaysSameRevisionWithNewToken() {
        var old=running();var next=old.acquire(200,300,"b");
        assertEquals(old.claim().revision(),next.acquired().orElseThrow().revision());
        assertTrue(next.work().succeed(old.claim(),210).isEmpty());
        assertTrue(old.succeed(old.claim(),200).isEmpty());
    }
    @Test void expirationDoesNotLoseNewerEvent() {
        var old=running();var next=old.signal(130).acquire(200,300,"b");
        assertEquals(2,next.acquired().orElseThrow().revision());
    }
    @Test void expirationPreservesFutureReplacementInsteadOfRunningEarly() {
        var next=running().reschedule(400).acquire(200,300,"b");
        assertTrue(next.acquired().isEmpty());assertNull(next.work().claim());
        assertEquals(400L,next.work().nextWakeAt());
    }
    @Test void failureSchedulesRetryWithoutInventingNewRevision() {
        var work=running();var next=work.retry(work.claim(),150,300).orElseThrow();
        assertEquals(300L,next.nextWakeAt());assertEquals(1,next.requestedRevision());
    }
    @Test void newerSignalWinsOverOldFailureBackoff() {
        var work=running();var next=work.signal(140).retry(work.claim(),150,300).orElseThrow();
        assertEquals(140L,next.nextWakeAt());
    }
    @Test void renewalKeepsIdentityAndPendingNotification() {
        var work=running();var next=work.signal(130).renew(work.claim(),150,300).orElseThrow();
        assertEquals(300L,next.nextWakeAt());
        assertEquals(130L,next.succeed(work.claim(),250).orElseThrow().nextWakeAt());
        assertTrue(work.renew(work.claim(),200,300).isEmpty());
    }
    @Test void cannotReuseExpiredTokenOrSetAnInvalidLease() {
        assertThrows(IllegalArgumentException.class,()->running().acquire(200,300,"a"));
        assertThrows(IllegalArgumentException.class,()->ScheduledWork.empty().acquire(100,100,"a"));
        assertThrows(IllegalArgumentException.class,()->ScheduledWork.empty().acquire(100,200," "));
    }
    @Test void foreignClaimCannotCompleteRenewOrRetry() {
        var work=running();var foreign=new ScheduledWork.Claim("other",1,200);
        assertTrue(work.succeed(foreign,150).isEmpty());
        assertTrue(work.renew(foreign,150,300).isEmpty());
        assertTrue(work.retry(foreign,150,300).isEmpty());
    }
    @Test void persistenceVersionAdvancesForEachMutation() {
        var initial=ScheduledWork.empty();var pending=initial.signal(100);
        var running=pending.acquire(100,200,"a").work();var newer=running.signal(130);
        var done=newer.succeed(running.claim(),150).orElseThrow();
        assertEquals(4,done.version());assertEquals(2,done.requestedRevision());
    }
}
