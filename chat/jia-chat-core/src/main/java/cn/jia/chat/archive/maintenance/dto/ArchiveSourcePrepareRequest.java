package cn.jia.chat.archive.maintenance.dto;

/** Administrator-supplied immutable UTF-8 file; no arbitrary URL or local path is accepted. */
public record ArchiveSourcePrepareRequest(String sourceName, String sourceVersion,
        String rightsBasis, String declaredSha256, String contentBase64) { }
