package cn.jia.agent.entity;

import java.util.Objects;

/** Exact persisted outcome/settlement pair and its fail-closed generated-guard semantics. */
public record AgentCommandRedriveOperationState(
        AgentCommandRedriveOutcomeState outcome,
        AgentCommandRedriveSettlementState settlement) {

    public static final AgentCommandRedriveOperationState PENDING = new AgentCommandRedriveOperationState(
            AgentCommandRedriveOutcomeState.PENDING,
            AgentCommandRedriveSettlementState.PENDING);
    public static final AgentCommandRedriveOperationState SUCCEEDED = new AgentCommandRedriveOperationState(
            AgentCommandRedriveOutcomeState.SUCCEEDED,
            AgentCommandRedriveSettlementState.SOURCE_ACKED);
    public static final AgentCommandRedriveOperationState FAILED_REQUEUED = new AgentCommandRedriveOperationState(
            AgentCommandRedriveOutcomeState.FAILED,
            AgentCommandRedriveSettlementState.SOURCE_REQUEUED);
    public static final AgentCommandRedriveOperationState FAILED_NOT_ACQUIRED = new AgentCommandRedriveOperationState(
            AgentCommandRedriveOutcomeState.FAILED,
            AgentCommandRedriveSettlementState.NOT_ACQUIRED);
    public static final AgentCommandRedriveOperationState FAILED_UNKNOWN = new AgentCommandRedriveOperationState(
            AgentCommandRedriveOutcomeState.FAILED,
            AgentCommandRedriveSettlementState.UNKNOWN);

    public AgentCommandRedriveOperationState {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(settlement, "settlement");
        boolean exact = switch (outcome) {
            case PENDING -> settlement == AgentCommandRedriveSettlementState.PENDING;
            case SUCCEEDED -> settlement == AgentCommandRedriveSettlementState.SOURCE_ACKED;
            case FAILED -> settlement == AgentCommandRedriveSettlementState.SOURCE_REQUEUED
                    || settlement == AgentCommandRedriveSettlementState.NOT_ACQUIRED
                    || settlement == AgentCommandRedriveSettlementState.UNKNOWN;
        };
        if (!exact) {
            throw new IllegalArgumentException("Invalid redrive outcome/settlement state");
        }
    }

    public boolean terminal() {
        return outcome != AgentCommandRedriveOutcomeState.PENDING;
    }

    public boolean holdsDispositionGuard() {
        return outcome == AgentCommandRedriveOutcomeState.PENDING;
    }

    public boolean holdsRedriveGuard() {
        return settlement != AgentCommandRedriveSettlementState.SOURCE_REQUEUED
                && settlement != AgentCommandRedriveSettlementState.NOT_ACQUIRED;
    }
}
