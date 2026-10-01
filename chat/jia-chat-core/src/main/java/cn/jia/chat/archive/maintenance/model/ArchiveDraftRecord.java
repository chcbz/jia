package cn.jia.chat.archive.maintenance.model;

public record ArchiveDraftRecord(String draftId, String jobId, long revision, String state,
        String contentJson, String contentSha256, Long validatedRevision, String validationId) { }
