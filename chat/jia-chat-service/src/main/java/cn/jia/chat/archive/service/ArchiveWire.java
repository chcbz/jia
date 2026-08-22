package cn.jia.chat.archive.service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class ArchiveWire {
    static final long MAX_VERSION = Long.MAX_VALUE;
    private ArchiveWire() { }

    static long decimal(String value, String errorCode) {
        if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) invalid(errorCode);
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) invalid(errorCode);
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new ArchivePersonalDataException(422, errorCode, "Invalid canonical decimal value");
        }
    }

    static String next(long current) {
        if (current == MAX_VERSION) {
            throw new ArchivePersonalDataException(409, "VERSION_EXHAUSTED",
                    "Resource version is exhausted", Long.toString(current));
        }
        return Long.toString(current + 1);
    }

    static boolean lowercaseUuid(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) return false;
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException ignored) { return false; }
    }

    static boolean visibleAsciiKey(String value) {
        if (value == null) return false;
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 1 || bytes.length > 128) return false;
        for (byte b : bytes) if ((b & 0xff) < 0x21 || (b & 0xff) > 0x7e) return false;
        return true;
    }

    static Long cursor(String value) {
        return value == null ? null : decimal(value, "INVALID_CURSOR");
    }

    private static void invalid(String code) {
        throw new ArchivePersonalDataException(422, code, "Invalid canonical decimal value");
    }
}
