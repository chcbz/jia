package cn.jia.economy.hosting;

public record HostingRentQuoteReceipt(
        String quoteId,
        HostingRentQuotePurpose purpose,
        String planId,
        long planVersion,
        long amountMicro,
        long periodSeconds,
        String personaCode,
        String agentId,
        String leaseId,
        Long expectedLeaseVersion,
        long expiresAt) {
}
