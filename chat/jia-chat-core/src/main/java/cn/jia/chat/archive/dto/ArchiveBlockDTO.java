package cn.jia.chat.archive.dto;

import cn.jia.core.security.ExactContentOutput;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder({"representationSchemaVersion", "editionId", "manifestSha256", "blockType", "blockId",
        "number", "title", "paragraphCount", "utf8ByteLength", "paragraphs"})
public record ArchiveBlockDTO(
        int representationSchemaVersion,
        String editionId,
        String manifestSha256,
        String blockType,
        String blockId,
        Integer number,
        @ExactContentOutput(reason = "published archive block title must be byte-faithful") String title,
        int paragraphCount,
        long utf8ByteLength,
        List<ArchiveParagraphDTO> paragraphs) {
    public ArchiveBlockDTO {
        paragraphs = List.copyOf(paragraphs);
    }
}
