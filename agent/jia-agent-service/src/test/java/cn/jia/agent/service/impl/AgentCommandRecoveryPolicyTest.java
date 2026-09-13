package cn.jia.agent.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCommandRecoveryPolicyTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final AgentCommandRecoveryPolicy.Scope SCOPE =
            new AgentCommandRecoveryPolicy.Scope(
                    "tenant-a", "client-a", "task-a", "work-a", "agent-a");

    private final AgentCommandRecoveryPolicy policy = new AgentCommandRecoveryPolicy();

    @Test
    void firstAutomaticReconnectIsImmediateButRepeatedAttemptsUseBoundedBackoff() {
        AgentCommandRecoveryPolicy.Decision first = policy.automatic(
                SCOPE, 1, NOW, NOW + 60_000L, NOW);
        assertEquals(AgentCommandRecoveryPolicy.Action.REISSUE, first.action());
        assertEquals(NOW, first.eligibleAt());

        AgentCommandRecoveryPolicy.Decision early = policy.automatic(
                SCOPE, 2, NOW, NOW + 60_000L, NOW + 4_999L);
        assertEquals(AgentCommandRecoveryPolicy.Action.DEFER, early.action());
        assertEquals(NOW + 5_000L, early.eligibleAt());
        assertFalse(early.permitsReissue());

        AgentCommandRecoveryPolicy.Decision boundary = policy.automatic(
                SCOPE, 2, NOW, NOW + 60_000L, NOW + 5_000L);
        assertEquals(AgentCommandRecoveryPolicy.Action.REISSUE, boundary.action());
        assertTrue(boundary.permitsReissue());

        AgentCommandRecoveryPolicy.Decision late = policy.automatic(
                SCOPE, 3, NOW, NOW + 60_000L, NOW + 29_999L);
        assertEquals(AgentCommandRecoveryPolicy.Action.DEFER, late.action());
        assertEquals(NOW + 30_000L, late.eligibleAt());
    }

    @Test
    void automaticBudgetStopsBeforeCreatingAnUnboundedTransportAttempt() {
        AgentCommandRecoveryPolicy.Decision exhausted = policy.automatic(
                SCOPE, AgentCommandRecoveryPolicy.DEFAULT_MAX_AUTOMATIC_ATTEMPTS,
                NOW, NOW + 60_000L, NOW + 30_000L);

        assertEquals(AgentCommandRecoveryPolicy.Action.MANUAL_TAKEOVER_REQUIRED,
                exhausted.action());
        assertFalse(exhausted.permitsReissue());
    }

    @Test
    void deadlineClampAndLargeEpochArithmeticNeverOverflowIntoEligibility() {
        long nearMax = Long.MAX_VALUE - 10L;
        AgentCommandRecoveryPolicy.Decision clamped = policy.automatic(
                SCOPE, 2, nearMax - 1_000L, nearMax, nearMax - 1L);
        assertEquals(AgentCommandRecoveryPolicy.Action.DEADLINE_EXHAUSTED, clamped.action());
        assertEquals(nearMax, clamped.eligibleAt());

        AgentCommandRecoveryPolicy.Decision expired = policy.automatic(
                SCOPE, 1, nearMax - 1_000L, nearMax, nearMax);
        assertEquals(AgentCommandRecoveryPolicy.Action.EXPIRED, expired.action());
    }

    @Test
    void manualTakeoverHasItsOwnBackoffAndOneFinalBoundedAttempt() {
        AgentCommandRecoveryPolicy.Decision early = policy.manual(
                SCOPE, 4, NOW, NOW + 60_000L, NOW + 29_999L);
        assertEquals(AgentCommandRecoveryPolicy.Action.DEFER, early.action());

        AgentCommandRecoveryPolicy.Decision allowed = policy.manual(
                SCOPE, 4, NOW, NOW + 60_000L, NOW + 30_000L);
        assertEquals(AgentCommandRecoveryPolicy.Action.REISSUE, allowed.action());

        AgentCommandRecoveryPolicy.Decision exhausted = policy.manual(
                SCOPE, AgentCommandRecoveryPolicy.DEFAULT_MAX_TOTAL_ATTEMPTS,
                NOW, NOW + 60_000L, NOW + 30_000L);
        assertEquals(AgentCommandRecoveryPolicy.Action.ATTEMPTS_EXHAUSTED,
                exhausted.action());
    }

    @Test
    void exactScopeAndMonotonicTimeAreFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> policy.automatic(
                new AgentCommandRecoveryPolicy.Scope(
                        " tenant-a", "client-a", "task-a", "work-a", "agent-a"),
                1, NOW, NOW + 60_000L, NOW));
        assertThrows(IllegalArgumentException.class, () -> policy.automatic(
                SCOPE, 1, NOW + 1L, NOW + 60_000L, NOW));
        assertThrows(IllegalArgumentException.class, () -> policy.manual(
                SCOPE, 6, NOW, NOW + 60_000L, NOW));
    }
}
