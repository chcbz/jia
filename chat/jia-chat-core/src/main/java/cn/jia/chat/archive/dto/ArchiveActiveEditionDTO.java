package cn.jia.chat.archive.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

@JsonPropertyOrder({"editionId", "manifestSha256", "sourceSha256", "prefaceParagraphCount",
        "chapterParagraphCount", "readerParagraphCount", "readerUtf8ByteLength", "preface", "chapters"})
public record ArchiveActiveEditionDTO(
        String editionId,
        String manifestSha256,
        String sourceSha256,
        int prefaceParagraphCount,
        int chapterParagraphCount,
        int readerParagraphCount,
        long readerUtf8ByteLength,
        ArchiveBlockSummaryDTO preface,
        List<ArchiveBlockSummaryDTO> chapters) {
    public ArchiveActiveEditionDTO {
        chapters = List.copyOf(chapters);
    }
}
