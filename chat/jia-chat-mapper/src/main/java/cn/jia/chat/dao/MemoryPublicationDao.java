package cn.jia.chat.dao;

import cn.jia.chat.entity.ConversationDataScope;
import cn.jia.chat.entity.MemoryPublicationRecord;
import cn.jia.chat.entity.MemoryPublicationPayload;
import cn.jia.chat.entity.MemoryWorkItem;

import java.util.List;
import java.util.Set;

public interface MemoryPublicationDao {
    void signalInsertedMessage(long messageId, long now);
    MemoryWorkItem claimNext(String workerId, long now, long leaseUntil);
    MemoryPublicationRecord stage(ConversationDataScope scope, String conversationId,
                                  long generation, String snapshotToken, String documentId,
                                  int messageCount, long now);
    MemoryPublicationRecord bindPayload(
            MemoryPublicationRecord publication, MemoryPublicationPayload payload, long now);
    void publish(MemoryPublicationRecord publication, long now);
    void complete(MemoryWorkItem work, String workerId, long now);
    void retry(MemoryWorkItem work, String workerId, long dueAt, String error, long now);
    void dead(MemoryWorkItem work, String workerId, String error, long now);
    Set<String> findVisibleDocumentIds(ConversationDataScope scope, List<String> documentIds);
    void tombstone(ConversationDataScope scope, String conversationId, long now);
}
