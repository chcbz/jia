package cn.jia.chat.output;

import cn.jia.agent.output.OutputConstants;
import cn.jia.agent.output.OutputDeliveryException;
import cn.jia.agent.output.OutputVersionProvider;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatOutputDao;
import cn.jia.chat.entity.ChatConversationEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
public final class ConversationOutputVersionProvider implements OutputVersionProvider {
    private final ChatConversationDao conversationDao;
    private final ChatOutputDao outputDao;

    public ConversationOutputVersionProvider(ChatConversationDao conversationDao,
            ChatOutputDao outputDao) {
        this.conversationDao = conversationDao;
        this.outputDao = outputDao;
    }

    @Override public String sourceType() { return OutputConstants.SOURCE_CONVERSATION; }

    @Override
    public void requireOwner(String tenantId, String clientId, String jiacn, String sourceId,
            boolean forUpdate) {
        ChatConversationEntity conversation;
        try {
            conversation = conversationDao.findExactOwnedById(
                    tenantId, clientId, jiacn, sourceId, forUpdate);
        } catch (IllegalArgumentException invalid) {
            throw hidden();
        }
        if (conversation == null || conversation.getId() == null
                || !Objects.equals(tenantId, conversation.getTenantId())
                || !Objects.equals(clientId, conversation.getClientId())
                || !Objects.equals(jiacn, conversation.getJiacn())
                || !Objects.equals(sourceId, Long.toString(conversation.getId()))
                || conversation.getDeletedAt() != null) throw hidden();
    }

    @Override
    public PublishRow findLatestForUpdate(String tenantId, String clientId, String sourceId,
            String outputId) {
        return row(outputDao.findLatestForUpdate(
                tenantId, clientId, conversationId(sourceId), outputId));
    }

    @Override
    public int insert(PublishRow r) {
        return outputDao.insert(new ChatOutputDao.Row(r.tenantId(), r.clientId(),
                conversationId(r.sourceId()), r.outputId(), r.version(), r.runId(),
                r.producerAgentId(), r.title(), r.fileName(), r.artifactType(), r.content(),
                r.objectId(), r.contentHash(), r.contentByteLength(), r.mimeType(), "AVAILABLE",
                r.retainUntil(), r.createdAt()), r.createdAt());
    }

    @Override public void appendPublicationEvent(PublishRow row) { }

    @Override
    public PublishRow findVersion(String tenantId, String clientId, String sourceId,
            String outputId, long version) {
        return row(outputDao.findVersion(
                tenantId, clientId, conversationId(sourceId), outputId, version));
    }

    @Override
    public List<PublishRow> list(String tenantId, String clientId, String sourceId,
            String outputId, boolean latestOnly, long snapshotAt, CursorBoundary after, int limit) {
        return outputDao.list(tenantId, clientId, conversationId(sourceId), outputId, latestOnly,
                snapshotAt, after == null ? null : after.createdAt(),
                after == null ? null : after.outputId(), after == null ? null : after.version(),
                limit).stream().map(this::row).toList();
    }

    private PublishRow row(ChatOutputDao.Row r) {
        if (r == null) return null;
        return new PublishRow(r.tenantId(), r.clientId(), Long.toString(r.conversationId()),
                r.outputId(), r.version(), r.runId(), r.producerAgentId(), r.title(), r.fileName(),
                r.artifactType(), r.content(), r.objectId(), r.contentHash(), r.contentByteLength(),
                r.mimeType(), "CONVERSATION_OUTPUT", "task_members", null, r.createdAt(),
                r.retainUntil(), r.createdAt());
    }

    private long conversationId(String sourceId) {
        try {
            long id = Long.parseLong(sourceId);
            if (id <= 0 || !Long.toString(id).equals(sourceId)) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException invalid) {
            throw hidden();
        }
    }

    private OutputDeliveryException hidden() {
        return new OutputDeliveryException("OUTPUT_NOT_FOUND", "Output source is unavailable", 404, false);
    }
}
