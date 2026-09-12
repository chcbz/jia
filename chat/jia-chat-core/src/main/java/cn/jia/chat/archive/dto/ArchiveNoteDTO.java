package cn.jia.chat.archive.dto;

public record ArchiveNoteDTO(
        String noteId, String editionId, String state, String text, ArchiveTextAnchorDTO anchor,
        String version, String createdAt, String updatedAt, String deletedAt) { }
