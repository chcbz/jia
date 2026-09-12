package cn.jia.chat.archive.dto;

public record ArchiveProgressPutRequest(
        String expectedVersion, ArchivePointLocationDTO location, Boolean markCompleted) { }
