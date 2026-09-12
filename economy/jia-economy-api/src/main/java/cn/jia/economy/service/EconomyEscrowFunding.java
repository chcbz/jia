package cn.jia.economy.service;

import cn.jia.economy.common.EconomyEscrowType;

public record EconomyEscrowFunding(
        EconomyEscrowType escrowType,
        EconomyAccountKey payerAccount,
        EconomyAccountKey escrowAccount,
        long amountMicro,
        Long expectedEscrowVersion) {
}
