package cn.jia.chat.archive.maintenance.dto;

/** Business-only fields that a restricted coordinator may narrow. */
public record ArchiveMaintenanceRequest(
        String collectionId,
        String operation,
        ArchiveNewWorkRequest newWork,
        String workId,
        String sourceId,
        String requestedPublicationMode) { }
