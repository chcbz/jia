package cn.jia.chat.archive.service;

import cn.jia.chat.archive.dto.ArchiveBlockDTO;
import cn.jia.chat.archive.dto.ArchiveCatalogDTO;

public interface ArchiveReaderService {
    cn.jia.chat.archive.dto.ArchiveShelfDTO books();

    ArchiveRepresentation<ArchiveCatalogDTO> catalog(String workId);

    ArchiveRepresentation<ArchiveBlockDTO> preface(String editionId);

    ArchiveRepresentation<ArchiveBlockDTO> chapter(String editionId, String chapterId);
}
