package cn.jia.chat.archive.maintenance.dto;

public record ArchiveDraftDTO(String draftId, String jobId, String revision, String state,
        ArchiveDraftUpdateRequest content, String contentSha256, String validatedRevision,
        String validationId) { }
