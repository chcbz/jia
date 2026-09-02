package cn.jia.economy.common;

public enum EconomyJournalType {
    ISSUE_SILVER,
    RESERVE_BOUNTY,
    CAPTURE_COMPUTE,
    PAY_AGENT,
    CAPTURE_FEE,
    REFUND_BOUNTY,
    RESERVE_SKILL,
    CAPTURE_SKILL,
    RECORD_PROVIDER_VARIANCE,
    REFUND_SKILL,
    REVERSE;

    public boolean requiresFundingLot() {
        return this == RESERVE_BOUNTY || this == RESERVE_SKILL;
    }
}
