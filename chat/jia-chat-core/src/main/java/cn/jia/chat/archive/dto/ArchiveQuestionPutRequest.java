package cn.jia.chat.archive.dto;

public record ArchiveQuestionPutRequest(
        String editionId,
        String question,
        ArchiveTextAnchorDTO anchor
) {
}
