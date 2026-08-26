package cn.jia.agent.service.impl;

import cn.jia.agent.access.AgentTaskAccessLevel;
import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.common.AgentErrorConstants;
import cn.jia.agent.entity.AgentCommandMailboxEntry;
import cn.jia.agent.entity.AgentCommandMailboxPage;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.mapper.AgentCommandMailboxRow;
import cn.jia.agent.mapper.AgentCommandTransportMapper;
import cn.jia.agent.service.AgentCommandMailboxAccessDeniedException;
import cn.jia.agent.service.AgentCommandMailboxService;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.AgentTaskCollaborationAccessService;
import cn.jia.agent.service.impl.AgentServiceImpl.AgentBizException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

/** Locked REQUIRED authorization and business-safe projection over durable delivery rows. */
public final class AgentCommandMailboxServiceImpl implements AgentCommandMailboxService {
    private static final Set<String> STATUSES = Set.of(
            "PENDING", "CLAIMED", "PUBLISHED", "CONSUMED", "SENT", "RECEIVED",
            "STARTED", "SUCCEEDED", "WAITING_AGENT", "RETRY", "FAILED", "REJECTED",
            "EXPIRED", "DEAD");
    private static final Set<String> TERMINAL_STATUSES = Set.of(
            "SUCCEEDED", "FAILED", "REJECTED", "EXPIRED", "DEAD");
    private static final Set<String> RUNTIME_STATUSES = Set.of(
            AgentConstants.STATUS_ONLINE, AgentConstants.STATUS_BUSY,
            AgentConstants.STATUS_OFFLINE, AgentConstants.STATUS_ERROR);

    private final AgentCommandTransportMapper mapper;
    private final AgentService agentService;
    private final AgentTaskCollaborationAccessService accessService;
    private final TransactionTemplate transaction;
    private final LongSupplier clock;

    public AgentCommandMailboxServiceImpl(
            AgentCommandTransportMapper mapper,
            AgentService agentService,
            AgentTaskCollaborationAccessService accessService,
            PlatformTransactionManager transactionManager) {
        this(mapper, agentService, accessService, transactionManager,
                System::currentTimeMillis);
    }

    AgentCommandMailboxServiceImpl(
            AgentCommandTransportMapper mapper,
            AgentService agentService,
            AgentTaskCollaborationAccessService accessService,
            PlatformTransactionManager transactionManager,
            LongSupplier clock) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.agentService = Objects.requireNonNull(agentService, "agentService");
        this.accessService = Objects.requireNonNull(accessService, "accessService");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
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
        AgentCommandMailboxPage page = transaction.execute(status -> queryAuthorized(
                tenantId, clientId, callerAgentId, targetAgentId, taskId,
                beforeCreateTime, beforeId, limit, includeTerminal));
        if (page == null) throw new IllegalStateException("mailbox transaction returned no projection");
        return page;
    }

    private AgentCommandMailboxPage queryAuthorized(
            String tenantId, String clientId, String callerAgentId, String targetAgentId,
            String taskId, Long beforeCreateTime, Long beforeId, int limit,
            boolean includeTerminal) {
        List<String> agents = Stream.of(callerAgentId, targetAgentId)
                .distinct().sorted(AgentCommandMailboxServiceImpl::compareUtf8Unsigned)
                .toList();
        if (taskId != null) {
            for (String agentId : agents) {
                AgentTaskAccessLevel access = accessService.resolveMemberAccessForUpdate(
                        tenantId, clientId, taskId, agentId);
                if (access == null || !access.canWrite()) {
                    throw new AgentCommandMailboxAccessDeniedException();
                }
            }
        }
        for (String agentId : agents) {
            requireOwnedRuntime(clientId, tenantId, agentId);
        }
        long now = clock.getAsLong();
        if (now <= 0) throw new IllegalStateException("mailbox clock returned an invalid time");

        List<AgentCommandMailboxRow> rows = mapper.selectMailboxPage(
                tenantId, clientId, callerAgentId, targetAgentId, taskId,
                beforeCreateTime, beforeId, limit + 1, includeTerminal, now);
        if (rows == null || rows.size() > limit + 1) {
            throw new IllegalStateException("mailbox query returned an invalid row count");
        }
        long previousTime = Long.MAX_VALUE;
        long previousId = Long.MAX_VALUE;
        java.util.ArrayList<AgentCommandMailboxEntry> entries = new java.util.ArrayList<>();
        for (AgentCommandMailboxRow row : rows) {
            validateRow(row, targetAgentId, previousTime, previousId, includeTerminal, now);
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

    private void requireOwnedRuntime(String clientId, String tenantId, String agentId) {
        AgentRuntimeDTO runtime;
        try {
            runtime = agentService.requireApiKeyOwnedAgentForUpdate(
                    clientId, tenantId, agentId);
        } catch (AgentBizException denied) {
            if (AgentErrorConstants.AGENT_FORBIDDEN.equals(denied.getCode())
                    || AgentErrorConstants.AGENT_NOT_FOUND.equals(denied.getCode())) {
                throw new AgentCommandMailboxAccessDeniedException();
            }
            throw denied;
        }
        if (runtime == null || !agentId.equals(runtime.getAgentId())
                || !RUNTIME_STATUSES.contains(runtime.getStatus())) {
            throw new AgentCommandMailboxAccessDeniedException();
        }
    }

    private void validateRow(
            AgentCommandMailboxRow row, String targetAgentId,
            long previousTime, long previousId, boolean includeTerminal, long now) {
        if (row == null || row.id() <= 0 || row.expiresAt() <= 0
                || row.createTime() <= 0 || row.updateTime() <= 0
                || !exact(row.commandId(), 100) || !exact(row.taskId(), 100)
                || (row.workItemId() != null && !exact(row.workItemId(), 100))
                || !targetAgentId.equals(row.targetAgentId())
                || !AgentCommandCanonicalCodec.isSupportedCommandType(row.commandType())
                || !STATUSES.contains(row.status())
                || (!includeTerminal && TERMINAL_STATUSES.contains(row.status()))
                || (!TERMINAL_STATUSES.contains(row.status()) && row.expiresAt() <= now)
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

    private static int compareUtf8Unsigned(String left, String right) {
        byte[] a = left.getBytes(StandardCharsets.UTF_8);
        byte[] b = right.getBytes(StandardCharsets.UTF_8);
        int limit = Math.min(a.length, b.length);
        for (int index = 0; index < limit; index++) {
            int comparison = Integer.compare(
                    Byte.toUnsignedInt(a[index]), Byte.toUnsignedInt(b[index]));
            if (comparison != 0) return comparison;
        }
        return Integer.compare(a.length, b.length);
    }
}
