package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskFormalDeliveryDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.entity.AgentTaskFormalDeliveryEntity;
import cn.jia.agent.entity.AgentTaskFormalDeliveryItemDTO;
import cn.jia.agent.entity.AgentTaskFormalDeliveryViewDTO;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.service.AgentTaskFormalDeliveryReadService;
import cn.jia.agent.state.AgentTaskFormalDeliveryState;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;

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

    @Inject
    public AgentTaskFormalDeliveryReadServiceImpl(AgentTaskFormalDeliveryDao deliveryDao,
            AgentTaskMetaDao taskMetaDao) {
        this.deliveryDao = Objects.requireNonNull(deliveryDao, "deliveryDao");
        this.taskMetaDao = Objects.requireNonNull(taskMetaDao, "taskMetaDao");
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
        return projectWindow(deliveries, task, tenantId, clientId, ownerJiacn, taskId);
    }

    /** Current authority never depends on traversing historical batches. */
    @Override
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public AgentTaskFormalDeliveryViewDTO currentForTaskOwner(String tenantId, String clientId, String ownerJiacn, String taskId, boolean includeAccepted) {
        var task = authorizedTask(tenantId, clientId, ownerJiacn, taskId);
        var submitted = deliveryDao.listTaskDeliveryWindow(tenantId, clientId, taskId, "submitted", null, 2);
        if (submitted.size() > 1) throw unavailable("Multiple current formal deliveries");
        var rows = submitted;
        if (rows.isEmpty() && includeAccepted)
            rows = deliveryDao.listTaskDeliveryWindow(tenantId, clientId, taskId, "accepted", null, 1);
        return rows.isEmpty() ? null : projectWindow(rows, task, tenantId, clientId, ownerJiacn, taskId).getFirst();
    }

    @Override
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public HistoryPage historyForTaskOwner(String tenantId, String clientId, String ownerJiacn, String taskId, Long beforeRevision) {
        var task = authorizedTask(tenantId, clientId, ownerJiacn, taskId);
        // The extra row determines continuation, but its items/sources are never hydrated.
        var rows = deliveryDao.listTaskDeliveryWindow(tenantId, clientId, taskId, null, beforeRevision, 21);
        boolean more = rows.size() > 20;
        var window = more ? rows.subList(0, 20) : rows;
        var items = projectWindow(window, task, tenantId, clientId, ownerJiacn, taskId);
        return new HistoryPage(items, more ? Long.toString(window.getLast().getRevision()) : null, more);
    }

    private AgentTaskMetaEntity authorizedTask(String tenantId, String clientId, String ownerJiacn, String taskId) {
        requireScope(tenantId, clientId, ownerJiacn, taskId);
        var task = taskMetaDao.findByTaskIdInOwnerScope(tenantId, clientId, ownerJiacn, taskId);
        requireTask(task, tenantId, clientId, ownerJiacn, taskId);
        return task;
    }

    private List<AgentTaskFormalDeliveryViewDTO> projectWindow(List<AgentTaskFormalDeliveryEntity> rows,
            AgentTaskMetaEntity task, String tenantId, String clientId, String ownerJiacn, String taskId) {
        var result = new ArrayList<AgentTaskFormalDeliveryViewDTO>();
        if (rows.isEmpty()) return List.of();
        long previousRevision = Long.MAX_VALUE;
        // Validate the window before querying associated rows, including excluding the lookahead.
        for (var delivery : rows) {
            requireDelivery(delivery, tenantId, clientId, taskId, previousRevision);
            previousRevision = delivery.getRevision();
        }
        var grouped = deliveryDao.listDisplayItems(tenantId, clientId, ownerJiacn, taskId,
                rows.stream().map(AgentTaskFormalDeliveryEntity::getDeliveryId).toList()).stream()
                .collect(Collectors.groupingBy(AgentTaskFormalDeliveryDao.DisplayItem::deliveryId));
        for (var delivery : rows) {
            var items = grouped.getOrDefault(delivery.getDeliveryId(), List.of());
            if (items.isEmpty() || items.getFirst().workItemVersion() == null
                    || items.getFirst().workItemVersion() < 0 || items.getFirst().workItemVersion() == Long.MAX_VALUE)
                throw unavailable("Formal delivery work item is unavailable");
            result.add(view(delivery, items, task.getTaskVersion(), items.getFirst().workItemVersion()));
        }
        return List.copyOf(result);
    }

    private static AgentTaskFormalDeliveryViewDTO view(AgentTaskFormalDeliveryEntity delivery,
            List<AgentTaskFormalDeliveryDao.DisplayItem> rows, long taskVersion, long workItemVersion) {
        if (rows == null || rows.isEmpty() || rows.size() > MAX_ITEMS) {
            throw unavailable("Formal delivery item catalog is unavailable");
        }
        List<AgentTaskFormalDeliveryItemDTO> items = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            if (row == null || !delivery.getDeliveryId().equals(row.deliveryId())
                    || !Objects.equals(index, row.itemOrder())
                    || !exact(row.artifactId(), 100) || row.artifactVersion() == null
                    || row.artifactVersion() < 1 || row.contentHash() == null
                    || !row.contentHash().matches("[0-9a-f]{64}")
                    || !text(row.purpose(), 255)) {
                throw unavailable("Formal delivery item data is invalid");
            }
            AgentTaskFormalDeliveryItemDTO item = new AgentTaskFormalDeliveryItemDTO();
            item.setArtifactId(row.artifactId());
            item.setArtifactVersion(row.artifactVersion());
            item.setContentHash(row.contentHash());
            item.setPurpose(row.purpose());
            item.setDisplay(display(row));
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

    private static final JsonMapper JSON = JsonMapper.builder().build();
    static AgentTaskFormalDeliveryItemDTO.Display display(AgentTaskFormalDeliveryDao.DisplayItem item) {
        if (item.title() == null)
            return new AgentTaskFormalDeliveryItemDTO.Display(null, null, null, "VERSION_UNAVAILABLE");
        if (item.inlineByteLength() != null)
            return new AgentTaskFormalDeliveryItemDTO.Display(item.title(), "text/plain", item.inlineByteLength(), null);
        if (item.storageMetadata() != null) {
            try {
                var metadata = JSON.readTree(item.storageMetadata());
                if (metadata == null) return new AgentTaskFormalDeliveryItemDTO.Display(item.title(), null, null, "METADATA_UNAVAILABLE");
                var length = metadata.path("byteLength");
                var mime = metadata.path("mimeType");
                if (length.isIntegralNumber() && length.canConvertToLong() && length.longValue() >= 0
                        && mime.isTextual() && mime.textValue().matches("[a-z0-9][a-z0-9!#$&^_.+-]{0,63}/[a-z0-9][a-z0-9!#$&^_.+-]{0,63}"))
                    return new AgentTaskFormalDeliveryItemDTO.Display(item.title(), mime.textValue(), length.longValue(), null);
            } catch (tools.jackson.core.JacksonException invalid) {
                // Display metadata is not execution authority; one damaged label cannot erase the batch.
            }
        }
        return new AgentTaskFormalDeliveryItemDTO.Display(item.title(), null, null, "METADATA_UNAVAILABLE");
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
