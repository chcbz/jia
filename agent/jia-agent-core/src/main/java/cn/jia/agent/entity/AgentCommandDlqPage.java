package cn.jia.agent.entity;

import java.util.List;

/** Bounded payload-redacted broker-redrive candidate page. */
public record AgentCommandDlqPage(
        List<AgentCommandDlqEntry> items,
        Long nextAfterDeliveryId,
        boolean hasMore) {
    public AgentCommandDlqPage {
        items = List.copyOf(items);
    }
}
