package cn.jia.agent.entity;

import java.util.Set;

/**
 * The broker delivery became visible before the matching D03 publish settlement committed.
 * Identity and wire provenance were already validated; callers may park the same message.
 */
public final class AgentInboxSourceNotSettledException extends IllegalStateException {
    public static final String CODE = "AGENT_INBOX_SOURCE_NOT_SETTLED";
    private static final Set<String> REASONS = Set.of(
            "OUTBOX_NOT_PUBLISHED", "DELIVERY_NOT_PUBLISHED");

    private final String reasonCode;

    public AgentInboxSourceNotSettledException(String reasonCode) {
        super(CODE + ": " + requireReason(reasonCode));
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }

    private static String requireReason(String reasonCode) {
        if (!REASONS.contains(reasonCode)) {
            throw new IllegalArgumentException("Unsupported source settlement reason");
        }
        return reasonCode;
    }
}
