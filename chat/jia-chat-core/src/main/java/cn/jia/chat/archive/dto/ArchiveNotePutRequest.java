package cn.jia.chat.archive.dto;

public record ArchiveNotePutRequest(
        String expectedVersion, String editionId, String text, ArchiveTextAnchorDTO anchor) { }
