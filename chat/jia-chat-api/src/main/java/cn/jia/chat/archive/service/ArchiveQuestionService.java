package cn.jia.chat.archive.service;

import cn.jia.chat.archive.dto.ArchiveQuestionDTO;
import cn.jia.chat.archive.model.ArchiveOwnerScope;

public interface ArchiveQuestionService {
    ArchiveMutationResult create(ArchiveOwnerScope owner, String questionId, String canonicalPath,
                                 String idempotencyKey, byte[] body);
    ArchiveQuestionDTO get(ArchiveOwnerScope owner, String questionId);
    ArchiveMutationResult retry(ArchiveOwnerScope owner, String questionId, String canonicalPath,
                                String idempotencyKey, byte[] body);
}
