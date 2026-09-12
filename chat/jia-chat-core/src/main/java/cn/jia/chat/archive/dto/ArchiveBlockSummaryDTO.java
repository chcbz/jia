package cn.jia.chat.archive.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder({"blockType", "blockId", "number", "title", "paragraphCount", "utf8ByteLength", "etag"})
public record ArchiveBlockSummaryDTO(
        String blockType,
        String blockId,
        Integer number,
        String title,
        int paragraphCount,
        long utf8ByteLength,
        String etag) {
}
