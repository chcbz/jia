package cn.jia.chat.archive.maintenance.model;

public record ArchiveJobEventRecord(String jobId, long sequence, long schemaVersion,
        String type, long jobRevision, String dataJson, String occurredAt) { }