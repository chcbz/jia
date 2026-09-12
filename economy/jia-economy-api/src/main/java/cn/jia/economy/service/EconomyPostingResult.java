package cn.jia.economy.service;

import java.util.List;

public record EconomyPostingResult(
        String transactionId,
        String status,
        String currency,
        long postedAt,
        long debitTotalMicro,
        long creditTotalMicro,
        List<EconomyPostedLine> lines,
        EconomyEscrowResult escrow) {

    public EconomyPostingResult {
        lines = List.copyOf(lines);
    }
}
