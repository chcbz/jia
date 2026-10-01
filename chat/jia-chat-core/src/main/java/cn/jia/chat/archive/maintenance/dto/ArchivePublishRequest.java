package cn.jia.chat.archive.maintenance.dto;

public record ArchivePublishRequest(String validationId, String expectedActiveEditionId,
        String expectedWorkRevision) { }
