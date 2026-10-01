package cn.jia.chat.archive.maintenance.dto;

/** Exact transport identity echoed by the controlled runner when claiming a run. */
public record ArchiveRuntimeStartRequest(String commandId, String messageId,
        String attempt, String executionEpoch) { }
