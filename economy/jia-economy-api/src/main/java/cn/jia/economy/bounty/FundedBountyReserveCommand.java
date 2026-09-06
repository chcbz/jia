package cn.jia.economy.bounty;

import cn.jia.economy.service.EconomyPrincipal;
import cn.jia.economy.service.EconomyScope;

public record FundedBountyReserveCommand(
        EconomyScope scope,
        EconomyPrincipal principal,
        String idempotencyKey,
        byte[] requestHash,
        String taskId,
        long amountMicro) {
    public FundedBountyReserveCommand {
        requestHash = requestHash == null ? null : requestHash.clone();
    }
    @Override public byte[] requestHash() { return requestHash == null ? null : requestHash.clone(); }
}
