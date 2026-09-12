package cn.jia.chat.archive.service;

import cn.jia.chat.archive.dto.ArchiveBookmarkDTO;
import cn.jia.chat.archive.dto.ArchiveNoteDTO;
import cn.jia.chat.archive.dto.ArchivePageDTO;
import cn.jia.chat.archive.dto.ArchiveProgressDTO;
import cn.jia.chat.archive.model.ArchiveOwnerScope;

public interface ArchivePersonalDataService {
    ArchiveProgressDTO progress(ArchiveOwnerScope owner, String editionId);
    ArchiveMutationResult putProgress(ArchiveOwnerScope owner, String editionId, String canonicalPath,
                                      String idempotencyKey, byte[] requestBody);
    ArchivePageDTO<ArchiveBookmarkDTO> bookmarks(ArchiveOwnerScope owner, String editionId,
                                                  String cursor, int limit);
    ArchiveMutationResult putBookmark(ArchiveOwnerScope owner, String bookmarkId, String canonicalPath,
                                      String idempotencyKey, byte[] requestBody);
    ArchiveMutationResult deleteBookmark(ArchiveOwnerScope owner, String bookmarkId, String expectedVersion,
                                         String canonicalPath, String idempotencyKey);
    ArchivePageDTO<ArchiveNoteDTO> notes(ArchiveOwnerScope owner, String editionId, String blockId,
                                         String cursor, int limit);
    ArchiveMutationResult putNote(ArchiveOwnerScope owner, String noteId, String canonicalPath,
                                  String idempotencyKey, byte[] requestBody);
    ArchiveMutationResult deleteNote(ArchiveOwnerScope owner, String noteId, String expectedVersion,
                                     String canonicalPath, String idempotencyKey);
}
