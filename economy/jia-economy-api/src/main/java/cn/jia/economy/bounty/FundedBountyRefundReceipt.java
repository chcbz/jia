package cn.jia.economy.bounty;

public record FundedBountyRefundReceipt(
        String transactionId, long escrowVersion, long amountWen, long postedAt) {
}
