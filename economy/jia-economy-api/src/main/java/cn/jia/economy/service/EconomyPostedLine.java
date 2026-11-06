package cn.jia.economy.service;

public record EconomyPostedLine(
        String accountId,
        int entrySequence,
        long signedAmountWen,
        long balanceAfterWen) {
}
