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
    void duplicateOrNonProgressingSequenceFailsClosed() {
        assertThrows(IllegalStateException.class, () -> ChatConversationReplayPager.load(
                4, 10, 500, (after, through, limit) -> List.of(5L, 5L), Long::longValue));
    }
}
