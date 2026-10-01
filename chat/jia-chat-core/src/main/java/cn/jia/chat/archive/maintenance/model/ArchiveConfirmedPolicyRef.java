package cn.jia.chat.archive.maintenance.model;

import cn.jia.chat.archive.maintenance.dto.ArchiveNewWorkRequest;

/** Immutable server-authorized ceiling for one confirmed archive request. */
public record ArchiveConfirmedPolicyRef(
        String policyRef,
        String collectionId,
        String operation,
        ArchiveNewWorkRequest newWork,
        String workId,
        String sourceId,
        String publicationModeCeiling) { }
