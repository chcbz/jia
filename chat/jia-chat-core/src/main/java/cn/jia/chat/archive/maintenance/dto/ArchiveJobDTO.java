package cn.jia.chat.archive.maintenance.dto;

public record ArchiveJobDTO(String jobId, String runId, String collectionId, String state,
        String waitReason, String revision, String appointmentId, String assignedAgentId,
        String permissionProfile, String publicationMode, String operation, String workId,
        String canonicalKey, String title, String sourceId, String draftId, String publicationId) { }
