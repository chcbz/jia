package cn.jia.chat.archive.dto;

public record ArchiveQuestionDTO(
        String questionId,
        String version,
        String currentSequence,
        String status,
        ArchiveQuestionResponderDTO responder,
        String question,
        ArchiveTextAnchorDTO anchor,
        String selectedText,
        String answer,
        int retryCount,
        String lastErrorCode,
        String createdAt,
        String updatedAt,
        String completedAt
) {
}
