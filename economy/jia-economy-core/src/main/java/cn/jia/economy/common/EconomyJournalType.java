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
    RESERVE_HOSTING_RENT,
    CAPTURE_HOSTING_RENT,
    REFUND_HOSTING_RENT,
    REVERSE;

    public boolean requiresFundingLot() {
        return this == RESERVE_BOUNTY || this == RESERVE_SKILL || this == RESERVE_HOSTING_RENT;
    }
}
