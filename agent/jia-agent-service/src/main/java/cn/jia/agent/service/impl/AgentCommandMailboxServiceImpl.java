package cn.jia.agent.service.impl;

import cn.jia.agent.entity.AgentCommandMailboxEntry;
import cn.jia.agent.entity.AgentCommandMailboxPage;
import cn.jia.agent.mapper.AgentCommandMailboxRow;
import cn.jia.agent.mapper.AgentCommandTransportMapper;
import cn.jia.agent.service.AgentCommandMailboxService;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Fail-closed business-safe projection over durable delivery rows. */
public final class AgentCommandMailboxServiceImpl implements AgentCommandMailboxService {
    private static final Set<String> STATUSES = Set.of(
            "PENDING", "CLAIMED", "PUBLISHED", "CONSUMED", "SENT", "RECEIVED",
            "STARTED", "SUCCEEDED", "WAITING_AGENT", "RETRY", "FAILED", "REJECTED",
            "EXPIRED", "DEAD");
    private static final Set<String> TERMINAL_STATUSES = Set.of(
            "SUCCEEDED", "FAILED", "REJECTED", "EXPIRED", "DEAD");

    private final AgentCommandTransportMapper mapper;

    public AgentCommandMailboxServiceImpl(AgentCommandTransportMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public AgentCommandMailboxPage query(
            String tenantId, String clientId, String callerAgentId, String targetAgentId,
            String taskId, Long beforeCreateTime, Long beforeId, int limit,
            boolean includeTerminal) {
        requireExact(tenantId, "tenantId", 50);
        requireExact(clientId, "clientId", 50);
        requireExact(callerAgentId, "callerAgentId", 100);
        requireExact(targetAgentId, "targetAgentId", 100);
        if (taskId != null) requireExact(taskId, "taskId", 100);
        if ((beforeCreateTime == null) != (beforeId == null)
                || (beforeCreateTime != null && (beforeCreateTime <= 0 || beforeId <= 0))) {
            throw new IllegalArgumentException("mailbox cursor is invalid");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("mailbox limit must be in 1..100");
        }
        List<AgentCommandMailboxRow> rows = mapper.selectMailboxPage(
                tenantId, clientId, callerAgentId, targetAgentId, taskId,
                beforeCreateTime, beforeId, limit + 1, includeTerminal);
        if (rows == null || rows.size() > limit + 1) {
            throw new IllegalStateException("mailbox query returned an invalid row count");
        }
        long previousTime = Long.MAX_VALUE;
        long previousId = Long.MAX_VALUE;
        java.util.ArrayList<AgentCommandMailboxEntry> entries = new java.util.ArrayList<>();
        for (AgentCommandMailboxRow row : rows) {
            validateRow(row, targetAgentId, previousTime, previousId, includeTerminal);
            if (entries.size() < limit) {
                entries.add(new AgentCommandMailboxEntry(
                        row.commandId(), row.taskId(), row.workItemId(), row.targetAgentId(),
                        row.commandType(), row.status(), row.expiresAt(), row.createTime(),
                        row.updateTime()));
            }
            previousTime = row.createTime();
            previousId = row.id();
        }
        AgentCommandMailboxRow last = rows.size() > limit ? rows.get(limit - 1) : null;
        return new AgentCommandMailboxPage(entries,
                last == null ? null : last.createTime(), last == null ? null : last.id());
    }

    private void validateRow(
            AgentCommandMailboxRow row, String targetAgentId,
            long previousTime, long previousId, boolean includeTerminal) {
        if (row == null || row.id() <= 0 || row.expiresAt() <= 0
                || row.createTime() <= 0 || row.updateTime() <= 0
                || !exact(row.commandId(), 100) || !exact(row.taskId(), 100)
                || (row.workItemId() != null && !exact(row.workItemId(), 100))
                || !targetAgentId.equals(row.targetAgentId())
                || !AgentCommandCanonicalCodec.isSupportedCommandType(row.commandType())
                || !STATUSES.contains(row.status())
                || (!includeTerminal && TERMINAL_STATUSES.contains(row.status()))
                || row.createTime() > previousTime
                || (row.createTime() == previousTime && row.id() >= previousId)) {
            throw new IllegalStateException("mailbox projection violated frozen scope or ordering");
        }
    }

    private void requireExact(String value, String field, int maxLength) {
        if (!exact(value, maxLength)) throw new IllegalArgumentException(field + " is invalid");
    }

    private boolean exact(String value, int maxLength) {
        return value != null && !value.isEmpty() && value.length() <= maxLength
                && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl);
    }
}
