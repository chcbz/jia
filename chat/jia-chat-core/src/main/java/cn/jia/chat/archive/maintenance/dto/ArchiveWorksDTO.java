package cn.jia.chat.archive.maintenance.dto;

import java.util.List;

public record ArchiveWorksDTO(List<ArchiveWorkSummaryDTO> items, String nextCursor) {
    public ArchiveWorksDTO { items = List.copyOf(items); }
}
