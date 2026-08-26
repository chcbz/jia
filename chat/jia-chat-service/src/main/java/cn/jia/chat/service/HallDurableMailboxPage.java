package cn.jia.chat.service;

import java.util.List;

public record HallDurableMailboxPage(
        List<HallDurableMailboxItem> items,
        String nextCursor,
        boolean terminalIncluded) {
    public HallDurableMailboxPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
