package cn.jia.chat.archive.maintenance.model;

public record ArchiveRuntimeScope(String tenantId, String clientId, String ownerJiacn,
        String agentId, String runtimeInstanceId, String grantRef, String executionRef,
        String commandId, long activeAttempt, long executionEpoch) { }
