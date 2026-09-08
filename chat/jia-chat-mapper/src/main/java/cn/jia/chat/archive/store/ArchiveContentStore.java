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
    void insertEdition(ArchiveEditionRecord edition);
    ArchiveEditionRecord findEdition(String editionId);
    void insertBlock(ArchiveBlockRecord block);
    ArchiveBlockRecord findBlock(String editionId, String blockId);
    void insertParagraph(ArchiveParagraphRecord paragraph);
    ArchiveParagraphRecord findParagraph(String editionId, String blockId, String paragraphId);
    List<ArchiveBlockRecord> listBlocks(String editionId);
    List<ArchiveParagraphRecord> listParagraphs(String editionId, String blockId);
    int markReady(String editionId);
    ArchiveWorkRecord lockWork(String workId);
    ArchiveEditionRecord lockEdition(String editionId);
    int switchActiveEdition(String workId, String editionId);
    int markActivated(String editionId);

    record ActiveContent(ArchiveWorkRecord work, ArchiveEditionRecord edition) { }
}
