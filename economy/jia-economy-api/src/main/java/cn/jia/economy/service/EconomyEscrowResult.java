package cn.jia.economy.service;

public record EconomyEscrowResult(
        String escrowId,
        int fundingSequence,
        long fundingAmountWen,
        long grossAfterWen,
        long escrowVersion) {
}
