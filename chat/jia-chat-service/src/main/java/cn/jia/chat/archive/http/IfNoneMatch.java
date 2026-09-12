package cn.jia.chat.archive.http;

import java.util.ArrayList;
import java.util.List;

/** Strict RFC entity-tag list parser with weak comparison and exact SP/HTAB OWS. */
public final class IfNoneMatch {
    private IfNoneMatch() {
    }

    public static void validate(String headerValue) {
        if (headerValue != null) {
            parse(headerValue);
        }
    }

    public static boolean matches(String headerValue, String currentEtag) {
        if (headerValue == null) {
            return false;
        }
        Parsed parsed = parse(headerValue);
        if (parsed.wildcard()) {
            return true;
        }
        String currentOpaque = opaque(currentEtag);
        return parsed.tags().stream().map(IfNoneMatch::opaque).anyMatch(currentOpaque::equals);
    }

    private static Parsed parse(String value) {
        int length = value.length();
        int position = skipOws(value, 0);
        if (position == length) {
            throw malformed();
        }
        if (value.charAt(position) == '*') {
            position = skipOws(value, position + 1);
            if (position != length) {
                throw malformed();
            }
            return new Parsed(true, List.of());
        }

        List<String> tags = new ArrayList<>();
        while (position < length) {
            int start = position;
            if (position + 2 <= length && value.startsWith("W/", position)) {
                position += 2;
            }
            if (position >= length || value.charAt(position) != '"') {
                throw malformed();
            }
            position++;
            while (position < length && value.charAt(position) != '"') {
                if (!etagCharacter(value.charAt(position))) {
                    throw malformed();
                }
                position++;
            }
            if (position >= length) {
                throw malformed();
            }
            position++;
            tags.add(value.substring(start, position));
            position = skipOws(value, position);
            if (position == length) {
                break;
            }
            if (value.charAt(position) != ',') {
                throw malformed();
            }
            position = skipOws(value, position + 1);
            if (position == length || value.charAt(position) == '*') {
                throw malformed();
            }
        }
        if (tags.isEmpty()) {
            throw malformed();
        }
        return new Parsed(false, List.copyOf(tags));
    }

    private static int skipOws(String value, int position) {
        while (position < value.length()) {
            char current = value.charAt(position);
            if (current != ' ' && current != '\t') {
                break;
            }
            position++;
        }
        return position;
    }

    private static boolean etagCharacter(char value) {
        return value == 0x21 || (value >= 0x23 && value <= 0x7e)
                || (value >= 0x80 && value <= 0xff);
    }

    private static String opaque(String etag) {
        if (etag == null) {
            throw malformed();
        }
        String candidate = etag.startsWith("W/") ? etag.substring(2) : etag;
        Parsed parsed = parse(candidate);
        if (parsed.wildcard() || parsed.tags().size() != 1) {
            throw malformed();
        }
        return parsed.tags().getFirst();
    }

    private static InvalidConditionalHeaderException malformed() {
        return new InvalidConditionalHeaderException();
    }

    private record Parsed(boolean wildcard, List<String> tags) {
    }
}
