package cn.jia.economy.hosting;

import cn.jia.economy.service.EconomyPrincipal;
import cn.jia.economy.service.EconomyScope;

public record HostingRentQuoteCommand(
        EconomyScope scope,
        EconomyPrincipal principal,
        String idempotencyKey,
        byte[] requestHash,
        HostingRentQuotePurpose purpose,
        String planId,
        long planVersion,
        String personaCode,
        String agentId,
        String leaseId,
        Long expectedLeaseVersion) {
    public HostingRentQuoteCommand {
        requestHash = requestHash == null ? null : requestHash.clone();
    }

    @Override
    public byte[] requestHash() {
        return requestHash == null ? null : requestHash.clone();
    }
}
