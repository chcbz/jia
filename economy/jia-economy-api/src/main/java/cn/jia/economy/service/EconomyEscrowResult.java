package cn.jia.economy.service;

public record EconomyEscrowResult(
        String escrowId,
        int fundingSequence,
        long fundingAmountMicro,
        long grossAfterMicro,
        long escrowVersion) {
}
