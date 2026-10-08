package cn.jia.chat.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

class ChatConversationReplayPagerTest {
    @Test
    void exactlyFiveHundredRowsFinishesWithoutExtraOrLoopingQuery() {
        AtomicInteger calls = new AtomicInteger();
        List<Long> rows = ChatConversationReplayPager.load(0, 500, 500, (after, through, limit) -> {
            calls.incrementAndGet();
            return LongStream.rangeClosed(after + 1, Math.min(through, after + limit)).boxed().toList();
        }, Long::longValue);
        assertEquals(500, rows.size());
        assertEquals(1, calls.get());
        assertEquals(500L, rows.getLast());
    }

    @Test
    void fiveHundredAndOneRowsContinueToSecondPageAndRemainOrdered() {
        AtomicInteger calls = new AtomicInteger();
        List<Long> rows = ChatConversationReplayPager.load(0, 501, 500, (after, through, limit) -> {
            calls.incrementAndGet();
            return LongStream.rangeClosed(after + 1, Math.min(through, after + limit)).boxed().toList();
        }, Long::longValue);
        assertEquals(501, rows.size());
        assertEquals(2, calls.get());
        assertEquals(1L, rows.getFirst());
        assertEquals(501L, rows.getLast());
    }

    @Test
    void nullOrEmptyPageBeforeWatermarkFailsClosed() {
        assertThrows(IllegalStateException.class, () -> ChatConversationReplayPager.load(
                0, 7, 500, (after, through, limit) -> null, Long::longValue));
        assertThrows(IllegalStateException.class, () -> ChatConversationReplayPager.load(
                0, 7, 500, (after, through, limit) -> List.<Long>of(), Long::longValue));
    }

    @Test
    void shortPartialPageDoesNotCertifyTheRequestedWatermark() {
        assertThrows(IllegalStateException.class, () -> ChatConversationReplayPager.load(
                0, 7, 500, (after, through, limit) -> List.of(2L, 5L), Long::longValue));
    }

    @Test
    void emptyPageAfterAFullPartialPageStillFailsClosed() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> ChatConversationReplayPager.load(
                0, 7, 2, (after, through, limit) -> {
                    calls.incrementAndGet();
                    return after == 0 ? List.of(2L, 5L) : List.<Long>of();
                }, Long::longValue));
        assertEquals(2, calls.get());
    }

    @Test
    void globalSequenceGapsAcrossPagesAreValidWhenEndpointIsPresent() {
        AtomicInteger calls = new AtomicInteger();
        List<Long> rows = ChatConversationReplayPager.load(1, 41, 2, (after, through, limit) -> {
            calls.incrementAndGet();
            assertEquals(41, through);
            assertEquals(2, limit);
            return after == 1 ? List.of(7L, 19L) : List.of(41L);
        }, Long::longValue);
        assertEquals(List.of(7L, 19L, 41L), rows);
        assertEquals(2, calls.get());
    }

    @Test
    void alreadyAtCapturedWatermarkNeedsNoRows() {
        assertEquals(List.of(), ChatConversationReplayPager.load(7, 7, 500,
                (after, through, limit) -> { fail("No replay query is needed"); return List.<Long>of(); },
                Long::longValue));
    }

    @Test
    void duplicateOrNonProgressingSequenceFailsClosed() {
        assertThrows(IllegalStateException.class, () -> ChatConversationReplayPager.load(
                4, 10, 500, (after, through, limit) -> List.of(5L, 5L), Long::longValue));
    }
}
