package cn.jia.chat.archive.maintenance.dto;

import java.util.Map;

public record ArchiveJobEventDTO(String jobId, String sequence, String schemaVersion,
        String type, String jobRevision, Map<String, String> data, String occurredAt) { }