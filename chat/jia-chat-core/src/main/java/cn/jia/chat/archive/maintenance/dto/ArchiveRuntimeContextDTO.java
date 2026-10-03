package cn.jia.chat.archive.maintenance.dto;

import cn.jia.core.security.ExactContentOutput;

public record ArchiveRuntimeContextDTO(String jobId, String runId, String collectionId,
        String workId, String operation, String expectedWorkRevision, String expectedActiveEditionId,
        String appointmentId, String appointmentRevision, String agentId, String bindingVersion,
        String permissionProfile, String publicationMode, String state, String waitReason,
        ArchiveSkillRef requiredSkill, String sourceId, String sourceSha256,
        @ExactContentOutput(reason = "validated source name and version summary must be byte-faithful")
        String sourceSummary,
        @ExactContentOutput(reason = "validated source rights metadata must be byte-faithful")
        String rightsBasis,
        String draftId, String draftRevision) { }
