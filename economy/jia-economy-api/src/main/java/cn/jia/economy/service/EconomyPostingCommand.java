package cn.jia.economy.service;

import cn.jia.economy.common.EconomyJournalType;

import java.util.List;

public record EconomyPostingCommand(
        EconomyScope scope,
        EconomyPrincipal principal,
        String idempotencyKey,
        byte[] requestHash,
        EconomyJournalType journalType,
        String businessId,
        List<EconomyPostingLine> lines,
        EconomyEscrowFunding escrowFunding,
        EconomyEscrowSettlement escrowSettlement) {

    public EconomyPostingCommand {
        requestHash = requestHash == null ? null : requestHash.clone();
        lines = lines == null ? null : List.copyOf(lines);
    }

    /** Compatibility constructor for W02/W03 callers that do not settle existing escrow. */
    public EconomyPostingCommand(
            EconomyScope scope,
            EconomyPrincipal principal,
            String idempotencyKey,
            byte[] requestHash,
            EconomyJournalType journalType,
            String businessId,
            List<EconomyPostingLine> lines,
            EconomyEscrowFunding escrowFunding) {
        this(scope, principal, idempotencyKey, requestHash, journalType, businessId,
                lines, escrowFunding, null);
    }

    @Override
    public byte[] requestHash() {
        return requestHash == null ? null : requestHash.clone();
    }
}
