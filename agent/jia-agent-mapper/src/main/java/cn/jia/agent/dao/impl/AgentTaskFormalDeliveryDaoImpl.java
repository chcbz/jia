package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentTaskFormalDeliveryDao;
import cn.jia.agent.entity.AgentTaskFormalDeliveryEntity;
import cn.jia.agent.entity.AgentTaskFormalDeliveryItemEntity;
import cn.jia.agent.mapper.AgentTaskFormalDeliveryMapper;
import cn.jia.agent.state.AgentTaskFormalDeliveryState;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.util.List;
import java.util.Objects;

/** Exact scope and optimistic-version checks for the R2 delivery persistence layer. */
@Named
public class AgentTaskFormalDeliveryDaoImpl implements AgentTaskFormalDeliveryDao {
    private final AgentTaskFormalDeliveryMapper mapper;

    @Inject
    public AgentTaskFormalDeliveryDaoImpl(AgentTaskFormalDeliveryMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public AgentTaskFormalDeliveryEntity findForUpdate(String tenantId, String clientId, String deliveryId) {
        scope(tenantId, clientId); id(deliveryId, "deliveryId");
        return mapper.selectExactForUpdate(tenantId, clientId, deliveryId);
    }

    @Override
    public AgentTaskFormalDeliveryEntity findTaskRevisionForUpdate(
            String tenantId, String clientId, String taskId, long revision) {
        scope(tenantId, clientId); id(taskId, "taskId"); positive(revision, "revision");
        return mapper.selectTaskRevisionForUpdate(tenantId, clientId, taskId, revision);
    }

    @Override
    public AgentTaskFormalDeliveryEntity findLatestTaskForUpdate(
            String tenantId, String clientId, String taskId) {
        scope(tenantId, clientId); id(taskId, "taskId");
        return mapper.selectLatestTaskForUpdate(tenantId, clientId, taskId);
    }

    @Override
    public int insert(String tenantId, String clientId, AgentTaskFormalDeliveryEntity delivery) {
        scope(tenantId, clientId); requireDelivery(delivery);
        delivery.setTenantId(tenantId).setClientId(clientId);
        delivery.init4Creation();
        return mapper.insertFormalDelivery(delivery);
    }

    @Override
    public int insertItem(String tenantId, String clientId, AgentTaskFormalDeliveryItemEntity item) {
        scope(tenantId, clientId); requireItem(item);
        item.setTenantId(tenantId).setClientId(clientId);
        item.init4Creation();
        return mapper.insertDeliveryItem(item);
    }

    @Override
    public List<AgentTaskFormalDeliveryItemEntity> listItems(
            String tenantId, String clientId, String deliveryId) {
        scope(tenantId, clientId); id(deliveryId, "deliveryId");
        return mapper.selectItems(tenantId, clientId, deliveryId);
    }

    @Override
    public int reviewByVersion(String tenantId, String clientId, String deliveryId, String expectedState,
            long expectedVersion, String nextState, String reviewedByJiacn, String reviewReason,
            long reviewedAt) {
        scope(tenantId, clientId); id(deliveryId, "deliveryId");
        AgentTaskFormalDeliveryState expected = state(expectedState);
        AgentTaskFormalDeliveryState next = state(nextState);
        if (!expected.canTransitionTo(next) || expectedVersion < 0 || expectedVersion == Long.MAX_VALUE
                || reviewedAt <= 0) {
            throw new IllegalArgumentException("formal delivery review transition is invalid");
        }
        id(reviewedByJiacn, "reviewedByJiacn");
        if (reviewedByJiacn.length() > 50) {
            throw new IllegalArgumentException("reviewedByJiacn is invalid");
        }
        if (next == AgentTaskFormalDeliveryState.CHANGES_REQUESTED) requiredText(reviewReason, "reviewReason");
        if (next == AgentTaskFormalDeliveryState.ACCEPTED && reviewReason != null) {
            throw new IllegalArgumentException("accepted formal delivery must not retain review reason");
        }
        return mapper.reviewByVersion(tenantId, clientId, deliveryId, expected.value(), expectedVersion,
                next.value(), reviewedByJiacn, reviewReason, reviewedAt, expectedVersion + 1);
    }

    private static void requireDelivery(AgentTaskFormalDeliveryEntity value) {
        if (value == null) throw new IllegalArgumentException("delivery is required");
        id(value.getTaskId(), "taskId"); id(value.getWorkItemId(), "workItemId");
        id(value.getDeliveryId(), "deliveryId"); id(value.getProducerAgentId(), "producerAgentId");
        id(value.getRunId(), "runId"); requiredText(value.getSummary(), "summary");
        sha256(value.getSubmissionDigest(), "submissionDigest");
        id(value.getManifestArtifactId(), "manifestArtifactId"); positive(value.getRevision(), "revision");
        if (value.getManifestArtifactVersion() == null || value.getManifestArtifactVersion() < 1
                || value.getSubmittedAt() == null || value.getSubmittedAt() <= 0
                || value.getVersion() == null || value.getVersion() != 0L
                || state(value.getState()) != AgentTaskFormalDeliveryState.SUBMITTED
                || value.getReviewedByJiacn() != null || value.getReviewReason() != null
                || value.getReviewedAt() != null) {
            throw new IllegalArgumentException("new formal delivery shape is invalid");
        }
        if (value.getSupersedesDeliveryId() != null) id(value.getSupersedesDeliveryId(), "supersedesDeliveryId");
    }

    private static void requireItem(AgentTaskFormalDeliveryItemEntity value) {
        if (value == null) throw new IllegalArgumentException("delivery item is required");
        id(value.getDeliveryId(), "deliveryId"); id(value.getArtifactId(), "artifactId");
        requiredText(value.getPurpose(), "purpose");
        if (value.getPurpose().length() > 255
                || value.getArtifactVersion() == null || value.getArtifactVersion() < 1
                || value.getItemOrder() == null || value.getItemOrder() < 0
                || value.getContentHash() == null || !value.getContentHash().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("formal delivery item is invalid");
        }
    }

    private static AgentTaskFormalDeliveryState state(String value) {
        try { return AgentTaskFormalDeliveryState.fromPersistedValue(value); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("formal delivery state is invalid", invalid); }
    }
    private static void scope(String tenantId, String clientId) {
        id(tenantId, "tenantId"); id(clientId, "clientId");
        if (tenantId.length() > 50 || clientId.length() > 50) throw new IllegalArgumentException("scope is invalid");
    }
    private static void id(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 100 || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }
    private static void sha256(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }
    private static void requiredText(String value, String name) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }
    private static void positive(Long value, String name) {
        if (value == null || value < 1) throw new IllegalArgumentException(name + " must be positive");
    }
    private static void positive(long value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
    }
}
