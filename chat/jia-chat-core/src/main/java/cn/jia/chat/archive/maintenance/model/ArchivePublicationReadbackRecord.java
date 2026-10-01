package cn.jia.chat.archive.maintenance.model;

import java.time.Instant;

public record ArchivePublicationReadbackRecord(String publicationId, String state, long revision,
        String verificationDigest, String findingsJson, Instant checkedAt) { }
