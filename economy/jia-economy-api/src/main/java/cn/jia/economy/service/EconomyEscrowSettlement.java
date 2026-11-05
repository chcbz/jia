package cn.jia.economy.service;

import cn.jia.economy.common.EconomyEscrowType;

/** Exact full/partial capture or refund against an existing escrow root. */
public record EconomyEscrowSettlement(
        EconomyEscrowType escrowType,
        EconomyAccountKey escrowAccount,
        EconomyAccountKey destinationAccount,
        long amountWen,
        long expectedEscrowVersion,
        String reserveTransactionId) {
}
