package cn.jia.chat.archive.maintenance.dto;

/** Sanitized terminal producer failure. No source, credential, log or free-form diagnostic field. */
public record ArchiveRuntimeFailureRequest(String phase, String code, Boolean retryable) { }
