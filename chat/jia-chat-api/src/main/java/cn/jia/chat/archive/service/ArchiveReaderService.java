package cn.jia.chat.archive.service;

import cn.jia.chat.archive.dto.ArchiveBlockDTO;
import cn.jia.chat.archive.dto.ArchiveCatalogDTO;

public interface ArchiveReaderService {
    ArchiveRepresentation<ArchiveCatalogDTO> catalog();

    ArchiveRepresentation<ArchiveBlockDTO> preface(String editionId);

    ArchiveRepresentation<ArchiveBlockDTO> chapter(String editionId, String chapterId);
}
