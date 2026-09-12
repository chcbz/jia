package cn.jia.chat.archive.dto;

import java.util.Map;

public record ArchiveQuestionEventDTO(
        int schemaVersion,
        String questionId,
        String sequence,
        String type,
        String occurredAt,
        Map<String, Object> payload
) {
    public ArchiveQuestionEventDTO {
        payload = Map.copyOf(payload);
    }
}
