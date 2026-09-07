package cn.jia.chat.archive.dto;

public record ArchiveQuestionPutRequest(
        String question,
        ArchiveTextAnchorDTO anchor
) {
}
