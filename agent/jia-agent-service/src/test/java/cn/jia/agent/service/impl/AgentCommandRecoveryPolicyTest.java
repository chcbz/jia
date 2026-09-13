package cn.jia.agent.service.impl;

import cn.jia.agent.entity.AgentCommandRecoveryDeferral;
import cn.jia.agent.entity.AgentCommandReissueScanResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
    void historicalAttemptsAboveNewLimitsAreExhaustedNotInvalid() {
        for (int attempt : new int[] {4, 5, 6, 9, Integer.MAX_VALUE}) {
            assertEquals(AgentCommandRecoveryPolicy.Action.MANUAL_TAKEOVER_REQUIRED,
                    policy.automatic(SCOPE, attempt, NOW, NOW + 60_000L, NOW).action());
            if (attempt >= 5) {
                assertEquals(AgentCommandRecoveryPolicy.Action.ATTEMPTS_EXHAUSTED,
                        policy.manual(SCOPE, attempt, NOW, NOW + 60_000L, NOW).action());
            }
        }
    }

    @Test
    void deferralReadbackIsBoundedImmutableAndThreeArgumentScanRemainsCompatible() {
        var observation = new AgentCommandRecoveryDeferral(
                "tenant-a", "client-a", "task-a", "work-a", "agent-a",
                1L, "message-a", Integer.MAX_VALUE, 7L,
                "MANUAL_TAKEOVER_REQUIRED", NOW, NOW, NOW + 60_000L);
        var mutable = new ArrayList<>(List.of(observation));
        var scan = new AgentCommandReissueScanResult(1, 0, 1L, mutable);
        mutable.clear();
        assertEquals(List.of(observation), scan.deferrals());
        assertThrows(UnsupportedOperationException.class, () -> scan.deferrals().clear());
        assertEquals(List.of(), new AgentCommandReissueScanResult(1, 0, 1L).deferrals());
        assertThrows(IllegalArgumentException.class, () -> new AgentCommandReissueScanResult(
                101, 0, 101L, Collections.nCopies(101, observation)));
        assertThrows(IllegalArgumentException.class, () -> new AgentCommandReissueScanResult(
                0, 0, 0L, List.of(observation)));
        assertThrows(IllegalArgumentException.class, () -> new AgentCommandRecoveryDeferral(
                "tenant-a", "client-a", "task-a", null, "agent-a",
                1L, "message-a", 4, 7L, "FAILED", NOW, NOW, NOW + 60_000L));
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
                SCOPE, 0, NOW, NOW + 60_000L, NOW));
    }
}
