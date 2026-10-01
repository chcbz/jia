package cn.jia.chat.archive.maintenance.dto;

public record ArchiveExecutionDTO(String grantRef, String runId, String executionRef,
        String commandId, String activeAttempt, String executionEpoch, String state,
        String installationRef, String installationRevision, String expiresAt) { }
