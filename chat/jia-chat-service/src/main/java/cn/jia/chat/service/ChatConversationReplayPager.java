package cn.jia.chat.service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

/** Deterministic bounded-page replay from an exclusive cursor through a fixed DB watermark. */
public final class ChatConversationReplayPager {
    private ChatConversationReplayPager() { }

    public static <T> List<T> load(long after, long watermark, int pageSize,
            PageLoader<T> loader, ToLongFunction<T> sequence) {
        if (after < 0 || watermark < after || pageSize < 1 || loader == null || sequence == null) {
            throw new IllegalArgumentException("Invalid replay range");
        }
        List<T> result = new ArrayList<>();
        long cursor = after;
        while (cursor < watermark) {
            List<T> page = loader.load(cursor, watermark, pageSize);
            if (page == null || page.isEmpty()) break;
            long previous = cursor;
            for (T item : page) {
                long next = sequence.applyAsLong(item);
                if (next <= cursor || next > watermark) throw new IllegalStateException("Replay sequence is invalid");
                result.add(item);
                cursor = next;
            }
            if (cursor <= previous) throw new IllegalStateException("Replay made no progress");
            if (page.size() < pageSize) break;
        }
        return List.copyOf(result);
    }

    @FunctionalInterface
    public interface PageLoader<T> {
        List<T> load(long after, long through, int limit);
    }
}
