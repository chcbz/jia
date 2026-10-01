package cn.jia.chat.archive.maintenance.dto;

import java.util.List;

public record ArchiveEditionHistoryDTO(String workId, String workRevision,
        String activeEditionId, List<ArchiveEditionVersionDTO> editions) {
    public ArchiveEditionHistoryDTO { editions = List.copyOf(editions); }
}
