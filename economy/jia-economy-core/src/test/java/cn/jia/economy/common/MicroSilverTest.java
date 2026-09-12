package cn.jia.economy.common;

import cn.jia.economy.exception.EconomyPostingException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MicroSilverTest {
    @Test
    void canonicalDecimalStringRoundTripsAtBigintBoundary() {
        assertEquals(0L, MicroSilver.parseUnsigned("0"));
        assertEquals(1_000_000L, MicroSilver.parseUnsigned("1000000"));
        assertEquals(Long.MAX_VALUE, MicroSilver.parseUnsigned("9223372036854775807"));
        assertEquals("1000000", MicroSilver.format(1_000_000L));
    }

    @Test
    void rejectsNonCanonicalNegativeAndOverflowValues() {
        for (String value : new String[]{null, "", "00", "01", "+1", "-1", "1.0", " 1", "1 "}) {
            assertThrows(EconomyPostingException.class, () -> MicroSilver.parseUnsigned(value), value);
        }
        EconomyPostingException overflow = assertThrows(EconomyPostingException.class,
                () -> MicroSilver.parseUnsigned("9223372036854775808"));
        assertEquals(EconomyPostingException.Reason.AMOUNT_RANGE_EXCEEDED, overflow.reason());
        assertThrows(EconomyPostingException.class, () -> MicroSilver.format(-1));
    }
}
