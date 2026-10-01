package cn.jia.chat.archive.maintenance.dto;

public record ArchiveRuntimeContextDTO(String jobId, String runId, String collectionId,
        String workId, String operation, String expectedWorkRevision, String expectedActiveEditionId,
        String appointmentId, String appointmentRevision, String agentId, String bindingVersion,
        String permissionProfile, String publicationMode, String state, String waitReason,
        ArchiveSkillRef requiredSkill, String sourceId, String sourceSha256, String sourceSummary, String rightsBasis,
        String draftId, String draftRevision) { }
