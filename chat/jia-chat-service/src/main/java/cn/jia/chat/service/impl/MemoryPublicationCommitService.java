package cn.jia.chat.service.impl;

import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.dao.MemoryPublicationDao;
import cn.jia.chat.entity.ConversationDataScope;
import cn.jia.chat.entity.MemoryPublicationRecord;
import cn.jia.chat.entity.MemoryWorkItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/** Commits SQL visibility only after the immutable ES snapshot has been written. */
@Service
public class MemoryPublicationCommitService {
    private final ChatMessageDao messageDao;
    private final MemoryPublicationDao publicationDao;

    public MemoryPublicationCommitService(
            ChatMessageDao messageDao, MemoryPublicationDao publicationDao) {
        this.messageDao = Objects.requireNonNull(messageDao, "messageDao");
        this.publicationDao = Objects.requireNonNull(publicationDao, "publicationDao");
    }

    @Transactional(rollbackFor = Exception.class)
    public void commit(
            ConversationDataScope scope, String conversationId,
            List<Long> pendingMessageIds, MemoryPublicationRecord publication,
            MemoryWorkItem work, String workerId, long now) {
        int updated = messageDao.markPendingMessagesSynced(
                scope, conversationId, pendingMessageIds);
        if (updated != pendingMessageIds.size()) {
            throw new IllegalStateException("Pending message snapshot CAS updated "
                    + updated + " of " + pendingMessageIds.size() + " rows");
        }
        publicationDao.publish(publication, now);
        if (work != null) {
            publicationDao.complete(work, workerId, now);
        }
    }
}
