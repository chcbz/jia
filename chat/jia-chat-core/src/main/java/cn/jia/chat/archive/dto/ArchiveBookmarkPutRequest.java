package cn.jia.chat.archive.dto;

public record ArchiveBookmarkPutRequest(
        String expectedVersion, String editionId, ArchivePointLocationDTO location) { }
