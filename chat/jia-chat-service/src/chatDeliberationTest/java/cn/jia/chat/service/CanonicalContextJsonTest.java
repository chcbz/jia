package cn.jia.chat.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CanonicalContextJsonTest {
    @Test
    void snapshotHashIsStableAcrossMapInsertionOrderAndKeepsArrayOrder() {
        Map<String, Object> left = new LinkedHashMap<>();
        left.put("z", List.of("b", "a"));
        Map<String, Object> leftNested = new LinkedHashMap<>();
        leftNested.put("n", 7L);
        leftNested.put("missing", null);
        left.put("a", leftNested);
        Map<String, Object> right = new LinkedHashMap<>();
        Map<String, Object> rightNested = new LinkedHashMap<>();
        rightNested.put("missing", null);
        rightNested.put("n", 7L);
        right.put("a", rightNested);
        right.put("z", List.of("b", "a"));

        assertEquals(CanonicalContextJson.write(left), CanonicalContextJson.write(right));
        assertEquals(ChatDeliberationService.digest(left), ChatDeliberationService.digest(right));
    }

    @Test
    void floatingPointFactsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> CanonicalContextJson.write(Map.of("unsafe", 0.1D)));
    }
}
