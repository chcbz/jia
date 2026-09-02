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
        EconomyEscrowFunding escrowFunding) {

    public EconomyPostingCommand {
        requestHash = requestHash == null ? null : requestHash.clone();
        lines = lines == null ? null : List.copyOf(lines);
    }

    @Override
    public byte[] requestHash() {
        return requestHash == null ? null : requestHash.clone();
    }
}
