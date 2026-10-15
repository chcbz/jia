package cn.jia.economy.common;

/** Public amount in the existing smallest ledger unit, never a floating-point quantity. */
public record Money(String amountMinor, String currency) {
    public Money {
        MicroSilver.parseUnsigned(amountMinor);
        if (!EconomyConstants.CURRENCY_SILVER.equals(currency)) throw new IllegalArgumentException("Unsupported currency");
    }
    public static Money silver(long amount) { return new Money(MicroSilver.format(amount), EconomyConstants.CURRENCY_SILVER); }
    public long minorUnits() { return MicroSilver.parseUnsigned(amountMinor); }
}
