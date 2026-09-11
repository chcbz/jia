package cn.jia.agent.output;

import java.util.List;

public interface OutputVersionProvider {
    record PublishRow(String tenantId, String clientId, String sourceId, String outputId,
            long version, String runId, String producerAgentId, String title, String fileName,
            String artifactType, String content, String objectId, byte[] contentHash,
            long contentByteLength, String mimeType, String publicationKind,
            String visibility, String workItemId, Long ownerSharedAt, long retainUntil,
            long createdAt) { }

    record CursorBoundary(long createdAt, String outputId, long version) { }

    String sourceType();
    void requireOwner(String tenantId, String clientId, String jiacn, String sourceId,
            boolean forUpdate);
    PublishRow findLatestForUpdate(String tenantId, String clientId, String sourceId,
            String outputId);
    int insert(PublishRow row);
    void appendPublicationEvent(PublishRow row);
    PublishRow findVersion(String tenantId, String clientId, String sourceId,
            String outputId, long version);
    List<PublishRow> list(String tenantId, String clientId, String sourceId,
            String outputId, boolean latestOnly, long snapshotAt, CursorBoundary after, int limit);
}
