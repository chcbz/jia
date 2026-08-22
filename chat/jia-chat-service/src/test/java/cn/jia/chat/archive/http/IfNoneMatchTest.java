package cn.jia.chat.archive.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IfNoneMatchTest {
    private static final String CURRENT = "W/\"archive-catalog-json-v1-1656be0bc81b6d73a9bd2bf44121df36ae5a5a66f39f34114bf108e91e317ccc\"";

    @Test
    void weakComparisonSupportsListsWildcardAndCommasInsideOpaqueTags() {
        assertTrue(IfNoneMatch.matches(CURRENT, CURRENT));
        assertTrue(IfNoneMatch.matches(CURRENT.substring(2), CURRENT));
        assertTrue(IfNoneMatch.matches("\"stale\", " + CURRENT, CURRENT));
        assertTrue(IfNoneMatch.matches("*", CURRENT));
        assertTrue(IfNoneMatch.matches("\t" + CURRENT + " \t", CURRENT));
        assertFalse(IfNoneMatch.matches("W/\"stale\"", CURRENT));
        assertFalse(IfNoneMatch.matches("W/\"stale,tag\"", CURRENT));
    }

    @Test
    void malformedSyntaxFailsClosed() {
        for (String malformed : new String[]{"", " ", CURRENT + ",", "W/\"unterminated",
                "W/\"stale tag\"", "W/\"超\"", "* , " + CURRENT,
                "\u000b" + CURRENT, "\n" + CURRENT, "\r" + CURRENT,
                "\f" + CURRENT, "\u00a0" + CURRENT}) {
            assertThrows(InvalidConditionalHeaderException.class,
                    () -> IfNoneMatch.matches(malformed, CURRENT), () -> printable(malformed));
        }
    }

    private String printable(String value) {
        return value.chars().collect(StringBuilder::new,
                (builder, c) -> builder.append(String.format("\\u%04x", c)), StringBuilder::append).toString();
    }
}
