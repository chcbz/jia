package cn.jia.chat.archive.dto;

public record ArchiveProgressDTO(
        String editionId, String state, ArchivePointLocationDTO location, String version,
        String completedAt, String createdAt, String updatedAt) { }
