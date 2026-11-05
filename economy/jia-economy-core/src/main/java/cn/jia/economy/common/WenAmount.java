package cn.jia.economy.common;

import cn.jia.economy.exception.EconomyPostingException;

import static cn.jia.economy.exception.EconomyPostingException.Reason.AMOUNT_RANGE_EXCEEDED;
import static cn.jia.economy.exception.EconomyPostingException.Reason.INVALID_COMMAND;

/** Canonical decimal-string boundary for non-negative wen amounts. */
public final class WenAmount {
    private WenAmount() {
    }

    public static long parseUnsigned(String value) {
        if (value == null || value.isEmpty()
                || !(value.equals("0") || value.charAt(0) >= '1' && value.charAt(0) <= '9')
                || value.chars().anyMatch(character -> character < '0' || character > '9')) {
            throw new EconomyPostingException(INVALID_COMMAND,
                    "wen amount must be a canonical unsigned decimal string");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new EconomyPostingException(AMOUNT_RANGE_EXCEEDED,
                    "wen amount exceeds signed BIGINT", exception);
        }
    }

    public static String format(long value) {
        if (value < 0) {
            throw new EconomyPostingException(INVALID_COMMAND,
                    "public wen amount must not be negative");
        }
        return Long.toString(value);
    }
}
