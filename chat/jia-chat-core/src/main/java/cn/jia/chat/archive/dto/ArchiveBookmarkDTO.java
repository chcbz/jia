package cn.jia.chat.archive.dto;

public record ArchiveBookmarkDTO(
        String bookmarkId, String editionId, String state, ArchivePointLocationDTO location,
        String version, String createdAt, String updatedAt, String deletedAt) { }
