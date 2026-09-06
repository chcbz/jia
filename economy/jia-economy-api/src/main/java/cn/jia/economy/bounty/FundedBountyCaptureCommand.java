package cn.jia.economy.bounty;

import cn.jia.economy.service.EconomyPrincipal;
import cn.jia.economy.service.EconomyScope;

/** Internal only: recipient and fee are taken from the accepted server claim/quote. */
public record FundedBountyCaptureCommand(EconomyScope scope, EconomyPrincipal principal,
        String idempotencyKey, byte[] requestHash, String taskId, String agentId,
        long grossMicro, long actualComputeMicro, long platformFeeMicro, long agentPayoutMicro,
        long expectedEscrowVersion, String reserveTransactionId) {
    public FundedBountyCaptureCommand { requestHash = requestHash == null ? null : requestHash.clone(); }
    @Override public byte[] requestHash() { return requestHash == null ? null : requestHash.clone(); }
}
