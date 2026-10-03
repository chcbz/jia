package cn.jia.chat.archive.dto;

import cn.jia.core.security.ExactContentOutput;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({"representationSchemaVersion", "workId", "title", "activeEdition"})
public record ArchiveCatalogDTO(
        int representationSchemaVersion,
        String workId,
        @ExactContentOutput(reason = "published archive work title must be byte-faithful") String title,
        ArchiveActiveEditionDTO activeEdition) {
}
