package cn.jia.chat.archive.dto;

import java.util.List;
public record ArchiveShelfDTO(List<ArchiveBookDTO> items) {
    public ArchiveShelfDTO { items = List.copyOf(items); }
}
