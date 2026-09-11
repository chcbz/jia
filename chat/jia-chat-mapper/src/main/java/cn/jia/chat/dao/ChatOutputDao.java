package cn.jia.chat.dao;

import java.util.List;

public interface ChatOutputDao {
    record Row(String tenantId, String clientId, long conversationId, String outputId,
            long version, String runId, String producerAgentId, String title, String fileName,
            String artifactType, String content, String objectId, byte[] contentHash,
            long contentByteLength, String mimeType, String state, long retainUntil,
            long createdAt) { }

    Row findLatestForUpdate(String tenantId, String clientId, long conversationId,
            String outputId);
    int insert(Row row, long now);
    Row findVersion(String tenantId, String clientId, long conversationId,
            String outputId, long version);
    List<Row> list(String tenantId, String clientId, long conversationId, String outputId,
            boolean latestOnly, long snapshotAt, Long afterCreatedAt, String afterOutputId,
            Long afterVersion, int limit);
}
