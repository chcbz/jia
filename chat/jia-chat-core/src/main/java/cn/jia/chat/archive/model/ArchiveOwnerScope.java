package cn.jia.chat.archive.model;

/** Exact trusted personal-data partition. HTTP derives tenant from process configuration, never caller input. */
public record ArchiveOwnerScope(String tenantId, String clientId, String ownerJiacn) { }
