package cn.jia.chat.archive.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({"paragraphId", "ordinal", "text", "utf8ByteLength", "sha256"})
public record ArchiveParagraphDTO(
        String paragraphId,
        int ordinal,
        String text,
        long utf8ByteLength,
        String sha256) {
}
