package cn.jia.chat.archive.maintenance.model;

import java.time.Instant;

/** Durable claim and acknowledgement for one archive business event projected into chat. */
public record ArchiveBusinessOutboxRecord(
        String projectionKey,
        String sourceType,
        String jobId,
        Long eventSequence,
        String withdrawalId,
        String state,
        long attemptCount,
        long fencingToken,
        Instant availableAt,
        Instant leaseUntil,
        String lastErrorCode,
        Long projectedMessageId) { }
