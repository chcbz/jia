package cn.jia.economy.hosting;

public record HostingRentMutationReceipt(
        String intentId,
        String leaseId,
        String quoteId,
        String transactionId,
        String status,
        long amountMicro,
        long periodSeconds,
        long occurredAt) {
}
