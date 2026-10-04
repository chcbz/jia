package cn.jia.chat.archive.service;

import cn.jia.chat.archive.dto.ArchiveBlockDTO;
import cn.jia.chat.archive.dto.ArchiveCatalogDTO;
import cn.jia.chat.archive.maintenance.dto.ArchiveWorksDTO;

public interface ArchiveReaderService {
    ArchiveRepresentation<ArchiveCatalogDTO> catalog();
    ArchiveRepresentation<ArchiveWorksDTO> works(int limit);
    ArchiveRepresentation<ArchiveWorksDTO> works(String tenantId,String clientId,String cursor,int limit);
    ArchiveRepresentation<ArchiveCatalogDTO> workCatalog(String workId);
    ArchiveRepresentation<ArchiveCatalogDTO> editionCatalog(String editionId);
    ArchiveRepresentation<ArchiveBlockDTO> preface(String editionId);
    ArchiveRepresentation<ArchiveBlockDTO> chapter(String editionId, String chapterId);
}
