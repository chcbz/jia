package cn.jia.agent.entity;

import java.util.List;

/** Internal page result; cursor components are encoded by the HTTP edge before exposure. */
public record AgentCommandMailboxPage(
        List<AgentCommandMailboxEntry> entries,
        Long nextBeforeCreateTime,
        Long nextBeforeId) {
    public AgentCommandMailboxPage {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
