package cn.jia.oauth.config;

import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Provider-only nickname validation. It must not be reused to rewrite user-edited profile data. */
@Component
public final class ExternalIdentityDisplayNamePolicy {
    static final int NICKNAME_MAX_CODE_POINTS = 50;

    public Decision evaluate(Source source, String rawValue) {
        if (source == null) {
            throw new IllegalArgumentException("external identity source is required");
        }
        String value = stripUnicodeSpace(rawValue);
        if (value == null || value.isEmpty()) {
            return new Decision(source, null, Classification.EMPTY, true);
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            return new Decision(source, null, Classification.CONTROL, true);
        }
        if (value.codePointCount(0, value.length()) > NICKNAME_MAX_CODE_POINTS) {
            return new Decision(source, null, Classification.TOO_LONG, true);
        }
        if (isHighConfidenceRawMojibake(value)) {
            return new Decision(source, null, Classification.SUSPECT_LEGACY_ENCODING, true);
        }
        return new Decision(source, value, Classification.ACCEPTED, false);
    }

    private static String stripUnicodeSpace(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!isUnicodeSpace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (end > start) {
            int codePoint = value.codePointBefore(end);
            if (!isUnicodeSpace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    private static boolean isUnicodeSpace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static boolean isHighConfidenceRawMojibake(String value) {
        int[] codePoints = value.codePoints().toArray();
        byte[] legacyBytes = new byte[codePoints.length];
        for (int index = 0; index < codePoints.length; index++) {
            int codePoint = codePoints[index];
            if (codePoint > 0xFF) {
                return false;
            }
            legacyBytes[index] = (byte) codePoint;
        }
        if (!containsUtf8MultibyteSequence(legacyBytes)) {
            return false;
        }

        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(legacyBytes))
                    .toString();
            return !decoded.isEmpty()
                    && decoded.codePoints().noneMatch(Character::isISOControl)
                    && Arrays.equals(legacyBytes, decoded.getBytes(StandardCharsets.UTF_8));
        } catch (CharacterCodingException exception) {
            return false;
        }
    }

    private static boolean containsUtf8MultibyteSequence(byte[] bytes) {
        for (int index = 0; index < bytes.length; index++) {
            int first = Byte.toUnsignedInt(bytes[index]);
            int continuationCount;
            if (first >= 0xC2 && first <= 0xDF) {
                continuationCount = 1;
            } else if (first >= 0xE0 && first <= 0xEF) {
                continuationCount = 2;
            } else if (first >= 0xF0 && first <= 0xF4) {
                continuationCount = 3;
            } else {
                continue;
            }
            if (index + continuationCount >= bytes.length) {
                continue;
            }
            boolean complete = true;
            for (int continuation = 1; continuation <= continuationCount; continuation++) {
                int next = Byte.toUnsignedInt(bytes[index + continuation]);
                if (next < 0x80 || next > 0xBF) {
                    complete = false;
                    break;
                }
            }
            if (complete) {
                return true;
            }
        }
        return false;
    }

    public enum Source {
        WXMP("wxmp"),
        WEIBO("weibo"),
        GITHUB("github");

        private final String provider;

        Source(String provider) {
            this.provider = provider;
        }

        public String provider() {
            return provider;
        }
    }

    public enum Classification {
        ACCEPTED,
        EMPTY,
        CONTROL,
        TOO_LONG,
        SUSPECT_LEGACY_ENCODING
    }

    public record Decision(
            Source source,
            String acceptedValue,
            Classification classification,
            boolean preserveExisting) {
    }
}
