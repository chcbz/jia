package cn.jia.agent.entity;

import java.util.List;

/** Bounded payload-free operations audit page. */
public record AgentCommandOperationAuditPage(
        List<AgentCommandOperationAuditEntry> items,
        Long nextAfterId,
        boolean hasMore) {
    public AgentCommandOperationAuditPage {
        items = List.copyOf(items);
    }
}
