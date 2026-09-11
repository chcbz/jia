package cn.jia.agent.output.service;

import cn.jia.agent.common.TaskEventPayload;
import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.dao.AgentTaskArtifactDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.entity.AgentTaskArtifactDTO;
import cn.jia.agent.entity.AgentTaskArtifactEntity;
import cn.jia.agent.entity.AgentTaskEventWriteCommand;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.output.OutputConstants;
import cn.jia.agent.output.OutputDeliveryException;
import cn.jia.agent.output.OutputVersionProvider;
import cn.jia.agent.mapper.AgentTaskOutputMapper;
import cn.jia.agent.service.AgentTaskEventWriter;
import org.springframework.stereotype.Component;

import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

@Component
public final class TaskOutputVersionProvider implements OutputVersionProvider {
    private final AgentTaskMetaDao taskDao;
    private final AgentTaskWorkItemDao workItemDao;
    private final AgentTaskArtifactDao artifactDao;
    private final AgentTaskOutputMapper outputMapper;
    private final AgentTaskEventWriter eventWriter;

    public TaskOutputVersionProvider(AgentTaskMetaDao taskDao, AgentTaskWorkItemDao workItemDao,
            AgentTaskArtifactDao artifactDao, AgentTaskOutputMapper outputMapper,
            AgentTaskEventWriter eventWriter) {
        this.taskDao = taskDao;
        this.workItemDao = workItemDao;
        this.artifactDao = artifactDao;
        this.outputMapper = outputMapper;
        this.eventWriter = eventWriter;
    }

    @Override public String sourceType() { return OutputConstants.SOURCE_TASK; }

    @Override
    public void requireOwner(String tenantId, String clientId, String jiacn, String sourceId,
            boolean forUpdate) {
        AgentTaskMetaEntity task = forUpdate
                ? taskDao.findByTaskIdForUpdate(tenantId, clientId, sourceId)
                : taskDao.findByTaskId(tenantId, clientId, sourceId);
        if (task == null || !Objects.equals(tenantId, jiacn)
                || !Objects.equals(tenantId, task.getTenantId())
                || !Objects.equals(clientId, task.getClientId())
                || !Objects.equals(sourceId, task.getTaskId())) throw hidden();
    }

    @Override
    public PublishRow findLatestForUpdate(String tenantId, String clientId, String sourceId,
            String outputId) {
        return row(artifactDao.findLatestVersionForUpdate(tenantId, clientId, sourceId, outputId));
    }

    @Override
    public int insert(PublishRow r) {
        if (r.workItemId() != null && workItemDao.findByTaskAndWorkItemId(
                r.tenantId(), r.clientId(), r.sourceId(), r.workItemId()) == null) return 0;
        AgentTaskArtifactDTO dto = new AgentTaskArtifactDTO();
        dto.setArtifactId(r.outputId()); dto.setTaskId(r.sourceId());
        dto.setWorkItemId(r.workItemId()); dto.setProducerAgentId(r.producerAgentId());
        dto.setArtifactType(r.artifactType()); dto.setTitle(r.title()); dto.setContent(r.content());
        dto.setStorageUri(null); dto.setObjectId(r.objectId()); dto.setRunId(r.runId());
        dto.setFileName(r.fileName()); dto.setContentByteLength(r.contentByteLength());
        dto.setMimeType(r.mimeType()); dto.setOwnerSharedAt(r.ownerSharedAt());
        dto.setRetainUntil(r.retainUntil()); dto.setContentHash(HexFormat.of().formatHex(r.contentHash()));
        dto.setArtifactVersion(Math.toIntExact(r.version())); dto.setVisibility(r.visibility());
        dto.setMetadataJson(null); dto.setCreatedAt(r.createdAt());
        return artifactDao.insert(r.tenantId(), r.clientId(), dto);
    }

    @Override
    public void appendPublicationEvent(PublishRow r) {
        TaskEventPayload.Builder payload = TaskEventPayload.builder()
                .put(TaskEventPayload.Key.ARTIFACT_ID, r.outputId())
                .put(TaskEventPayload.Key.ARTIFACT_TYPE, r.artifactType())
                .put(TaskEventPayload.Key.ARTIFACT_VERSION, r.version())
                .put(TaskEventPayload.Key.VISIBILITY, r.visibility())
                .put(TaskEventPayload.Key.CONTENT_SHA256, HexFormat.of().formatHex(r.contentHash()))
                .put(TaskEventPayload.Key.CONTENT_BYTE_LENGTH, r.contentByteLength());
        if (r.workItemId() != null) payload.put(TaskEventPayload.Key.WORK_ITEM_ID, r.workItemId());
        String seed = r.tenantId()+'\0'+r.clientId()+'\0'+r.sourceId()+'\0'
                +TaskEventType.ARTIFACT_PUBLISHED+'\0'+TaskEventType.Aggregate.ARTIFACT+'\0'
                +r.outputId()+'\0'+r.version();
        String eventId = "evt_" + TaskEventPayload.ContentDigest.fromUtf8(seed).sha256();
        eventWriter.append(new AgentTaskEventWriteCommand().setTenantId(r.tenantId())
                .setClientId(r.clientId()).setTaskId(r.sourceId()).setEventId(eventId)
                .setEventType(TaskEventType.ARTIFACT_PUBLISHED)
                .setActorType(TaskEventType.ActorType.AGENT).setActorId(r.producerAgentId())
                .setAggregateType(TaskEventType.Aggregate.ARTIFACT).setAggregateId(r.outputId())
                .setEventJson(payload.toJson()).setOccurredAt(r.createdAt()));
    }

    @Override
    public PublishRow findVersion(String tenantId, String clientId, String sourceId,
            String outputId, long version) {
        return row(outputMapper.findVersion(tenantId, clientId, sourceId, outputId, version));
    }

    @Override
    public List<PublishRow> list(String tenantId, String clientId, String sourceId,
            String outputId, boolean latestOnly, long snapshotAt, CursorBoundary after, int limit) {
        long now = System.currentTimeMillis();
        return outputMapper.listOwnerVisible(tenantId, clientId, sourceId, outputId, latestOnly,
                snapshotAt, now, after == null ? null : after.createdAt(),
                after == null ? null : after.outputId(), after == null ? null : after.version(),
                limit).stream().map(this::row).toList();
    }

    private PublishRow row(AgentTaskArtifactEntity e) {
        if (e == null) return null;
        byte[] hash;
        try { hash = HexFormat.of().parseHex(e.getContentHash()); }
        catch (RuntimeException bad) { throw new OutputDeliveryException(
                "OUTPUT_DATA_INVALID", "Output metadata is invalid", 503, true); }
        return new PublishRow(e.getTenantId(), e.getClientId(), e.getTaskId(), e.getArtifactId(),
                e.getArtifactVersion(), e.getRunId(), e.getProducerAgentId(), e.getTitle(),
                e.getFileName(), e.getArtifactType(), e.getContent(), e.getObjectId(), hash,
                e.getContentByteLength() == null ? 0 : e.getContentByteLength(), e.getMimeType(),
                e.getOwnerSharedAt() == null ? "INTERNAL" : "OWNER_SHARE", e.getVisibility(),
                e.getWorkItemId(), e.getOwnerSharedAt(), e.getRetainUntil() == null ? 0 : e.getRetainUntil(),
                e.getCreatedAt());
    }

    private OutputDeliveryException hidden() {
        return new OutputDeliveryException("OUTPUT_NOT_FOUND", "Output source is unavailable", 404, false);
    }
}
