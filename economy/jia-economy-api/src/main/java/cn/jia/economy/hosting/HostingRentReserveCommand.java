package cn.jia.economy.hosting;

import cn.jia.economy.service.EconomyPrincipal;
import cn.jia.economy.service.EconomyScope;

public record HostingRentReserveCommand(
        EconomyScope scope,
        EconomyPrincipal principal,
        String idempotencyKey,
        byte[] requestHash,
        String quoteId) {
    public HostingRentReserveCommand {
        requestHash = requestHash == null ? null : requestHash.clone();
    }

    @Override
    public byte[] requestHash() {
        return requestHash == null ? null : requestHash.clone();
    }
}
