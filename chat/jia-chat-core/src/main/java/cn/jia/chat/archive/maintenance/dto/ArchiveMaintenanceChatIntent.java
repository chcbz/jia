package cn.jia.chat.archive.maintenance.dto;

/** Opaque reference to a server-persisted confirmed archive request. */
public record ArchiveMaintenanceChatIntent(
        int schemaVersion,
        String confirmationRef) { }
