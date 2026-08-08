package cn.jia.agent.service.impl;

import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.dao.AgentTaskEventDao;
import cn.jia.agent.entity.AgentTaskEventEntity;
import cn.jia.agent.entity.AgentTaskEventWriteCommand;
import cn.jia.agent.entity.AgentTaskEventWriteResult;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.exception.AgentTaskCollaborationException.Reason;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.core.util.DateUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTaskEventWriterImpl implements AgentTaskEventWriter {

    private final AgentTaskEventDao eventDao;
    private final TransactionTemplate transactionTemplate;

    @Override
    public AgentTaskEventWriteResult append(AgentTaskEventWriteCommand command) {
        validateCommand(command);

        return transactionTemplate.execute(status -> {
            // 1. Lock and get current version
            Long currentVersion = eventDao.lockAndAllocateVersion(
                    command.getTenantId(), command.getClientId(), command.getTaskId());
            if (currentVersion == null) {
                throw new AgentTaskCollaborationException(Reason.NOT_FOUND,
                        "Task not found in scope: tenant=" + command.getTenantId()
                        + " client=" + command.getClientId()
                        + " taskId=" + command.getTaskId());
            }
            if (currentVersion == Long.MAX_VALUE) {
                throw new IllegalStateException(
                        "current_event_version has reached Long.MAX_VALUE; cannot allocate further versions");
            }

            // 2. Compute next version
            long newVersion = currentVersion + 1;
            long now = DateUtil.nowTime();

            // 3. Build and insert event entity
            AgentTaskEventEntity event = new AgentTaskEventEntity();
            event.setTaskId(command.getTaskId());
            event.setEventVersion(newVersion);
            event.setEventId(command.getEventId());
            event.setEventType(command.getEventType());
            event.setActorType(command.getActorType());
            event.setActorId(command.getActorId());
            event.setAggregateType(command.getAggregateType());
            event.setAggregateId(command.getAggregateId());
            event.setEventJson(command.getEventJson());
            event.setOccurredAt(command.getOccurredAt());
            event.setTenantId(command.getTenantId());
            event.setClientId(command.getClientId());
            event.setCreateTime(now);
            event.setUpdateTime(now);

            int inserted = eventDao.insertEvent(event);
            if (inserted != 1) {
                throw new IllegalStateException(
                        "Event insert returned " + inserted + " rows; expected 1");
            }

            // 4. CAS-update current_event_version
            int updated = eventDao.commitEventVersion(
                    command.getTenantId(), command.getClientId(), command.getTaskId(),
                    currentVersion, newVersion, now);
            if (updated != 1) {
                throw new IllegalStateException(
                        "current_event_version CAS failed: expected=" + currentVersion
                        + " new=" + newVersion + " rows=" + updated);
            }

            // 5. Build result
            return new AgentTaskEventWriteResult()
                    .setEventVersion(newVersion)
                    .setPreviousVersion(currentVersion)
                    .setCurrentVersion(newVersion)
                    .setEvent(event);
        });
    }

    private void validateCommand(AgentTaskEventWriteCommand cmd) {
        if (cmd == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        requireNonBlank(cmd.getTenantId(), "tenantId", 50);
        requireNonBlank(cmd.getClientId(), "clientId", 50);
        requireNonBlank(cmd.getTaskId(), "taskId", 100);
        requireNonBlank(cmd.getEventId(), "eventId", 100);
        requireNonBlank(cmd.getActorType(), "actorType", 20);
        requireNonBlank(cmd.getAggregateType(), "aggregateType", 30);
        requireNonBlank(cmd.getAggregateId(), "aggregateId", 100);
        requireNonBlank(cmd.getEventJson(), "eventJson", Integer.MAX_VALUE);
        if (cmd.getOccurredAt() == null || cmd.getOccurredAt() <= 0) {
            throw new IllegalArgumentException("occurredAt must be positive");
        }

        if (cmd.getActorId() != null) {
            requireClean(cmd.getActorId(), "actorId", 100);
        }

        TaskEventType.requireKnown(cmd.getEventType());
        TaskEventType.Aggregate.requireKnown(cmd.getAggregateType());
    }

    private void requireNonBlank(String value, String name, int maxLength) {
        if (value == null || value.isBlank()
                || value.length() > maxLength
                || !value.equals(value.strip())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    name + " is invalid: must be non-blank, byte-exact, ≤ " + maxLength + " chars");
        }
    }

    private void requireClean(String value, String name, int maxLength) {
        if (value.isBlank()
                || value.length() > maxLength
                || !value.equals(value.strip())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    name + " is invalid: must be clean, ≤ " + maxLength + " chars");
        }
    }
}
