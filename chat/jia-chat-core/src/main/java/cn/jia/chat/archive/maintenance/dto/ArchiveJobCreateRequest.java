package cn.jia.chat.archive.maintenance.dto;

public record ArchiveJobCreateRequest(String operation, ArchiveNewWorkRequest newWork, String workId,
        String sourceId, String publicationMode, String requestIntentId) { }
