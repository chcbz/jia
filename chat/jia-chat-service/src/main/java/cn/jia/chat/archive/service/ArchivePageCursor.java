package cn.jia.chat.archive.service;

import cn.jia.chat.archive.content.ArchiveEtags;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Objects;

/** Opaque, scope-bound keyset cursor. It never carries authorization. */
public final class ArchivePageCursor {
    private static final String VERSION = "p1";
    private static final int MAX_CURSOR_LENGTH = 320;
    private static final int MAX_KEY_BYTES = 128;

    private ArchivePageCursor() { }

    public static String binding(String purpose, String... parts) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, purpose);
        for (String part : parts) append(canonical, part == null ? "" : part);
        return ArchiveEtags.sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static String encode(String binding, String lastKey) {
        requireBinding(binding);
        requireKey(lastKey);
        String encodedKey = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(lastKey.getBytes(StandardCharsets.UTF_8));
        String digest = digest(binding, lastKey);
        return VERSION + "." + encodedKey + "." + digest;
    }

    public static String decode(String binding, String cursor) {
        requireBinding(binding);
        if (cursor == null || cursor.isBlank() || cursor.length() > MAX_CURSOR_LENGTH
                || !cursor.equals(cursor.strip())) throw invalid();
        String[] fields = cursor.split("\\.", -1);
        if (fields.length != 3 || !VERSION.equals(fields[0])
                || !fields[1].matches("[A-Za-z0-9_-]{1,172}")
                || !fields[2].matches("[0-9a-f]{64}")) throw invalid();
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(fields[1]);
        } catch (IllegalArgumentException malformed) {
            throw invalid();
        }
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(fields[1])) throw invalid();
        String key;
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            key = decoded.toString();
        } catch (CharacterCodingException malformed) {
            throw invalid();
        }
        requireKey(key);
        if (!MessageDigest.isEqual(digest(binding, key).getBytes(StandardCharsets.US_ASCII),
                fields[2].getBytes(StandardCharsets.US_ASCII))) throw invalid();
        return key;
    }

    private static String digest(String binding, String key) {
        return ArchiveEtags.sha256((VERSION + "\0" + binding + "\0" + key)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static void append(StringBuilder target, String value) {
        String exact = Objects.requireNonNull(value, "cursor binding component");
        target.append(exact.getBytes(StandardCharsets.UTF_8).length).append(':').append(exact).append(';');
    }

    private static void requireBinding(String binding) {
        if (binding == null || !binding.matches("[0-9a-f]{64}")) throw invalid();
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank() || !key.equals(key.strip())
                || key.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_BYTES
                || key.chars().anyMatch(Character::isISOControl)) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid archive page cursor");
    }
}
