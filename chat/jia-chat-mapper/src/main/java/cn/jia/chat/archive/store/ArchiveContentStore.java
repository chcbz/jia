package cn.jia.chat.archive.store;

import cn.jia.chat.archive.model.ArchiveBlockRecord;
import cn.jia.chat.archive.model.ArchiveEditionRecord;
import cn.jia.chat.archive.model.ArchiveParagraphRecord;
import cn.jia.chat.archive.model.ArchiveWorkRecord;

import java.util.List;

public interface ArchiveContentStore {
    void insertWork(ArchiveWorkRecord work);
    ArchiveWorkRecord findWork(String workId);
    ActiveContent findActiveContent(String workId);
    default ActiveContent findPublishedContent(String editionId) { return null; }
    default List<ArchiveWorkRecord> listActiveWorks(int limit) { return List.of(); }
    default boolean isPublished(String editionId) { return true; }
    default void ensureLegacyPublication(String collectionId, String canonicalKey,
                                         ArchiveWorkRecord work, ArchiveEditionRecord edition) { }
    void insertEdition(ArchiveEditionRecord edition);
    ArchiveEditionRecord findEdition(String editionId);
    void insertBlock(ArchiveBlockRecord block);
    ArchiveBlockRecord findBlock(String editionId, String blockId);
    void insertParagraph(ArchiveParagraphRecord paragraph);
    ArchiveParagraphRecord findParagraph(String editionId, String blockId, String paragraphId);
    List<ArchiveBlockRecord> listBlocks(String editionId);
    List<ArchiveParagraphRecord> listParagraphs(String editionId, String blockId);
    default List<ArchiveParagraphRecord> listAllParagraphs(String editionId) {
        throw new UnsupportedOperationException("listAllParagraphs is not supported by this store");
    }
    int markReady(String editionId);
    ArchiveWorkRecord lockWork(String workId);
    ArchiveEditionRecord lockEdition(String editionId);
    int switchActiveEdition(String workId, String editionId);
    int markActivated(String editionId);

    record ActiveContent(ArchiveWorkRecord work, ArchiveEditionRecord edition) { }
}
