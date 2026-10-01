package cn.jia.chat.archive.maintenance.dto;

public record ArchiveExecutionRecoveryDTO(String jobId, String runId,
        String executionEpoch, String state) { }
