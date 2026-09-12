package cn.jia.chat.archive.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({"representationSchemaVersion", "workId", "title", "activeEdition"})
public record ArchiveCatalogDTO(
        int representationSchemaVersion,
        String workId,
        String title,
        ArchiveActiveEditionDTO activeEdition) {
}
