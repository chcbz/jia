package cn.jia.economy.bounty;

import cn.jia.economy.service.EconomyPrincipal;
import cn.jia.economy.service.EconomyScope;

public record FundedBountyRefundCommand(
        EconomyScope scope,
        EconomyPrincipal principal,
        String idempotencyKey,
        byte[] requestHash,
        String taskId,
        long amountMicro,
        long expectedEscrowVersion,
        String reserveTransactionId) {
    public FundedBountyRefundCommand {
        requestHash = requestHash == null ? null : requestHash.clone();
    }
    @Override public byte[] requestHash() { return requestHash == null ? null : requestHash.clone(); }
}
