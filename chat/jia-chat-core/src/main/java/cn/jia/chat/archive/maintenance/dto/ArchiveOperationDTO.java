package cn.jia.chat.archive.maintenance.dto;

/** A scoped idempotency snapshot; PENDING does not prove the original POST has failed. */
public record ArchiveOperationDTO(String key, String state, String targetType, String targetId) { }
