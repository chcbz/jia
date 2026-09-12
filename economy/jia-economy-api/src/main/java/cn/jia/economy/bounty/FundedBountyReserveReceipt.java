package cn.jia.economy.bounty;

public record FundedBountyReserveReceipt(
        String transactionId, String escrowId, long escrowVersion, long amountMicro, long postedAt) {
}
