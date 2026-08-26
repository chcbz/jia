package cn.jia.agent.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCommandRedriveOperationStateTest {
    @Test
    void exactStatePairsExposeFailClosedGuardSemantics() {
        assertFalse(AgentCommandRedriveOperationState.PENDING.terminal());
        assertTrue(AgentCommandRedriveOperationState.PENDING.holdsDispositionGuard());
        assertTrue(AgentCommandRedriveOperationState.PENDING.holdsRedriveGuard());

        assertTerminalGuards(AgentCommandRedriveOperationState.SUCCEEDED, true);
        assertTerminalGuards(AgentCommandRedriveOperationState.FAILED_UNKNOWN, true);
        assertTerminalGuards(AgentCommandRedriveOperationState.FAILED_REQUEUED, false);
        assertTerminalGuards(AgentCommandRedriveOperationState.FAILED_NOT_ACQUIRED, false);
    }

    @Test
    void entityProjectsTheExactPersistedPair() {
        AgentCommandRedriveOperationEntity entity = new AgentCommandRedriveOperationEntity()
                .setOutcomeState(AgentCommandRedriveOutcomeState.SUCCEEDED)
                .setSettlementState(AgentCommandRedriveSettlementState.SOURCE_ACKED);
        assertTrue(entity.state().equals(AgentCommandRedriveOperationState.SUCCEEDED));
    }

    @Test
    void invalidOutcomeSettlementCrossProductsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AgentCommandRedriveOperationState(
                AgentCommandRedriveOutcomeState.PENDING,
                AgentCommandRedriveSettlementState.SOURCE_ACKED));
        assertThrows(IllegalArgumentException.class, () -> new AgentCommandRedriveOperationState(
                AgentCommandRedriveOutcomeState.SUCCEEDED,
                AgentCommandRedriveSettlementState.UNKNOWN));
        assertThrows(IllegalArgumentException.class, () -> new AgentCommandRedriveOperationState(
                AgentCommandRedriveOutcomeState.FAILED,
                AgentCommandRedriveSettlementState.PENDING));
    }

    private void assertTerminalGuards(
            AgentCommandRedriveOperationState state, boolean holdsRedriveGuard) {
        assertTrue(state.terminal());
        assertFalse(state.holdsDispositionGuard());
        if (holdsRedriveGuard) {
            assertTrue(state.holdsRedriveGuard());
        } else {
            assertFalse(state.holdsRedriveGuard());
        }
    }
}
