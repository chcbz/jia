package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskFormalDeliveryDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.entity.AgentTaskFormalDeliveryEntity;
import cn.jia.agent.entity.AgentTaskFormalDeliveryItemDTO;
import cn.jia.agent.entity.AgentTaskFormalDeliveryItemEntity;
import cn.jia.agent.entity.AgentTaskFormalDeliveryViewDTO;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.service.AgentTaskFormalDeliveryReadService;
import cn.jia.agent.state.AgentTaskFormalDeliveryState;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Browser-owner read model for immutable R2 delivery batches.
 *
 * <p>The collaboration tenant is the task owner identity. This service never accepts an actor
 * supplied by a caller: its exact tenant/client/task scope is used for every DAO read. A read can
 * race with an owner decision; callers receive the current root/work-item versions and must use
 * them for a later decision CAS.</p>
 */
@Named
public class AgentTaskFormalDeliveryReadServiceImpl implements AgentTaskFormalDeliveryReadService {
    private static final int MAX_DELIVERIES = 100;
    private static final int MAX_ITEMS = 100;

    private final AgentTaskFormalDeliveryDao deliveryDao;
    private final AgentTaskMetaDao taskMetaDao;
    private final AgentTaskWorkItemDao workItemDao;

    @Inject
    public AgentTaskFormalDeliveryReadServiceImpl(AgentTaskFormalDeliveryDao deliveryDao,
            AgentTaskMetaDao taskMetaDao, AgentTaskWorkItemDao workItemDao) {
        this.deliveryDao = Objects.requireNonNull(deliveryDao, "deliveryDao");
        this.taskMetaDao = Objects.requireNonNull(taskMetaDao, "taskMetaDao");
        this.workItemDao = Objects.requireNonNull(workItemDao, "workItemDao");
    }

    @Override
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<AgentTaskFormalDeliveryViewDTO> listForTaskOwner(
            String tenantId, String clientId, String ownerJiacn, String taskId) {
        requireScope(tenantId, clientId, ownerJiacn, taskId);
        AgentTaskMetaEntity task = taskMetaDao.findByTaskIdInOwnerScope(
                tenantId, clientId, ownerJiacn, taskId);
        requireTask(task, tenantId, clientId, ownerJiacn, taskId);
        List<AgentTaskFormalDeliveryEntity> deliveries = deliveryDao.listTaskDeliveries(
                tenantId, clientId, taskId, MAX_DELIVERIES);
        if (deliveries == null || deliveries.size() > MAX_DELIVERIES) {
            throw unavailable("Formal delivery catalog is unavailable");
        }
        List<AgentTaskFormalDeliveryViewDTO> result = new ArrayList<>(deliveries.size());
        long previousRevision = Long.MAX_VALUE;
        for (AgentTaskFormalDeliveryEntity delivery : deliveries) {
            requireDelivery(delivery, tenantId, clientId, taskId, previousRevision);
            previousRevision = delivery.getRevision();
            AgentTaskWorkItemEntity workItem = workItemDao.findByTaskAndWorkItemId(
                    tenantId, clientId, ownerJiacn, taskId, delivery.getWorkItemId());
            requireWorkItem(workItem, tenantId, clientId, ownerJiacn, taskId, delivery);
            List<AgentTaskFormalDeliveryItemEntity> rows = deliveryDao.listItems(
                    tenantId, clientId, delivery.getDeliveryId());
            result.add(view(delivery, rows, task.getTaskVersion(), workItem.getVersion()));
        }
        return List.copyOf(result);
    }

    private static AgentTaskFormalDeliveryViewDTO view(AgentTaskFormalDeliveryEntity delivery,
            List<AgentTaskFormalDeliveryItemEntity> rows, long taskVersion, long workItemVersion) {
        if (rows == null || rows.isEmpty() || rows.size() > MAX_ITEMS) {
            throw unavailable("Formal delivery item catalog is unavailable");
        }
        List<AgentTaskFormalDeliveryItemDTO> items = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            AgentTaskFormalDeliveryItemEntity row = rows.get(index);
            if (row == null || !delivery.getDeliveryId().equals(row.getDeliveryId())
                    || !Objects.equals(index, row.getItemOrder())
                    || !exact(row.getArtifactId(), 100) || row.getArtifactVersion() == null
                    || row.getArtifactVersion() < 1 || row.getContentHash() == null
                    || !row.getContentHash().matches("[0-9a-f]{64}")
                    || !text(row.getPurpose(), 255)) {
                throw unavailable("Formal delivery item data is invalid");
            }
            AgentTaskFormalDeliveryItemDTO item = new AgentTaskFormalDeliveryItemDTO();
            item.setArtifactId(row.getArtifactId());
            item.setArtifactVersion(row.getArtifactVersion());
            item.setContentHash(row.getContentHash());
            item.setPurpose(row.getPurpose());
            items.add(item);
        }
        AgentTaskFormalDeliveryViewDTO value = new AgentTaskFormalDeliveryViewDTO();
        value.setTaskId(delivery.getTaskId()); value.setWorkItemId(delivery.getWorkItemId());
        value.setDeliveryId(delivery.getDeliveryId()); value.setRevision(delivery.getRevision());
        value.setDeliveryVersion(delivery.getVersion());
        value.setState(delivery.getState()); value.setRunId(delivery.getRunId());
        value.setProducerAgentId(delivery.getProducerAgentId()); value.setSummary(delivery.getSummary());
        value.setManifestArtifactId(delivery.getManifestArtifactId());
        value.setManifestArtifactVersion(delivery.getManifestArtifactVersion());
        value.setSubmittedAt(delivery.getSubmittedAt()); value.setReviewedAt(delivery.getReviewedAt());
        value.setReviewReason(delivery.getReviewReason()); value.setTaskVersion(taskVersion);
        value.setWorkItemVersion(workItemVersion); value.setReplayed(false); value.setItems(List.copyOf(items));
        return value;
    }

    private static void requireTask(AgentTaskMetaEntity task, String tenantId, String clientId,
            String ownerJiacn, String taskId) {
        if (task == null || !tenantId.equals(task.getTenantId()) || !clientId.equals(task.getClientId())
                || !ownerJiacn.equals(task.getOwnerJiacn()) || !taskId.equals(task.getTaskId()) || task.getTaskVersion() == null
                || task.getTaskVersion() < 0 || task.getTaskVersion() == Long.MAX_VALUE) {
            throw notFound();
        }
    }

    private static void requireDelivery(AgentTaskFormalDeliveryEntity delivery, String tenantId,
            String clientId, String taskId, long previousRevision) {
        if (delivery == null || !tenantId.equals(delivery.getTenantId())
                || !clientId.equals(delivery.getClientId()) || !taskId.equals(delivery.getTaskId())
                || !exact(delivery.getWorkItemId(), 100) || !exact(delivery.getDeliveryId(), 100)
                || delivery.getRevision() == null || delivery.getRevision() < 1
                || delivery.getRevision() >= previousRevision || !exact(delivery.getProducerAgentId(), 100)
                || !exact(delivery.getRunId(), 100) || !text(delivery.getSummary(), 4_000)
                || !exact(delivery.getManifestArtifactId(), 100)
                || delivery.getManifestArtifactVersion() == null
                || delivery.getManifestArtifactVersion() < 1 || delivery.getSubmittedAt() == null
                || delivery.getSubmittedAt() <= 0 || delivery.getVersion() == null
                || delivery.getVersion() < 0 || delivery.getVersion() > 1) {
            throw unavailable("Formal delivery data is invalid");
        }
        AgentTaskFormalDeliveryState state;
        try {
            state = AgentTaskFormalDeliveryState.fromPersistedValue(delivery.getState());
        } catch (IllegalArgumentException invalid) {
            throw unavailable("Formal delivery state is invalid");
        }
        if (state == AgentTaskFormalDeliveryState.SUBMITTED
                && (delivery.getReviewedAt() != null || delivery.getReviewReason() != null)) {
            throw unavailable("Pending formal delivery has an invalid review");
        }
        if (state == AgentTaskFormalDeliveryState.ACCEPTED
                && (delivery.getReviewedAt() == null || delivery.getReviewedAt() <= 0
                        || delivery.getReviewReason() != null)) {
            throw unavailable("Accepted formal delivery has an invalid review");
        }
        if (state == AgentTaskFormalDeliveryState.CHANGES_REQUESTED
                && (delivery.getReviewedAt() == null || delivery.getReviewedAt() <= 0
                        || !text(delivery.getReviewReason(), 4_000))) {
            throw unavailable("Rework formal delivery has an invalid review");
        }
    }

    private static void requireWorkItem(AgentTaskWorkItemEntity item, String tenantId,
            String clientId, String ownerJiacn, String taskId, AgentTaskFormalDeliveryEntity delivery) {
        if (item == null || !tenantId.equals(item.getTenantId()) || !clientId.equals(item.getClientId())
                || !ownerJiacn.equals(item.getOwnerJiacn()) || !taskId.equals(item.getTaskId()) || !delivery.getWorkItemId().equals(item.getWorkItemId())
                || item.getVersion() == null || item.getVersion() < 0
                || item.getVersion() == Long.MAX_VALUE) {
            throw unavailable("Formal delivery work item is unavailable");
        }
    }

    private static void requireScope(String tenantId, String clientId, String ownerJiacn, String taskId) {
        if (!"0".equals(tenantId) || !exact(clientId, 50) || "0".equals(clientId)
                || !exact(ownerJiacn, 50) || "0".equals(ownerJiacn) || !exact(taskId, 100)) {
            throw notFound();
        }
    }

    private static boolean exact(String value, int maxLength) {
        return value != null && !value.isEmpty() && !value.isBlank() && value.length() <= maxLength
                && value.equals(value.strip()) && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static boolean text(String value, int maxLength) {
        return exact(value, maxLength);
    }

    private static AgentTaskCollaborationException notFound() {
        return new AgentTaskCollaborationException(
                AgentTaskCollaborationException.Reason.NOT_FOUND, "Formal delivery is unavailable");
    }

    private static AgentTaskCollaborationException unavailable(String message) {
        return new AgentTaskCollaborationException(
                AgentTaskCollaborationException.Reason.INVALID_PERSISTED_STATE, message);
    }
}
