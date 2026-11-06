package cn.jia.economy.common;

/** Public amount in the existing smallest ledger unit, never a floating-point quantity. */
public record Money(String amountMinor, String currency) {
    public Money {
        WenAmount.parseUnsigned(amountMinor);
        if (!EconomyConstants.CURRENCY_WEN.equals(currency)) throw new IllegalArgumentException("Unsupported currency");
    }
    public static Money wen(long amount) { return new Money(WenAmount.format(amount), EconomyConstants.CURRENCY_WEN); }
    public long minorUnits() { return WenAmount.parseUnsigned(amountMinor); }
}
