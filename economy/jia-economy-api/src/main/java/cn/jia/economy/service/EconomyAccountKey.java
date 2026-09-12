package cn.jia.economy.service;

import cn.jia.economy.common.EconomyAccountOwnerType;
import cn.jia.economy.common.EconomyAccountPurpose;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

public record EconomyAccountKey(
        String currency,
        EconomyAccountOwnerType ownerType,
        String ownerId,
        EconomyAccountPurpose purpose) {

    /** Global account lock order is unsigned lexicographic order of the exact UTF-8 identity bytes. */
    public static final Comparator<EconomyAccountKey> LOCK_ORDER = (left, right) -> {
        int compared = compareExactUtf8(left.currency(), right.currency());
        if (compared != 0) return compared;
        compared = compareExactUtf8(left.ownerType().name(), right.ownerType().name());
        if (compared != 0) return compared;
        compared = compareExactUtf8(left.ownerId(), right.ownerId());
        if (compared != 0) return compared;
        return compareExactUtf8(left.purpose().name(), right.purpose().name());
    };

    private static int compareExactUtf8(String left, String right) {
        return Arrays.compareUnsigned(exactUtf8(left), exactUtf8(right));
    }

    private static byte[] exactUtf8(String value) {
        if (value == null) throw new IllegalArgumentException("account lock identity must not be null");
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("account lock identity must be valid UTF-8", exception);
        }
    }
}
