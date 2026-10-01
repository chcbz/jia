package cn.jia.chat.archive.maintenance.model;

public record ArchiveValidationRecord(String validationId, String draftId, long draftRevision,
        String outcome, String validationDigest, String findingsJson) { }
