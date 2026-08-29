package cn.jia.chat.archive.model;

/** Exact server-derived owner tuple. */
public record ArchiveOwnerScope(String tenantId, String clientId, String ownerJiacn) { }
